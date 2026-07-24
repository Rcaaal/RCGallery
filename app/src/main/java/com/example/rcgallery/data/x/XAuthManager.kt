package com.example.rcgallery.data.x

import android.content.Context
import android.webkit.CookieManager
import java.io.File

class XAuthManager(context: Context) {
    private val appContext = context.applicationContext
    private val cookieFile = File(appContext.filesDir, "x_cookies.txt")

    fun hasLogin(): Boolean {
        val cookies = readCookies()
        return !cookies["auth_token"].isNullOrBlank() && !cookies["ct0"].isNullOrBlank()
    }

    fun cookiePath(): String? = cookieFile.takeIf { hasLogin() && it.length() > 64L }?.absolutePath

    fun captureWebViewCookies(): Boolean {
        runCatching { CookieManager.getInstance().flush() }
        val raw = sequenceOf("https://x.com", "https://twitter.com", "https://mobile.x.com")
            .mapNotNull { url -> runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull() }
            .joinToString("; ")
        if (raw.isBlank()) return false

        val uniqueCookies = linkedMapOf<String, String>()
        raw.split(';').map { it.trim() }.forEach { pair ->
            val index = pair.indexOf('=')
            if (index > 0) uniqueCookies[pair.substring(0, index)] = pair.substring(index + 1)
        }
        // auth_token identifies the session and ct0 is required for X's authenticated requests.
        if (uniqueCookies["auth_token"].isNullOrBlank() || uniqueCookies["ct0"].isNullOrBlank()) {
            return false
        }

        cookieFile.parentFile?.mkdirs()
        cookieFile.bufferedWriter().use { writer ->
            writer.appendLine("# Netscape HTTP Cookie File")
            writer.appendLine("# Captured from RCGallery X login")
            uniqueCookies.forEach { (name, value) ->
                writer.appendLine(".x.com\tTRUE\t/\tTRUE\t0\t$name\t$value")
            }
        }
        return true
    }

    /** Cookie header for direct media requests after yt-dlp resolved the source URL. */
    fun cookieHeader(): String? = readCookies()
        .entries
        .takeIf { it.isNotEmpty() }
        ?.joinToString("; ") { (name, value) -> "$name=$value" }

    fun clear() {
        cookieFile.delete()
    }

    private fun readCookies(): LinkedHashMap<String, String> {
        if (!cookieFile.isFile) return linkedMapOf()
        return linkedMapOf<String, String>().apply {
            cookieFile.useLines { lines ->
                lines.filterNot { it.startsWith("#") }.forEach { line ->
                    val fields = line.split('\t')
                    if (fields.size >= 7) {
                        val name = fields[5]
                        val value = fields[6]
                        if (name.isNotBlank() && value.isNotBlank()) put(name, value)
                    }
                }
            }
        }
    }
}
