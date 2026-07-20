package com.example.rcgallery.data.bilibili

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer

object BiliMuxer {
    fun mux(videoFile: File, audioFile: File, output: FileDescriptor) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)
            val videoSourceTrack = videoExtractor.findTrack("video/")
            val audioSourceTrack = audioExtractor.findTrack("audio/")
            if (videoSourceTrack < 0 || audioSourceTrack < 0) {
                throw IllegalStateException("下载分片中缺少可合并的音频或视频轨道")
            }

            val videoFormat = videoExtractor.getTrackFormat(videoSourceTrack)
            val audioFormat = audioExtractor.getTrackFormat(audioSourceTrack)
            muxer = MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                muxer.setOrientationHint(videoFormat.getInteger(MediaFormat.KEY_ROTATION))
            }
            val videoTargetTrack = muxer.addTrack(videoFormat)
            val audioTargetTrack = muxer.addTrack(audioFormat)
            muxer.start()
            started = true

            copyTrack(videoExtractor, videoSourceTrack, muxer, videoTargetTrack, videoFormat)
            copyTrack(audioExtractor, audioSourceTrack, muxer, audioTargetTrack, audioFormat)
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        sourceTrack: Int,
        muxer: MediaMuxer,
        targetTrack: Int,
        format: MediaFormat,
    ) {
        extractor.selectTrack(sourceTrack)
        val requestedSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else {
            DEFAULT_BUFFER_SIZE
        }
        val buffer = ByteBuffer.allocateDirect(requestedSize.coerceIn(DEFAULT_BUFFER_SIZE, MAX_BUFFER_SIZE))
        val info = MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.set(
                0,
                size,
                extractor.sampleTime.coerceAtLeast(0L),
                extractor.sampleFlags,
            )
            muxer.writeSampleData(targetTrack, buffer, info)
            extractor.advance()
        }
        extractor.unselectTrack(sourceTrack)
    }

    private fun MediaExtractor.findTrack(mimePrefix: String): Int {
        for (index in 0 until trackCount) {
            val mime = getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(mimePrefix)) return index
        }
        return -1
    }

    private const val DEFAULT_BUFFER_SIZE = 2 * 1024 * 1024
    private const val MAX_BUFFER_SIZE = 32 * 1024 * 1024
}
