package com.example.rcgallery.data.youtube

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared ytdlnis-compatible authentication context for both parse and download. */
class YouTubeAuthManager(context: Context) {
    private val app = context.applicationContext
    private val prefs: SharedPreferences = app.getSharedPreferences("youtube_prefs", 0)
    private val mutex = Mutex()
    private var generator: YouTubePoTokenWebView? = null
    private var visitorData: String = prefs.getString(KEY_VISITOR_DATA, "").orEmpty()
    private var streamingToken: String = prefs.getString(KEY_STREAMING_TOKEN, "").orEmpty()

    fun setVisitorData(value: String) {
        visitorData = value.trim()
        prefs.edit().putString(KEY_VISITOR_DATA, visitorData).apply()
    }

    fun hasVisitorData(): Boolean = visitorData.isNotBlank()

    fun clear() {
        visitorData = ""
        streamingToken = ""
        prefs.edit().remove(KEY_VISITOR_DATA).remove(KEY_STREAMING_TOKEN).apply()
        generator?.close()
        generator = null
    }

    suspend fun extractorArgs(videoId: String): String? = mutex.withLock {
        if (visitorData.isBlank()) return@withLock null
        try {
            if (generator == null || generator!!.isExpired() || streamingToken.isBlank()) {
                generator?.close()
                generator = YouTubePoTokenWebView.create(app)
                streamingToken = generator!!.generate(visitorData)
                prefs.edit().putString(KEY_STREAMING_TOKEN, streamingToken).apply()
            }
            val playerToken = generator!!.generate(videoId)
            "player_skip=webpage,configs;player_client=web;visitor_data=$visitorData;" +
                "po_token=web.gvs+$streamingToken,web.player+$playerToken"
        } catch (error: Throwable) {
            generator?.close()
            generator = null
            throw YouTubeParseException("YouTube PO Token 生成失败：${error.message ?: "未知错误"}", error)
        }
    }

    companion object {
        private const val KEY_VISITOR_DATA = "youtube_visitor_data"
        private const val KEY_STREAMING_TOKEN = "youtube_streaming_po_token"
    }
}
