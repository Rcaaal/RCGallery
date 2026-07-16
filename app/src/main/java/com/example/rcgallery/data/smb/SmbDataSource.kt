package com.example.rcgallery.data.smb

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.example.rcgallery.util.AppLogger
import jcifs.smb.SmbRandomAccessFile

/** ExoPlayer data source backed by a buffered SMB random-access stream. */
class SmbDataSource : DataSource {

    companion object {
        private const val PREFETCH_BYTES = 4 * 1024 * 1024
        private const val INITIAL_PREFETCH = 64 * 1024
        private const val TAG = "SMB-IO"
    }

    private var currentUri: Uri? = null
    @Volatile
    private var closed = false
    private var sourceUrl = ""
    private var raf: SmbRandomAccessFile? = null
    private var fileLength = 0L

    /** Next byte that has not yet been delivered to ExoPlayer. */
    private var deliveredPosition = 0L
    private var readBuf = ByteArray(INITIAL_PREFETCH)
    private var bufPos = 0
    private var bufSize = 0

    override fun open(dataSpec: DataSpec): Long {
        try { raf?.close() } catch (_: Exception) { }
        raf = null
        closed = false
        currentUri = dataSpec.uri
        sourceUrl = dataSpec.uri.toString()
        val position = dataSpec.position
        deliveredPosition = position

        bufPos = 0
        bufSize = 0
        readBuf = ByteArray(INITIAL_PREFETCH)

        openFileAt(position)
        val prefetched = readFromSmb(readBuf)
        if (prefetched > 0) bufSize = prefetched

        val remaining = if (fileLength > position) fileLength - position else C.LENGTH_UNSET.toLong()
        AppLogger.d(TAG, "open OK: pos=$position len=$fileLength prebuf=${bufSize / 1024}KB")
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed) return C.RESULT_END_OF_INPUT
        if (raf == null) throw IllegalStateException("Not opened")

        val remaining = bufSize - bufPos
        if (remaining > 0) {
            val toCopy = minOf(length, remaining)
            System.arraycopy(readBuf, bufPos, buffer, offset, toCopy)
            bufPos += toCopy
            deliveredPosition += toCopy
            return toCopy
        }

        if (readBuf.size <= INITIAL_PREFETCH) {
            readBuf = ByteArray(PREFETCH_BYTES)
        }
        val count = readFromSmb(readBuf)
        if (count <= 0) return C.RESULT_END_OF_INPUT

        bufSize = count
        bufPos = 0
        val toCopy = minOf(length, count)
        System.arraycopy(readBuf, 0, buffer, offset, toCopy)
        bufPos = toCopy
        deliveredPosition += toCopy
        AppLogger.d(TAG, "refill: ${count / 1024}KB")
        return toCopy
    }

    private fun openFileAt(position: Long) {
        val opened = SmbRepository.getInstance().getRandomAccessFile(sourceUrl).getOrThrow()
        try {
            fileLength = opened.length()
            if (position > 0L) opened.seek(position)
            raf = opened
        } catch (e: Exception) {
            try { opened.close() } catch (_: Exception) { }
            throw e
        }
    }

    /** Reopen once at the exact next unread byte when a long-lived SMB handle dies. */
    private fun readFromSmb(target: ByteArray): Int {
        try {
            return (raf ?: throw IllegalStateException("Not opened")).read(target, 0, target.size)
        } catch (first: Exception) {
            if (closed) return C.RESULT_END_OF_INPUT
            AppLogger.e(TAG, "read failed at=$deliveredPosition; reconnecting", first)
            try { raf?.close() } catch (_: Exception) { }
            raf = null
            SmbRepository.getInstance().invalidateConnection(sourceUrl)
            return try {
                openFileAt(deliveredPosition)
                val count = (raf ?: throw IllegalStateException("Reconnect did not open file"))
                    .read(target, 0, target.size)
                AppLogger.d(TAG, "reconnect OK at=$deliveredPosition read=$count")
                count
            } catch (second: Exception) {
                second.addSuppressed(first)
                AppLogger.e(TAG, "reconnect failed at=$deliveredPosition", second)
                throw second
            }
        }
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        closed = true
        bufSize = 0
        bufPos = 0
        try { raf?.close() } catch (_: Exception) { }
        raf = null
        currentUri = null
    }

    override fun addTransferListener(listener: TransferListener) = Unit

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource()
    }
}
