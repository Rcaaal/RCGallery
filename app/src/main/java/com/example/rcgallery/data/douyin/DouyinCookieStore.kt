package com.example.rcgallery.data.douyin

import android.annotation.SuppressLint
import android.util.Base64
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import com.example.rcgallery.util.AppLogger
import org.json.JSONObject
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

    /** Base cookies improve request compatibility but do not prove that the user is signed in. */
    private val baseCookies = setOf("s_v_web_id", "ttwid")
    private val sessionCookies = setOf("sid_tt", "sessionid", "sessionid_ss", "sid_guard")
    private val loginMarkers = setOf("passport_auth_status", "passport_assist_user")

    /**
     * Try to extract Douyin cookies from CookieManager.
     * These may be from a previous WebView session (persistent across app runs).
     */
    fun getAuthenticatedCookies(): String? {
        val cookies = currentCookies()
        val hasBase = baseCookies.all { required -> cookies.any { it.startsWith("$required=") } }
        val hasSession = sessionCookies.any { required -> cookies.any { it.startsWith("$required=") } }
        val hasLoginMarker = cookies.any { cookie ->
            val name = cookie.substringBefore('=').trim()
            name in loginMarkers && cookie.substringAfter('=', "").trim() !in setOf("", "0", "false")
        }
        val names = cookies.map { it.substringBefore('=').trim() }.sorted()
        AppLogger.d(LOG_TAG, "auth check session=$hasSession marker=$hasLoginMarker base=$hasBase cookies=$names")

        // Douyin does not consistently issue both base cookies after WebView login.
        // Some current WebView login flows expose an authenticated passport marker before
        // the session cookie is mirrored to the public web domain.
        return cookies.takeIf { hasSession || hasLoginMarker }?.joinToString("; ")
    }

    fun isLoggedIn(): Boolean = getAuthenticatedCookies() != null

    /**
     * Runs the detail request inside an authenticated WebView. Douyin's page scripts attach
     * the current a_bogus and browser fingerprint that native HTTP clients cannot reproduce.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchDetailViaWebView(workId: String): String? = suspendCancellableCoroutine { continuation ->
        Handler(Looper.getMainLooper()).post {
            val webView = WebView(APP_CONTEXT)
            val handler = Handler(Looper.getMainLooper())
            var completed = false
            var detailRequested = false
            lateinit var timeout: Runnable
            fun complete(value: String?, reason: String) {
                if (completed) return
                completed = true
                handler.removeCallbacks(timeout)
                finishWebDetail(webView, continuation, value, reason)
            }
            timeout = Runnable {
                complete(null, "timeout")
            }
            fun handlePayload(payloadText: String) {
                if (completed) return
                val payload = runCatching { JSONObject(payloadText) }.getOrNull()
                val status = payload?.optInt("status", -1) ?: -1
                val encoded = payload?.optString("body").orEmpty()
                val jsError = payload?.optString("error").orEmpty()
                val signer = payload?.optString("signer").orEmpty()
                val signed = payload?.optBoolean("signed", false) ?: false
                val signError = payload?.optString("signError").orEmpty()
                val json = runCatching {
                    String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                }.getOrNull()
                if (status !in 200..299 || json.isNullOrBlank()) {
                    val details = buildString {
                        append("http=$status signer=$signer signed=$signed")
                        if (jsError.isNotBlank()) append(" js=$jsError")
                        if (signError.isNotBlank()) append(" signError=$signError")
                    }
                    complete(null, details)
                } else {
                    complete(json, "http=$status signer=$signer signed=$signed")
                }
            }
            fun requestDetail() {
                if (detailRequested) return
                detailRequested = true
                val script = """
                    (async function() {
                        const cookieValue = (name) => {
                            const match = document.cookie.match(new RegExp('(?:^|;\\s*)' + name.replace(/[.*+?^${'$'}{}()|[\\]\\\\]/g, '\\$&') + '=([^;]*)'));
                            return match ? decodeURIComponent(match[1]) : '';
                        };
                        const randomToken = () => {
                            const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
                            let value = '';
                            for (let i = 0; i < 96; i++) value += alphabet[Math.floor(Math.random() * alphabet.length)];
                            return value;
                        };
                        const encodeBody = (text) => {
                            const bytes = new TextEncoder().encode(text);
                            let binary = '';
                            for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
                            return btoa(binary);
                        };
                        try {
                            const params = new URLSearchParams({
                                aweme_id: '$workId',
                                aid: '6383',
                                device_platform: 'webapp',
                                channel: 'channel_pc_web',
                                pc_client_type: '1',
                                version_code: '290100',
                                version_name: '29.1.0',
                                cookie_enabled: 'true',
                            });
                            const verifyFp = cookieValue('s_v_web_id');
                            if (verifyFp) {
                                params.set('verifyFp', verifyFp);
                                params.set('fp', verifyFp);
                            }
                            params.set('msToken', cookieValue('msToken') || randomToken());

                            let signer = 'none';
                            let signError = '';
                            const basePath = '/aweme/v1/web/aweme/detail/?' + params.toString();
                            const applySignature = (signature) => {
                                if (!signature) return false;
                                if (typeof signature === 'string') {
                                    if (signature.includes('=')) {
                                        new URLSearchParams(signature.replace(/^\?/, '')).forEach((value, key) => params.set(key, value));
                                    } else {
                                        params.set('a_bogus', signature);
                                    }
                                    return true;
                                }
                                if (typeof signature === 'object') {
                                    const pairs = {
                                        a_bogus: signature.a_bogus || signature.aBogus,
                                        'X-Bogus': signature['X-Bogus'] || signature.x_bogus || signature.xBogus,
                                        _signature: signature._signature || signature.signature,
                                    };
                                    let applied = false;
                                    Object.entries(pairs).forEach(([key, value]) => {
                                        if (value) { params.set(key, String(value)); applied = true; }
                                    });
                                    return applied;
                                }
                                return false;
                            };
                            try {
                                const acrawler = window.byted_acrawler || {};
                                if (typeof acrawler.sign === 'function') {
                                    signer = 'sign';
                                    applySignature(acrawler.sign({ url: basePath }) || acrawler.sign(basePath));
                                } else if (typeof acrawler.frontierSign === 'function') {
                                    signer = 'frontierSign';
                                    applySignature(acrawler.frontierSign(basePath));
                                }
                            } catch (error) {
                                signError = String(error);
                            }
                            const requestUrl = '/aweme/v1/web/aweme/detail/?' + params.toString();
                            const response = await fetch(requestUrl, {
                                credentials: 'include',
                                headers: { 'Accept': 'application/json, text/plain, */*' }
                            });
                            const body = await response.text();
                            RCGalleryDetailBridge.deliver(JSON.stringify({
                                status: response.status,
                                body: encodeBody(body),
                                signer: signer,
                                signed: params.has('a_bogus') || params.has('X-Bogus') || params.has('_signature'),
                                signError: signError,
                            }));
                        } catch (error) {
                            RCGalleryDetailBridge.deliver(JSON.stringify({ error: String(error), cookie: document.cookie.includes('sessionid') }));
                        }
                    })();
                """.trimIndent()
                webView.evaluateJavascript(script, null)
            }
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = true
                userAgentString = REQUEST_USER_AGENT
            }
            CookieManager.getInstance().setAcceptCookie(true)
            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun deliver(payload: String) {
                    handler.post { handlePayload(payload) }
                }
            }, "RCGalleryDetailBridge")
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (!completed && url.startsWith(DOUYIN_URL)) requestDetail()
                }
            }
            continuation.invokeOnCancellation {
                handler.post {
                    complete(null, "cancelled")
                }
            }
            handler.postDelayed(timeout, WEB_DETAIL_TIMEOUT_MS)
            webView.loadUrl(DOUYIN_URL)
        }
    }

    fun clearLogin() {
        val manager = CookieManager.getInstance()
        currentCookies().map { it.substringBefore('=').trim() }.forEach { name ->
            COOKIE_URLS.forEach { url ->
                manager.setCookie(url, "$name=; Max-Age=0; Path=/")
            }
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

    private fun currentCookies(): List<String> = COOKIE_URLS
        .flatMap { CookieManager.getInstance().getCookie(it).orEmpty().split(';') }
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy { it.substringBefore('=') }

    private fun finishWebDetail(
        webView: WebView,
        continuation: kotlinx.coroutines.CancellableContinuation<String?>,
        value: String?,
        reason: String,
    ) {
        AppLogger.d(LOG_TAG, "web detail finished $reason success=${value != null}")
        webView.stopLoading()
        webView.destroy()
        if (continuation.isActive) continuation.resume(value)
    }

    companion object {
        private const val DOUYIN_URL = "https://www.douyin.com/"
        private const val IES_DOUYIN_URL = "https://www.iesdouyin.com/"
        private val COOKIE_URLS = listOf(
            DOUYIN_URL,
            "https://m.douyin.com/",
            "https://passport.douyin.com/",
            IES_DOUYIN_URL,
        )
        const val REQUEST_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"
        private const val WEBVIEW_USER_AGENT = REQUEST_USER_AGENT
        private const val LOG_TAG = "DouyinLogin"
        private const val WEB_DETAIL_TIMEOUT_MS = 20_000L

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
