package com.example.rcgallery.ui.screen

import android.content.ClipboardManager
import android.content.Context
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.rcgallery.viewmodel.XImportState
import com.example.rcgallery.viewmodel.XImportViewModel
import org.json.JSONObject

private const val X2TWITTER_URL = "https://x2twitter.com/en4"
private const val X_LOGIN_URL = "https://x.com/i/flow/login"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XImportScreen(
    onDismiss: () -> Unit,
    onMediaSaved: () -> Unit,
    onOpenAlbum: () -> Unit,
    initialInput: String? = null,
    viewModel: XImportViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hasLogin by viewModel.hasLogin.collectAsStateWithLifecycle()
    var input by remember(initialInput) { mutableStateOf(initialInput.orEmpty()) }
    var pendingInput by remember { mutableStateOf<String?>(null) }
    var showBrowser by remember { mutableStateOf(false) }
    var browserMode by remember { mutableStateOf("fallback") }
    var loginCaptureFailed by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    fun parse() {
        val link = input.trim()
        if (link.isBlank()) return
        showBrowser = false
        viewModel.parse(link)
    }

    fun openWebFallback() {
        val link = input.trim()
        if (link.isBlank()) return
        pendingInput = link
        browserMode = "fallback"
        showBrowser = true
    }

    fun openXLogin() {
        pendingInput = null
        browserMode = "login"
        loginCaptureFailed = false
        showBrowser = true
    }

    fun close() {
        onDismiss()
    }

    BackHandler(onBack = ::close)

    LaunchedEffect(initialInput) {
        initialInput?.takeIf { it.isNotBlank() }?.let {
            input = it
            showBrowser = false
            viewModel.parse(it)
        }
    }

    if (showBrowser) {
        XBrowserPage(
            loginMode = browserMode == "login",
            sourceUrl = pendingInput ?: input.trim(),
            onBack = {
                showBrowser = false
                pendingInput = null
            },
            onCaptureLogin = {
                val captured = viewModel.captureLoginCookies()
                if (captured) {
                    loginCaptureFailed = false
                    showBrowser = false
                } else {
                    loginCaptureFailed = true
                }
                captured
            },
            onDownload = { url, agent, disposition, mimeType, cookie ->
                viewModel.download(
                    sourceUrl = input.trim(),
                    downloadUrl = url,
                    userAgent = agent,
                    cookie = cookie,
                    contentDisposition = disposition,
                    advertisedMime = mimeType,
                    onSaved = onMediaSaved,
                )
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("X 导入") },
                navigationIcon = { TextButton(onClick = ::close) { Text("← 返回") } },
                actions = { TextButton(onClick = onOpenAlbum) { Text("相册") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("X / Twitter 帖子链接") },
                minLines = 2,
                maxLines = 3,
                enabled = state !is XImportState.Downloading,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        input = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("粘贴") }
                Button(
                    onClick = ::parse,
                    enabled = input.isNotBlank() && state !is XImportState.Downloading,
                    modifier = Modifier.weight(1f),
                ) { Text("开始解析") }
            }

            if (hasLogin) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("X 已登录", color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = viewModel::clearLogin) { Text("清除登录") }
                }
            } else {
                OutlinedButton(onClick = ::openXLogin, modifier = Modifier.fillMaxWidth()) {
                    Text("登录 X（用于受限帖子本地解析）")
                }
            }
            if (false && showBrowser && browserMode == "login") {
                Button(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                    Text("完成 X 登录并保存 Cookie")
                }
            }
            if (loginCaptureFailed) {
                Text("尚未检测到 X 登录 Cookie，请完成登录后再点击完成登录", color = MaterialTheme.colorScheme.error)
            }

            when (val current = state) {
                XImportState.Idle -> Text(
                    "解析由 X2Twitter 提供。请选择网页生成的下载项，应用会接管保存、进度与历史记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                XImportState.Parsing -> Row {
                    CircularProgressIndicator(Modifier.height(22.dp))
                    Spacer(Modifier.padding(6.dp))
                    Text("正在本地解析 X 帖子")
                }
                is XImportState.Ready -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(current.work.title, style = MaterialTheme.typography.titleMedium)
                    current.work.author.takeIf { it.isNotBlank() }?.let { Text("作者：$it") }
                    Text("已找到 ${current.work.media.size} 个媒体资源")
                    Button(
                        onClick = { viewModel.downloadParsed(current.work, onMediaSaved) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("下载全部 ${current.work.media.size} 项") }
                }
                is XImportState.Downloading -> Row {
                    CircularProgressIndicator(Modifier.height(22.dp))
                    Spacer(Modifier.padding(6.dp))
                    val percent = if (current.progress >= 0f) " ${(current.progress * 100).toInt()}%" else ""
                    Text("正在保存$percent")
                }
                is XImportState.Success -> Text("已保存：${current.displayName}", color = MaterialTheme.colorScheme.primary)
                is XImportState.Error -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(current.message, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = ::openWebFallback, modifier = Modifier.fillMaxWidth()) {
                        Text("使用网页解析兜底")
                    }
                }
            }

            if (false && showBrowser) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { androidContext ->
                        WebView(androidContext).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = true
                            settings.userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36"
                            CookieManager.getInstance().setAcceptCookie(true)
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String) {
                                    super.onPageFinished(view, url)
                                    val link = pendingInput ?: return
                                    if (browserMode != "fallback" || !url.contains("x2twitter.com", ignoreCase = true)) return
                                    pendingInput = null
                                    val quoted = JSONObject.quote(link)
                                    view.evaluateJavascript(
                                        """
                                        (function() {
                                          const input = document.querySelector('input[type="url"], input[type="text"]');
                                          if (!input) return 'input-missing';
                                          const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
                                          setter.call(input, $quoted);
                                          input.dispatchEvent(new Event('input', {bubbles: true}));
                                          input.dispatchEvent(new Event('change', {bubbles: true}));
                                          const form = input.form;
                                          const button = Array.from(document.querySelectorAll('button, input[type="submit"]'))
                                            .find(item => /download|submit|get/i.test(item.innerText || item.value || ''));
                                          setTimeout(function() {
                                            if (button) button.click();
                                            else if (form) form.requestSubmit ? form.requestSubmit() : form.submit();
                                          }, 250);
                                          return 'submitted';
                                        })();
                                        """.trimIndent(),
                                        null,
                                    )
                                }
                            }
                            setDownloadListener(DownloadListener { url, agent, disposition, mimeType, _ ->
                                viewModel.download(
                                    sourceUrl = input.trim(),
                                    downloadUrl = url,
                                    userAgent = agent.orEmpty(),
                                    cookie = CookieManager.getInstance().getCookie(url),
                                    contentDisposition = disposition,
                                    advertisedMime = mimeType,
                                    onSaved = onMediaSaved,
                                )
                            })
                            webView = this
                            loadUrl(if (browserMode == "login") X_LOGIN_URL else X2TWITTER_URL)
                        }
                    },
                    update = { webView = it },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun XBrowserPage(
    loginMode: Boolean,
    sourceUrl: String,
    onBack: () -> Unit,
    onCaptureLogin: () -> Boolean,
    onDownload: (url: String, userAgent: String, contentDisposition: String?, mimeType: String?, cookie: String?) -> Unit,
) {
    var browser by remember { mutableStateOf<WebView?>(null) }
    var submitted by remember(sourceUrl) { mutableStateOf(false) }
    var loginError by remember { mutableStateOf(false) }

    BackHandler {
        val current = browser
        if (current?.canGoBack() == true) current.goBack() else onBack()
    }

    DisposableEffect(Unit) {
        onDispose {
            browser?.apply {
                stopLoading()
                onPause()
                pauseTimers()
                destroy()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (loginMode) "登录 X" else "X 网页解析") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            if (loginMode) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (loginError) {
                        Text(
                            "尚未检测到完整登录信息。请完成登录并进入 X 首页后再保存。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        onClick = {
                            if (onCaptureLogin()) onBack() else loginError = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("保存 X 登录 Cookie") }
                }
            }
        },
    ) { padding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { androidContext ->
                WebView(androidContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = true
                    settings.userAgentString = DEFAULT_WEB_USER_AGENT
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            super.onPageFinished(view, url)
                            if (loginMode || submitted || !url.contains("x2twitter.com", ignoreCase = true)) return
                            submitted = true
                            val quoted = JSONObject.quote(sourceUrl)
                            view.evaluateJavascript(
                                """
                                (function() {
                                  const input = document.querySelector('input[type="url"], input[type="text"]');
                                  if (!input) return 'input-missing';
                                  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
                                  setter.call(input, $quoted);
                                  input.dispatchEvent(new Event('input', {bubbles: true}));
                                  input.dispatchEvent(new Event('change', {bubbles: true}));
                                  const form = input.form;
                                  const button = Array.from(document.querySelectorAll('button, input[type="submit"]'))
                                    .find(item => /download|submit|get/i.test(item.innerText || item.value || ''));
                                  setTimeout(function() {
                                    if (button) button.click();
                                    else if (form) form.requestSubmit ? form.requestSubmit() : form.submit();
                                  }, 250);
                                  return 'submitted';
                                })();
                                """.trimIndent(),
                                null,
                            )
                        }
                    }
                    setDownloadListener(DownloadListener { url, agent, disposition, mimeType, _ ->
                        onDownload(
                            url,
                            agent.orEmpty(),
                            disposition,
                            mimeType,
                            CookieManager.getInstance().getCookie(url),
                        )
                    })
                    browser = this
                    loadUrl(if (loginMode) X_LOGIN_URL else X2TWITTER_URL)
                }
            },
            update = { browser = it },
        )
    }
}

private const val DEFAULT_WEB_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36"
