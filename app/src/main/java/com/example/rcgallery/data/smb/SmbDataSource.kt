package com.example.rcgallery.data.smb

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.example.rcgallery.util.AppLogger
import jcifs.smb.SmbFileInputStream
import jcifs.smb.SmbRandomAccessFile

/**
 * ExoPlayer 自定义 DataSource — SMB 协议流式读取。
 *
 * ### 双模式策略
 *
 * jcifs-ng 两个 API：
 * - [SmbFileInputStream]：大块读 17-35MB/s（已验证）
 * - [SmbRandomAccessFile]：大块读 0.7-1.6MB/s，但支持 seek()
 *
 * | 场景 | 用什么 | 原因 |
 * |------|--------|------|
 * | 首次打开(pos=0) | SmbFileInputStream | 快读 16MB (~1s)，seek 不需要 |
 * | 快进跳转(pos>0) | SmbRandomAccessFile | seek() 到位置，读 2MB（无需跳过大量数据）|
 * | seek 后连续播放 | SmbRandomAccessFile | 慢但已是最坏情况可接受 |
 */
class SmbDataSource : DataSource {

    companion object {
        private const val FIRST_CHUNK = 16 * 1024 * 1024
        private const val NEXT_CHUNK = 8 * 1024 * 1024
        private const val SEEK_CHUNK = 2 * 1024 * 1024
    }

    private var inputStream: SmbFileInputStream? = null
    private var raf: SmbRandomAccessFile? = null
    private var currentUri: Uri? = null
    @Volatile private var closed = false

    // ── 预读缓冲区 ──
    private var readBuf = ByteArray(FIRST_CHUNK)
    private var bufPos = 0
    private var bufSize = 0

    override fun open(dataSpec: DataSpec): Long {
        closed = false
        currentUri = dataSpec.uri
        val url = dataSpec.uri.toString()
        val position = dataSpec.position

        bufPos = 0
        bufSize = 0
        readBuf = if (position == 0L) ByteArray(FIRST_CHUNK) else ByteArray(SEEK_CHUNK)

        val repo = SmbRepository.getInstance()

        if (position == 0L) {
            // ── 首次打开：SmbFileInputStream 快读 ──
            val stream = repo.getInputStreamForFile(url).getOrThrow()
            inputStream = stream
            AppLogger.d("SMB-IO", "open(pos=0): stream mode, chunk=${readBuf.size / 1024}KB")
        } else {
            // ── seek：SmbRandomAccessFile 跳转 ──
            val file = repo.getRandomAccessFile(url).getOrThrow()
            try { file.seek(position) } catch (e: Exception) { file.close(); throw e }
            raf = file
            AppLogger.d("SMB-IO", "open(pos=$position): RAF mode, chunk=${readBuf.size / 1024}KB")
        }

        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed) return C.RESULT_END_OF_INPUT

        // ── 缓冲有数据 → 直接返回 ──
        val remaining = bufSize - bufPos
        if (remaining > 0) {
            val toCopy = minOf(length, remaining)
            System.arraycopy(readBuf, bufPos, buffer, offset, toCopy)
            bufPos += toCopy
            return toCopy
        }

        // ── 缓冲耗尽 → 从 SMB 读下一块 ──
        if (readBuf.size != NEXT_CHUNK) readBuf = ByteArray(NEXT_CHUNK)
        val chunk = readBuf.size

        val n = if (inputStream != null) {
            // 流模式：快读
            try { inputStream!!.read(readBuf, 0, chunk) }
            catch (e: Exception) { if (closed) return C.RESULT_END_OF_INPUT; throw e }
        } else if (raf != null) {
            // RAF 模式：慢读（支持 seek 的代价）
            try { raf!!.read(readBuf, 0, chunk) }
            catch (e: Exception) { if (closed) return C.RESULT_END_OF_INPUT; throw e }
        } else {
            return C.RESULT_END_OF_INPUT
        }

        if (n <= 0) return C.RESULT_END_OF_INPUT

        bufSize = n
        bufPos = 0
        val toCopy = minOf(length, n)
        System.arraycopy(readBuf, 0, buffer, offset, toCopy)
        bufPos = toCopy
        AppLogger.d("SMB-IO", "  refill: ${n / 1024}KB (stream=${inputStream != null})")
        return toCopy
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        closed = true
        bufSize = 0; bufPos = 0
        try { inputStream?.close() } catch (_: Exception) { }
        try { raf?.close() } catch (_: Exception) { }
        inputStream = null; raf = null
    }

    override fun addTransferListener(listener: TransferListener) { }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource()
    }
}
