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
 * SMB 缩略图异步加载器。
 *
 * ### 为什么不用 decodeStream？
 * [BitmapFactory.decodeStream] 依赖 InputStream 的 mark/reset 来预读图片格式头，
 * 但 [jcifs.smb.SmbFileInputStream] 不支持 mark/reset。即使包裹 BufferedInputStream，
 * SMB 网络流的缓冲行为也可能导致 reset 失败，最终 decodeStream 返回 null。
 *
 * ### 方案
 * 先将整个 SMB 文件读入 byte[]（使用 SmbRepository.readBytes），
 * 再用 [BitmapFactory.decodeByteArray] 解码，彻底避免 mark/reset 问题。
 * 有 60 个 Bitmap 的 LRU 内存缓存 + 15 秒超时保护。
 */
object SmbThumbnailLoader {

    private const val MAX_CACHE_SIZE = 60
    private const val TIMEOUT_MS = 15_000L
    private const val MAX_FAILED_URLS = 200
    private const val THUMB_MAX_PX = 400

    /** 视频缩略图：从 SMB 读取前 500KB（大概率覆盖 faststart 首帧），不走整文件读取 */
    private const val VIDEO_HEADER_BYTES = 512 * 1024
    /** 最小有效视频头部字节数 */
    private const val MIN_VIDEO_HEADER_BYTES = 100
    /** 视频缩略图并发数上限（限制同时读取数量，避免 SMB 服务器过载） */
    private const val MAX_CONCURRENT_VIDEO_THUMBS = 4

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_SIZE) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.allocationByteCount / 1024
        }
    }

    private val pendingJobs = HashSet<String>()
    private val failedUrls = object : LruCache<String, Boolean>(MAX_FAILED_URLS) {}

    /** 视频缩略图加载信号量：限制并发读 SMB 头部数 */
    private val videoThumbSemaphore = Semaphore(MAX_CONCURRENT_VIDEO_THUMBS)

    /** MD5 哈希 URL 为安全的临时文件名 */
    private fun urlHash(url: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * 加载 SMB 图片缩略图。
     *
     * @param url smb:// 完整路径
     * @param fileSize 文件大小（字节），用于估算采样率。0 或负值 = 不采样
     * @param maxPx 缩略图最大像素尺寸
     * @return Bitmap 或 null（失败/超时/历史失败）
     */
    suspend fun load(url: String, fileSize: Long = 0, maxPx: Int = THUMB_MAX_PX, context: Context? = null): Bitmap? {
        cache.get(url)?.let { return it }
        if (failedUrls.get(url) != null) return null

        synchronized(pendingJobs) {
            if (url in pendingJobs) return null
            pendingJobs.add(url)
        }

        try {
            return withContext(Dispatchers.IO) {
                runCatching {
                    withTimeout(TIMEOUT_MS) {
                        loadBytes(url, fileSize, maxPx, context)
                    }
                }.onFailure { e ->
                    if (e is TimeoutCancellationException) {
                        failedUrls.put(url, true)
                    }
                }.getOrNull()
            }
        } finally {
            synchronized(pendingJobs) { pendingJobs.remove(url) }
        }
    }

    /**
     * 核心逻辑：图片用 readBytes + decodeByteArray；视频用 partial read + MediaMetadataRetriever。
     */
    private suspend fun loadBytes(url: String, fileSize: Long, maxPx: Int, context: Context? = null): Bitmap? {
        val urlLower = url.lowercase()
        if (SmbRepository.VIDEO_EXTS.any { urlLower.endsWith(it) }) {
            // 视频文件：用 partial read（~500KB）+ MediaMetadataRetriever 提取首帧
            return if (context != null) loadVideoThumbnail(url, context) else null
        }

        // ── 以下为图片缩略图加载（不变）──
        AppLogger.d("SMB-THUMB", "loadBytes start: $url size=$fileSize")
        val repo = SmbRepository.getInstance()

        val roughSample = estimateSampleSize(fileSize)
        AppLogger.d("SMB-THUMB", "roughSample=$roughSample")

        val bytesResult = repo.readBytes(url)
        val bytes = bytesResult.getOrNull()
        if (bytes == null) {
            AppLogger.d("SMB-THUMB", "readBytes FAILED: ${bytesResult.exceptionOrNull()?.message}")
            return null
        }
        AppLogger.d("SMB-THUMB", "readBytes OK: ${bytes.size} bytes")

        // 用 inJustDecodeBounds 获取实际尺寸
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        AppLogger.d("SMB-THUMB", "bounds: ${boundsOpts.outWidth}x${boundsOpts.outHeight} type=${boundsOpts.outMimeType}")

        val exactSample = if (boundsOpts.outWidth > 0 && boundsOpts.outHeight > 0) {
            computeSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, maxPx)
        } else {
            roughSample
        }
        AppLogger.d("SMB-THUMB", "exactSample=$exactSample")

        val opts = BitmapFactory.Options().apply {
            inSampleSize = exactSample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        AppLogger.d("SMB-THUMB", "decodeByteArray: bitmap=${if (bitmap != null) "${bitmap.width}x${bitmap.height}" else "null"}")

        if (bitmap != null) {
            cache.put(url, bitmap)
        } else {
            failedUrls.put(url, true)
        }
        return bitmap
    }

    /**
     * 视频缩略图加载：读前 500KB → 写临时文件 → MediaMetadataRetriever 提取首帧。
     *
     * ### 为什么用临时文件？
     * [MediaMetadataRetriever.setDataSource] 不支持 SMB URI 或 byte[]，需要本地文件路径。
     *
     * ### 性能
     * 局域网内读 500KB ~40ms，MediaMetadataRetriever 解码 ~100ms，总耗时 <200ms。
     * 结果缓存到 LRU，滚动回收不重复加载。
     *
     * ### 降级
     * 非 faststart 视频（moov atom 在末尾）或编解码失败 → 返回 null → 调用方保持 ▶ 图标。
     */
    private suspend fun loadVideoThumbnail(url: String, context: Context): Bitmap? {
        // 限制并发数：避免大量视频同时读 SMB 头部拖慢服务器
        videoThumbSemaphore.acquire()
        try {
            return loadVideoThumbnailInternal(url, context)
        } finally {
            videoThumbSemaphore.release()
        }
    }

    private suspend fun loadVideoThumbnailInternal(url: String, context: Context): Bitmap? {
        AppLogger.d("SMB-THUMB", "loadVideoThumbnail start: $url")
        val repo = SmbRepository.getInstance()

        val bytesResult = repo.readBytesPartial(url, VIDEO_HEADER_BYTES)
        val bytes = bytesResult.getOrNull()
        if (bytes == null || bytes.size < MIN_VIDEO_HEADER_BYTES) {
            AppLogger.d("SMB-THUMB", "loadVideoThumbnail: header too small or failed")
            return null
        }
        AppLogger.d("SMB-THUMB", "loadVideoThumbnail: read ${bytes.size} bytes")

        val hash = urlHash(url)
        val tempFile = File(context.cacheDir, "smb_vid_thumb_$hash")
        try {
            tempFile.writeBytes(bytes)

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(tempFile.absolutePath)
                val rawBitmap = retriever.frameAtTime
                    ?: run {
                        AppLogger.d("SMB-THUMB", "loadVideoThumbnail: frameAtTime returned null (non-faststart?)")
                        return null
                    }

                val w = rawBitmap.width
                val h = rawBitmap.height
                if (w <= 1 || h <= 1) return null

                // 缩放到 THUMB_MAX_PX
                val scale = computeSampleSize(w, h, THUMB_MAX_PX)
                val thumbnail = if (scale <= 1) {
                    rawBitmap
                } else {
                    Bitmap.createScaledBitmap(rawBitmap, w / scale, h / scale, true)
                }

                cache.put(url, thumbnail)
                AppLogger.d("SMB-THUMB", "loadVideoThumbnail OK: ${thumbnail.width}x${thumbnail.height}")
                return thumbnail
            } catch (e: Exception) {
                AppLogger.d("SMB-THUMB", "loadVideoThumbnail decode failed: ${e.message}")
                return null
            } finally {
                retriever.release()
            }
        } catch (e: Exception) {
            AppLogger.d("SMB-THUMB", "loadVideoThumbnail IO failed: ${e.message}")
            return null
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

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

    fun evict(url: String) { cache.remove(url) }

    fun clear() {
        cache.evictAll()
        failedUrls.evictAll()
    }
}
