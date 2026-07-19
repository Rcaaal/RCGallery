package com.example.rcgallery.data.smb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import com.example.rcgallery.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore as CoroutineSemaphore
import kotlinx.coroutines.sync.withPermit
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * SMB 缩略图加载器（CX 文件管理器风格 + EXIF 内嵌缩略图优化）。
 *
 * ### 架构
 *
 * ```
 * load(url)
 *    ├── ① 内存 LRU 缓存 (200 Bitmap)
 *    ├── ② 磁盘 LRU 缓存 (50MB JPEG)
 *    ├── ③ SMB 远程加载（无并发限制 — CX 模式：所有可见条目同时加载）
 *    │     ├── 图片: 双层策略
 *    │     │    ├─ 策略1: 读文件头 64KB → EXIF 内嵌缩略图 ← ★ 核心优化
 *    │     │    ├─ 策略2: decodeStream(InputStream) 流式解码 ← CX 模式
 *    │     │    └─ 失败 → null（下次滚动重试）
 *    │     ├── 视频: 两层降级 + Semaphore(1)
 *    │     │    ├─ 策略1: CX 原生 MediaMetadataRetriever(smb://)
 *    │     │    ├─ 策略2: 头部 256KB → embeddedPicture（无 frameAtTime）
 *    │     │    └─ 失败 → null（下次滚动重试）
 *    │     └── 成功 → 同步写入磁盘缓存 + 内存缓存
 *    └── ④ 去重（pendingJobs）+ 8s 超时
 * ```
 *
 * ### EXIF 内嵌缩略图（核心性能优化）
 * 大多数手机相机和数码相机在 JPEG 文件的 APP1 (EXIF) 段中嵌入小尺寸缩略图。
 * - 缩略图通常为 160×120 JPEG，大小 2-20KB
 * - 位于文件开头 ~4-64KB 范围内
 * - 读取量从全文件（5-20MB）降至头部（64KB），**提速 100-500 倍**
 * - 无 EXIF 缩略图时自动降级为流式解码
 *
 * ### 与 CX 的差异
 * - CX 使用 `file/n.openInputStream()` + `BitmapFactory.decodeStream` 流式解码 ✅ 已复刻
 * - CX 在真机上 `MediaMetadataRetriever.setDataSource(smb://)` 直接提取视频帧 ✅ 已复刻
 * - CX 不做并发限制，所有可见条目同时加载 ✅ 已复刻
 * - CX 有后台 ScanService 预生成缩略图；我们按需延迟生成
 * - **但磁盘缓存效果一致**：第一次加载后，后续直接从磁盘读取，无需 SMB
 */
object SmbThumbnailLoader {

    private const val MAX_MEM_CACHE = 200
    /** 单次缩略图读取超时（8 秒 — 足够完成一次 SMB read，太长会堵塞 IO 线程池） */
    private const val TIMEOUT_MS = 8_000L
    private const val THUMB_MAX_PX = 400
    /** 磁盘缓存上限（50MB） */
    private const val DISK_CACHE_MAX_BYTES = 50L * 1024 * 1024

    /** 图片文件最大大小（超过此值的文件跳过缩略图，防止 OOM） */
    private const val MAX_IMAGE_FILE_SIZE = 20L * 1024 * 1024

    /** EXIF 头部读取大小（64KB：足够覆盖绝大多数 EXIF APP1 段） */
    private const val EXIF_HEADER_BYTES = 64 * 1024

    /** 视频缩略图：从 SMB 读取前 [VIDEO_HEADER_BYTES] 字节 */
    private const val VIDEO_HEADER_BYTES = 256 * 1024

    /** 缩略图整体并发上限（仅视频需要限制，图片不限制） */
    private const val MAX_CONCURRENT_VIDEO_THUMBS = 1
    private const val MAX_CONCURRENT_REMOTE_THUMBS = 2

    private const val TAG = "SMB-THUMB"

    // ── 内存缓存 ──
    private val memCache = object : LruCache<String, Bitmap>(MAX_MEM_CACHE) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.allocationByteCount / 1024
        }
    }

    // ── 视频缩略图并发控制（MediaMetadataRetriever 可能占用长时间连接）──
    private val videoThumbSemaphore = Semaphore(MAX_CONCURRENT_VIDEO_THUMBS)
    private val remoteThumbSemaphore = CoroutineSemaphore(MAX_CONCURRENT_REMOTE_THUMBS)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var initialized = false

    // ── 磁盘缓存目录 ──
    private var diskCacheDir: File? = null

    // ── 预览缓存（屏幕分辨率采样图，CX 式 temp/SMB/ 本地缓存）──
    private var previewCacheDir: File? = null

    /**
     * 初始化磁盘缓存目录。
     * 同时清理所有 SMB 相关缓存（播放缓存等），防止膨胀。
     */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
        val appCtx = context.applicationContext
        // 初始化缩略图缓存目录
        val dir = File(appCtx.cacheDir, "smb_thumb_cache")
        if (!dir.exists()) dir.mkdirs()
        diskCacheDir = dir

        // 初始化预览缓存目录（CX 式：本地缓存屏幕分辨率采样图，秒开预览）
        val previewDir = File(appCtx.cacheDir, "smb_preview_cache")
        if (!previewDir.exists()) previewDir.mkdirs()
        previewCacheDir = previewDir

        // 清理 smb_play_cache（完整视频缓存，没有大小限制，易膨胀）
            initialized = true
            cleanupScope.launch {
                val playCacheDir = File(appCtx.cacheDir, "smb_play_cache")
                playCacheDir.listFiles()?.forEach { it.delete() }
                appCtx.cacheDir.listFiles { file ->
                    file.name.startsWith("smb_vid_thumb_") ||
                        file.name.startsWith("smb_video_") ||
                        file.name.startsWith("smb_exif_")
                }?.forEach { it.delete() }
                AppLogger.d(TAG, "init once: disk cache dir=${dir.absolutePath} cleaned play cache")
            }
        }
    }

    /**
     * 加载缩略图（CX 模式：无并发限制，先到先得）。
     *
     * 与 CX 文件管理器行为一致：
     * - 不限制并发连接数，所有可见条目同时开始加载
     * - 不黑名单——加载失败下次滚动再试
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

        // ③ CX 式：不去重，直接加载。去重在内存/磁盘缓存层已经做了
        if (!coroutineContext.isActive) return@withContext null
        var result: Bitmap? = null
        try {
            remoteThumbSemaphore.withPermit {
                if (!coroutineContext.isActive) return@withPermit
                withTimeout(TIMEOUT_MS) {
                    result = loadBytes(url, fileSize, maxPx, context)
                }
            }
        } catch (e: TimeoutCancellationException) {
            AppLogger.d(TAG, "timeout: $url")
        }
        result
    }

    /**
     * 核心加载逻辑（CX 模式）。
     *
     * - 图片: EXIF 64KB 头部快速提取 → 失败则 decodeStream(InputStream) 流式解码
     * - 视频: 优先 setDataSource(smb://) → 失败则 256KB 头部 embeddedPicture
     *
     * 流式解码优势：峰值内存从 15MB byte[] 降至 512KB buffer，避免 OOM。
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
        val repo = SmbRepository.getInstance()

        // 跳过超大文件
        if (fileSize > MAX_IMAGE_FILE_SIZE) {
            AppLogger.d(TAG, "skip large image (>20MB): $url size=$fileSize")
            return null
        }

        // 策略 1（★ 核心优化）: EXIF 内嵌缩略图提取
        val exifResult = loadExifThumbnail(url, maxPx, repo)
        if (exifResult != null) return exifResult

        // 策略 2（CX 式）: 流式解码 — decodeStream(InputStream)
        // 相比 readBytes+decodeByteArray，无需预先分配整个文件的 ByteArray
        AppLogger.d(TAG, "no EXIF thumbnail, stream decode: $url")
        val streamResult = repo.getInputStream(url)
        val stream = streamResult.getOrNull()
        if (stream == null) {
            AppLogger.d(TAG, "image stream open failed: $url")
            return null
        }
        try {
            val buffered = BufferedInputStream(stream, 512 * 1024)

            // 先读取边界计算采样率（使用 mark/reset 避免重开 SMB 连接）
            buffered.mark(512 * 1024)
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(buffered, null, boundsOpts)
            buffered.reset()

            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {
                AppLogger.d(TAG, "image bounds failed: $url")
                return null
            }

            val exactSample = computeSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, maxPx)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = exactSample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = BitmapFactory.decodeStream(buffered, null, opts)
            if (bitmap == null) {
                AppLogger.d(TAG, "image decode failed: $url")
                return null
            }

            // 缓存
            memCache.put(url, bitmap)
            saveToDiskCache(url, bitmap)
            AppLogger.d(TAG, "image thumbnail OK (stream): ${bitmap.width}x${bitmap.height} url=$url")
            return bitmap
        } finally {
            try { stream.close() } catch (_: Exception) { }
        }
    }

    // ══════════════════════════════════════
    //  EXIF 内嵌缩略图提取（核心性能优化）
    // ══════════════════════════════════════

    /**
     * 尝试从 JPEG 文件的 EXIF APP1 段中提取内嵌缩略图。
     *
     * ### 原理
     * 大多数手机相机和数码相机拍摄 JPEG 时会在 EXIF 中嵌入一个
     * 小尺寸缩略图（通常 160×120）。该缩略图位于文件开头 ~4-64KB 范围，
     * 而 JPEG 完整文件通常 5-20MB。
     *
     * ### 流程
     * ```
     * readBytesPartial(64KB) → 解析 JPEG marker → 找到 APP1(Exif)
     *   → 解析 TIFF 头 → 导航 IFD0 → IFD1(缩略图)
     *   → 读取缩略图偏移 + 长度 → decodeByteArray
     * ```
     *
     * @return 缩略图 Bitmap，失败返回 null（调用方降级为完整读取）
     */
    private suspend fun loadExifThumbnail(
        url: String,
        maxPx: Int,
        repo: SmbRepository
    ): Bitmap? {
        try {
            // 读文件前 64KB（足够覆盖 EXIF APP1 段 + 内嵌缩略图）
            val headerResult = repo.readBytesPartial(url, EXIF_HEADER_BYTES)
            val header = headerResult.getOrNull()
            if (header == null) {
                AppLogger.d(TAG, "EXIF header read failed: $url")
                return null
            }
            if (header.size < 100) {
                AppLogger.d(TAG, "EXIF header too small (${header.size}): $url")
                return null
            }

            val thumbData = parseExifThumbnail(header)
            if (thumbData == null) {
                AppLogger.d(TAG, "EXIF not found in header: $url")
                return null
            }

            // 解码 EXIF 缩略图（通常是 JPEG 格式的小图）
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            var bm = BitmapFactory.decodeByteArray(thumbData, 0, thumbData.size, opts)
            if (bm == null) return null

            // 缩略图通常 160×120，但有些相机会嵌入较大尺寸，需要缩放
            if (bm.width > maxPx || bm.height > maxPx) {
                bm = Bitmap.createScaledBitmap(bm, maxPx,
                    (maxPx * bm.height / bm.width).coerceAtLeast(1), true)
            }

            // 缓存
            memCache.put(url, bm)
            saveToDiskCache(url, bm)
            AppLogger.d(TAG, "EXIF thumbnail OK: ${bm.width}x${bm.height} url=$url")
            return bm
        } catch (e: Exception) {
            AppLogger.d(TAG, "EXIF fallback: ${e.message}")
            return null
        }
    }

    /**
     * 解析 JPEG EXIF APP1 段，提取内嵌缩略图数据。
     *
     * ### JPEG 文件结构（简化）
     * ```
     * SOI: FF D8                       (2 字节)
     * APP1: FF E1 [长度] "Exif\0\0"    (至少 12 字节)
     *   └─ TIFF 头
     *        ├─ 字节序 ("II"/"MM")     (2 字节)
     *        ├─ 魔数 0x002A            (2 字节)
     *        ├─ IFD0 偏移              (4 字节)
     *        └─ IFD0 (图像属性目录)
     *             ├─ 条目数            (2 字节)
     *             ├─ 条目 ...          (12 字节 × 条目数)
     *             └─ IFD1 偏移         (4 字节)
     *                └─ IFD1 (缩略图目录)
     *                     ├─ 条目数    (2 字节)
     *                     ├─ 条目 ...
     *                     │   ├─ 0x0201 JPEGInterchangeFormat → 缩略图偏移
     *                     │   └─ 0x0202 JPEGInterchangeFormatLength → 缩略图长度
     *                     └─ (缩略图数据紧跟其后或单独存储)
     * ```
     *
     * @param data JPEG 文件头部数据（至少包含完整的 APP1 段）
     * @return 内嵌缩略图的原始字节数据（JPEG 编码），null 表示无缩略图或解析失败
     */
    private fun parseExifThumbnail(data: ByteArray): ByteArray? {
        // 第一步：定位 APP1 (FF E1) 段
        var i = 0
        // 跳过 SOI (FF D8)
        if (data.size < 2 || data[0] != 0xFF.toByte() || data[1] != 0xD8.toByte()) return null
        i = 2

        // 遍历 marker，找到 APP1
        var app1Data: ByteArray? = null
        while (i + 4 < data.size) {
            // 每段以 FF + marker 开头
            if (data[i] != 0xFF.toByte()) break // 无效的 marker
            val marker = data[i + 1].toInt() and 0xFF
            // SOF/SOS 特殊处理——SOS 之后是图像数据，不再有 APP1
            if (marker == 0xDA) break // SOS — 图像数据开始
            // RST markers (0xD0-0xD7) 没有长度字段
            if (marker in 0xD0..0xD7) { i += 2; continue }
            // 标记段长度（大端 2 字节，含长度字段本身）
            if (i + 4 > data.size) break
            val segLen = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
            if (segLen < 2) break

            if (marker == 0xE1) {
                // APP1 — Exif 段
                if (i + 4 + 6 > data.size) return null
                // 检查标识符："Exif\0\0"
                if (data[i + 4] == 0x45.toByte() && // 'E'
                    data[i + 5] == 0x78.toByte() && // 'x'
                    data[i + 6] == 0x69.toByte() && // 'i'
                    data[i + 7] == 0x66.toByte() && // 'f'
                    data[i + 8] == 0x00.toByte() &&
                    data[i + 9] == 0x00.toByte()) {
                    // JPEG segment: FF E1 [len_hi][len_lo] "Exif\0\0" [TIFF...]
                    //                ^                        ^
                    //                i                       i+10
                    // TIFF 头从 "Exif\0\0" 之后开始 = i + 4 + 6 = i + 10
                    app1Data = data
                    break
                }
            }

            i += 2 + segLen // 跳过当前 marker 段
        }

        if (app1Data == null) return null

        // TIFF 头从 "Exif\0\0" 后开始 = i + 4(FF E1 + len) + 6("Exif\0\0") = i + 10
        val tiffOffset = i + 4 + 6
        if (tiffOffset + 8 > data.size) return null

        // 字节序
        val le: Boolean = when {
            data[tiffOffset] == 0x49.toByte() && data[tiffOffset + 1] == 0x49.toByte() -> true
            data[tiffOffset] == 0x4D.toByte() && data[tiffOffset + 1] == 0x4D.toByte() -> false
            else -> return null
        }

        // TIFF 魔数 0x002A
        val magic = readShort(data, tiffOffset + 2, le)
        if (magic != 42) return null

        // IFD0 偏移（相对于 TIFF 头）
        val ifd0OffsetRel = readInt(data, tiffOffset + 4, le)
        if (ifd0OffsetRel < 8) return null
        val ifd0Offset = tiffOffset + ifd0OffsetRel
        if (ifd0Offset + 2 > data.size) return null

        // 第三步：读取 IFD0 条目数，找到指向 IFD1 的偏移
        val ifd0Count = readShort(data, ifd0Offset, le)
        // IFD1 偏移在 IFD0 条目之后（偏移 = IFD0 + 2 + count * 12）
        val ifd1OffsetPos = ifd0Offset + 2 + ifd0Count * 12
        if (ifd1OffsetPos + 4 > data.size) return null
        val ifd1OffsetRel = readInt(data, ifd1OffsetPos, le)
        if (ifd1OffsetRel <= 0) return null
        val ifd1Offset = tiffOffset + ifd1OffsetRel
        if (ifd1Offset + 2 > data.size) return null

        // 第四步：解析 IFD1（缩略图目录）
        val ifd1Count = readShort(data, ifd1Offset, le)
        var thumbOffset = 0
        var thumbLength = 0

        for (j in 0 until ifd1Count) {
            val entryPos = ifd1Offset + 2 + j * 12
            if (entryPos + 12 > data.size) break
            val tag = readShort(data, entryPos, le)
            // 每个 IFD 条目 12 字节：
            // [tag 2B] [type 2B] [count 4B] [value/offset 4B]
            when (tag) {
                0x0201 -> thumbOffset = readInt(data, entryPos + 8, le) // JPEGInterchangeFormat
                0x0202 -> thumbLength = readInt(data, entryPos + 8, le) // JPEGInterchangeFormatLength
            }
        }

        if (thumbOffset <= 0 || thumbLength <= 0) return null

        // 缩略图偏移也是相对于 TIFF 头的
        val thumbDataOffset = tiffOffset + thumbOffset
        if (thumbDataOffset + thumbLength > data.size) return null

        return data.copyOfRange(thumbDataOffset, thumbDataOffset + thumbLength)
    }

    /**
     * 从字节数组读取 2 字节无符号短整数（小端或大端）。
     */
    private fun readShort(data: ByteArray, offset: Int, le: Boolean): Int {
        return if (le) {
            (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8)
        } else {
            ((data[offset].toInt() and 0xFF) shl 8) or
            (data[offset + 1].toInt() and 0xFF)
        }
    }

    /**
     * 从字节数组读取 4 字节整数（小端或大端）。
     */
    private fun readInt(data: ByteArray, offset: Int, le: Boolean): Int {
        return if (le) {
            (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
        } else {
            ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
        }
    }

    // ══════════════════════════════════════
    //  视频缩略图
    // ══════════════════════════════════════

    /**
     * 视频缩略图加载 — 两层策略 + 专用 Semaphore(1) 保护。
     *
     * ### 策略 1（CX 原生方式）
     * 直接传 SMB URL 给 [MediaMetadataRetriever.setDataSource]，
     * 如果 Android 支持 smb:// URI，优先提取 embeddedPicture，再 frameAtTime。
     *
     * ### 策略 2（嵌入式封面头部读取，非 faststart 视频可能失败）
     * 读视频头部 256KB 到临时文件，尝试 MediaMetadataRetriever.embeddedPicture。
     * 不再尝试 frameAtTime（对非 faststart 视频前 256KB 不包含关键帧）。
     *
     * ### 视频单并发 (Semaphore 1)
     * MediaMetadataRetriever.setDataSource(smb://) 在真机上可能打开大连接，
     * 限制 1 个并发防止 SMB 连接耗尽。
     */
    private suspend fun loadVideoThumbnail(url: String, context: Context): Bitmap? {
        val got = videoThumbSemaphore.tryAcquire(5, TimeUnit.SECONDS)
        if (!got) {
            AppLogger.d(TAG, "video semaphore timeout (5s): $url")
            return null
        }
        try {
            // ── 策略 1: CX 原生 — 直接传 SMB URL ──
            val cxResult = loadVideoCxDirect(url)
            if (cxResult != null) return cxResult

            // ── 策略 2: 256KB 头部 embeddedPicture 尝试 ──
            return loadVideoEmbeddedFallback(url, context)
        } finally {
            videoThumbSemaphore.release()
        }
    }

    /**
     * CX 原生视频缩略图：直接传 SMB URL 给 MediaMetadataRetriever。
     * 只在真机有效，模拟器不支持 smb://。
     */
    private suspend fun loadVideoCxDirect(url: String): Bitmap? {
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
        return null
    }

    /**
     * 视频缩略图头部嵌入式封面提取（回退策略）。
     *
     * 读取视频前 256KB 到临时文件，尝试 [MediaMetadataRetriever.embeddedPicture]。
     *
     * ### 为什么不尝试 frameAtTime？
     * - 非 faststart 视频的关键帧在文件末尾，256KB 头部不包含
     * - 之前 1MB 头部读取 frameAtTime 实测几乎 100% 失败
     * - 每次失败浪费 SMB 连接和传输时间
     * - CX 的 ScanService 也是只提取 embeddedPicture，不做 frameAtTime
     */
    private suspend fun loadVideoEmbeddedFallback(url: String, context: Context): Bitmap? {
        try {
            val bytesResult = SmbRepository.getInstance().readBytesPartial(url, VIDEO_HEADER_BYTES)
            val bytes = bytesResult.getOrNull()
            if (bytes != null && bytes.size >= 100) {
                val hash = urlHash(url)
                val tempFile = File(context.cacheDir, "smb_vid_thumb_$hash")
                try {
                    tempFile.writeBytes(bytes)
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(tempFile.absolutePath)
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
                        AppLogger.d(TAG, "video no embedded picture in header: $url")
                    } finally {
                        retriever.release()
                    }
                } finally {
                    if (tempFile.exists()) tempFile.delete()
                }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "video header embedded error: ${e.message}")
        }
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
     * 每存储 10 个文件检查一次，避免频繁遍历。
     */
    private var saveCounter = 0
    private fun trimDiskCache() {
        saveCounter++
        if (saveCounter % 10 != 0) return // 每 10 次检查一次
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

    /**
     * 从磁盘缓存读取指定 URL 的缩略图 Bitmap。
     * 用于预览页面的即时占位图：列表/网格已缓存过的图，预览瞬间显示。
     * 返回 null 表示无缓存（首次打开或缓存已清理）。
     */
    fun getDiskCacheBitmap(url: String): Bitmap? {
        val dir = diskCacheDir ?: return null
        val file = File(dir, urlHash(url))
        if (!file.exists()) return null
        try {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            return BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Exception) { return null }
    }

    // ══════════════════════════════════════
    //  预览缓存（CX 式本地缓存屏幕分辨率采样图）
    // ══════════════════════════════════════

    /**
     * 从预览缓存读取屏幕分辨率采样图。
     * CX 的 cache/temp/SMB/ 等价物。
     */
    fun getPreviewCacheBitmap(url: String): Bitmap? {
        val dir = previewCacheDir ?: return null
        val file = File(dir, urlHash(url))
        if (!file.exists()) return null
        try {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Exception) {
            file.delete()
            return null
        }
    }

    /**
     * 将屏幕分辨率采样图存入预览缓存。
     * 与 CX 的 cache/temp/SMB/ 行为一致——存 JPEG quality=90。
     */
    fun savePreviewCache(url: String, bitmap: Bitmap) {
        val dir = previewCacheDir ?: return
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, urlHash(url))
        if (file.exists()) return
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.flush()
            }
        } catch (_: Exception) { }
    }

    /**
     * 备份预缓存一张图片：流式解码到屏幕分辨率后写入预览缓存。
     * 在 Phase 2 或文件夹加载完成后后台调用。
     */
    suspend fun precachePreview(url: String, fileSize: Long = 0) = withContext(Dispatchers.IO) {
        val dir = previewCacheDir ?: return@withContext
        val cacheFile = File(dir, urlHash(url))
        if (cacheFile.exists()) return@withContext // 已缓存

        val repo = SmbRepository.getInstance()
        val streamResult = repo.getInputStream(url)
        val stream = streamResult.getOrNull() ?: return@withContext
        try {
            val buffered = BufferedInputStream(stream, 256 * 1024)
            buffered.mark(256 * 1024)
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(buffered, null, boundsOpts)
            buffered.reset()
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return@withContext

            var sample = 1
            while (boundsOpts.outWidth / sample > 1080 || boundsOpts.outHeight / sample > 1080) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bm = BitmapFactory.decodeStream(buffered, null, opts)
            if (bm != null) {
                FileOutputStream(cacheFile).use { out ->
                    bm.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                bm.recycle()
            }
        } finally {
            try { stream.close() } catch (_: Exception) { }
        }
    }
}
