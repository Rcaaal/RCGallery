package com.example.rcgallery.data.smb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import com.example.rcgallery.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Semaphore

/**
 * SMB 缩略图加载器（CX 文件管理器风格）。
 *
 * ### 架构
 *
 * ```
 * load(url)
 *    ├── ① 内存 LRU 缓存 (60 Bitmap)
 *    ├── ② 磁盘 LRU 缓存 (50MB JPEG)
 *    ├── ③ SMB 远程加载（Semaphore 6 并发控制）
 *    │     ├── 图片: readBytes → decodeByteArray              ← 完整读取 + 采样解码
 *    │     │       20MB 上限，Semaphore(6) 防 OOM
 *    │     ├── 视频: 三层降级
 *    │     ├── 视频: 三层降级
 *    │     │     ├─ 策略1: CX 原生 MediaMetadataRetriever(smb://)
 *    │     │     ├─ 策略2: HTTP 代理 (Range 自由 seek)
 *    │     │     └─ 策略3: 头部 1MB → 临时文件 → frameAtTime
 *    │     └── 成功 → 同步写入磁盘缓存 + 内存缓存
 *    └── ④ Semaphore(6) 控制并发 SMB 读取数
 * ```
 *
 * ### 与 CX 的差异
 * - CX 用 `file/n.openInputStream()` + `decodeStream` 流式解码；我们用 readBytes + decodeByteArray
 * - CX 有后台 ScanService 预生成缩略图；我们按需延迟生成
 * - 但**磁盘缓存效果一致**：第一次加载后，后续直接从磁盘读取，无需 SMB
 *
 * ### 磁盘缓存行为
 * - 目录: `context.cacheDir/smb_thumb_cache/`
 * - 名称: URL 的 MD5 哈希
 * - 格式: JPEG quality=85
 * - 自动清理: 超过 [DISK_CACHE_MAX_BYTES] 时删除最旧文件
 */
object SmbThumbnailLoader {

    private const val MAX_MEM_CACHE = 60
    private const val TIMEOUT_MS = 60_000L
    private const val THUMB_MAX_PX = 400
    /** 磁盘缓存上限（50MB） */
    private const val DISK_CACHE_MAX_BYTES = 50L * 1024 * 1024

    /** 图片文件最大大小（超过此值的文件跳过缩略图，防止 OOM） */
    private const val MAX_IMAGE_FILE_SIZE = 20L * 1024 * 1024

    /** 视频缩略图：从 SMB 读取前 [VIDEO_HEADER_BYTES] 字节 */
    private const val VIDEO_HEADER_BYTES = 1024 * 1024
    /** 最小有效视频头部字节数 */
    private const val MIN_VIDEO_HEADER_BYTES = 100

    /** 缩略图整体并发上限 */
    private const val MAX_CONCURRENT_THUMBS = 4

    private const val TAG = "SMB-THUMB"

    // ── 内存缓存 ──
    private val memCache = object : LruCache<String, Bitmap>(MAX_MEM_CACHE) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.allocationByteCount / 1024
        }
    }

    // ── 并发控制 ──
    private val thumbSemaphore = Semaphore(MAX_CONCURRENT_THUMBS)

    // ── 去重 ──
    private val pendingJobs = HashSet<String>()

    // ── 磁盘缓存目录 ──
    private var diskCacheDir: File? = null

    /**
     * 初始化磁盘缓存目录。
     * 同时清理所有 SMB 相关缓存（播放缓存等），防止膨胀。
     */
    fun init(context: Context) {
        val appCtx = context.applicationContext
        // 初始化缩略图缓存目录
        val dir = File(appCtx.cacheDir, "smb_thumb_cache")
        if (!dir.exists()) dir.mkdirs()
        diskCacheDir = dir

        // 清理 smb_play_cache（完整视频缓存，没有大小限制，易膨胀）
        val playCacheDir = File(appCtx.cacheDir, "smb_play_cache")
        if (playCacheDir.exists()) {
            playCacheDir.listFiles()?.forEach { it.delete() }
        }

        // 清理旧的缓存临时文件
        val oldTempFiles = appCtx.cacheDir.listFiles { f ->
            f.name.startsWith("smb_vid_thumb_") || f.name.startsWith("smb_video_")
        }
        oldTempFiles?.forEach { it.delete() }

        AppLogger.d(TAG, "init: disk cache dir=${dir.absolutePath} cleaned play cache")
    }

    /**
     * 加载缩略图。
     *
     * CX 式的加载策略：
     * - 不黑名单——加载失败下次滚动再试
     * - Semaphore(6) 防止 SMB 过载
     * - 超时保护但不黑名单
     * - 磁盘缓存让第二次浏览秒出
     *
     * @param url smb:// 完整路径
     * @param fileSize 文件大小（字节），用于估算。0 或负值 = 未知
     * @param maxPx 缩略图最大像素尺寸
     * @param context 用于视频缩略图的临时文件目录，图片缩略图不需要
     * @return Bitmap 或 null
     */
    suspend fun load(
        url: String,
        fileSize: Long = 0,
        maxPx: Int = THUMB_MAX_PX,
        context: Context? = null
    ): Bitmap? = withContext(Dispatchers.IO) {
        // ① 内存缓存
        memCache.get(url)?.let { return@withContext it }

        // ② 磁盘缓存
        val diskFile = getDiskCacheFile(url)
        if (diskFile.exists()) {
            try {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inSampleSize = estimateSampleSize(diskFile.length())
                }
                val bm = BitmapFactory.decodeFile(diskFile.absolutePath, opts)
                if (bm != null) {
                    memCache.put(url, bm)
                    AppLogger.d(TAG, "disk cache hit: $url")
                    return@withContext bm
                }
            } catch (_: Exception) { }
            // 磁盘文件损坏则删除
            try { diskFile.delete() } catch (_: Exception) { }
        }

        // ③ 去重：同一 URL 只有一个加载任务
        synchronized(pendingJobs) {
            if (url in pendingJobs) return@withContext null
            pendingJobs.add(url)
        }

        try {
            // 用信号量限制并发 SMB 读取数
            thumbSemaphore.acquire()
            try {
                var result: Bitmap? = null
                try {
                    withTimeout(TIMEOUT_MS) {
                        result = loadBytes(url, fileSize, maxPx, context)
                    }
                } catch (e: TimeoutCancellationException) {
                    AppLogger.d(TAG, "timeout: $url")
                }
                result
            } finally {
                thumbSemaphore.release()
            }
        } finally {
            synchronized(pendingJobs) { pendingJobs.remove(url) }
        }
    }

    /**
     * 核心加载逻辑。
     */
    private suspend fun loadBytes(
        url: String,
        fileSize: Long,
        maxPx: Int,
        context: Context?
    ): Bitmap? {
        val urlLower = url.lowercase()
        if (SmbRepository.VIDEO_EXTS.any { urlLower.endsWith(it) }) {
            return if (context != null) loadVideoThumbnail(url, context) else null
        }

        // ── 图片缩略图 ──
        // 用 readBytes 完整读取（受 Semaphore(6) 限制，最多 6 个文件同时加载）
        // 20MB 上限 + 并发控制 → 峰值内存 ≤ 120MB，安全

        val repo = SmbRepository.getInstance()

        // 跳过超大文件
        if (fileSize > MAX_IMAGE_FILE_SIZE) {
            AppLogger.d(TAG, "skip large image (>20MB): $url size=$fileSize")
            return null
        }

        val bytesResult = repo.readBytes(url)
        val bytes = bytesResult.getOrNull()
        if (bytes == null || bytes.size < 100) {
            AppLogger.d(TAG, "image read failed or too small: $url")
            return null
        }

        // 取边界计算采样率
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {
            AppLogger.d(TAG, "image bounds failed: $url")
            return null
        }

        val exactSample = computeSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, maxPx)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = exactSample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (bitmap == null) {
            AppLogger.d(TAG, "image decode failed: $url")
            return null
        }

        // 缓存
        memCache.put(url, bitmap)
        saveToDiskCache(url, bitmap)
        AppLogger.d(TAG, "image thumbnail OK: ${bitmap.width}x${bitmap.height} url=$url")
        return bitmap
    }

    // ══════════════════════════════════════
    //  视频缩略图
    // ══════════════════════════════════════

    /**
     * 视频缩略图加载 — 两层策略。
     *
     * ### 策略 1（CX 原生方式）
     * 直接传 SMB URL 给 [MediaMetadataRetriever.setDataSource]，
     * 如果 Android 支持 smb:// URI，非 faststart 视频也能出图。
     *
     * ### 策略 2（头部读取回退）
     * 读文件头部 [VIDEO_HEADER_BYTES] 到临时文件，尝试 embeddedPicture 或 frameAtTime。
     * 对 faststart 视频和内嵌封面有效。失败则返回 null（显示 ▶ 图标）。
     *
     * ### ⚠️ 为什么不用 HTTP 代理？
     * 策略 2（HTTP 代理）会为每个视频打开完整的 RAF 连接（几百 MB），
     * 大量并发时耗尽 SMB 连接 → Broken pipe → UI 卡死。缩略图场景不适合。
     */
    private suspend fun loadVideoThumbnail(url: String, context: Context): Bitmap? {
        // ── 策略 1: CX 原生方式 — 直接传 SMB URL ──
        try {
            val retriever = MediaMetadataRetriever()
            try {
                val headers = HashMap<String, String>()
                retriever.setDataSource(url, headers)

                // ① embedded picture
                val embedded = retriever.embeddedPicture
                if (embedded != null) {
                    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                    var bm = BitmapFactory.decodeByteArray(embedded, 0, embedded.size, opts)
                    if (bm != null) {
                        if (bm.width > THUMB_MAX_PX || bm.height > THUMB_MAX_PX) {
                            bm = Bitmap.createScaledBitmap(bm, THUMB_MAX_PX,
                                (THUMB_MAX_PX * bm.height / bm.width).coerceAtLeast(1), true)
                        }
                        memCache.put(url, bm)
                        saveToDiskCache(url, bm)
                        AppLogger.d(TAG, "video embedded OK (CX): ${bm.width}x${bm.height}")
                        return bm
                    }
                }

                // ② frameAtTime
                val rawBitmap = retriever.frameAtTime
                if (rawBitmap != null && rawBitmap.width > 1 && rawBitmap.height > 1) {
                    val w = rawBitmap.width; val h = rawBitmap.height
                    if (w > THUMB_MAX_PX || h > THUMB_MAX_PX) {
                        val scale = computeSampleSize(w, h, THUMB_MAX_PX)
                        val thumb = Bitmap.createScaledBitmap(rawBitmap, w / scale, h / scale, true)
                        rawBitmap.recycle()
                        memCache.put(url, thumb)
                        saveToDiskCache(url, thumb)
                        AppLogger.d(TAG, "video frame OK (CX): ${thumb.width}x${thumb.height}")
                        return thumb
                    } else {
                        memCache.put(url, rawBitmap)
                        saveToDiskCache(url, rawBitmap)
                        AppLogger.d(TAG, "video frame OK (CX): ${rawBitmap.width}x${rawBitmap.height}")
                        return rawBitmap
                    }
                }
                AppLogger.d(TAG, "video CX direct failed, trying header fallback")
            } finally {
                retriever.release()
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "video CX direct error: ${e.message}, trying header fallback")
        }

        // ── 策略 2: 头部读取回退（仅限 embedded picture / faststart）──
        try {
            val bytesResult = SmbRepository.getInstance().readBytesPartial(url, VIDEO_HEADER_BYTES)
            val bytes = bytesResult.getOrNull()
            if (bytes != null && bytes.size >= MIN_VIDEO_HEADER_BYTES) {
                val hash = urlHash(url)
                val tempFile = File(context.cacheDir, "smb_vid_thumb_$hash")
                try {
                    tempFile.writeBytes(bytes)
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(tempFile.absolutePath)

                        // embedded picture
                        val embedded = retriever.embeddedPicture
                        if (embedded != null) {
                            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                            var bm = BitmapFactory.decodeByteArray(embedded, 0, embedded.size, opts)
                            if (bm != null) {
                                if (bm.width > THUMB_MAX_PX || bm.height > THUMB_MAX_PX) {
                                    bm = Bitmap.createScaledBitmap(bm, THUMB_MAX_PX,
                                        (THUMB_MAX_PX * bm.height / bm.width).coerceAtLeast(1), true)
                                }
                                memCache.put(url, bm)
                                saveToDiskCache(url, bm)
                                AppLogger.d(TAG, "video embedded OK (header): ${bm.width}x${bm.height}")
                                return bm
                            }
                        }

                        // frameAtTime
                        val rawBitmap = retriever.frameAtTime
                        if (rawBitmap != null && rawBitmap.width > 1 && rawBitmap.height > 1) {
                            val w = rawBitmap.width; val h = rawBitmap.height
                            val thumb = if (w > THUMB_MAX_PX || h > THUMB_MAX_PX) {
                                val scale = computeSampleSize(w, h, THUMB_MAX_PX)
                                Bitmap.createScaledBitmap(rawBitmap, w / scale, h / scale, true)
                            } else rawBitmap
                            if (thumb !== rawBitmap) rawBitmap.recycle()
                            memCache.put(url, thumb)
                            saveToDiskCache(url, thumb)
                            AppLogger.d(TAG, "video frame OK (header): ${thumb.width}x${thumb.height}")
                            return thumb
                        }
                    } finally {
                        retriever.release()
                    }
                } finally {
                    if (tempFile.exists()) tempFile.delete()
                }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "video header fallback error: ${e.message}")
        }

        AppLogger.d(TAG, "video all strategies failed: $url")
        return null
    }

    // ══════════════════════════════════════
    //  磁盘缓存
    // ══════════════════════════════════════

    /** MD5 哈希 URL 为安全的磁盘文件名 */
    private fun urlHash(url: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun getDiskCacheFile(url: String): File {
        val dir = diskCacheDir
        if (dir != null) return File(dir, urlHash(url))
        // 未初始化时返回一个不可能存在的文件（兜底）
        return File("/dev/null")
    }

    /**
     * 将缩略图存到磁盘（JPEG quality=85）。
     * 存完后检查缓存总大小，超过上限删除最旧文件。
     */
    private fun saveToDiskCache(url: String, bitmap: Bitmap) {
        val file = getDiskCacheFile(url) ?: return
        if (file.exists()) return  // 已缓存
        try {
            file.parentFile?.let { parent ->
                if (!parent.exists()) parent.mkdirs()
            }
            val out = java.io.FileOutputStream(file)
            try {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.flush()
            } finally {
                out.close()
            }
            // 清理超限缓存文件
            trimDiskCache()
        } catch (e: Exception) {
            AppLogger.d(TAG, "disk cache save failed: ${e.message}")
            try { file.delete() } catch (_: Exception) { }
        }
    }

    /**
     * 检查磁盘缓存总大小，超过 [DISK_CACHE_MAX_BYTES] 时删除最旧文件。
     */
    private fun trimDiskCache() {
        val dir = diskCacheDir ?: return
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_CACHE_MAX_BYTES) return
        for (f in files) {
            if (total <= DISK_CACHE_MAX_BYTES) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    /**
     * 从磁盘缓存移除指定 URL 的缩略图。
     */
    fun evict(url: String) {
        memCache.remove(url)
        getDiskCacheFile(url).let { if (it.exists()) it.delete() }
    }

    /**
     * 清除内存和磁盘缓存。
     */
    fun clear() {
        memCache.evictAll()
        diskCacheDir?.let { dir ->
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    // ══════════════════════════════════════
    //  工具
    // ══════════════════════════════════════

    /** 从文件大小估算 inSampleSize（2 的幂） */
    private fun estimateSampleSize(fileSize: Long): Int = when {
        fileSize <= 0L -> 1
        fileSize < 400_000L -> 1
        fileSize < 1_500_000L -> 2
        fileSize < 6_000_000L -> 4
        fileSize < 24_000_000L -> 8
        else -> 16
    }

    /** 根据实际宽高计算 inSampleSize */
    private fun computeSampleSize(width: Int, height: Int, maxPx: Int): Int {
        var sample = 1
        while (width / sample > maxPx || height / sample > maxPx) {
            sample *= 2
        }
        return sample
    }
}
