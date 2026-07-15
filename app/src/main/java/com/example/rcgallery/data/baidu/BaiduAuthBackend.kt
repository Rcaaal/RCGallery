package com.example.rcgallery.data.baidu

import android.net.Uri
import com.example.rcgallery.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class BaiduAuthBackend {
    val isConfigured: Boolean get() = baseUrl.isNotEmpty()

    fun authorizationUrl(state: String): String {
        check(isConfigured) { "尚未配置百度授权后端" }
        return "$baseUrl/oauth/baidu/start?state=${Uri.encode(state)}" +
            "&app_callback=${Uri.encode(APP_CALLBACK)}"
    }

    suspend fun exchange(oneTimeCode: String, state: String): BaiduToken = postToken(
        path = "/oauth/baidu/exchange",
        body = JSONObject().put("code", oneTimeCode).put("state", state)
    )

    suspend fun refresh(refreshToken: String): BaiduToken = postToken(
        path = "/oauth/baidu/refresh",
        body = JSONObject().put("refresh_token", refreshToken)
    )

    private suspend fun postToken(path: String, body: JSONObject): BaiduToken =
        withContext(Dispatchers.IO) {
            check(isConfigured) { "尚未配置百度授权后端" }
            val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            try {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val stream = if (connection.responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (connection.responseCode !in 200..299) {
                    error(JSONObject(response.ifEmpty { "{}" }).optString("message", "授权服务错误 ${connection.responseCode}"))
                }
                parseToken(JSONObject(response))
            } finally {
                connection.disconnect()
            }
        }

    private fun parseToken(json: JSONObject): BaiduToken {
        val accessToken = json.optString("access_token")
        val refreshToken = json.optString("refresh_token")
        require(accessToken.isNotEmpty() && refreshToken.isNotEmpty()) { "授权服务返回的令牌不完整" }
        val expiresIn = json.optLong("expires_in", 2_592_000L).coerceAtLeast(60L)
        return BaiduToken(accessToken, refreshToken, System.currentTimeMillis() + expiresIn * 1000L)
    }

    private val baseUrl = BuildConfig.BAIDU_AUTH_BACKEND_URL.trim().trimEnd('/')

    companion object {
        const val APP_CALLBACK = "rcgallery://baidu/oauth"
    }
}
