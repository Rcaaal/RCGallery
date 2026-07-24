package com.example.rcgallery.ui.screen

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.rcgallery.data.douyin.DouyinCookieStore
import com.example.rcgallery.util.AppLogger

class DouyinLoginActivity : Activity() {
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setOnApplyWindowInsetsListener { view, insets ->
                val systemBars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(0, systemBars.top, 0, systemBars.bottom)
                insets
            }
        }
        window.statusBarColor = Color.rgb(18, 18, 18)
        window.navigationBarColor = Color.rgb(18, 18, 18)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
            setBackgroundColor(Color.rgb(33, 33, 33))
        }
        bar.addView(Button(this).apply {
            text = "取消"
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = "登录抖音后点击完成"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(Button(this).apply {
            text = "完成"
            setOnClickListener { completeLogin() }
        })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * density).toInt()))
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.userAgentString = DouyinCookieStore.REQUEST_USER_AGENT
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {}
            loadUrl("https://www.douyin.com/")
        }
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onPause() {
        pauseWebMedia()
        webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    private fun pauseWebMedia() {
        webView.evaluateJavascript(
            "document.querySelectorAll('video,audio').forEach(function(media){media.pause();});",
            null
        )
    }

    private fun completeLogin() {
        CookieManager.getInstance().flush()
        val isLoggedIn = DouyinCookieStore().isLoggedIn()
        AppLogger.d(LOG_TAG, "complete requested loggedIn=$isLoggedIn")
        if (isLoggedIn) {
            setResult(RESULT_OK)
            finish()
        } else {
            Toast.makeText(this, "未检测到登录态，请完成抖音登录后再试", Toast.LENGTH_LONG).show()
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    private companion object {
        const val LOG_TAG = "DouyinLogin"
    }
}
