package com.example.rcgallery.data.youtube

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** ytdlnis BotGuard WebView generator, kept isolated from the UI. */
internal class YouTubePoTokenWebView private constructor(
    context: Context,
    private val ready: kotlin.coroutines.Continuation<YouTubePoTokenWebView>,
) {
    private val webView = WebView(context)
    private val continuations = mutableMapOf<String, kotlin.coroutines.Continuation<String>>()
    private var expiration = Instant.MIN

    init {
        webView.settings.javaScriptEnabled = true
        if (Build.VERSION.SDK_INT >= 26) webView.settings.safeBrowsingEnabled = false
        webView.settings.userAgentString = USER_AGENT
        webView.settings.blockNetworkLoads = true
        webView.addJavascriptInterface(this, JS_INTERFACE)
    }

    private fun start(context: Context) {
        android.os.Handler(Looper.getMainLooper()).post {
            runCatching {
                val html = context.assets.open("po_token.html").bufferedReader().use { it.readText() }
                webView.loadDataWithBaseURL(
                    "https://www.youtube.com", html.replaceFirst(
                        "</script>", "\n$JS_INTERFACE.downloadAndRunBotguard()</script>"
                    ), "text/html", "utf-8", null
                )
            }.onFailure { fail(it) }
        }
    }

    @JavascriptInterface
    fun downloadAndRunBotguard() {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = request("https://www.youtube.com/api/jnn/v1/Create", listOf(REQUEST_KEY))
                val challenge = YouTubeJavascriptUtil.parseChallengeData(body)
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript("""try { data = $challenge; runBotGuard(data).then(function (result) { this.webPoSignalOutput = result.webPoSignalOutput; $JS_INTERFACE.onRunBotguardResult(result.botguardResponse); }, function (error) { $JS_INTERFACE.onJsInitializationError(error + "\\n" + error.stack); }); } catch (error) { $JS_INTERFACE.onJsInitializationError(error + "\\n" + error.stack); }""") {}
                }
            } catch (error: Throwable) { fail(error) }
        }
    }

    @JavascriptInterface fun onJsInitializationError(error: String) = fail(Exception(error))

    @JavascriptInterface
    fun onRunBotguardResult(response: String) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = request("https://www.youtube.com/api/jnn/v1/GenerateIT", listOf(REQUEST_KEY, response))
                val (token, seconds) = YouTubeJavascriptUtil.parseIntegrityTokenData(body)
                expiration = Instant.now().plusSeconds((seconds - 600).coerceAtLeast(60))
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript("this.integrityToken = $token") {
                        ready.resume(this@YouTubePoTokenWebView)
                    }
                }
            } catch (error: Throwable) { fail(error) }
        }
    }

    suspend fun generate(identifier: String): String = suspendCancellableCoroutine { cont ->
        continuations[identifier] = cont
        Handler(Looper.getMainLooper()).post {
            webView.evaluateJavascript("""try { identifier = ${org.json.JSONObject.quote(identifier)}; u8Identifier = ${YouTubeJavascriptUtil.stringToU8(identifier)}; poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, u8Identifier); poTokenU8String = poTokenU8.join(); $JS_INTERFACE.onObtainPoTokenResult(identifier, poTokenU8String); } catch (error) { $JS_INTERFACE.onObtainPoTokenError(identifier, error + "\\n" + error.stack); }""") {}
        }
        cont.invokeOnCancellation { continuations.remove(identifier) }
    }

    @JavascriptInterface fun onObtainPoTokenError(id: String, error: String) {
        continuations.remove(id)?.resumeWithException(Exception(error))
    }

    @JavascriptInterface fun onObtainPoTokenResult(id: String, value: String) {
        runCatching { YouTubeJavascriptUtil.u8ToBase64(value) }
            .onSuccess { continuations.remove(id)?.resume(it) }
            .onFailure { continuations.remove(id)?.resumeWithException(it) }
    }

    fun isExpired(): Boolean = Instant.now().isAfter(expiration)

    fun close() {
        Handler(Looper.getMainLooper()).post {
            webView.loadUrl("about:blank")
            webView.onPause(); webView.removeAllViews(); webView.destroy()
        }
    }

    private fun fail(error: Throwable) {
        Log.e(TAG, "PO token initialization failed", error)
        close()
        Handler(Looper.getMainLooper()).post { ready.resumeWithException(error) }
    }

    private suspend fun request(endpoint: String, values: List<String>): String = withContext(Dispatchers.IO) {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "application/json+protobuf")
            conn.setRequestProperty("x-goog-api-key", GOOGLE_API_KEY)
            conn.setRequestProperty("x-user-agent", "grpc-web-javascript/0.1")
            conn.outputStream.use { it.write(JSONArray(values).toString().toByteArray()) }
            if (conn.responseCode !in 200..299) error("PO token HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }

    companion object {
        private const val TAG = "YouTubePoToken"
        private const val JS_INTERFACE = "PoTokenWebView"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"

        suspend fun create(context: Context): YouTubePoTokenWebView = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont -> YouTubePoTokenWebView(context, cont).start(context) }
        }
    }
}
