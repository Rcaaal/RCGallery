package com.example.rcgallery.data.x

import android.content.Context
import com.example.rcgallery.util.AppLogger
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class XMediaItem(
    val id: String,
    val url: String,
    val displayName: String,
    val extension: String,
    val mimeType: String,
    val isVideo: Boolean,
    val headers: Map<String, String>,
)

data class XWorkInfo(
    val sourceUrl: String,
    val title: String,
    val author: String,
    val thumbnailUrl: String?,
    val media: List<XMediaItem>,
)

class XParser(context: Context, private val cookieFile: () -> String?) {
    private val appContext = context.applicationContext

    suspend fun parse(input: String): XWorkInfo = withContext(Dispatchers.IO) {
        val url = extractUrl(input)
        YoutubeDL.getInstance().init(appContext)
        val failures = mutableListOf<String>()
        for (api in apiModes) {
            try {
                val work = requestInfo(url, api)
                if (work.media.isNotEmpty()) {
                    AppLogger.d("XParse", "success api=$api media=${work.media.size} author=${work.author}")
                    return@withContext work
                }
                failures += "$api: no downloadable media"
            } catch (error: Exception) {
                val detail = error.message.orEmpty().lineSequence()
                    .lastOrNull { line -> line.isNotBlank() }
                    ?: error::class.java.simpleName
                failures += "$api: $detail"
                AppLogger.e("XParse", "metadata failed api=$api", error)
            }
        }
        throw IllegalStateException("Local X parse failed: ${failures.joinToString(" | ").take(500)}")
    }

    @Suppress("unused")
    private suspend fun parseLegacy(input: String): XWorkInfo = withContext(Dispatchers.IO) {
        val url = extractUrl(input)
        YoutubeDL.getInstance().init(appContext)

        val result = apiModes.firstNotNullOfOrNull { mode ->
            runCatching { requestInfo(url, mode) }
                .onFailure { AppLogger.e("XParse", "metadata failed api=$mode", it) }
                .getOrNull()
                ?.takeIf { it.media.isNotEmpty() }
        } ?: throw IllegalStateException("未找到可下载的 X 媒体，帖子可能已删除、受限或需要登录")

        AppLogger.d("XParse", "success media=${result.media.size} author=${result.author}")
        result
    }

    private suspend fun requestInfo(url: String, api: String): XWorkInfo {
        val request = YoutubeDLRequest(url).apply {
            addOption("--dump-single-json")
            addOption("--skip-download")
            addOption("--no-warnings")
            addOption("--ignore-errors")
            addOption("--no-check-certificates")
            addOption("--socket-timeout", 60)
            addOption("--retries", 5)
            addOption("--extractor-retries", 5)
            addOption("--extractor-args", "twitter:api=$api")
            cookieFile()?.let { addOption("--cookies", it) }
        }
        val response = runInterruptible { YoutubeDL.getInstance().execute(request) }
        if (response.exitCode != 0 && response.out.isBlank()) {
            throw IllegalStateException(response.err.ifBlank { "X 解析失败" })
        }
        return toWorkInfo(url, JSONObject(response.out))
    }

    private fun toWorkInfo(sourceUrl: String, root: JSONObject): XWorkInfo {
        val title = root.optString("title").ifBlank { "X 媒体" }
        val author = root.optString("uploader").ifBlank { root.optString("uploader_id") }
        val thumbnail = root.optString("thumbnail").takeIf { it.startsWith("http") }
        val records = mutableListOf<JSONObject>()
        collectEntries(root, records)
        val usedUrls = HashSet<String>()
        val media = records.mapNotNull { record -> toMediaItem(record, title, author) }
            .filter { usedUrls.add(it.url) }
        return XWorkInfo(sourceUrl, title, author, thumbnail, media)
    }

    private fun collectEntries(record: JSONObject, output: MutableList<JSONObject>) {
        val entries = record.optJSONArray("entries")
        if (entries == null) {
            output += record
            return
        }
        for (index in 0 until entries.length()) {
            (entries.opt(index) as? JSONObject)?.let { collectEntries(it, output) }
        }
    }

    private fun toMediaItem(record: JSONObject, rootTitle: String, author: String): XMediaItem? {
        val format = bestFormat(record)
        val mediaUrl = format?.optString("url")?.takeIf { it.startsWith("http") }
            ?: record.optString("url").takeIf { it.startsWith("http") }
            ?: return null
        val video = format?.optString("vcodec")?.let { it.isNotBlank() && !it.equals("none", true) }
            ?: record.optString("ext").lowercase() in videoExtensions
        val extension = (format?.optString("ext").orEmpty().ifBlank { record.optString("ext") })
            .lowercase()
            .ifBlank { if (video) "mp4" else "jpg" }
        val mimeType = when (extension) {
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "gif" -> "image/gif"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> if (video) "video/mp4" else "image/jpeg"
        }
        val itemId = record.optString("id").ifBlank { mediaUrl.hashCode().toString() }
        val sequence = record.optInt("playlist_index", 0).takeIf { it > 0 } ?: 1
        val rawName = listOf(author, record.optString("title").ifBlank { rootTitle }, sequence.toString())
            .filter { it.isNotBlank() }
            .joinToString("_")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(160)
        return XMediaItem(
            id = itemId,
            url = mediaUrl,
            displayName = "$rawName.$extension",
            extension = extension,
            mimeType = mimeType,
            isVideo = video,
            headers = headersFrom(format ?: record),
        )
    }

    private fun bestFormat(record: JSONObject): JSONObject? {
        val formats = record.optJSONArray("formats") ?: return null
        var best: JSONObject? = null
        var bestScore = Long.MIN_VALUE
        for (index in 0 until formats.length()) {
            val format = formats.optJSONObject(index) ?: continue
            val url = format.optString("url")
            val vcodec = format.optString("vcodec")
            if (!url.startsWith("http") || vcodec.isBlank() || vcodec.equals("none", true)) continue
            if (url.contains(".m3u8", true)) continue
            val score = format.optLong("height").toLong() * 1_000_000L + format.optLong("tbr")
            if (score > bestScore) {
                best = format
                bestScore = score
            }
        }
        return best
    }

    private fun headersFrom(record: JSONObject): Map<String, String> {
        val source = record.optJSONObject("http_headers") ?: return emptyMap()
        return buildMap {
            source.keys().forEach { key ->
                source.optString(key).takeIf { it.isNotBlank() }?.let { put(key, it) }
            }
        }
    }

    private fun extractUrl(input: String): String {
        val url = URL_REGEX.find(input)?.value ?: throw IllegalArgumentException("请粘贴 X/Twitter 帖子链接")
        if (!url.contains("x.com", true) && !url.contains("twitter.com", true)) {
            throw IllegalArgumentException("当前仅支持 X/Twitter 帖子链接")
        }
        return url
    }

    private companion object {
        val URL_REGEX = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
        val apiModes = listOf("graphql", "legacy", "syndication")
        val videoExtensions = setOf("mp4", "webm", "mkv", "mov")
    }
}
