package com.example.rcgallery.data.smb

import jcifs.CIFSContext
import jcifs.context.SingletonContext
import jcifs.smb.SmbAuthException
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import jcifs.smb.SmbFileOutputStream
import jcifs.smb.SmbRandomAccessFile
import com.example.rcgallery.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * SMB 仓库 — 封装 jcifs-ng 的所有操作。
 *
 * ### 认证策略
 * 三级认证回退链：匿名 → 空凭据 → Guest + 空密码
 * 认证缓存按 host 缓存，带时间戳。`scanFolderContent` 认证失败自清理缓存并重试。
 * 不对文件名调 listFiles()，仅对目录认证。
 *
 * ### 协议配置
 * - 禁用 SMB1，仅用 SMB2/3（适配现代 Win10/11）
 * - 禁用签名偏好（大文件传输性能优化）
 */
class SmbRepository {

    companion object {
        private var propsSet = false
        private fun ensureProps() {
            if (!propsSet) {
                System.setProperty("jcifs.smb.client.connTimeout", "15000")
                // SMB 套接字超时：增大到 120 秒以容忍 SMB2 大请求的传输延迟
                // （8MB SMB2 READ 请求在模拟器/WiFi 下可能需要数秒完成）
                System.setProperty("jcifs.smb.client.soTimeout", "120000")
                System.setProperty("jcifs.smb.client.encoding", "UTF-8")
                System.setProperty("jcifs.smb.client.dfs.disabled", "true")
                // 禁用 SMB1，强制使用 SMB2/3（现代 Win10/11 默认禁用 SMB1）
                System.setProperty("jcifs.smb.client.disableSMB1", "true")
                // 禁用签名偏好，提升大文件传输性能
                System.setProperty("jcifs.smb.client.signingPreferred", "false")
                // jcifs-ng 2.1.9 uses this value as the SMB2 READ request size.
                // Its default is about 64KB, which severely limits high-latency Wi-Fi throughput.
                System.setProperty("jcifs.smb.client.rcv_buf_size", "1048576")
                propsSet = true
            }
        }

        @Volatile
        private var _instance: SmbRepository? = null
        fun getInstance(): SmbRepository {
            return _instance ?: synchronized(this) {
                _instance ?: SmbRepository().also { _instance = it }
            }
        }

        val IMAGE_EXTS = listOf(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp")
        val VIDEO_EXTS = listOf(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".asf", ".flv", ".3gp", ".webm")

        /** 单次 readBytes 最大读取字节数（50MB，防止 OOM） */
        private const val MAX_READ_BYTES = 50L * 1024 * 1024

        /** 认证缓存生存时间（10 分钟） */
        private const val AUTH_CACHE_TTL_MS = 10 * 60 * 1000L
    }

    private val baseCtx: CIFSContext by lazy {
        ensureProps()
        SingletonContext.getInstance()
    }

    // ══════════════════════════════════════
    //  认证系统
    // ══════════════════════════════════════

    private data class CachedAuth(
        val context: CIFSContext,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isFresh(): Boolean = (System.currentTimeMillis() - timestamp) < AUTH_CACHE_TTL_MS
    }

    private val authCache = HashMap<String, CachedAuth>()

    /**
     * 针对路径获取认证上下文（按 host 缓存，带 TTL 失效）。
     */
    private fun getContextForPath(path: String): CIFSContext {
        val host = extractHost(path)
        val cached = authCache[host]
        if (cached != null && cached.isFresh()) return cached.context

        authCache.remove(host)
        val dirPath = extractDirectory(path)

        for (cred in listOf(
            baseCtx.withAnonymousCredentials(),
            baseCtx.withCredentials(NtlmPasswordAuthenticator(null, "", "")),
            baseCtx.withCredentials(NtlmPasswordAuthenticator(null, "Guest", ""))
        )) {
            try {
                val testFile = SmbFile(dirPath, cred)
                testFile.listFiles()
                authCache[host] = CachedAuth(cred)
                return cred
            } catch (_: SmbAuthException) {
            }
        }
        val fallback = baseCtx.withCredentials(NtlmPasswordAuthenticator(null, "", ""))
        authCache[host] = CachedAuth(fallback)
        return fallback
    }

    /** 取路径的父目录，保证以 / 结尾，无双重 // */
    private fun extractDirectory(path: String): String {
        val clean = path.trimEnd('/')
        val lastSlash = clean.lastIndexOf('/')
        if (lastSlash <= "smb://".length) {
            return "$clean/"
        }
        val dir = clean.substring(0, lastSlash + 1)
        return ensureTrailingSlash(dir)
    }

    // ══════════════════════════════════════
    //  共享发现
    // ══════════════════════════════════════

    suspend fun listShares(host: String): Result<List<SmbShare>> = withContext(Dispatchers.IO) {
        runCatching {
            val ctx = getContextForPath("smb://$host/")
            val root = SmbFile("smb://$host/", ctx)
            root.listFiles().mapNotNull { smbFile ->
                val rawName = smbFile.name.trimEnd('/')
                if (rawName.endsWith("$")) null
                else SmbShare(name = rawName, path = smbFile.path)
            }
        }
    }

    // ══════════════════════════════════════
    //  快速扫描（两阶段加载 Part 1）
    // ══════════════════════════════════════

    /**
     * 快速扫描文件夹内容 — 只做顶层 [listFiles]，不扫子文件夹内部。
     *
     * 子文件夹的 [SmbSubFolder.mediaCount] 始终为 0，
     * [SmbSubFolder.coverPath] 始终为空字符串。
     * 由调用方后续通过 [scanSingleFolder] 逐步填充。
     *
     * 与 [scanFolderContent] 不同：不遍历子文件夹内容，返回极快。
     * 认证失败时自动清理缓存并重试一次。
     */
    suspend fun quickScanFolderContent(
        url: String
    ): Result<SmbFolderScanResult> = withContext(Dispatchers.IO) {
        try {
            val dirPath = ensureTrailingSlash(url)
            val ctx = getContextForPath(dirPath)
            Result.success(doQuickScan(dirPath, ctx))
        } catch (e: SmbAuthException) {
            val host = extractHost(url)
            authCache.remove(host)
            val dirPath = ensureTrailingSlash(url)
            val newCtx = getContextForPath(dirPath)
            Result.success(doQuickScan(dirPath, newCtx))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun doQuickScan(dirPath: String, ctx: CIFSContext): SmbFolderScanResult {
        val dir = SmbFile(dirPath, ctx)
        val all = dir.listFiles()
        val subFolders = mutableListOf<SmbSubFolder>()
        val mediaFiles = mutableListOf<SmbFileInfo>()

        for (f in all) {
            if (f.isDirectory) {
                subFolders.add(SmbSubFolder(name = f.name, path = f.path, lastModified = f.lastModified()))
            } else {
                val name = f.name
                val isImg = IMAGE_EXTS.any { name.endsWith(it, ignoreCase = true) }
                val isVid = VIDEO_EXTS.any { name.endsWith(it, ignoreCase = true) }
                if (isImg || isVid) {
                    mediaFiles.add(SmbFileInfo(name = name, path = f.path, size = f.length(), isVideo = isVid, lastModified = f.lastModified()))
                }
            }
        }

        subFolders.sortBy { it.name.lowercase() }
        mediaFiles.sortByDescending { it.name }
        return SmbFolderScanResult(subFolders, mediaFiles)
    }

    // ══════════════════════════════════════
    //  单文件夹扫描（两阶段加载 Part 2）
    // ══════════════════════════════════════

    /**
     * 扫描单个子文件夹，获取其中的媒体文件数量和封面路径。
     *
     * 适用于快速显示顶层内容后，异步填充每个文件夹的详情。
     * 认证上下文从缓存获取（~0ms）。
     *
     * @param folder 原始文件夹信息（name 和 path），扫描后返回更新版
     * @return 填充了 [mediaCount] 和 [coverPath] 的 [SmbSubFolder]
     */
    suspend fun scanSingleFolder(folder: SmbSubFolder): SmbSubFolder = withContext(Dispatchers.IO) {
        runCatching {
            val ctx = getContextForPath(folder.path)
            val dirPath = ensureTrailingSlash(folder.path)
            val dir = SmbFile(dirPath, ctx)
            val children = dir.listFiles()
            var cover = ""
            var count = 0
            for (c in children) {
                if (!c.isDirectory) {
                    val name = c.name
                    val isImg = IMAGE_EXTS.any { name.endsWith(it, ignoreCase = true) }
                    val isVid = VIDEO_EXTS.any { name.endsWith(it, ignoreCase = true) }
                    if (isImg || isVid) {
                        count++
                        if (cover.isEmpty() && isImg) cover = c.path
                    }
                }
            }
            folder.copy(coverPath = cover, mediaCount = count)
        }.getOrDefault(folder)
    }

    // ══════════════════════════════════════
    //  扫描文件夹内容（顺序扫描，旧接口）
    // ══════════════════════════════════════

    data class SmbFolderScanResult(
        val subFolders: List<SmbSubFolder>,
        val mediaFiles: List<SmbFileInfo>
    )

    /**
     * 扫描文件夹内容。
     * 子文件夹顺序扫描，稳定可靠。
     * 认证失败时自动清理缓存并重试一次。
     */
    suspend fun scanFolderContent(
        url: String,
        onProgress: ((scanned: Int, total: Int) -> Unit)? = null
    ): Result<SmbFolderScanResult> = withContext(Dispatchers.IO) {
        try {
            val dirPath = ensureTrailingSlash(url)
            val ctx = getContextForPath(dirPath)
            Result.success(doScan(dirPath, ctx, onProgress))
        } catch (e: SmbAuthException) {
            val host = extractHost(url)
            authCache.remove(host)
            val dirPath = ensureTrailingSlash(url)
            val newCtx = getContextForPath(dirPath)
            Result.success(doScan(dirPath, newCtx, onProgress))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun doScan(
        dirPath: String, ctx: CIFSContext,
        onProgress: ((scanned: Int, total: Int) -> Unit)? = null
    ): SmbFolderScanResult {
        val dir = SmbFile(dirPath, ctx)
        val all = dir.listFiles()
        val subFolders = mutableListOf<SmbSubFolder>()
        val mediaFiles = mutableListOf<SmbFileInfo>()

        var folderIdx = 0
        for (f in all) {
            if (f.isDirectory) {
                var cover = ""
                var count = 0
                try {
                    val subDir = SmbFile(ensureTrailingSlash(f.path), ctx)
                    val children = subDir.listFiles()
                    for (c in children) {
                        if (!c.isDirectory) {
                            val name = c.name
                            val isImg = IMAGE_EXTS.any { name.endsWith(it, ignoreCase = true) }
                            val isVid = VIDEO_EXTS.any { name.endsWith(it, ignoreCase = true) }
                            if (isImg || isVid) {
                                count++
                                if (cover.isEmpty() && isImg) cover = c.path
                            }
                        }
                    }
                } catch (_: Exception) { }
                folderIdx++
                val folder = SmbSubFolder(name = f.name, path = f.path, coverPath = cover, mediaCount = count, lastModified = f.lastModified())
                subFolders.add(folder)
                onProgress?.invoke(folderIdx, all.size)
            } else {
                val name = f.name
                val isImg = IMAGE_EXTS.any { name.endsWith(it, ignoreCase = true) }
                val isVid = VIDEO_EXTS.any { name.endsWith(it, ignoreCase = true) }
                if (isImg || isVid) {
                    val info = SmbFileInfo(name = name, path = f.path, size = f.length(), isVideo = isVid, lastModified = f.lastModified())
                    mediaFiles.add(info)
                }
            }
        }

        subFolders.sortBy { it.name.lowercase() }
        mediaFiles.sortByDescending { it.name }
        return SmbFolderScanResult(subFolders, mediaFiles)
    }

    // ══════════════════════════════════════
    //  文件读取
    // ══════════════════════════════════════

    /**
     * 获取 SMB 文件输入流。
     * ⚠️ [BitmapFactory.decodeStream] 不支持该流（缺少可靠的 mark/reset），
     * 如需解码图片请使用 [readBytes] + [BitmapFactory.decodeByteArray]。
     */
    suspend fun getInputStream(url: String): Result<InputStream> = withContext(Dispatchers.IO) {
        runCatching {
            val ctx = getContextForPath(url)
            SmbFileInputStream(SmbFile(url, ctx)) as InputStream
        }
    }

    /**
     * 同步获取 SMB 文件输入流。
     * 不涉及协程调度，适合在 ExoPlayer 的 Loader 线程中直接调用。
     * 用于 [SmbCacheDataSource] 的全速缓存复制。
     */
    fun getSyncInputStream(url: String): Result<InputStream> = runCatching {
        val ctx = getContextForPath(url)
        SmbFileInputStream(SmbFile(url, ctx)) as InputStream
    }

    /**
     * 同步获取 SMB 认证上下文（内部方法）。
     * 用于 [SmbCacheService] 直接构造 SmbFile。
     */
    fun getContextForPathSync(path: String): CIFSContext = getContextForPath(path)

    /**
     * 在 SMB 服务器上递归创建目录。
     * 与 [ensureTrailingSlash] 配合使用，确保路径以 / 结尾。
     * 模式与 [deleteFile] 一致：认证上下文解析 → jcifs-ng API 调用 → 异常处理。
     */
    suspend fun mkdirs(dirPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val ctx = getContextForPath(dirPath)
            val dir = SmbFile(ensureTrailingSlash(dirPath), ctx)
            dir.mkdirs()
        }
    }

    /**
     * 删除 SMB 文件（永久删除，不可恢复）。
     */
    suspend fun deleteFile(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val ctx = getContextForPath(path)
            val smbFile = SmbFile(path, ctx)
            if (!smbFile.exists()) throw IllegalStateException("文件不存在: $path")
            smbFile.delete()
        }
    }

    /**
     * 将 SMB 文件完整读入字节数组。
     *
     * 当文件长度不可用（返回 0）时，使用默认 4MB 缓冲区逐步读取。
     */
    suspend fun readBytes(url: String, maxBytes: Long = MAX_READ_BYTES): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val ctx = getContextForPath(url)
            val smbFile = SmbFile(url, ctx)
            var totalSize = smbFile.length()
            // 某些 SMB 服务器对某些文件返回 0 长度
            if (totalSize <= 0L) totalSize = 4L * 1024 * 1024
            if (totalSize > maxBytes) {
                throw IllegalStateException("File too large: ${totalSize} bytes (max $maxBytes)")
            }
            SmbFileInputStream(smbFile).use { stream ->
                val bufSize = totalSize.toInt().coerceAtMost(maxBytes.toInt())
                val buffer = ByteArray(bufSize)
                var offset = 0
                while (offset < bufSize) {
                    val read = stream.read(buffer, offset, bufSize - offset)
                    if (read < 0) break
                    offset += read
                }
                if (offset < bufSize) buffer.copyOf(offset) else buffer
            }
        }
    }

    /**
     * 读取 SMB 文件前 [maxBytes] 字节（部分读取，不会因文件过大报错）。
     *
     * 与 [readBytes] 的关键区别：
     * - 不检查文件总长度，只读前 [maxBytes] 字节
     * - 文件不足时返回实际读取量
     * - 不抛出 "File too large" 异常
     * 适合读取文件头部（视频缩略图提取等）。
     */
    suspend fun readBytesPartial(url: String, maxBytes: Int): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val ctx = getContextForPath(url)
            SmbFileInputStream(SmbFile(url, ctx)).use { stream ->
                val buffer = ByteArray(maxBytes)
                var offset = 0
                while (offset < maxBytes) {
                    val read = stream.read(buffer, offset, maxBytes - offset)
                    if (read < 0) break
                    offset += read
                }
                if (offset == 0) ByteArray(0) else buffer.copyOf(offset)
            }
        }
    }

    /**
     * 流式复制 SMB 文件到本地文件，带进度回调。
     *
     * 用于视频播放前的本地缓存：先完整复制到本地缓存，
     * 再从本地文件播放，彻底消除 SMB seek 和 moov 索引延迟问题。
     *
     * @param url smb:// 完整路径
     * @param destFile 本地目标文件
     * @param onProgress 进度回调：(bytesCopied, totalBytes)，在 IO 线程调用
     * @param bufferSize 每次读取的缓冲区大小（默认 256KB）
     */
    suspend fun copyFile(
        url: String,
        destFile: File,
        onProgress: ((bytesCopied: Long, totalBytes: Long) -> Unit)? = null,
        bufferSize: Int = 256 * 1024
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val ctx = getContextForPath(url)
            val smbFile = SmbFile(url, ctx)
            val totalSize = smbFile.length()
            SmbFileInputStream(smbFile).use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(bufferSize)
                    var totalCopied = 0L
                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead < 0) break
                        output.write(buffer, 0, bytesRead)
                        totalCopied += bytesRead
                        onProgress?.invoke(totalCopied, totalSize)
                    }
                    output.flush()
                }
            }
        }
    }

    /** Stream an SMB file into a caller-owned output stream without buffering it in memory. */
    suspend fun copyToOutputStream(
        url: String,
        output: OutputStream,
        onProgress: ((bytesCopied: Long, totalBytes: Long) -> Unit)? = null,
        bufferSize: Int = 1024 * 1024
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val startedAtNs = System.nanoTime()
            val ctx = getContextForPath(url)
            val smbFile = SmbFile(url, ctx)
            if (!smbFile.exists()) throw IllegalStateException("SMB source does not exist: $url")
            val totalSize = smbFile.length()
            var totalCopied = 0L
            SmbFileInputStream(smbFile).use { input ->
                val buffer = ByteArray(bufferSize)
                while (true) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead < 0) break
                    output.write(buffer, 0, bytesRead)
                    totalCopied += bytesRead
                    onProgress?.invoke(totalCopied, totalSize)
                }
            }
            output.flush()
            val elapsedSeconds = ((System.nanoTime() - startedAtNs) / 1_000_000_000.0)
                .coerceAtLeast(0.001)
            val throughputMbps = totalCopied / (1024.0 * 1024.0) / elapsedSeconds
            AppLogger.d(
                "SMB-Transfer",
                "read complete: bytes=$totalCopied seconds=${"%.2f".format(elapsedSeconds)} " +
                    "speed=${"%.1f".format(throughputMbps)}MB/s buffer=${bufferSize / 1024}KB"
            )
            Result.success(totalCopied)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    // ══════════════════════════════════════
    //  SMB 文件写入（复制/移动）
    // ══════════════════════════════════════

    /**
     * 获取 SMB 文件输出流（写入到 SMB 服务器）。
     */
    suspend fun getOutputStream(url: String): Result<OutputStream> = withContext(Dispatchers.IO) {
        runCatching {
            val ctx = getContextForPath(url)
            SmbFileOutputStream(SmbFile(url, ctx)) as OutputStream
        }
    }

    /**
     * 在同一 SMB 共享内重命名/移动文件（服务器端瞬间完成，不传输数据）。
     * 跨共享会失败，由调用方 fallback 到 copy+delete。
     */
    suspend fun renameFile(sourcePath: String, destPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val ctx = getContextForPath(sourcePath)
            val srcFile = SmbFile(sourcePath, ctx)
            if (!srcFile.exists()) throw IllegalStateException("源文件不存在: $sourcePath")
            val destFile = SmbFile(destPath, ctx)
            srcFile.renameTo(destFile)
        }
    }

    /**
     * 在 SMB 目录中生成唯一的文件名（不覆盖已有文件）。
     * 不冲突时返回原文件名；冲突时追加 (1)、(2)... 后缀。
     */
    private fun generateUniqueSmbName(dirUrl: String, baseName: String, ctx: CIFSContext): String {
        val dir = SmbFile(ensureTrailingSlash(dirUrl), ctx)
        val existing = dir.listFiles().map { it.name }.toSet()
        if (baseName !in existing) return baseName
        val dotIdx = baseName.lastIndexOf('.')
        val stem = if (dotIdx > 0) baseName.substring(0, dotIdx) else baseName
        val ext = if (dotIdx > 0) baseName.substring(dotIdx) else ""
        var suffix = 1
        while ("${stem}($suffix)$ext" in existing) { suffix++ }
        return "${stem}($suffix)$ext"
    }

    /**
     * 流式复制 SMB 文件到另一个 SMB 位置。
     * 自动处理目标文件名冲突（追加 (1)、(2)...）。
     *
     * @param sourceUrl 源文件 smb:// 路径
     * @param destDirUrl 目标目录 smb:// 路径
     * @return 最终目标文件路径
     */
    suspend fun smbCopyToSmb(sourceUrl: String, destDirUrl: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val ctx = getContextForPath(sourceUrl)
            val srcFile = SmbFile(sourceUrl, ctx)
            val baseName = srcFile.name
            val dirPath = ensureTrailingSlash(destDirUrl)
            val uniqueName = generateUniqueSmbName(dirPath, baseName, ctx)
            val destPath = dirPath + uniqueName

            SmbFileInputStream(srcFile).use { input ->
                SmbFileOutputStream(SmbFile(destPath, ctx)).use { output ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read < 0) break
                        output.write(buf, 0, read)
                    }
                    output.flush()
                }
            }
            destPath
        }
    }

    /**
     * 移动 SMB 文件到另一个位置（同 IP 内）。
     *
     * ### 策略
     * 1. 优先尝试 [renameTo]（同一共享内瞬间完成）
     * 2. 失败则 fallback 到 [smbCopyToSmb] + [deleteFile]（跨共享）
     *
     * @param sourceUrl 源文件 smb:// 路径
     * @param destDirUrl 目标目录 smb:// 路径
     * @return 最终目标文件路径
     */
    suspend fun smbMoveToSmb(sourceUrl: String, destDirUrl: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val ctx = getContextForPath(sourceUrl)
            val srcFile = SmbFile(sourceUrl, ctx)
            val baseName = srcFile.name
            val dirPath = ensureTrailingSlash(destDirUrl)
            val uniqueName = generateUniqueSmbName(dirPath, baseName, ctx)
            val destPath = dirPath + uniqueName

            // 先尝试 renameTo（同共享内瞬时完成）
            val renameOk = try {
                srcFile.renameTo(SmbFile(destPath, ctx))
                true
            } catch (_: Exception) {
                false
            }
            if (renameOk) return@runCatching destPath

            // Fallback: 流式复制 + 删除源文件（跨共享）
            smbCopyToSmb(sourceUrl, destDirUrl).getOrThrow()
            deleteFile(sourceUrl).getOrThrow()
            destPath
        }
    }

    fun getRandomAccessFile(url: String): Result<SmbRandomAccessFile> = runCatching {
        val ctx = getContextForPath(url)
        SmbRandomAccessFile(SmbFile(url, ctx), "r")
    }

    // ══════════════════════════════════════
    //  工具
    // ══════════════════════════════════════

    private fun extractHost(url: String): String {
        val withoutScheme = url.removePrefix("smb://").trimStart('/')
        return withoutScheme.substringBefore("/").substringBefore("\\")
    }

    private fun ensureTrailingSlash(path: String): String {
        return if (path.endsWith("/")) path else "$path/"
    }

    /** 按 host 或全局清理认证缓存 */
    fun clearAuthCache(host: String? = null) {
        if (host != null) authCache.remove(host)
        else authCache.clear()
    }
}
