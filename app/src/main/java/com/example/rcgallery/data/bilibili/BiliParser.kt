package com.example.rcgallery.data.bilibili

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Bilibili public-video parser. Protocol behavior and stream selection are informed by
 * BBDown 1.6.3 (MIT, Copyright 2020 nilaoda), rewritten for Android/Kotlin.
 */
class BiliParser(
    private val cookieProvider: () -> String? = { null },
) {
    suspend fun parse(sharedText: String): BiliWorkInfo = withContext(Dispatchers.IO) {
        val resolved = resolveInput(sharedText)
        val identity = extractIdentity(resolved)
        val query = identity.apiQuery()
        val root = getJson("https://api.bilibili.com/x/web-interface/view?$query")
        val data = requireApiData(root)
        val pages = data.optJSONArray("pages").toPages()
        if (pages.isEmpty()) throw BiliParseException("作品中没有可下载的分P")

        val provisional = BiliWorkInfo(
            bvid = data.optString("bvid").ifBlank { identity.bvid.orEmpty() },
            aid = data.optLong("aid", identity.aid ?: 0L),
            title = sanitizeTitle(data.optString("title").ifBlank { "bilibili_video" }),
            ownerName = data.optJSONObject("owner")?.optString("name").orEmpty(),
            coverUrl = data.optString("pic").takeIf { it.isNotBlank() }?.toHttps(),
            publishTimeSeconds = data.optLong("pubdate"),
            pages = pages,
            qualities = emptyList(),
        )
        val streams = loadStreams(provisional, pages.first(), MAX_QUALITY)
        val qualities = streams.videos
            .groupBy { it.qualityId }
            .map { (id, tracks) ->
                val best = tracks.maxByOrNull { it.width.toLong() * it.height } ?: tracks.first()
                BiliQuality(
                    id = id,
                    label = streams.qualityLabels[id] ?: qualityLabel(id),
                    width = best.width,
                    height = best.height,
                )
            }
            .sortedBy { qualityRank(it.id) }
        if (qualities.isEmpty()) throw BiliParseException("没有获取到可用的普通视频流")
        provisional.copy(qualities = qualities)
    }

    suspend fun loadStreams(
        work: BiliWorkInfo,
        page: BiliPage,
        qualityId: Int,
    ): BiliPageStreams = withContext(Dispatchers.IO) {
        val identityQuery = if (work.bvid.isNotBlank()) {
            "bvid=${encode(work.bvid)}"
        } else {
            "avid=${work.aid}"
        }
        val url = "https://api.bilibili.com/x/player/playurl?" +
            "$identityQuery&cid=${page.cid}&qn=$qualityId&fnval=4048&fnver=0&fourk=1&otype=json"
        val data = requireApiData(getJson(url, referer(work)))
        val labels = buildMap {
            val formats = data.optJSONArray("support_formats") ?: JSONArray()
            for (index in 0 until formats.length()) {
                val format = formats.optJSONObject(index) ?: continue
                put(
                    format.optInt("quality"),
                    format.optString("new_description").ifBlank {
                        format.optString("display_desc").ifBlank { qualityLabel(format.optInt("quality")) }
                    }
                )
            }
        }
        val dash = data.optJSONObject("dash")
            ?: throw BiliParseException("当前作品没有返回可合并的 DASH 音视频流")
        val videos = dash.optJSONArray("video").toVideoTracks()
        val audios = dash.optJSONArray("audio").toAudioTracks()
        if (videos.isEmpty()) throw BiliParseException("没有可用的视频轨")
        if (audios.isEmpty()) throw BiliParseException("没有可用的 AAC 音频轨")
        BiliPageStreams(videos = videos, audios = audios, qualityLabels = labels)
    }

    private fun resolveInput(text: String): String {
        val directId = BV_REGEX.find(text)?.value ?: AV_REGEX.find(text)?.value
        val url = URL_REGEX.find(text)?.value?.trimEnd('.', ',', '，', '。', ')', '）')
        if (url == null) return directId ?: text.trim()
        if (!url.contains("b23.tv") && !url.contains("bili2233.cn")) return url
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            connection.responseCode
            connection.url.toString()
        } finally {
            connection.disconnect()
        }
    }

    private fun extractIdentity(value: String): VideoIdentity {
        BV_REGEX.find(value)?.value?.let { return VideoIdentity(bvid = it, aid = null) }
        val av = AV_REGEX.find(value)?.groupValues?.getOrNull(1)?.toLongOrNull()
        if (av != null) return VideoIdentity(bvid = null, aid = av)
        throw BiliParseException("没有识别到 BV 号或 AV 号")
    }

    private fun getJson(url: String, referer: String = "https://www.bilibili.com/"): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Referer", referer)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            cookieProvider()?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw BiliParseException("B站接口请求失败（HTTP $code）")
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun requireApiData(root: JSONObject): JSONObject {
        val code = root.optInt("code", Int.MIN_VALUE)
        if (code != 0) {
            val message = root.optString("message").ifBlank { "B站接口返回错误 $code" }
            throw BiliParseException(message)
        }
        return root.optJSONObject("data") ?: throw BiliParseException("B站接口没有返回数据")
    }

    private fun JSONArray?.toPages(): List<BiliPage> {
        val array = this ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val page = array.optJSONObject(index) ?: continue
                add(
                    BiliPage(
                        cid = page.optLong("cid"),
                        pageNumber = page.optInt("page", index + 1),
                        title = page.optString("part").ifBlank { "P${index + 1}" },
                        durationSeconds = page.optInt("duration"),
                    )
                )
            }
        }
    }

    private fun JSONArray?.toVideoTracks(): List<BiliVideoTrack> {
        val array = this ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val node = array.optJSONObject(index) ?: continue
                val codecs = node.optString("codecs")
                if (!codecs.startsWith("avc", true) && !codecs.startsWith("hev", true) &&
                    !codecs.startsWith("hvc", true) && !codecs.startsWith("av01", true)) continue
                val urls = node.urls()
                if (urls.isEmpty()) continue
                add(
                    BiliVideoTrack(
                        qualityId = node.optInt("id"),
                        codecs = codecs,
                        width = node.optInt("width"),
                        height = node.optInt("height"),
                        frameRate = node.optString("frameRate").ifBlank { node.optString("frame_rate") },
                        bandwidth = node.optLong("bandwidth"),
                        urls = urls,
                    )
                )
            }
        }
    }

    private fun JSONArray?.toAudioTracks(): List<BiliAudioTrack> {
        val array = this ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val node = array.optJSONObject(index) ?: continue
                val codecs = node.optString("codecs")
                if (!codecs.startsWith("mp4a", true)) continue
                val urls = node.urls()
                if (urls.isEmpty()) continue
                add(BiliAudioTrack(codecs, node.optLong("bandwidth"), urls))
            }
        }
    }

    private fun JSONObject.urls(): List<String> = buildList {
        optString("baseUrl").ifBlank { optString("base_url") }.takeIf { it.isNotBlank() }?.let(::add)
        val backups = optJSONArray("backupUrl") ?: optJSONArray("backup_url") ?: JSONArray()
        for (index in 0 until backups.length()) backups.optString(index).takeIf { it.isNotBlank() }?.let(::add)
    }.map { it.toHttps() }.distinct()

    private fun VideoIdentity.apiQuery(): String = bvid?.let { "bvid=${encode(it)}" } ?: "aid=$aid"

    private fun referer(work: BiliWorkInfo): String = "https://www.bilibili.com/video/${work.bvid}"

    private fun String.toHttps(): String = if (startsWith("http://")) replaceFirst("http://", "https://") else this

    private fun sanitizeTitle(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(120)
        .ifBlank { "bilibili_video" }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private data class VideoIdentity(val bvid: String?, val aid: Long?)

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private const val MAX_QUALITY = 127
        private val URL_REGEX = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
        private val BV_REGEX = Regex("BV[0-9A-Za-z]{10,}", RegexOption.IGNORE_CASE)
        private val AV_REGEX = Regex("(?:^|[/\\s])av(\\d+)", RegexOption.IGNORE_CASE)

        fun qualityRank(id: Int): Int = QUALITY_ORDER.indexOf(id).let { if (it >= 0) it else Int.MAX_VALUE }

        fun qualityLabel(id: Int): String = when (id) {
            127 -> "8K"
            126 -> "杜比视界"
            125 -> "HDR"
            120 -> "4K"
            116 -> "1080P 60帧"
            112 -> "1080P 高码率"
            80 -> "1080P"
            74 -> "720P 60帧"
            64 -> "720P"
            32 -> "480P"
            16 -> "360P"
            else -> "清晰度 $id"
        }

        private val QUALITY_ORDER = listOf(127, 126, 125, 120, 116, 112, 80, 74, 64, 32, 16, 6, 5)
    }
}
