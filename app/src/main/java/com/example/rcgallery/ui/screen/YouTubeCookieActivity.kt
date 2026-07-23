package com.example.rcgallery.ui.screen

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.rcgallery.data.youtube.YouTubeCookieExtractor
import java.io.File

/**
 * WebView-based YouTube cookie login.
 */
class YouTubeCookieActivity : Activity() {

    companion object {
        const val EXTRA_COOKIE_PATH = "cookie_path"
        const val EXTRA_VISITOR_DATA = "visitor_data"
    }

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val d = resources.displayMetrics.density
        val barH = (48 * d).toInt()
        val btnW = (48 * d).toInt()

        // LinearLayout: 上 = 控制栏, 下 = WebView（无重叠）
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.WHITE)
            fitsSystemWindows = true
        }

        // ── 顶部控制栏 ──
        val bar = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barH
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#FF212121"))
        }

        // 关闭按钮
        bar.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(btnW, btnW)
            setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { finish() }
        })

        // 标题
        bar.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER
            text = "登录后点击 ✓ 提取"
            setTextColor(Color.WHITE)
            textSize = 15f
        })

        // 确认按钮
        bar.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(btnW, btnW)
            setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
            setImageResource(android.R.drawable.ic_menu_save)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { onExtract() }
        })
        root.addView(bar)

        // ── WebView ──
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    (bar.getChildAt(1) as? TextView)?.text = view?.title ?: "YouTube"
                }
            }
            webChromeClient = WebChromeClient()
            loadUrl("https://www.youtube.com")
        }
        root.addView(webView)

        setContentView(root)
    }

    private fun onExtract() {
        // Keep the exported file outside cacheDir so it survives cache cleanup.
        val target = File(filesDir, "youtube_cookies.txt")
        val ok = YouTubeCookieExtractor.extractAndSave(this, target) ||
            YouTubeCookieExtractor.saveFallbackCookies(target)

        if (ok && target.exists() && target.length() > 0L) {
            Toast.makeText(this, "Cookie 已提取", Toast.LENGTH_SHORT).show()
            webView.evaluateJavascript("ytcfg.get('VISITOR_DATA')") { raw ->
                val visitor = runCatching {
                    org.json.JSONTokener(raw ?: "").nextValue() as? String ?: ""
                }.getOrDefault("")
                setResult(RESULT_OK, Intent().apply {
                    putExtra(EXTRA_COOKIE_PATH, target.absolutePath)
                    putExtra(EXTRA_VISITOR_DATA, visitor)
                })
                finish()
            }
        } else {
            Toast.makeText(this, "未检测到登录 Cookie，请先在 YouTube 登录", Toast.LENGTH_LONG).show()
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
