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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
    var input by remember(initialInput) { mutableStateOf(initialInput.orEmpty()) }
    var showBrowser by remember { mutableStateOf(false) }
    var pendingLink by remember { mutableStateOf<String?>(null) }

    fun openBrowser(link: String) {
        pendingLink = link
        showBrowser = true
    }

    fun parse() {
        input.trim().takeIf { it.isNotEmpty() }?.let(::openBrowser)
    }

    fun close() {
        viewModel.reset()
        onDismiss()
    }

    BackHandler(onBack = ::close)

    LaunchedEffect(initialInput) {
        initialInput?.trim()?.takeIf { it.isNotEmpty() }?.let {
            input = it
            openBrowser(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("X 导入") },
                navigationIcon = { TextButton(onClick = ::close) { Text("← 返回") } },
                actions = { TextButton(onClick = onOpenAlbum) { Text("相册") } },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .then(if (showBrowser) Modifier else Modifier.verticalScroll(rememberScrollState()))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("X / Twitter 帖子链接") },
                trailingIcon = {
                    if (input.isNotBlank()) {
                        TextButton(onClick = {
                            input = ""
                        }) { Text("清空") }
                    }
                },
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
                    enabled = state !is XImportState.Downloading,
                ) { Text("粘贴") }
                Button(
                    onClick = ::parse,
                    enabled = input.isNotBlank() && state !is XImportState.Downloading,
                    modifier = Modifier.weight(1f),
                ) { Text("打开网页解析") }
            }

            if (showBrowser) {
                XEmbeddedBrowser(
                    sourceUrl = pendingLink.orEmpty(),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onDownload = { sourceUrl, url, userAgent, disposition, mimeType, cookie, referer ->
                        viewModel.download(
                            sourceUrl = sourceUrl,
                            downloadUrl = url,
                            userAgent = userAgent,
                            cookie = cookie,
                            referer = referer,
                            contentDisposition = disposition,
                            advertisedMime = mimeType,
                            onSaved = onMediaSaved,
                        )
                    },
                )
            }

            if (!showBrowser) when (val current = state) {
                XImportState.Idle -> Text(
                    "将使用网页解析并接管下载保存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is XImportState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val percent = current.progress.takeIf { it >= 0f }?.let { " ${(it * 100).toInt()}%" }.orEmpty()
                    Text("正在保存$percent")
                }
                is XImportState.Success -> Text("已保存到相册：${current.displayName}", color = MaterialTheme.colorScheme.primary)
                is XImportState.Error -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(current.message, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = ::parse, enabled = input.isNotBlank()) {
                        Text("重新打开网页")
                    }
                }
            }

        }
    }
}

private class XBrowserRequest {
    var sourceUrl: String = ""
    var loadedSourceUrl: String? = null
    var submittedSourceUrl: String? = null
}

@Composable
private fun XEmbeddedBrowser(
    sourceUrl: String,
    modifier: Modifier,
    onDownload: (
        sourceUrl: String,
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?,
        cookie: String?,
        referer: String?,
    ) -> Unit,
) {
    var browser by remember { mutableStateOf<WebView?>(null) }
    val request = remember { XBrowserRequest() }

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

    AndroidView(
        modifier = modifier,
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
                        val link = request.sourceUrl
                        if (link.isBlank() || request.submittedSourceUrl == link ||
                            !url.contains("x2twitter.com", ignoreCase = true)
                        ) return
                        request.submittedSourceUrl = link
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
                    onDownload(
                        request.sourceUrl,
                        url,
                        agent.orEmpty(),
                        disposition,
                        mimeType,
                        CookieManager.getInstance().getCookie(url),
                        this.url,
                    )
                })
                browser = this
            }
        },
        update = { view ->
            browser = view
            request.sourceUrl = sourceUrl
            if (request.loadedSourceUrl != sourceUrl) {
                request.loadedSourceUrl = sourceUrl
                request.submittedSourceUrl = null
                view.loadUrl(X2TWITTER_URL)
            }
        },
    )
}

private const val DEFAULT_WEB_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36"
