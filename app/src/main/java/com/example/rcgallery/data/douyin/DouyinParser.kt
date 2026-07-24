package com.example.rcgallery.data.douyin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.SecureRandom
import java.util.Base64
import kotlin.coroutines.coroutineContext

class DouyinParser {

    data class DynamicEnhancement(
        val urlsBySourceKey: Map<String, List<String>>,
        val urlsByIndex: Map<Int, List<String>>,
    )

    suspend fun parse(sharedText: String, cookies: String? = null): DouyinWorkInfo = withContext(Dispatchers.IO) {
        val inputUrl = extractUrl(sharedText)
        val userAgent = MOBILE_USER_AGENT
        val finalUrl = resolveRedirects(inputUrl, userAgent, cookies)
        val workId = extractWorkId(finalUrl)
        val pathType = extractPathType(finalUrl)
        val fetchPath = if (pathType == "slides") "note" else pathType
        val html = fetchText("https://www.iesdouyin.com/share/$fetchPath/$workId", userAgent, cookies)
        val item = findItem(JSONObject(extractRouterData(html)))
        val author = item.optJSONObject("author")?.optString("nickname")?.takeIf { it.isNotBlank() }
        val title = sanitizeTitle(item.optString("desc").ifBlank { "douyin_$workId" })

        val imageResources = extractImageResources(item)
        val topVideoUrls = videoUrls(item.optJSONObject("video"))
        val media = mutableListOf<DouyinMediaResource>()
        media.addAll(imageResources)
        if (imageResources.isEmpty() && topVideoUrls.isNotEmpty()) {
            media.add(DouyinMediaResource.Video(media.size + 1, topVideoUrls))
        }

        if (media.isEmpty()) throw DouyinParseException("作品中没有可用的图片或视频")
        DouyinWorkInfo(
            workId = workId,
            title = title,
            author = author,
            coverUrl = imageResources.firstOrNull()?.urls?.firstOrNull()
                ?: firstUrl(item.optJSONObject("video")?.optJSONObject("cover")?.optJSONArray("url_list")),
            userAgent = userAgent,
            media = media,
        )
    }

    /**
     * Call the authenticated Douyin API to get rich data including per-image animated video URLs.
     * Requires valid cookies (especially s_v_web_id) and X-Bogus signature.
     *
     * @return Map of image index (0-based) -> animated video URLs, or null if API fails or no animated data.
     */
    internal suspend fun enhanceWithApi(
        workId: String,
        userAgent: String,
        cookies: String,
    ): DynamicEnhancement {
        var lastFailure = "详情接口没有返回作品数据"
        for (aid in DETAIL_AID_CANDIDATES) {
            val params = buildApiParams(workId, aid)
            val (signedParams, _, _) = XBogus(userAgent).getXBogus(params)
            val json = fetchApiJson("$POST_DETAIL?$signedParams", userAgent, cookies)
            if (json == null) {
                lastFailure = "详情接口请求失败"
                continue
            }
            val detail = json.optJSONObject("aweme_detail")
            if (detail == null) {
                val filterReason = json.optJSONObject("filter_detail")?.optString("filter_reason")
                lastFailure = filterReason?.takeIf { it.isNotBlank() }
                    ?.let { "作品数据被接口过滤：$it" } ?: "详情接口没有返回作品数据"
                continue
            }
            return dynamicEnhancementFromDetail(detail)
        }
        throw DouyinParseException(lastFailure)
    }

    internal fun enhanceFromDetailJson(rawJson: String): DynamicEnhancement {
        val detail = JSONObject(rawJson).optJSONObject("aweme_detail")
            ?: throw DouyinParseException("详情接口没有返回作品数据")
        return dynamicEnhancementFromDetail(detail)
    }

    private fun dynamicEnhancementFromDetail(detail: JSONObject): DynamicEnhancement {
        val images = detailImageCandidates(detail).firstOrNull { it.length() > 0 }
            ?: return DynamicEnhancement(emptyMap(), emptyMap())
        val bySourceKey = mutableMapOf<String, List<String>>()
        val byIndex = mutableMapOf<Int, List<String>>()
        for (index in 0 until images.length()) {
            val image = images.optJSONObject(index) ?: continue
            val urls = animatedVideoUrls(image)
            if (urls.isEmpty()) continue
            byIndex[index] = urls
            image.optString("uri").takeIf { it.isNotBlank() }?.let { bySourceKey[it] = urls }
        }
        return DynamicEnhancement(bySourceKey, byIndex)
    }

    /**
     * Build API parameters similar to f2's PostDetail model defaults.
     */
    private fun buildApiParams(awemeId: String, aid: String): String {
        val msToken = generateMsToken()
        return buildString {
            append("device_platform=webapp")
            append("&aid=$aid")
            append("&channel=channel_pc_web")
            append("&pc_client_type=1")
            append("&publish_video_strategy_type=2")
            append("&pc_libra_divert=Windows")
            append("&version_code=290100")
            append("&version_name=29.1.0")
            append("&cookie_enabled=true")
            append("&screen_width=1920")
            append("&screen_height=1080")
            append("&browser_language=zh-CN")
            append("&browser_platform=Win32")
            append("&browser_name=Edge")
            append("&browser_version=130.0.0.0")
            append("&browser_online=true")
            append("&engine_name=Blink")
            append("&engine_version=130.0.0.0")
            append("&os_name=Windows")
            append("&os_version=10")
            append("&cpu_core_num=12")
            append("&device_memory=8")
            append("&platform=PC")
            append("&downlink=10")
            append("&effective_type=4g")
            append("&round_trip_time=100")
            append("&msToken=$msToken")
            append("&aweme_id=$awemeId")
        }
    }

    /** Generate a fake msToken (base64-encoded random string). */
    private fun generateMsToken(): String {
        val random = SecureRandom()
        val bytes = ByteArray(60)
        random.nextBytes(bytes)
        return Base64.getEncoder().withoutPadding().encodeToString(bytes).replace('+', '-').replace('/', '_')
    }

    /**
     * Fetch JSON from the authenticated API endpoint.
     * Returns null on any failure (server returns empty body, HTTP error, etc.)
     */
    private suspend fun fetchApiJson(url: String, userAgent: String, cookies: String): JSONObject? {
        return try {
            val connection = openConnection(url, userAgent, followRedirects = true).apply {
                setRequestProperty("Cookie", cookies)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                readTimeout = 15_000
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) return null
                val body = BufferedInputStream(connection.inputStream).bufferedReader(Charsets.UTF_8).readText()
                if (body.isBlank()) return null
                JSONObject(body)
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractImageResources(item: JSONObject): List<DouyinMediaResource> {
        val qualityImages = highestQualityImages(item)
        val candidates = buildList {
            item.optJSONArray("images")?.let(::add)
            item.optJSONObject("image_post_info")?.let { post ->
                post.optJSONArray("images")?.let(::add)
                post.optJSONArray("image_list")?.let(::add)
            }
        }
        val seen = linkedSetOf<String>()
        val result = mutableListOf<DouyinMediaResource>()
        candidates.forEach { array ->
            for (index in 0 until array.length()) {
                val image = array.optJSONObject(index) ?: continue
                val qualityImage = image.optString("uri").takeIf { it.isNotBlank() }?.let(qualityImages::get)
                val urls = imageUrls(image, qualityImage).filter { seen.add(it) }
                if (urls.isEmpty()) continue
                val sourceKey = image.optString("uri").takeIf { it.isNotBlank() }
                val animatedUrls = animatedVideoUrls(image)
                if (animatedUrls.isNotEmpty()) {
                    result += DouyinMediaResource.AnimatedImage(result.size + 1, urls, animatedUrls, sourceKey)
                } else {
                    result += DouyinMediaResource.Image(result.size + 1, urls, sourceKey)
                }
            }
        }
        return result
    }

    private fun detailImageCandidates(detail: JSONObject): List<JSONArray> = buildList {
        detail.optJSONArray("images")?.let(::add)
        detail.optJSONObject("image_post_info")?.let { post ->
            post.optJSONArray("images")?.let(::add)
            post.optJSONArray("image_list")?.let(::add)
        }
        detail.optJSONArray("image_infos")?.let(::add)
    }

    private fun animatedVideoUrls(image: JSONObject): List<String> =
        listOf("live_photo", "clip_video", "video")
            .flatMap { key -> videoUrls(image.optJSONObject(key)) }
            .distinct()

    private fun highestQualityImages(item: JSONObject): Map<String, JSONObject> {
        val bitRates = item.optJSONArray("img_bitrate") ?: return emptyMap()
        var bestArea = -1L
        var bestImages: JSONArray? = null
        for (index in 0 until bitRates.length()) {
            val images = bitRates.optJSONObject(index)?.optJSONArray("images") ?: continue
            var area = 0L
            for (imageIndex in 0 until images.length()) {
                val image = images.optJSONObject(imageIndex) ?: continue
                area = maxOf(area, image.optLong("width") * image.optLong("height"))
            }
            if (area > bestArea) {
                bestArea = area
                bestImages = images
            }
        }
        return buildMap {
            val images = bestImages ?: return@buildMap
            for (index in 0 until images.length()) {
                val image = images.optJSONObject(index) ?: continue
                image.optString("uri").takeIf { it.isNotBlank() }?.let { put(it, image) }
            }
        }
    }

    private fun videoUrls(video: JSONObject?): List<String> {
        if (video == null) return emptyList()
        val bitRates = video.optJSONArray("bit_rate")
        var bestBitRate = Long.MIN_VALUE
        var bestUrls = emptyList<String>()
        if (bitRates != null) {
            for (index in 0 until bitRates.length()) {
                val entry = bitRates.optJSONObject(index) ?: continue
                val urls = allUrls(entry.optJSONObject("play_addr")?.optJSONArray("url_list"))
                val bitRate = entry.optLong("bit_rate", -1L)
                if (urls.isNotEmpty() && bitRate > bestBitRate) {
                    bestBitRate = bitRate
                    bestUrls = urls
                }
            }
        }
        val fallbackUrls = sequenceOf(
            video.optJSONObject("play_addr")?.optJSONArray("url_list"),
            video.optJSONObject("play_addr_h264")?.optJSONArray("url_list"),
            video.optJSONArray("url_list"),
        ).map(::allUrls).firstOrNull { it.isNotEmpty() }.orEmpty()
        return (bestUrls.ifEmpty { fallbackUrls })
            .map { it.replace("playwm", "play") }
            .filterNot(::isAudioUrl)
            .distinct()
    }

    private fun isAudioUrl(url: String): Boolean {
        val lower = runCatching { java.net.URLDecoder.decode(url, Charsets.UTF_8.name()) }
            .getOrDefault(url)
            .lowercase()
        return AUDIO_EXTENSIONS.any { lower.contains(it) }
    }

    private fun imageUrls(image: JSONObject, qualityImage: JSONObject?): List<String> {
        val original = sequenceOf(
            image.optJSONObject("origin_image")?.optJSONArray("url_list"),
            image.optJSONObject("original_image")?.optJSONArray("url_list"),
        ).flatMap { allUrls(it).asSequence() }.distinct().toList()
        if (original.isNotEmpty()) return bestImageMirrors(original)

        val fullSizeCandidates = sequenceOf(
            image.optJSONArray("url_list"),
            qualityImage?.optJSONArray("url_list"),
        ).flatMap { allUrls(it).asSequence() }.distinct().toList()
        if (fullSizeCandidates.isNotEmpty()) return bestImageMirrors(fullSizeCandidates)

        val fallback = sequenceOf(
            image.optJSONArray("download_url_list"),
            image.optJSONObject("display_image")?.optJSONArray("url_list"),
        ).flatMap { allUrls(it).asSequence() }.distinct().toList()
        return bestImageMirrors(fallback)
    }

    private fun bestImageMirrors(urls: List<String>): List<String> {
        if (urls.isEmpty()) return emptyList()
        val bestScore = urls.maxOf(::imageQualityScore)
        return urls.filter { imageQualityScore(it) == bestScore }
    }

    private fun imageQualityScore(url: String): Int {
        val lower = runCatching { java.net.URLDecoder.decode(url, Charsets.UTF_8.name()) }
            .getOrDefault(url)
            .lowercase()
        var score = 100
        if ("origin" in lower || "original" in lower) score += 1000
        if ("water" in lower) score -= 800
        if ("tplv-dy-shrink" in lower) score -= 500
        if ("tplv-dy-lqen" in lower) score -= 400
        if (Regex(":\\d{2,5}:\\d{2,5}").containsMatchIn(lower)) score -= 300
        if ("~q80." in lower && "tplv-" !in lower) score += 200
        return score
    }

    private fun allUrls(array: JSONArray?): List<String> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            array.optString(index).takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let(::add)
        }
    }

    private fun extractUrl(text: String): String {
        val value = URL_PATTERN.find(text)?.value
            ?.trimEnd('，', '。', '！', '？', ',', '.', ';', '；', ')', '）', ']', '】')
            ?: throw DouyinParseException("没有找到抖音链接")
        validateDouyinHost(value)
        return value
    }

    private fun resolveRedirects(input: String, userAgent: String, cookies: String?): String {
        var current = input
        repeat(MAX_REDIRECTS) {
            validateDouyinHost(current)
            val connection = openConnection(current, userAgent, followRedirects = false).apply {
                cookies?.let { setRequestProperty("Cookie", it) }
            }
            try {
                val code = connection.responseCode
                if (code !in 300..399) {
                    if (code !in 200..299) throw DouyinParseException("抖音链接访问失败（HTTP $code）")
                    return current
                }
                val location = connection.getHeaderField("Location")
                    ?: throw DouyinParseException("抖音短链接缺少跳转地址")
                current = URI(current).resolve(location).toString()
            } finally {
                connection.disconnect()
            }
        }
        throw DouyinParseException("抖音短链接跳转次数过多")
    }

    private fun extractWorkId(url: String): String {
        val uri = URI(url)
        WORK_ID_PATTERN.find(uri.path.orEmpty())?.groupValues?.get(1)?.let { return it }
        val modalId = uri.rawQuery.orEmpty().split('&')
            .mapNotNull { it.split('=', limit = 2).takeIf { pair -> pair.size == 2 } }
            .firstOrNull { it[0] == "modal_id" }?.get(1)
        return modalId?.takeIf { it.all(Char::isDigit) }
            ?: throw DouyinParseException("无法从链接中识别作品 ID")
    }

    /**
     * Extract the path type (video/note/slides) from the resolved URL.
     * Used to construct the correct fetch URL for the SSR page.
     */
    private fun extractPathType(url: String): String {
        val path = URI(url).path.orEmpty()
        return PATH_TYPE_PATTERN.find(path)?.groupValues?.get(1)?.lowercase() ?: "video"
    }

    private suspend fun fetchText(url: String, userAgent: String, cookies: String?): String {
        val connection = openConnection(url, userAgent, followRedirects = true).apply {
            cookies?.let { setRequestProperty("Cookie", it) }
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw DouyinParseException("作品页面访问失败（HTTP $code）")
            BufferedInputStream(connection.inputStream).bufferedReader(Charsets.UTF_8).use { reader ->
                val output = StringBuilder()
                val buffer = CharArray(16 * 1024)
                var total = 0
                while (true) {
                    coroutineContext.ensureActive()
                    val read = reader.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_HTML_BYTES) throw DouyinParseException("作品页面数据异常")
                    output.append(buffer, 0, read)
                }
                output.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractRouterData(html: String): String {
        val markerIndex = html.indexOf(ROUTER_MARKER)
        if (markerIndex < 0) throw DouyinParseException("作品页面结构已变化，未找到作品数据")
        val start = html.indexOf('{', markerIndex + ROUTER_MARKER.length)
        if (start < 0) throw DouyinParseException("作品页面数据不完整")
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until html.length) {
            val char = html[index]
            if (inString) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') inString = false
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> if (--depth == 0) return html.substring(start, index + 1)
            }
        }
        throw DouyinParseException("作品页面数据不完整")
    }

    private fun findItem(routerData: JSONObject): JSONObject {
        val loaderData = routerData.optJSONObject("loaderData")
            ?: throw DouyinParseException("作品页面缺少 loaderData")
        val keys = loaderData.keys()
        while (keys.hasNext()) {
            val page = loaderData.optJSONObject(keys.next()) ?: continue
            val itemList = page.optJSONObject("videoInfoRes")?.optJSONArray("item_list") ?: continue
            if (itemList.length() > 0) return itemList.getJSONObject(0)
        }
        throw DouyinParseException("没有找到公开作品信息，作品可能已删除或不可见")
    }

    private fun firstUrl(array: JSONArray?): String? = array?.let(::allUrls)?.firstOrNull()

    private fun validateDouyinHost(value: String) {
        val host = runCatching { URI(value).host?.lowercase() }.getOrNull()
            ?: throw DouyinParseException("链接格式无效")
        val allowed = host == "douyin.com" || host.endsWith(".douyin.com") ||
            host == "iesdouyin.com" || host.endsWith(".iesdouyin.com")
        if (!allowed) throw DouyinParseException("只支持抖音链接")
        if (!value.startsWith("https://")) throw DouyinParseException("只支持 HTTPS 抖音链接")
    }

    private fun openConnection(url: String, userAgent: String, followRedirects: Boolean) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = followRedirects
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            setRequestProperty("Referer", "https://www.douyin.com/")
        }

    private fun sanitizeTitle(value: String): String = value
        .replace(Regex("""[\\/:*?\"<>|#\n\r]"""), "_")
        .replace(Regex("""\.{2,}"""), ".")
        .trim(' ', '.')
        .take(80)
        .ifBlank { "douyin_work" }

    companion object {
        private const val MAX_REDIRECTS = 5
        private const val MAX_HTML_BYTES = 5 * 1024 * 1024
        private const val ROUTER_MARKER = "window._ROUTER_DATA"
        private const val POST_DETAIL = "https://www.douyin.com/aweme/v1/web/aweme/detail/"
        private val URL_PATTERN = Regex("https?://[^\\s]+")
        private val WORK_ID_PATTERN = Regex("/(?:video|note|slides)/(\\d+)")
        private val PATH_TYPE_PATTERN = Regex("/(?:share/)?(video|note|slides)/")
        private val AUDIO_EXTENSIONS = listOf(".mp3", ".m4a", ".aac", ".wav", ".ogg", ".flac")
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"
        private val DETAIL_AID_CANDIDATES = listOf("6383", "1128")
    }
}

class DouyinParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
