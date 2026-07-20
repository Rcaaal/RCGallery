package com.example.rcgallery.data.youtube

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import com.yausername.youtubedl_android.mapper.VideoInfo
import com.example.rcgallery.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

class YouTubeParser(context: Context) {
    private val appContext = context.applicationContext

    suspend fun parse(input: String): YouTubeWorkInfo = withContext(Dispatchers.IO) {
        val url = extractUrl(input)
        try {
            YoutubeDL.getInstance().init(appContext)
            val info = try {
                loadInfo(url)
            } catch (error: Exception) {
                if (!isLoginChallenge(error)) throw error
                AppLogger.d("YouTubeImport", "default client challenged; retry android_vr")
                loadInfo(url, "android_vr,web_embedded")
            }
            val videos = info.formats.orEmpty().mapNotNull(::toVideoTrack)
            val audios = info.formats.orEmpty().mapNotNull(::toAudioTrack)
            if (videos.isEmpty()) throw YouTubeParseException("没有找到可下载的 MP4 视频轨道")
            if (audios.isEmpty()) throw YouTubeParseException("没有找到可下载的 M4A 音频轨道")
            val qualities = videos.groupBy { it.height }.keys
                .filter { it > 0 }.sortedDescending()
                .map { YouTubeQuality(it, qualityLabel(it)) }
            YouTubeWorkInfo(
                id = info.id.orEmpty().ifBlank { "youtube" },
                title = info.title.orEmpty().ifBlank { info.fulltitle.orEmpty().ifBlank { "YouTube 视频" } },
                channelName = info.uploader.orEmpty(),
                thumbnailUrl = info.thumbnail,
                durationSeconds = info.duration,
                uploadDate = info.uploadDate,
                webpageUrl = info.webpageUrl ?: url,
                qualities = qualities,
                videos = videos,
                audios = audios,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: YouTubeParseException) {
            throw error
        } catch (error: Exception) {
            val detail = error.message.orEmpty()
            val friendly = when {
                isLoginChallenge(error) -> "当前网络/IP触发了 YouTube 机器人验证，公开客户端回退仍然失败"
                detail.contains("Private video", true) -> "这是私密视频，无法解析"
                detail.contains("Video unavailable", true) -> "视频不可用或存在地区限制"
                else -> detail.lineSequence().lastOrNull { it.isNotBlank() } ?: "YouTube 解析失败"
            }
            throw YouTubeParseException(friendly.take(240), error)
        }
    }

    private suspend fun loadInfo(url: String, playerClients: String? = null): VideoInfo {
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--skip-download")
            addOption("--no-warnings")
            addOption("--socket-timeout", 15)
            addOption("--retries", 1)
            addOption("--extractor-retries", 1)
            addOption("--force-ipv4")
            playerClients?.let {
                addOption("--extractor-args", "youtube:player_client=$it")
            }
        }
        return runInterruptible { YoutubeDL.getInstance().getInfo(request) }
    }

    private fun isLoginChallenge(error: Throwable): Boolean {
        val detail = error.message.orEmpty()
        return detail.contains("Sign in", true) ||
            detail.contains("confirm you.re not a bot", true) ||
            detail.contains("confirm you're not a bot", true)
    }

    private fun toVideoTrack(format: VideoFormat): YouTubeVideoTrack? {
        val codec = format.vcodec.orEmpty()
        val audioCodec = format.acodec.orEmpty()
        val url = format.url.orEmpty()
        if (codec.isBlank() || codec.equals("none", true)) return null
        if (audioCodec.isNotBlank() && !audioCodec.equals("none", true)) return null
        if (!format.ext.equals("mp4", true) || url.isBlank() || format.height <= 0) return null
        return YouTubeVideoTrack(
            formatId = format.formatId.orEmpty(), codec = codec,
            width = format.width, height = format.height, fps = format.fps,
            bitrateKbps = format.tbr,
            estimatedBytes = format.fileSize.takeIf { it > 0L } ?: format.fileSizeApproximate,
            url = url, headers = format.httpHeaders.toStringMap(),
        )
    }

    private fun toAudioTrack(format: VideoFormat): YouTubeAudioTrack? {
        val videoCodec = format.vcodec.orEmpty()
        val audioCodec = format.acodec.orEmpty()
        val url = format.url.orEmpty()
        if (audioCodec.isBlank() || audioCodec.equals("none", true)) return null
        if (videoCodec.isNotBlank() && !videoCodec.equals("none", true)) return null
        if (format.ext?.lowercase() !in setOf("m4a", "mp4") || url.isBlank()) return null
        if (!audioCodec.startsWith("mp4a", true)) return null
        return YouTubeAudioTrack(
            formatId = format.formatId.orEmpty(), codec = audioCodec,
            bitrateKbps = format.abr.takeIf { it > 0 } ?: format.tbr,
            estimatedBytes = format.fileSize.takeIf { it > 0L } ?: format.fileSizeApproximate,
            url = url, headers = format.httpHeaders.toStringMap(),
        )
    }

    private fun Map<*, *>?.toStringMap(): Map<String, String> = this.orEmpty().mapNotNull { (key, value) ->
        val name = key?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        name to value?.toString().orEmpty()
    }.toMap()

    private fun extractUrl(input: String): String {
        val match = URL_REGEX.find(input)?.value
            ?: throw YouTubeParseException("请粘贴 YouTube 视频链接")
        if (!match.contains("youtube.com", true) && !match.contains("youtu.be", true)) {
            throw YouTubeParseException("当前仅支持 YouTube 链接")
        }
        return match
    }

    companion object {
        private val URL_REGEX = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
        private fun qualityLabel(height: Int): String = when {
            height >= 2160 -> "4K"
            height >= 1440 -> "1440P"
            height >= 1080 -> "1080P"
            height >= 720 -> "720P"
            height >= 480 -> "480P"
            height >= 360 -> "360P"
            else -> "${height}P"
        }
    }
}
