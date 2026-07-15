package com.example.rcgallery.ui.screen

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.rcgallery.data.baidu.BaiduBrowseState
import com.example.rcgallery.data.baidu.BaiduCloudEntry
import com.example.rcgallery.viewmodel.BaiduNetdiskViewModel
import com.example.rcgallery.PipState
import androidx.compose.runtime.DisposableEffect

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun BaiduNetdiskScreen(
    viewModel: BaiduNetdiskViewModel,
    onDismiss: () -> Unit
) {
    val state by viewModel.browseState.collectAsState()
    val progress by viewModel.downloadProgress.collectAsState()
    val context = LocalContext.current
    var previewState by remember { mutableStateOf<Pair<Int, List<BaiduCloudEntry>>?>(null) }

    DisposableEffect(Unit) {
        PipState.isBaiduBrowserActive = true
        onDispose { PipState.isBaiduBrowserActive = false }
    }

    previewState?.let { (initialIndex, items) ->
        BaiduPreviewScreen(
            initialIndex = initialIndex,
            items = items,
            baiduViewModel = viewModel,
            onDismiss = { previewState = null },
            onDownload = viewModel::download
        )
        return
    }

    fun goBack() {
        if (!viewModel.goBack()) onDismiss()
    }

    BackHandler { goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (val current = state) {
                            is BaiduBrowseState.Folder -> current.path.substringAfterLast('/').ifEmpty { "百度网盘" }
                            else -> "百度网盘"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    TextButton(onClick = ::goBack) { Text("← 返回") }
                },
                actions = {
                    if (state !is BaiduBrowseState.SignedOut) {
                        TextButton(onClick = viewModel::signOut) { Text("退出登录") }
                    }
                },
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val current = state) {
                BaiduBrowseState.SignedOut -> {
                    Column(
                        Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("☁", fontSize = 52.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("连接百度网盘", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (viewModel.isBackendConfigured) "登录后可浏览并下载图片和视频"
                            else "尚未配置 BAIDU_AUTH_BACKEND_URL",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(
                            enabled = viewModel.isBackendConfigured,
                            onClick = {
                                viewModel.beginLogin()
                                    .onSuccess { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                    .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                            }
                        ) { Text("百度账号登录") }
                    }
                }
                is BaiduBrowseState.Loading -> {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(current.message)
                    }
                }
                is BaiduBrowseState.Error -> {
                    Column(
                        Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(current.message, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = viewModel::retry) { Text("重试") }
                    }
                }
                is BaiduBrowseState.Folder -> {
                    BaiduFolderList(
                        entries = current.entries,
                        onFolderClick = { viewModel.openFolder(it.path) },
                        onPreview = { entry ->
                            val media = current.entries.filter(BaiduCloudEntry::isMedia)
                            val index = media.indexOfFirst { it.fsId == entry.fsId }
                            if (index >= 0) previewState = index to media
                        },
                        onDownload = viewModel::download
                    )
                }
            }

            progress?.let { download ->
                Card(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("正在下载 ${download.fileName}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        val ratio = if (download.totalBytes > 0) {
                            (download.bytesCopied.toFloat() / download.totalBytes).coerceIn(0f, 1f)
                        } else 0f
                        LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun BaiduFolderList(
    entries: List<BaiduCloudEntry>,
    onFolderClick: (BaiduCloudEntry) -> Unit,
    onPreview: (BaiduCloudEntry) -> Unit,
    onDownload: (BaiduCloudEntry) -> Unit
) {
    val visibleEntries = entries.filter { it.isDirectory || it.isMedia }
    if (visibleEntries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("此目录中没有图片或视频", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(visibleEntries, key = { it.fsId }) { entry ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable {
                    if (entry.isDirectory) onFolderClick(entry) else onPreview(entry)
                },
                elevation = CardDefaults.cardElevation(1.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (entry.thumbnailUrl.isNotEmpty() && !entry.isDirectory) {
                        AsyncImage(
                            model = entry.thumbnailUrl,
                            contentDescription = entry.name,
                            modifier = Modifier.size(54.dp)
                        )
                    } else {
                        Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                            Text(if (entry.isDirectory) "📁" else if (entry.isVideo) "▶" else "▧", fontSize = 28.sp)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!entry.isDirectory) {
                            Text(formatBytes(entry.size), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (!entry.isDirectory) {
                        TextButton(onClick = { onDownload(entry) }) { Text("下载") }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
