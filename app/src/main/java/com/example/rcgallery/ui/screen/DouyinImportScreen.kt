package com.example.rcgallery.ui.screen

import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.example.rcgallery.data.douyin.DouyinImportState
import com.example.rcgallery.data.douyin.DouyinMediaResource
import com.example.rcgallery.data.douyin.DouyinWorkInfo
import com.example.rcgallery.viewmodel.DouyinImportViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DouyinImportScreen(
    onDismiss: () -> Unit,
    onMediaSaved: () -> Unit,
    onOpenAlbum: () -> Unit,
    viewModel: DouyinImportViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }

    fun close() {
        viewModel.reset()
        onDismiss()
    }

    BackHandler { close() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("抖音链接解析") },
                navigationIcon = {
                    TextButton(onClick = ::close) { Text("← 返回") }
                },
                actions = {
                    TextButton(onClick = onOpenAlbum) { Text("相册") }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "粘贴抖音公开视频或图文作品的分享文本。解析和下载均在本机完成。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("抖音分享链接") },
                minLines = 3,
                maxLines = 5,
                enabled = state !is DouyinImportState.Parsing && state !is DouyinImportState.Downloading,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        input = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = state !is DouyinImportState.Downloading,
                ) { Text("粘贴") }
                Button(
                    onClick = { viewModel.parse(input) },
                    modifier = Modifier.weight(1f),
                    enabled = state !is DouyinImportState.Parsing && state !is DouyinImportState.Downloading,
                ) { Text("开始解析") }
            }

            when (val current = state) {
                DouyinImportState.Idle -> Unit
                DouyinImportState.Parsing -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("正在展开短链接并读取作品信息…")
                    }
                }
                is DouyinImportState.Ready -> {
                    DouyinResultCard(
                        work = current.work,
                    )
                    Button(
                        onClick = { viewModel.download(current.work, onMediaSaved) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Text(
                            if (current.work.media.size > 1) "一键保存全部 ${current.work.media.size} 项"
                            else "保存到本地相册"
                        )
                    }
                }
                is DouyinImportState.Downloading -> {
                    DouyinResultCard(work = current.work)
                    val progress = if (current.totalBytes > 0) {
                        (current.downloadedBytes.toFloat() / current.totalBytes.toFloat()).coerceIn(0f, 1f)
                    } else null
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    val totalText = if (current.totalBytes > 0) {
                        " / ${formatBytes(current.totalBytes)}"
                    } else ""
                    Text(
                        "正在保存 ${current.currentIndex + 1}/${current.itemCount} · " +
                            "已完成 ${current.completedCount}，失败 ${current.failedCount}\n" +
                            "已下载 ${formatBytes(current.downloadedBytes)}$totalText" +
                            " · ${formatBytes(current.bytesPerSecond)}/s",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth()) {
                        Text("取消下载")
                    }
                }
                is DouyinImportState.Success -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                if (current.failedCount == 0) "保存成功" else "批量保存完成",
                                fontWeight = FontWeight.Bold
                            )
                            Text("成功 ${current.savedCount} 项，失败 ${current.failedCount} 项")
                            current.firstDisplayName?.let {
                                Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                "位置：DCIM/RCGallery/Douyin",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Button(onClick = { viewModel.reset(); input = "" }, modifier = Modifier.fillMaxWidth()) {
                        Text("继续解析")
                    }
                }
                is DouyinImportState.Error -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                    ) {
                        Text(
                            current.message,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DouyinResultCard(work: DouyinWorkInfo) {
    Card(shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth()) {
            val images = work.media.filterIsInstance<DouyinMediaResource.Image>()
            if (images.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().height(210.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(images, key = { it.index }) { image ->
                        AsyncImage(
                            model = image.urls.firstOrNull(),
                            contentDescription = "第 ${image.index} 张",
                            modifier = Modifier.width(156.dp).height(210.dp),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            } else if (work.coverUrl != null) {
                AsyncImage(
                    model = work.coverUrl,
                    contentDescription = work.title,
                    modifier = Modifier.fillMaxWidth().height(210.dp),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(Modifier.padding(16.dp)) {
                Text(work.title, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val imageCount = work.media.count { it is DouyinMediaResource.Image }
                val videoCount = work.media.count { it is DouyinMediaResource.Video }
                if (work.media.size > 1 || imageCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        buildList {
                            if (imageCount > 0) add("$imageCount 张图片")
                            if (videoCount > 0) add("$videoCount 个视频")
                        }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (!work.author.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(work.author.orEmpty(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return "%.1f %s".format(value, units[index])
}
