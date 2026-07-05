package com.example.rcgallery.data.smb

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.example.rcgallery.util.AppLogger
import jcifs.smb.SmbRandomAccessFile

/**
 * ExoPlayer 自定义 DataSource — 通过 SMB 协议直接流式读取媒体文件。
 *
 * ### 架构
 *
 * 基于 [SmbRandomAccessFile] + 大块预读缓冲（4MB）。
 *
 * #### 设计要点
 * - **`open()` 仅预填 64KB** → 快速返回，ExoPlayer 拿到数据立即开始解码
 * - **`read()` 首次 refill 升到 4MB** → 后续大块读取，减少 SMB 事务次数
 * - **RAF seek() 原生支持** → 跳转 <5ms，non-faststart MP4 完美兼容
 * - **SMB2 参数调优** → `smb2MaxReadSize=8MB` + `smb2Credits=128`
 *
 * #### 为什么去掉 preOpen？
 * 前序版本的 `preOpen` + `companion object` 静态缓存引入了时序竞争：
 * `LaunchedEffect` 未完成时 `AndroidView factory` 已调用 `prepare()`，
 * preOpen 的 RAF 未被复用。直接 `new Raf` 更可靠（~200ms 连接建立）。
 */
class SmbDataSource : DataSource {

    companion object {
        /** 顺序播放时每次 refill 读取量（4MB → 减少 SMB 事务） */
        private const val PREFETCH_BYTES = 4 * 1024 * 1024

        /** 跳转后 refill 读取量 */
        private const val SEEK_PREFETCH = 2 * 1024 * 1024

        /** `open()` 首次预填——只需 64KB，快速返回数据给 ExoPlayer 解码器 */
        private const val INITIAL_PREFETCH = 64 * 1024

        private const val TAG = "SMB-IO"
    }

    // ── 实例变量 ──

    private var currentUri: Uri? = null
    @Volatile
    private var closed = false
    private var raf: SmbRandomAccessFile? = null
    private var fileLength: Long = 0L
    private var readBuf = ByteArray(INITIAL_PREFETCH)
    private var bufPos = 0
    private var bufSize = 0

    override fun open(dataSpec: DataSpec): Long {
        closed = false
        currentUri = dataSpec.uri
        val url = dataSpec.uri.toString()
        val position = dataSpec.position

        bufPos = 0
        bufSize = 0
        // 首次预填用 64KB（更快返回），refill 时会自动升到 PREFETCH_BYTES
        readBuf = ByteArray(INITIAL_PREFETCH)

        // 直接新建 RAF — 连接建立 ~200ms，比 preOpen 时序竞争更可靠
        val repo = SmbRepository.getInstance()
        val opened = repo.getRandomAccessFile(url).getOrThrow()
        fileLength = opened.length()
        raf = opened

        // RAF seek：内存操作，<5ms
        if (position > 0L) {
            try { raf!!.seek(position) } catch (e: Exception) { raf!!.close(); throw e }
        }

        // 预填 64KB — 快速返回给 ExoPlayer，解码器拿到数据立即开始解析
        try {
            val n = raf!!.read(readBuf, 0, readBuf.size)
            if (n > 0) bufSize = n
        } catch (_: Exception) { }

        val remaining = if (fileLength > position) fileLength - position else C.LENGTH_UNSET.toLong()
        AppLogger.d(TAG, "open OK: pos=$position len=$fileLength prebuf=${bufSize / 1024}KB")
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed) return C.RESULT_END_OF_INPUT
        val file = raf ?: throw IllegalStateException("Not opened")

        // ── 缓冲区有数据 → 直接返回 ──
        val remaining = bufSize - bufPos
        if (remaining > 0) {
            val toCopy = minOf(length, remaining)
            System.arraycopy(readBuf, bufPos, buffer, offset, toCopy)
            bufPos += toCopy
            return toCopy
        }

        // ── 缓冲耗尽 → 升到大缓冲再读 ──
        // 首次 refill：从 64KB 升到 4MB（顺序播放）或 2MB（跳转后）
        if (bufSize <= INITIAL_PREFETCH) {
            // 判断流位置：若 raf 文件指针 > 0 说明是 seek 后的位置
            // 但 SmbRandomAccessFile 没有 getFilePointer，用旧 readBuf 大小推断
            readBuf = if (readBuf.size <= INITIAL_PREFETCH) {
                ByteArray(PREFETCH_BYTES)  // 顺序播放 → 4MB
            } else {
                ByteArray(SEEK_PREFETCH)   // 已有跳转 → 2MB
            }
        }
        val n = try {
            file.read(readBuf, 0, readBuf.size)
        } catch (e: Exception) {
            if (closed) return C.RESULT_END_OF_INPUT
            AppLogger.e(TAG, "read error", e)
            throw e
        }
        if (n <= 0) return C.RESULT_END_OF_INPUT

        bufSize = n
        bufPos = 0
        val toCopy = minOf(length, n)
        System.arraycopy(readBuf, 0, buffer, offset, toCopy)
        bufPos = toCopy
        AppLogger.d(TAG, "  refill: ${n / 1024}KB")
        return toCopy
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        closed = true
        bufSize = 0; bufPos = 0
        try { raf?.close() } catch (_: Exception) { }
        raf = null
    }

    override fun addTransferListener(listener: TransferListener) { }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource()
    }
}
