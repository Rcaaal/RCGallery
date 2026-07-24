package com.example.rcgallery.ui.screen

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.rcgallery.data.youtube.YouTubeCodecMode
import com.example.rcgallery.data.youtube.YouTubeDownloadHistory
import com.example.rcgallery.data.youtube.YouTubeImportState
import com.example.rcgallery.data.youtube.YouTubeWorkInfo
import com.example.rcgallery.viewmodel.YouTubeImportViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeImportScreen(
    onDismiss: () -> Unit,
    onMediaSaved: () -> Unit,
    onOpenAlbum: () -> Unit,
    initialInput: String? = null,
    viewModel: YouTubeImportViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hasCookie by viewModel.hasCookie.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var input by remember(initialInput) { mutableStateOf(initialInput.orEmpty()) }
    var selectedHeight by remember { mutableIntStateOf(0) }
    var codecMode by remember { mutableStateOf(YouTubeCodecMode.AUTO) }

    androidx.compose.runtime.LaunchedEffect(initialInput) {
        initialInput?.trim()?.takeIf { it.isNotEmpty() }?.let(viewModel::parse)
    }

    val cookieLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.getStringExtra(YouTubeCookieActivity.EXTRA_COOKIE_PATH)?.let { path ->
                viewModel.setCookieFile(path)
            }
            result.data?.getStringExtra(YouTubeCookieActivity.EXTRA_VISITOR_DATA)
                ?.takeIf { it.isNotBlank() }
                ?.let(viewModel::setVisitorData)
        }
    }

    val work = when (val s = state) {
        is YouTubeImportState.Ready -> s.work
        is YouTubeImportState.Downloading -> s.work
        is YouTubeImportState.Merging -> s.work
        else -> null
    }

    // auto-select first quality when work changes
    androidx.compose.runtime.LaunchedEffect(work?.id) {
        selectedHeight = work?.qualities?.firstOrNull()?.height ?: 0
    }

    fun close() { viewModel.reset(); onDismiss() }
    BackHandler { close() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("YouTube 导入") },
                navigationIcon = { TextButton(onClick = ::close) { Text("← 返回") } },
                actions = {
                    TextButton(onClick = onOpenAlbum) { Text("相册") }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("公开视频链接或 Shorts 链接") },
                trailingIcon = {
                    if (input.isNotBlank()) {
                        TextButton(onClick = { input = "" }) {
                            Text("清空")
                        }
                    }
                },
                minLines = 2,
                maxLines = 4,
                enabled = state !is YouTubeImportState.Initializing &&
                    state !is YouTubeImportState.Parsing &&
                    state !is YouTubeImportState.Downloading,
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        input = cb.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = state !is YouTubeImportState.Downloading,
                ) { Text("粘贴") }
                Button(
                    onClick = { viewModel.parse(input) },
                    modifier = Modifier.weight(1f),
                    enabled = state !is YouTubeImportState.Initializing &&
                        state !is YouTubeImportState.Parsing &&
                        state !is YouTubeImportState.Downloading,
                ) { Text("开始解析") }
            }

            // ── cookie card ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (hasCookie) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (hasCookie) {
                        Text("✅ 已登录 YouTube", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.clearCookie() }) {
                            Text("清除", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        Text("登录后可解析私密/年龄限制视频",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            cookieLauncher.launch(Intent(context, YouTubeCookieActivity::class.java))
                        }) {
                            Text("登录", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // ── state body ──
            when (val s = state) {
                YouTubeImportState.Idle -> Text(
                    "仅用于保存你有权下载的公开内容。第一版不支持登录、播放列表、直播和 DRM 内容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                YouTubeImportState.Initializing -> LoadingRow("首次使用正在初始化本地解析引擎…")
                YouTubeImportState.Parsing -> LoadingRow("正在读取视频与可用清晰度…")

                is YouTubeImportState.Ready -> {
                    WorkCard(s.work)
                    Text("清晰度", style = MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(s.work.qualities, key = { it.height }) { q ->
                            FilterChip(
                                selected = selectedHeight == q.height,
                                onClick = { selectedHeight = q.height },
                                label = { Text(q.label) },
                            )
                        }
                    }
                    Text("编码模式", style = MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(YouTubeCodecMode.entries, key = { it.name }) { mode ->
                            FilterChip(
                                selected = codecMode == mode,
                                onClick = { codecMode = mode },
                                label = { Text(mode.label) },
                            )
                        }
                    }
                    Text(
                        codecMode.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { viewModel.download(s.work, selectedHeight, codecMode, onMediaSaved) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        enabled = selectedHeight > 0,
                    ) { Text("保存视频") }
                }

                is YouTubeImportState.Downloading -> {
                    WorkCard(s.work)
                    LinearProgressIndicator(
                        progress = { s.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "下载中 ${(s.progress * 100).toInt()}%" +
                            if (s.speed.isNotBlank()) " · ${s.speed}" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { viewModel.cancel() }, modifier = Modifier.fillMaxWidth()) {
                        Text("取消")
                    }
                }

                is YouTubeImportState.Merging -> {
                    WorkCard(s.work)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在合并音视频…", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { viewModel.cancel() }, modifier = Modifier.fillMaxWidth()) {
                        Text("取消")
                    }
                }

                is YouTubeImportState.Success -> {
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("保存完成", fontWeight = FontWeight.Bold)
                            Text(s.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("位置：DCIM/RCGallery/YouTube", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Button(
                        onClick = { viewModel.reset(); input = "" },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("继续解析") }
                }

                is YouTubeImportState.Error -> {
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )) {
                        Text(
                            s.message,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    OutlinedButton(onClick = { viewModel.reset() }, modifier = Modifier.fillMaxWidth()) {
                        Text("重新尝试")
                    }
                }
            }
        }
    }
}

// ── shared composables ──────────────────────────────────────────────────

@Composable
private fun YouTubeHistorySection(entries: List<YouTubeDownloadHistory>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("下载历史", style = MaterialTheme.typography.titleMedium)
        entries.forEach { entry ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(
                        entry.title,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(entry.displayName, style = MaterialTheme.typography.bodySmall)
                    Text(
                        entry.webpageUrl,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingRow(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.width(24.dp).height(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(message)
    }
}

@Composable
private fun WorkCard(work: YouTubeWorkInfo) {
    Card {
        Column(Modifier.fillMaxWidth()) {
            work.thumbnailUrl?.let {
                AsyncImage(
                    model = it,
                    contentDescription = work.title,
                    modifier = Modifier.fillMaxWidth().height(210.dp),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(work.title, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (work.channelName.isNotBlank()) Text(work.channelName, style = MaterialTheme.typography.bodySmall)
                Text(
                    "${work.qualities.size} 种清晰度 · ${formatDuration(work.durationSeconds)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatDuration(seconds: Int): String =
    "%d:%02d".format((seconds.coerceAtLeast(0) / 60), seconds.coerceAtLeast(0) % 60)
