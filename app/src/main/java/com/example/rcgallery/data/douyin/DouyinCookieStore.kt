package com.example.rcgallery.data.douyin

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Manages Douyin cookie extraction via WebView.
 *
 * Loads douyin.com in a headless WebView to trigger the anti-bot
 * challenge that sets the `s_v_web_id` cookie required by the API.
 * Cookies are extracted via Android's CookieManager after the page loads.
 *
 * Cookies persist across calls until the device reboots or the user clears
 * WebView data (default CookieManager persistence).
 */
class DouyinCookieStore {

    /** Minimum cookies we consider the session valid. */
    private val baseCookies = setOf("s_v_web_id", "ttwid")
    private val sessionCookies = setOf("sid_tt", "sessionid", "sessionid_ss", "sid_guard")

    /**
     * Try to extract Douyin cookies from CookieManager.
     * These may be from a previous WebView session (persistent across app runs).
     */
    fun getAuthenticatedCookies(): String? {
        val cookies = currentCookies()
        val hasBase = baseCookies.all { required -> cookies.any { it.startsWith("$required=") } }
        val hasSession = sessionCookies.any { required -> cookies.any { it.startsWith("$required=") } }
        return cookies.takeIf { hasBase && hasSession }?.joinToString("; ")
    }

    fun isLoggedIn(): Boolean = getAuthenticatedCookies() != null

    fun clearLogin() {
        val manager = CookieManager.getInstance()
        currentCookies().map { it.substringBefore('=').trim() }.forEach { name ->
            manager.setCookie(DOUYIN_URL, "$name=; Max-Age=0; Path=/")
            manager.setCookie(IES_DOUYIN_URL, "$name=; Max-Age=0; Path=/")
        }
        manager.flush()
    }

    /**
     * Load douyin.com in a WebView and extract cookies after page loads.
     * Must be called from the main thread (WebView requirement).
     *
     * @param onResult callback with cookie string on success, null on failure
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun extractCookies(onResult: (String?) -> Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            try {
                val webView = WebView(APP_CONTEXT)
                webView.settings.apply {
                    javaScriptEnabled = true
                    userAgentString = WEBVIEW_USER_AGENT
                    domStorageEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    builtInZoomControls = false
                    displayZoomControls = false
                    cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (url == null || !url.startsWith("https://www.douyin.com")) return
                        val result = currentCookies().joinToString("; ")
                        // Keep WebView alive in case we need it again; just detach
                        webView.destroy()
                        onResult(result.ifBlank { null })
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?,
                    ) {
                        // Try to use whatever cookies we have even on error
                        webView?.destroy()
                        onResult(currentCookies().joinToString("; ").ifBlank { null })
                    }
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().flush()
                webView.loadUrl(DOUYIN_URL)
            } catch (_: Exception) {
                onResult(null)
            }
        }
    }

    /**
     * Suspend version of extractCookies.
     * Runs WebView loading on the main thread and suspends until cookies arrive.
     */
    suspend fun extractCookiesSuspend(): String? = suspendCancellableCoroutine { continuation ->
        extractCookies { cookies ->
            if (continuation.isActive) {
                continuation.resume(cookies)
            }
        }
    }

    private fun currentCookies(): List<String> = listOf(DOUYIN_URL, IES_DOUYIN_URL)
        .flatMap { CookieManager.getInstance().getCookie(it).orEmpty().split(';') }
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy { it.substringBefore('=') }

    companion object {
        private const val DOUYIN_URL = "https://www.douyin.com/"
        private const val IES_DOUYIN_URL = "https://www.iesdouyin.com/"
        const val REQUEST_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"
        private const val WEBVIEW_USER_AGENT = REQUEST_USER_AGENT

        /**
         * Application context set once (e.g. from Application.onCreate).
         * Must be set before any WebView operations.
         */
        lateinit var APP_CONTEXT: android.content.Context
            private set

        fun initialize(context: android.content.Context) {
            APP_CONTEXT = context.applicationContext
        }
    }
}
