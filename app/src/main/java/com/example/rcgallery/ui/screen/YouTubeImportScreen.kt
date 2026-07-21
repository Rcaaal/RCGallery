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
import androidx.compose.runtime.LaunchedEffect
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
import com.example.rcgallery.data.youtube.YouTubeImportState
import com.example.rcgallery.data.youtube.YouTubeWorkInfo
import com.example.rcgallery.viewmodel.YouTubeImportViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeImportScreen(
    onDismiss: () -> Unit,
    onMediaSaved: () -> Unit,
    onOpenAlbum: () -> Unit,
    viewModel: YouTubeImportViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hasCookie by viewModel.hasCookie.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var selectedHeight by remember { mutableIntStateOf(0) }
    var codecMode by remember { mutableStateOf(YouTubeCodecMode.AUTO) }

    val cookieLoginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.getStringExtra(YouTubeCookieActivity.EXTRA_COOKIE_PATH)?.let { path ->
                viewModel.setCookieFile(path)
            }
        }
    }

    val work = when (val current = state) {
        is YouTubeImportState.Ready -> current.work
        is YouTubeImportState.Downloading -> current.work
        else -> null
    }
    LaunchedEffect(work?.id) {
        selectedHeight = work?.qualities?.firstOrNull()?.height ?: 0
    }

    fun close() {
        viewModel.reset()
        onDismiss()
    }

    BackHandler { close() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("YouTube 导入") },
                navigationIcon = { TextButton(onClick = ::close) { Text("← 返回") } },
                actions = { TextButton(onClick = onOpenAlbum) { Text("相册") } },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
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
                minLines = 2,
                maxLines = 4,
                enabled = state !is YouTubeImportState.Initializing &&
                    state !is YouTubeImportState.Parsing && state !is YouTubeImportState.Downloading,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        input = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = state !is YouTubeImportState.Downloading,
                ) { Text("粘贴") }
                Button(
                    onClick = { viewModel.parse(input) },
                    modifier = Modifier.weight(1f),
                    enabled = state !is YouTubeImportState.Initializing &&
                        state !is YouTubeImportState.Parsing && state !is YouTubeImportState.Downloading,
                ) { Text("开始解析") }
            }

            // Cookie 登录状态卡
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
                            cookieLoginLauncher.launch(Intent(context, YouTubeCookieActivity::class.java))
                        }) {
                            Text("登录", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            when (val current = state) {
                YouTubeImportState.Idle -> Text(
                    "仅用于保存你有权下载的公开内容。第一版不支持登录、播放列表、直播和 DRM 内容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                YouTubeImportState.Initializing -> LoadingRow("首次使用正在初始化本地解析引擎…")
                YouTubeImportState.Parsing -> LoadingRow("正在读取视频与可用清晰度…")
                is YouTubeImportState.Ready -> {
                    YouTubeResultCard(current.work)
                    Text("清晰度", style = MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(current.work.qualities, key = { it.height }) { quality ->
                            FilterChip(
                                selected = selectedHeight == quality.height,
                                onClick = { selectedHeight = quality.height },
                                label = { Text(quality.label) },
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
                        onClick = { viewModel.download(current.work, selectedHeight, codecMode, onMediaSaved) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        enabled = selectedHeight > 0,
                    ) { Text("保存视频") }
                }
                is YouTubeImportState.Downloading -> {
                    YouTubeResultCard(current.work)
                    val progress = if (current.totalBytes > 0L) {
                        (current.downloadedBytes.toFloat() / current.totalBytes).coerceIn(0f, 1f)
                    } else null
                    if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${current.stage}\n${formatYouTubeBytes(current.downloadedBytes)}" +
                            (if (current.totalBytes > 0L) " / ${formatYouTubeBytes(current.totalBytes)}" else "") +
                            " · ${formatYouTubeBytes(current.bytesPerSecond)}/s",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth()) {
                        Text("取消")
                    }
                }
                is YouTubeImportState.Success -> {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("保存完成", fontWeight = FontWeight.Bold)
                            Text(current.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("位置：DCIM/RCGallery/YouTube", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Button(
                        onClick = { viewModel.reset(); input = "" },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("继续解析") }
                }
                is YouTubeImportState.Error -> {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(
                            current.message,
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

@Composable
private fun LoadingRow(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.width(24.dp).height(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(message)
    }
}

@Composable
private fun YouTubeResultCard(work: YouTubeWorkInfo) {
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

private fun formatYouTubeBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return "%.1f %s".format(value, units[index])
}
