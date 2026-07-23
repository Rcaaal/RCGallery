package com.example.rcgallery.data.youtube

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import com.yausername.youtubedl_android.mapper.VideoInfo
import com.example.rcgallery.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File

class YouTubeParser(
    context: Context,
    private val cookieFile: String? = null,
    private val authManager: YouTubeAuthManager = YouTubeAuthManager(context),
) {
    private val appContext = context.applicationContext

    suspend fun parse(input: String): YouTubeWorkInfo = withContext(Dispatchers.IO) {
        val url = extractUrl(input)
        val videoId = extractVideoId(url)
        val infoJsonFile = File(
            appContext.filesDir,
            "youtube_info/${videoId}_${System.nanoTime()}.info.json",
        ).apply { parentFile?.mkdirs() }
        AppLogger.d("YouTubeParse", "start id=$videoId hasVisitorData=${authManager.hasVisitorData()} hasCookie=${cookieFile?.let { File(it).exists() } == true}")
        try {
            AppLogger.d("YouTubeParse", "init yt-dlp")
            YoutubeDL.getInstance().init(appContext)
            FFmpeg.getInstance().init(appContext)
            // 更新 yt-dlp 到最新版
            kotlin.runCatching {
                YoutubeDL.getInstance().updateYoutubeDL(appContext, YoutubeDL.UpdateChannel.STABLE)
            }.onFailure { AppLogger.e("YouTubeParse", "yt-dlp update failed", it) }

            val authArgs = if (authManager.hasVisitorData()) {
                authManager.extractorArgs(videoId)
            } else null
            AppLogger.d(
                "YouTubeParse",
                "auth args ready=${authArgs != null} clients=${authArgs?.substringAfter("player_client=")?.substringBefore(';') ?: "anonymous"}"
            )
            val info = try {
                AppLogger.d("YouTubeParse", "getInfo request id=$videoId")
                loadInfo(url, authArgs, infoJsonFile)
            } catch (error: Exception) {
                AppLogger.e("YouTubeParse", "getInfo failed id=$videoId message=${error.message}", error)
                if (authArgs != null && isFormatUnavailable(error)) {
                    // A PO-token/client combination can expose a restricted format
                    // set for one video. Retry metadata with yt-dlp's normal client
                    // negotiation; cookies remain attached to the request.
                    AppLogger.d("YouTubeParse", "retry getInfo without forced player client id=$videoId")
                    loadInfo(url, null, infoJsonFile)
                } else if (authArgs != null && isLoginChallenge(error) && hasCookieFile()) {
                    // A stale or client-incompatible PO token can trigger the bot
                    // challenge even when the WebView cookies are still usable.
                    // Let yt-dlp authenticate with the exported cookies alone.
                    AppLogger.d("YouTubeParse", "retry getInfo with cookies only id=$videoId")
                    loadInfo(url, null, infoJsonFile)
                } else {
                    if (!isLoginChallenge(error) || authArgs != null) throw error
                    AppLogger.d("YouTubeImport", "anonymous client challenged; no ytdlnis auth context available")
                    throw error
                }
            }

            val videos = info.formats.orEmpty().mapNotNull(::toVideoTrack)
            val audios = info.formats.orEmpty().mapNotNull(::toAudioTrack)
            AppLogger.d("YouTubeParse", "getInfo success formats=${info.formats?.size ?: 0} videos=${videos.size} audios=${audios.size}")
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
                infoJsonPath = infoJsonFile.takeIf { it.isFile && it.length() > 128L }?.absolutePath,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: YouTubeParseException) {
            throw error
        } catch (error: Exception) {
            AppLogger.e("YouTubeParse", "parse failed id=$videoId type=${error::class.java.simpleName}", error)
            val detail = error.message.orEmpty()
            val friendly = when {
                isLoginChallenge(error) -> "当前网络/IP触发了 YouTube 机器人验证，请在 App 内登录 YouTube 后重试"
                detail.contains("Private video", true) -> "这是私密视频，请在 App 内登录后重试"
                detail.contains("Video unavailable", true) -> "视频不可用或存在地区限制"
                else -> detail.lineSequence().lastOrNull { it.isNotBlank() } ?: "YouTube 解析失败"
            }
            throw YouTubeParseException(friendly.take(240), error)
        }
    }

    /**
     * 完全照搬库的 getInfo() 用法——库内部加 --dump-json 并调用 execute()。
     * 加上 --ignore-errors 后，库的 ignoreErrors() 会检查：
     *   hasOption("--dump-json") && !out.isEmpty() && hasOption("--ignore-errors")
     * 三个条件全满足 → 即使 exit code > 0 也不抛异常。
     */
    private suspend fun loadInfo(url: String, extractorArgs: String?, infoJsonFile: File): VideoInfo {
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--skip-download")
            addOption("--no-warnings")
            addOption("--ignore-errors")
            addOption("--no-check-certificates")
            addOption("--compat-options", "manifest-filesize-approx")
            addOption("--socket-timeout", 15)
            addOption("--retries", 1)
            addOption("--extractor-retries", 1)
            addOption("--force-ipv4")
            extractorArgs?.let {
                addOption("--extractor-args", "youtube:$it")
            }
            cookieFile?.let { path ->
                if (File(path).exists()) addOption("--cookies", path)
            }
            // Keep the exact JSON used for this successful metadata request.
            // The download can then use --load-info-json without contacting YouTube again.
            addCommands(listOf("--print-to-file", "video:%()j", infoJsonFile.absolutePath))
        }
        AppLogger.d(
            "YouTubeParse",
            "request options auth=${extractorArgs != null} cookies=${cookieFile?.let { File(it).exists() } == true}"
        )
        return runInterruptible { YoutubeDL.getInstance().getInfo(request) }
    }

    private fun isLoginChallenge(error: Throwable): Boolean {
        val detail = error.message.orEmpty()
        return detail.contains("Sign in", true) ||
            detail.contains("confirm you.re not a bot", true) ||
            detail.contains("confirm you're not a bot", true)
    }

    private fun isFormatUnavailable(error: Throwable): Boolean =
        error.message.orEmpty().contains("Requested format is not available", true)

    private fun hasCookieFile(): Boolean =
        cookieFile?.let { path -> File(path).isFile && File(path).length() > 128L } == true

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
        )
    }

    private fun extractUrl(input: String): String {
        val match = URL_REGEX.find(input)?.value
            ?: throw YouTubeParseException("请粘贴 YouTube 视频链接")
        if (!match.contains("youtube.com", true) && !match.contains("youtu.be", true)) {
            throw YouTubeParseException("当前仅支持 YouTube 链接")
        }
        return match
    }

    private fun extractVideoId(url: String): String = when {
        url.contains("youtu.be/", true) -> url.substringAfter("youtu.be/").substringBefore('?').substringBefore('/')
        else -> Regex("[?&]v=([^&]+)").find(url)?.groupValues?.get(1).orEmpty()
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
