package com.example.rcgallery.data

import android.content.Context
import com.example.rcgallery.model.TrashEntry
import com.example.rcgallery.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 回收站管理器——逻辑删除的索引存储。
 * 文件本身保留在原位，仅通过 `trash_index.json` 标记哪些文件已被「快删」。
 *
 * - 写入使用临时文件 → renameTo 实现原子替换
 * - 启动时自动清理 30 天以上的过期条目
 */
class TrashManager(private val context: Context) {

    private val file: File get() = File(context.filesDir, "trash_index.json")
    private val lock = Any()

    companion object {
        private const val MAX_AGE_DAYS = 30L
        private const val TAG = "Trash"
    }

    init {
        cleanupExpired()
    }

    /** 获取全部回收站条目（按删除时间降序） */
    fun getAll(): List<TrashEntry> {
        synchronized(lock) {
            if (!file.exists()) return emptyList()
            return try {
                val json = file.readText().trim()
                if (json.isEmpty() || json == "[]") return emptyList()
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.getJSONObject(i)
                    val uri = obj.getString("uri")
                    // 过滤空白 URI（防御 corrupted JSON）
                    if (uri.isBlank()) return@mapNotNull null
                    TrashEntry(
                        uri = uri,
                        filePath = obj.optString("filePath", ""),
                        fileName = obj.optString("fileName", ""),
                        deleteTime = obj.optLong("deleteTime", 0L),
                        originalAlbumId = obj.optString("originalAlbumId").takeIf { it.isNotEmpty() && it != "null" } ?: null,
                        originalAlbumName = obj.optString("originalAlbumName").takeIf { it.isNotEmpty() && it != "null" } ?: null,
                        mimeType = obj.optString("mimeType", "")
                    )
                }.sortedByDescending { it.deleteTime }
                    // 按 URI 去重（防止快速连续删除产生的重复条目导致 TrashScreen 闪退）
                    .distinctBy { it.uri }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to read trash index", e)
                emptyList()
            }
        }
    }

    /** 添加一条快删记录（如果已存在同 URI 的条目，先移除旧的再加入新的） */
    fun add(entry: TrashEntry) {
        synchronized(lock) {
            val list = getAll().toMutableList()
            // 移除已存在的同 URI 条目（防止快速连续删除写入重复 key）
            list.removeAll { it.uri == entry.uri }
            list.add(entry)
            writeAll(list)
            AppLogger.d(TAG, "add: ${entry.fileName} total=${list.size}")
        }
    }

    /** Batch add with a single index read and durable write. */
    fun addAll(entries: List<TrashEntry>): Int {
        if (entries.isEmpty()) return count()
        synchronized(lock) {
            val merged = LinkedHashMap<String, TrashEntry>()
            getAll().forEach { merged[it.uri] = it }
            entries.forEach { entry ->
                merged.remove(entry.uri)
                merged[entry.uri] = entry
            }
            val result = merged.values.toList()
            writeAll(result)
            AppLogger.d(TAG, "addAll: ${entries.size} entries total=${result.size}")
            return result.size
        }
    }

    /** 从回收站移除一条记录（恢复或永久删除后） */
    fun remove(uri: String) {
        synchronized(lock) {
            val before = getAll().size
            val list = getAll().filter { it.uri != uri }
            if (list.size == before) return
            writeAll(list)
            AppLogger.d(TAG, "remove: $uri remaining=${list.size}")
        }
    }

    /** 检查 URI 是否已标记为删除 */
    fun isTrashed(uri: String): Boolean {
        return getAll().any { it.uri == uri }
    }

    /** 获取回收站条目数量 */
    fun count(): Int = getAll().size

    /** 获取图片类条目数量 */
    fun getImageCount(): Int = getAll().count { !it.isVideo }

    /** 获取视频类条目数量 */
    fun getVideoCount(): Int = getAll().count { it.isVideo }

    /** 清空回收站（返回被清空的 URI 列表，供批量 createDeleteRequest 使用） */
    fun clearAll(): List<String> {
        synchronized(lock) {
            val all = getAll()
            val uris = all.map { it.uri }
            writeAll(emptyList())
            AppLogger.d(TAG, "clearAll: ${uris.size} entries removed")
            return uris
        }
    }

    /** 批量移除条目 */
    fun removeAll(uris: List<String>) {
        synchronized(lock) {
            val list = getAll().filter { it.uri !in uris }
            writeAll(list)
            AppLogger.d(TAG, "removeAll: removed ${uris.size} entries, remaining=${list.size}")
        }
    }

    // ── 原子写入（带 fsync 落盘确认）──

    private fun writeAll(entries: List<TrashEntry>) {
        val temp = File(file.absolutePath + ".tmp")
        try {
            val arr = JSONArray()
            entries.forEach { entry ->
                arr.put(JSONObject().apply {
                    put("uri", entry.uri)
                    put("filePath", entry.filePath)
                    put("fileName", entry.fileName)
                    put("deleteTime", entry.deleteTime)
                    put("originalAlbumId", entry.originalAlbumId ?: JSONObject.NULL)
                    put("originalAlbumName", entry.originalAlbumName ?: JSONObject.NULL)
                    put("mimeType", entry.mimeType)
                })
            }
            // 写入临时文件 + fsync 确保数据落盘
            FileOutputStream(temp).use { fos ->
                fos.write(arr.toString().toByteArray(Charsets.UTF_8))
                fos.fd.sync()
            }
            // 原子替换：rename(2) 在 Linux/Android 上即使目标存在也是原子的
            // 不需要先 delete()，避免断电窗口
            temp.renameTo(file)
            AppLogger.d(TAG, "writeAll: ${entries.size} entries")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to write trash index", e)
            // 写入失败时清理临时文件
            try { temp.delete() } catch (_: Exception) {}
        }
    }

    // ── 自动清理过期条目 ──

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val cutoff = now - MAX_AGE_DAYS * 24 * 60 * 60 * 1000L
        synchronized(lock) {
            if (!file.exists()) return
            try {
                val all = getAll()
                val fresh = all.filter { it.deleteTime >= cutoff }
                if (fresh.size < all.size) {
                    writeAll(fresh)
                    AppLogger.d(TAG, "cleanup: removed ${all.size - fresh.size} expired entries")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "cleanup failed", e)
            }
        }
    }
}
