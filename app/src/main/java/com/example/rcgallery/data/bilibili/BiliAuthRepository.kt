package com.example.rcgallery.data.bilibili

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class BiliAuthRepository {
    suspend fun generateQr(): BiliQrSession = withContext(Dispatchers.IO) {
        val response = requestJson(QR_GENERATE_URL)
        val data = requireData(response.json)
        BiliQrSession(
            qrUrl = data.optString("url").takeIf { it.isNotBlank() }
                ?: throw BiliParseException("登录接口没有返回二维码地址"),
            qrKey = data.optString("qrcode_key").takeIf { it.isNotBlank() }
                ?: throw BiliParseException("登录接口没有返回二维码密钥"),
        )
    }

    suspend fun pollQr(qrKey: String): BiliQrPollResult = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(qrKey, Charsets.UTF_8.name())
        val response = requestJson("$QR_POLL_URL?qrcode_key=$encoded")
        val data = requireData(response.json)
        when (data.optInt("code")) {
            86101 -> BiliQrPollResult.Waiting
            86090 -> BiliQrPollResult.Scanned
            86038 -> BiliQrPollResult.Expired
            0 -> {
                val cookies = response.cookies
                if (cookies.none { it.startsWith("SESSDATA=", true) }) {
                    throw BiliParseException("登录成功，但响应中没有 SESSDATA")
                }
                BiliQrPollResult.Success(cookies.joinToString("; "))
            }
            else -> throw BiliParseException(data.optString("message").ifBlank { "二维码登录失败" })
        }
    }

    suspend fun accountInfo(cookieHeader: String): BiliAccountInfo = withContext(Dispatchers.IO) {
        val response = requestJson(NAV_URL, cookieHeader)
        val data = requireData(response.json)
        if (!data.optBoolean("isLogin")) throw BiliParseException("登录状态已失效")
        val vipStatus = data.optJSONObject("vipStatus")
        val vip = data.optJSONObject("vip")
        val vipActive = vip?.optInt("status", vipStatus?.optInt("status", 0) ?: 0) == 1
        val vipType = vip?.optInt("type", data.optInt("vipType", 0)) ?: data.optInt("vipType", 0)
        BiliAccountInfo(
            userName = data.optString("uname").ifBlank { "已登录用户" },
            vipLabel = when {
                vipActive && vipType == 2 -> "年度大会员"
                vipActive -> "大会员"
                else -> "普通会员"
            },
        )
    }

    private fun requestJson(url: String, cookieHeader: String? = null): JsonResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", BiliParser.USER_AGENT)
            setRequestProperty("Referer", "https://www.bilibili.com/")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            cookieHeader?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw BiliParseException("登录接口请求失败（HTTP $code）")
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val cookies = connection.headerFields.entries
                .filter { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
                .flatMap { it.value.orEmpty() }
                .mapNotNull { it.substringBefore(';').trim().takeIf(String::isNotBlank) }
                .distinctBy { it.substringBefore('=').lowercase() }
            JsonResponse(JSONObject(body), cookies)
        } finally {
            connection.disconnect()
        }
    }

    private fun requireData(root: JSONObject): JSONObject {
        val code = root.optInt("code", Int.MIN_VALUE)
        if (code != 0) {
            throw BiliParseException(root.optString("message").ifBlank { "登录接口返回错误 $code" })
        }
        return root.optJSONObject("data") ?: throw BiliParseException("登录接口没有返回数据")
    }

    private data class JsonResponse(val json: JSONObject, val cookies: List<String>)

    private companion object {
        const val QR_GENERATE_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate"
        const val QR_POLL_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll"
        const val NAV_URL = "https://api.bilibili.com/x/web-interface/nav"
    }
}
