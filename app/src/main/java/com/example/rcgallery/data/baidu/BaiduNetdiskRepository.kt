package com.example.rcgallery.data.baidu

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class BaiduNetdiskRepository(context: Context) {
    private val appContext = context.applicationContext
    private val tokenStore = BaiduSecureTokenStore(appContext)
    private val authBackend = BaiduAuthBackend()
    private val refreshMutex = Mutex()

    val isBackendConfigured: Boolean get() = authBackend.isConfigured
    val isSignedIn: Boolean get() = tokenStore.read() != null

    fun authorizationUrl(state: String): String = authBackend.authorizationUrl(state)

    suspend fun completeLogin(code: String, state: String) {
        tokenStore.write(authBackend.exchange(code, state))
    }

    fun signOut() = tokenStore.clear()

    suspend fun mediaUrl(entry: BaiduCloudEntry): String = withContext(Dispatchers.IO) {
        require(entry.isMedia) { "仅支持预览图片和视频" }
        val (dlink, token) = authorizedDownloadLink(entry.fsId)
        appendAccessToken(dlink, token)
    }

    suspend fun listFolder(path: String): List<BaiduCloudEntry> = withContext(Dispatchers.IO) {
        val result = mutableListOf<BaiduCloudEntry>()
        var start = 0
        var authRetried = false
        do {
            val token = validToken().accessToken
            val url = Uri.parse(FILE_API).buildUpon()
                .appendQueryParameter("method", "list")
                .appendQueryParameter("access_token", token)
                .appendQueryParameter("dir", path)
                .appendQueryParameter("start", start.toString())
                .appendQueryParameter("limit", PAGE_SIZE.toString())
                .appendQueryParameter("order", "name")
                .appendQueryParameter("desc", "0")
                .appendQueryParameter("web", "1")
                .appendQueryParameter("folder", "0")
                .appendQueryParameter("showempty", "0")
                .build().toString()
            val json = getJson(url)
            val errno = json.optInt("errno", 0)
            if (errno in setOf(-6, 110, 111) && !authRetried) {
                refreshToken(force = true)
                authRetried = true
                continue
            }
            check(errno == 0) { "百度网盘目录读取失败（$errno）" }
            val page = json.optJSONArray("list") ?: JSONArray()
            for (i in 0 until page.length()) result += parseEntry(page.getJSONObject(i))
            start += page.length()
        } while (start > 0 && start % PAGE_SIZE == 0)
        result
    }

    suspend fun downloadToGallery(
        entry: BaiduCloudEntry,
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> }
    ): Uri = withContext(Dispatchers.IO) {
        require(!entry.isDirectory) { "文件夹不能直接下载" }
        val (dlink, token) = authorizedDownloadLink(entry.fsId)
        val collection = if (entry.isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, entry.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType(entry.name, entry.isVideo))
            put(MediaStore.MediaColumns.RELATIVE_PATH, TARGET_RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = appContext.contentResolver.insert(collection, values)
            ?: error("无法创建本地媒体文件")
        try {
            val connection = openDownloadConnection(appendAccessToken(dlink, token))
            try {
                check(connection.responseCode in 200..299) { "百度下载失败（HTTP ${connection.responseCode}）" }
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: entry.size
                appContext.contentResolver.openOutputStream(target, "w")!!.buffered(TRANSFER_BUFFER).use { output ->
                    connection.inputStream.buffered(TRANSFER_BUFFER).use { input ->
                        val buffer = ByteArray(TRANSFER_BUFFER)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            onProgress(copied, total)
                        }
                        output.flush()
                        if (entry.size > 0) check(copied == entry.size) { "下载文件大小校验失败" }
                    }
                }
            } finally {
                connection.disconnect()
            }
            appContext.contentResolver.update(
                target,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
            target
        } catch (error: Throwable) {
            appContext.contentResolver.delete(target, null, null)
            throw error
        }
    }

    private suspend fun resolveDownloadLink(fsId: Long, accessToken: String): String {
        val url = Uri.parse(MULTIMEDIA_API).buildUpon()
            .appendQueryParameter("method", "filemetas")
            .appendQueryParameter("access_token", accessToken)
            .appendQueryParameter("fsids", JSONArray().put(fsId).toString())
            .appendQueryParameter("dlink", "1")
            .build().toString()
        val json = getJson(url)
        val errno = json.optInt("errno", 0)
        if (errno != 0) throw BaiduApiException(errno, "无法获取百度下载地址（$errno）")
        val info = json.optJSONArray("list")?.optJSONObject(0)
        return info?.optString("dlink").orEmpty().ifEmpty { error("百度未返回下载地址") }
    }

    private suspend fun authorizedDownloadLink(fsId: Long): Pair<String, String> {
        var token = validToken().accessToken
        val first = runCatching { resolveDownloadLink(fsId, token) }
        val error = first.exceptionOrNull()
        if (error !is BaiduApiException || error.errno !in setOf(-6, 110, 111)) {
            return first.getOrThrow() to token
        }
        token = refreshToken(force = true).accessToken
        return resolveDownloadLink(fsId, token) to token
    }

    private suspend fun validToken(): BaiduToken {
        val current = tokenStore.read() ?: error("请先登录百度网盘")
        return if (current.expiresAtMillis - System.currentTimeMillis() > TOKEN_SAFETY_WINDOW_MS) {
            current
        } else {
            refreshToken()
        }
    }

    private suspend fun refreshToken(force: Boolean = false): BaiduToken = refreshMutex.withLock {
        val latest = tokenStore.read() ?: error("百度网盘登录已失效")
        if (!force && latest.expiresAtMillis - System.currentTimeMillis() > TOKEN_SAFETY_WINDOW_MS) {
            return@withLock latest
        }
        val refreshed = authBackend.refresh(latest.refreshToken)
        tokenStore.write(refreshed)
        refreshed
    }

    private fun getJson(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", BAIDU_USER_AGENT)
        }
        return try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (body.isEmpty()) error("百度服务未返回数据")
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun openDownloadConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", BAIDU_USER_AGENT)
        }

    private fun parseEntry(json: JSONObject): BaiduCloudEntry {
        val thumbs = json.optJSONObject("thumbs")
        return BaiduCloudEntry(
            fsId = json.getLong("fs_id"),
            name = json.optString("server_filename"),
            path = json.optString("path"),
            isDirectory = json.optInt("isdir") == 1,
            size = json.optLong("size"),
            modifiedAtMillis = json.optLong("server_mtime") * 1000L,
            category = json.optInt("category", 6),
            thumbnailUrl = thumbs?.optString("url3").orEmpty()
        )
    }

    private fun appendAccessToken(url: String, token: String): String =
        Uri.parse(url).buildUpon().appendQueryParameter("access_token", token).build().toString()

    private fun mimeType(name: String, video: Boolean): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            else -> if (video) "video/*" else "image/*"
        }
    }

    private companion object {
        const val FILE_API = "https://pan.baidu.com/rest/2.0/xpan/file"
        const val MULTIMEDIA_API = "https://pan.baidu.com/rest/2.0/xpan/multimedia"
        const val BAIDU_USER_AGENT = "pan.baidu.com"
        val TARGET_RELATIVE_PATH = "${Environment.DIRECTORY_DCIM}/RCGallery/BaiduNetdisk/"
        const val PAGE_SIZE = 1000
        const val TRANSFER_BUFFER = 1024 * 1024
        const val TOKEN_SAFETY_WINDOW_MS = 60_000L
    }

    private class BaiduApiException(val errno: Int, message: String) : IllegalStateException(message)
}
