package com.example.rcgallery.ui.screen

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.rcgallery.data.bilibili.BiliAuthState
import com.example.rcgallery.data.bilibili.BiliCodecMode
import com.example.rcgallery.data.bilibili.BiliImportState
import com.example.rcgallery.data.bilibili.BiliWorkInfo
import com.example.rcgallery.viewmodel.BiliImportViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BiliImportScreen(
    onDismiss: () -> Unit,
    onMediaSaved: () -> Unit,
    onOpenAlbum: () -> Unit,
    initialInput: String? = null,
    viewModel: BiliImportViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var input by remember(initialInput) { mutableStateOf(initialInput.orEmpty()) }
    var selectedPages by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedQuality by remember { mutableIntStateOf(0) }
    var selectedCodecMode by remember { mutableStateOf(BiliCodecMode.AUTO) }
    var showAccountDialog by remember { mutableStateOf(false) }

    LaunchedEffect(initialInput) {
        initialInput?.trim()?.takeIf { it.isNotEmpty() }?.let(viewModel::parse)
    }

    fun close() {
        viewModel.reset()
        onDismiss()
    }

    val readyWork = when (val current = state) {
        is BiliImportState.Ready -> current.work
        is BiliImportState.Downloading -> current.work
        else -> null
    }
    LaunchedEffect(readyWork?.bvid) {
        val work = readyWork ?: return@LaunchedEffect
        selectedPages = setOfNotNull(work.pages.firstOrNull()?.pageNumber)
        selectedQuality = work.qualities.firstOrNull()?.id ?: 0
    }

    BackHandler { close() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("哔哩哔哩导入") },
                navigationIcon = { TextButton(onClick = ::close) { Text("← 返回") } },
                actions = {
                    TextButton(onClick = { showAccountDialog = true }) {
                        Text(if (authState is BiliAuthState.LoggedIn) "账号" else "登录")
                    }
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
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("视频链接、BV 号或 AV 号") },
                trailingIcon = {
                    if (input.isNotBlank()) {
                        TextButton(onClick = { input = "" }) {
                            Text("清空")
                        }
                    }
                },
                minLines = 2,
                maxLines = 4,
                enabled = state !is BiliImportState.Parsing && state !is BiliImportState.Downloading,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        input = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = state !is BiliImportState.Downloading,
                ) { Text("粘贴") }
                Button(
                    onClick = { viewModel.parse(input) },
                    modifier = Modifier.weight(1f),
                    enabled = state !is BiliImportState.Parsing && state !is BiliImportState.Downloading,
                ) { Text("开始解析") }
            }

            when (val current = state) {
                BiliImportState.Idle -> Unit
                BiliImportState.Parsing -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.width(24.dp).height(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("正在读取作品和可用清晰度…")
                    }
                }
                is BiliImportState.Ready -> {
                    BiliResultCard(current.work)
                    Text("选择分P", style = MaterialTheme.typography.titleSmall)
                    if (current.work.pages.size > 1) {
                        TextButton(onClick = {
                            selectedPages = if (selectedPages.size == current.work.pages.size) {
                                emptySet()
                            } else {
                                current.work.pages.map { it.pageNumber }.toSet()
                            }
                        }) {
                            Text(if (selectedPages.size == current.work.pages.size) "取消全选" else "全选")
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(current.work.pages, key = { it.cid }) { page ->
                            FilterChip(
                                selected = page.pageNumber in selectedPages,
                                onClick = {
                                    selectedPages = if (page.pageNumber in selectedPages) {
                                        selectedPages - page.pageNumber
                                    } else {
                                        selectedPages + page.pageNumber
                                    }
                                },
                                label = {
                                    Text(
                                        "P${page.pageNumber} ${page.title}",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
                    Text("清晰度", style = MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(current.work.qualities, key = { it.id }) { quality ->
                            FilterChip(
                                selected = selectedQuality == quality.id,
                                onClick = { selectedQuality = quality.id },
                                label = {
                                    Text(
                                        buildString {
                                            append(quality.label)
                                            if (quality.width > 0 && quality.height > 0) {
                                                append(" · ${quality.width}×${quality.height}")
                                            }
                                        }
                                    )
                                },
                            )
                        }
                    }
                    Text("编码模式", style = MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(BiliCodecMode.entries, key = { it.name }) { mode ->
                            FilterChip(
                                selected = selectedCodecMode == mode,
                                onClick = { selectedCodecMode = mode },
                                label = { Text(mode.label) },
                            )
                        }
                    }
                    Text(
                        selectedCodecMode.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            viewModel.download(
                                current.work,
                                selectedPages,
                                selectedQuality,
                                selectedCodecMode,
                                onMediaSaved,
                            )
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        enabled = selectedPages.isNotEmpty() && selectedQuality != 0,
                    ) {
                        Text("保存选中的 ${selectedPages.size} 个分P")
                    }
                }
                is BiliImportState.Downloading -> {
                    BiliResultCard(current.work)
                    val progress = if (current.totalBytes > 0L) {
                        (current.downloadedBytes.toFloat() / current.totalBytes).coerceIn(0f, 1f)
                    } else null
                    if (progress == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        "${current.stage} · ${current.currentPage}/${current.pageCount}\n" +
                            "${formatBiliBytes(current.downloadedBytes)}" +
                            if (current.totalBytes > 0L) " / ${formatBiliBytes(current.totalBytes)}" else "" +
                            " · ${formatBiliBytes(current.bytesPerSecond)}/s",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth()) {
                        Text("取消")
                    }
                }
                is BiliImportState.Success -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("保存完成", fontWeight = FontWeight.Bold)
                            Text("成功 ${current.savedCount} 项，失败 ${current.failedCount} 项")
                            current.firstDisplayName?.let {
                                Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Text("位置：DCIM/RCGallery/Bilibili", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Button(
                        onClick = { viewModel.reset(); input = "" },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("继续解析") }
                }
                is BiliImportState.Error -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
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

    if (showAccountDialog) {
        BiliAccountDialog(
            state = authState,
            onStartLogin = viewModel::startLogin,
            onLogout = viewModel::logout,
            onDismiss = {
                if (authState !is BiliAuthState.LoggedIn) viewModel.cancelLogin()
                showAccountDialog = false
            },
        )
    }
}

@Composable
private fun BiliAccountDialog(
    state: BiliAuthState,
    onStartLogin: () -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val qrUrl = (state as? BiliAuthState.AwaitingScan)?.qrUrl
    val qrBitmap = remember(qrUrl) { qrUrl?.let { createQrBitmap(it, 720) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state is BiliAuthState.LoggedIn) "哔哩哔哩账号" else "扫码登录") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state) {
                    BiliAuthState.LoggedOut -> {
                        Text("使用哔哩哔哩 App 扫码登录。应用不会读取账号密码。")
                    }
                    BiliAuthState.Loading -> {
                        CircularProgressIndicator()
                        Text("正在连接登录服务…")
                    }
                    is BiliAuthState.AwaitingScan -> {
                        qrBitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "哔哩哔哩登录二维码",
                                modifier = Modifier.width(240.dp).height(240.dp),
                            )
                        }
                        Text(state.status)
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(state.qrUrl))
                                    )
                                }
                            }
                        ) {
                            Text("在哔哩哔哩中打开")
                        }
                    }
                    is BiliAuthState.LoggedIn -> {
                        Text(state.userName, fontWeight = FontWeight.Bold)
                        Text(state.vipLabel, color = MaterialTheme.colorScheme.primary)
                        Text("登录后会按账号权限显示可用清晰度。")
                    }
                    is BiliAuthState.Error -> {
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                BiliAuthState.LoggedOut, is BiliAuthState.Error -> {
                    TextButton(onClick = onStartLogin) { Text("生成二维码") }
                }
                is BiliAuthState.LoggedIn -> {
                    TextButton(onClick = onLogout) { Text("退出登录") }
                }
                else -> Unit
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun createQrBitmap(content: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val offset = y * size
        for (x in 0 until size) {
            pixels[offset + x] = if (matrix[x, y]) {
                android.graphics.Color.BLACK
            } else {
                android.graphics.Color.WHITE
            }
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
private fun BiliResultCard(work: BiliWorkInfo) {
    Card {
        Column(Modifier.fillMaxWidth()) {
            work.coverUrl?.let {
                AsyncImage(
                    model = it,
                    contentDescription = work.title,
                    modifier = Modifier.fillMaxWidth().height(210.dp),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(work.title, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (work.ownerName.isNotBlank()) {
                    Text(work.ownerName, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "${work.bvid} · ${work.pages.size} 个分P",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatBiliBytes(bytes: Long): String {
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
