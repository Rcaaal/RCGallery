package com.example.rcgallery.viewmodel

import android.app.Application
import android.content.ContentValues
import android.media.MediaCodecList
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rcgallery.data.bilibili.BiliMuxer
import com.example.rcgallery.data.youtube.YouTubeAudioTrack
import com.example.rcgallery.data.youtube.YouTubeCodecMode
import com.example.rcgallery.data.youtube.YouTubeImportState
import com.example.rcgallery.data.youtube.YouTubeParseException
import com.example.rcgallery.data.youtube.YouTubeParser
import com.example.rcgallery.data.youtube.YouTubeVideoTrack
import com.example.rcgallery.data.youtube.YouTubeWorkInfo
import com.example.rcgallery.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

class YouTubeImportViewModel(application: Application) : AndroidViewModel(application) {
    private val parser = YouTubeParser(application)
    private val _state = MutableStateFlow<YouTubeImportState>(YouTubeImportState.Idle)
    val state: StateFlow<YouTubeImportState> = _state.asStateFlow()
    private var operationJob: Job? = null

    fun parse(input: String) {
        if (input.isBlank()) {
            _state.value = YouTubeImportState.Error("请粘贴 YouTube 视频链接")
            return
        }
        cancel()
        operationJob = viewModelScope.launch {
            _state.value = YouTubeImportState.Initializing
            try {
                _state.value = YouTubeImportState.Parsing
                _state.value = YouTubeImportState.Ready(
                    withTimeout(PARSE_TIMEOUT_MS) { parser.parse(input) }
                )
            } catch (_: TimeoutCancellationException) {
                _state.value = YouTubeImportState.Error("解析超时，请检查网络后重试")
            } catch (_: CancellationException) {
                return@launch
            } catch (error: YouTubeParseException) {
                _state.value = YouTubeImportState.Error(error.message ?: "YouTube 解析失败")
            } catch (error: Exception) {
                AppLogger.e("YouTubeImport", "parse failed", error)
                _state.value = YouTubeImportState.Error(error.message ?: "YouTube 解析失败")
            }
        }
    }

    fun download(
        work: YouTubeWorkInfo,
        qualityHeight: Int,
        codecMode: YouTubeCodecMode,
        onSaved: () -> Unit,
    ) {
        cancel()
        operationJob = viewModelScope.launch {
            try {
                val displayName = downloadWork(work, qualityHeight, codecMode)
                _state.value = YouTubeImportState.Success(displayName)
                onSaved()
            } catch (_: CancellationException) {
                return@launch
            } catch (error: Exception) {
                AppLogger.e("YouTubeImport", "download failed id=${work.id}", error)
                _state.value = YouTubeImportState.Error(
                    error.message?.takeIf { it.isNotBlank() } ?: "YouTube 下载失败"
                )
            }
        }
    }

    fun cancel() {
        val current = _state.value
        operationJob?.cancel()
        operationJob = null
        _state.value = when (current) {
            is YouTubeImportState.Downloading -> YouTubeImportState.Ready(current.work)
            YouTubeImportState.Initializing, YouTubeImportState.Parsing -> YouTubeImportState.Idle
            else -> current
        }
    }

    fun reset() {
        cancel()
        _state.value = YouTubeImportState.Idle
    }

    private suspend fun downloadWork(
        work: YouTubeWorkInfo,
        qualityHeight: Int,
        codecMode: YouTubeCodecMode,
    ): String = withContext(Dispatchers.IO) {
        val video = chooseVideo(work.videos, qualityHeight, codecMode)
        val audio = work.audios.maxWithOrNull(
            compareBy<YouTubeAudioTrack> { it.bitrateKbps }.thenBy { it.estimatedBytes }
        ) ?: throw IllegalStateException("没有可用的音频轨道")
        val tempDir = File(getApplication<Application>().cacheDir, "youtube_import/${work.id}")
        tempDir.mkdirs()
        val videoPart = File(tempDir, "video.mp4")
        val audioPart = File(tempDir, "audio.m4a")
        var pendingUri: Uri? = null
        try {
            AppLogger.d(
                "YouTubeImport",
                "selected video=${video.formatId}/${video.codec}/${video.height} audio=${audio.formatId}/${audio.codec}",
            )
            downloadTrack(work, video.url, video.headers, videoPart, "正在下载视频")
            downloadTrack(work, audio.url, audio.headers, audioPart, "正在下载音频")
            coroutineContext.ensureActive()
            _state.value = YouTubeImportState.Downloading(work, "正在合并音视频", 0L, 0L, 0L)

            val resolver = getApplication<Application>().contentResolver
            val displayName = uniqueDisplayName(
                sanitizeFileName("${work.title} [${qualityLabel(video.height)}].mp4")
            )
            pendingUri = resolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, YOUTUBE_RELATIVE_PATH)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            ) ?: throw IllegalStateException("无法创建本地媒体文件")
            val uri = pendingUri
            resolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                BiliMuxer.mux(videoPart, audioPart, descriptor.fileDescriptor)
                if (descriptor.statSize <= 0L) throw IllegalStateException("音视频合并结果为空")
            } ?: throw IllegalStateException("无法写入本地媒体文件")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            pendingUri = null
            displayName
        } finally {
            pendingUri?.let { getApplication<Application>().contentResolver.delete(it, null, null) }
            videoPart.delete()
            audioPart.delete()
            tempDir.delete()
        }
    }

    private suspend fun downloadTrack(
        work: YouTubeWorkInfo,
        url: String,
        headers: Map<String, String>,
        target: File,
        stage: String,
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Connection", "keep-alive")
            headers.forEach { (name, value) ->
                if (!name.equals("Accept-Encoding", true) && value.isNotBlank()) {
                    setRequestProperty(name, value)
                }
            }
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("YouTube CDN 下载失败（HTTP $code）")
            }
            val total = connection.contentLengthLong
            BufferedInputStream(connection.inputStream, BUFFER_SIZE).use { input ->
                BufferedOutputStream(target.outputStream(), BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloaded = 0L
                    var windowBytes = 0L
                    var windowStart = System.nanoTime()
                    var lastUpdate = windowStart
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        windowBytes += read
                        val now = System.nanoTime()
                        if (now - lastUpdate >= PROGRESS_INTERVAL_NS) {
                            val seconds = (now - windowStart) / 1_000_000_000.0
                            val speed = if (seconds > 0.0) (windowBytes / seconds).toLong() else 0L
                            _state.value = YouTubeImportState.Downloading(
                                work, stage, downloaded, total, speed
                            )
                            windowStart = now
                            windowBytes = 0L
                            lastUpdate = now
                        }
                    }
                    output.flush()
                    if (downloaded <= 0L) throw IllegalStateException("下载结果为空")
                    if (total > 0L && downloaded != total) {
                        throw IllegalStateException("下载不完整（$downloaded/$total）")
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun chooseVideo(
        videos: List<YouTubeVideoTrack>,
        height: Int,
        mode: YouTubeCodecMode,
    ): YouTubeVideoTrack {
        val tracks = videos.filter { it.height == height }.ifEmpty {
            val nearest = videos.minByOrNull { kotlin.math.abs(it.height - height) }?.height
            videos.filter { it.height == nearest }
        }
        val order = when (mode) {
            YouTubeCodecMode.SPACE_SAVING -> listOf(Codec.AV1, Codec.HEVC, Codec.AVC)
            YouTubeCodecMode.COMPATIBLE -> listOf(Codec.AVC, Codec.HEVC, Codec.AV1)
            YouTubeCodecMode.AUTO -> when {
                hasHardwareDecoder("video/av01") -> listOf(Codec.AV1, Codec.HEVC, Codec.AVC)
                hasHardwareDecoder("video/hevc") -> listOf(Codec.HEVC, Codec.AVC, Codec.AV1)
                else -> listOf(Codec.AVC, Codec.HEVC, Codec.AV1)
            }
        }
        return tracks.sortedWith(
            compareBy<YouTubeVideoTrack> { order.indexOf(codecOf(it.codec)).let { rank -> if (rank < 0) Int.MAX_VALUE else rank } }
                .thenByDescending { it.bitrateKbps }
        ).firstOrNull() ?: throw IllegalStateException("所选清晰度没有可用视频轨道")
    }

    private fun codecOf(codec: String): Codec = when {
        codec.startsWith("av01", true) -> Codec.AV1
        codec.startsWith("hev", true) || codec.startsWith("hvc", true) -> Codec.HEVC
        else -> Codec.AVC
    }

    private fun hasHardwareDecoder(mimeType: String): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.isHardwareAccelerated &&
                info.supportedTypes.any { it.equals(mimeType, true) }
        }
    }.getOrDefault(false)

    private fun uniqueDisplayName(baseName: String): String {
        val resolver = getApplication<Application>().contentResolver
        var candidate = baseName
        val stem = baseName.substringBeforeLast('.', baseName)
        var suffix = 1
        while (true) {
            val exists = resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.DISPLAY_NAME}=? AND ${MediaStore.Video.Media.RELATIVE_PATH}=?",
                arrayOf(candidate, YOUTUBE_RELATIVE_PATH),
                null,
            )?.use { it.moveToFirst() } == true
            if (!exists) return candidate
            candidate = "$stem ($suffix).mp4"
            suffix++
        }
    }

    private fun sanitizeFileName(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim().take(180).ifBlank { "youtube_video.mp4" }

    private fun qualityLabel(height: Int): String = when {
        height >= 2160 -> "4K"
        height >= 1440 -> "1440P"
        else -> "${height}P"
    }

    private enum class Codec { AVC, HEVC, AV1 }

    companion object {
        private const val BUFFER_SIZE = 256 * 1024
        private const val PROGRESS_INTERVAL_NS = 250_000_000L
        private const val PARSE_TIMEOUT_MS = 45_000L
        const val YOUTUBE_RELATIVE_PATH = "DCIM/RCGallery/YouTube/"
    }
}
