package com.example.rcgallery.ui.screen

import android.app.Activity
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.rcgallery.PipState
import com.example.rcgallery.ui.component.DevOverlay
import com.example.rcgallery.ui.component.InertiaSettings
import com.example.rcgallery.ui.component.InertiaSettingsPanel
import com.example.rcgallery.ui.component.VideoPlayer
import com.example.rcgallery.util.AppLogger
import com.example.rcgallery.viewmodel.GalleryViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    initialIndex: Int = 0,
    onBackClick: () -> Unit = {},
    volumeEnabled: Boolean = false,
    onVolumeToggle: () -> Unit = {},
    items: List<com.example.rcgallery.model.MediaItem>? = null  // 非空时覆盖 ViewModel 的全量数据
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val viewModel: GalleryViewModel = viewModel(activity)
    val fullItems by viewModel.mediaItems.collectAsStateWithLifecycle()
    val mediaItems = items ?: fullItems
    val scope = rememberCoroutineScope()

    // 用 remember 快照「进入时」的 items 大小，避免 ViewModel 清空数据时误触发返回
    val initialItemCount = remember { mediaItems.size }
    AppLogger.d("Preview", "enter initialIndex=$initialIndex items=$initialItemCount")

    if (initialItemCount == 0) {
        AppLogger.d("Preview", "skip → onBackClick (initialItemCount=0)")
        LaunchedEffect(Unit) { onBackClick() }
        return
    }

    val safeIndex = initialIndex.coerceIn(0, mediaItems.lastIndex)

    val pagerState = rememberPagerState(
        pageCount = { mediaItems.size },
        initialPage = safeIndex
    )
    var pagerScrollEnabled by remember { mutableStateOf(true) }
    val savedPositions = remember { mutableMapOf<Uri, Long>() }

    // ── PiP 覆盖层管理 ──
    var pipOverlayHidden by remember { mutableStateOf(false) }
    var pipTriggered by remember { mutableStateOf(false) }

    // ── 图片信息面板 ──
    var showInfo by remember { mutableStateOf(false) }
    // 返回键直接退出预览（不拦截），信息面板通过点击图片或翻页关闭

    // 用户点击"小窗"按钮 → 请求进入 PiP
    // 1. 隐藏全部 UI chrome + 禁用 PlayerView 控制器（useController=false）
    // 2. 同步视频真实尺寸到 PipState
    // 3. 触发 PiP
    if (pipTriggered) {
        LaunchedEffect(Unit) {
            pipOverlayHidden = true
            withFrameNanos { }
            delay(200)  // 等 LaunchedEffect(hideUiOverlays) → useController=false 生效

            // 强制同步视频尺寸到 PipState（直接从 exoPlayer 读实时值）
            val player = PipState.exoPlayer
            if (player != null) {
                val vs = player.videoSize
                if (vs != null && vs.width > 0 && vs.height > 0) {
                    val rot = vs.unappliedRotationDegrees
                    if (rot == 90 || rot == 270) {
                        PipState.videoWidth = vs.height; PipState.videoHeight = vs.width
                    } else {
                        PipState.videoWidth = vs.width; PipState.videoHeight = vs.height
                    }
                }
                AppLogger.d("PiP", "synced size: ${PipState.videoWidth}x${PipState.videoHeight} rot=${vs?.unappliedRotationDegrees}°")
            }

            activity.enterPictureInPictureMode(
                PipState.buildPipParams()
            )
            pipTriggered = false
        }
    }

    // PiP 退出 → 恢复 UI 覆盖层
    LaunchedEffect(PipState.isInPip) {
        if (!PipState.isInPip && pipOverlayHidden) {
            AppLogger.d("PiP", "exit PiP → restore overlays")
            pipOverlayHidden = false
        }
    }

    val currentItem = mediaItems.getOrNull(pagerState.currentPage)

    // ── 检测 Pager 边缘 overscroll → 提示 ──
    var overscrollToastTime by remember { mutableLongStateOf(0L) }
    val overscrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.x != 0f && source == NestedScrollSource.Drag) {
                    val now = System.currentTimeMillis()
                    if (now - overscrollToastTime > 2000L) {
                        overscrollToastTime = now
                        val page = pagerState.currentPage
                        val total = pagerState.pageCount
                        if (page == 0 && available.x > 0) {
                            Toast.makeText(context, "已经是第一张", Toast.LENGTH_SHORT).show()
                        } else if (page == total - 1 && available.x < 0) {
                            Toast.makeText(context, "已经是最后一张", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                return Offset.Zero
            }
        }
    }
    // 日志：当前页面变更
    LaunchedEffect("page:${pagerState.currentPage}") {
        showInfo = false  // 翻页关闭信息面板
        AppLogger.d("Preview", "page=${pagerState.currentPage} total=${mediaItems.size} uri=${currentItem?.uri?.lastPathSegment ?: "?"}")
    }

    // ── 相册重命名（只记录虚拟名+入队列，不弹授权窗）──
    var showAlbumRenameDialog by remember { mutableStateOf(false) }

    // ── 相册重命名对话框 ──
    if (showAlbumRenameDialog && currentItem != null) {
        val currentAlbumName = viewModel.getAlbumDisplayName(currentItem?.albumId, currentItem?.albumName)
        var editText by remember { mutableStateOf(currentAlbumName) }
        AlertDialog(
            onDismissRequest = { showAlbumRenameDialog = false },
            title = { Text("重命名相册") },
            text = {
                Column {
                    Text("相册名立即更改，返回主页后点击按钮搬运。", color = Color(0xFF999999), fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        singleLine = true,
                        label = { Text("新相册名") }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val newName = editText.trim()
                    if (newName.isEmpty()) return@Button
                    showAlbumRenameDialog = false
                    val bucketId = currentItem?.albumId ?: return@Button
                    val oldName = currentAlbumName
                    viewModel.buildAndQueueTask(bucketId, oldName, newName)
                    Toast.makeText(context, "已加入搬运队列 (${viewModel.renameQueue.value.size}个)", Toast.LENGTH_SHORT).show()
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { showAlbumRenameDialog = false }) { Text("取消") } }
        )
    }

    var showInertiaSettings by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    if (showInertiaSettings) InertiaSettingsPanel(
        onDismiss = { showInertiaSettings = false },
        onOpenLog = { showInertiaSettings = false; showLogDialog = true }
    )
    if (showLogDialog) {
        Box(Modifier.fillMaxSize().clickable { showLogDialog = false }) {
            DevOverlay(initialShow = true)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Scaffold（TopAppBar 在 PiP 时隐藏，使 PlayerView 充满屏幕）──
        Scaffold(
            topBar = {
                if (!pipOverlayHidden) {
                    TopAppBar(
                        title = {},
                        navigationIcon = {
                            TextButton(onClick = {
                                onBackClick()
                            }) { Text("← 返回", color = Color.White) }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.3f))
                    )
                }
            },
            containerColor = Color.Black
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                // ── 图片/视频区域（信息面板展开时被推到上半部分）──
                val isInfoShown = showInfo && currentItem != null && !currentItem.isVideo
                Box(
                    modifier = Modifier
                        .weight(if (isInfoShown) 0.7f else 1f)
                        .fillMaxWidth()
                        .nestedScroll(overscrollConnection)
                ) {
                    HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = pagerScrollEnabled,
                        beyondViewportPageCount = 1,
                        modifier = Modifier.fillMaxSize(),
                        pageSpacing = 0.dp
                    ) { page ->
                        val item = mediaItems.getOrNull(page)
                        if (item != null) {
                            if (item.isVideo) {
                                VideoPlayer(
                                    uri = item.uri,
                                    isActive = page == pagerState.currentPage,
                                    volumeEnabled = volumeEnabled,
                                    onVolumeToggle = onVolumeToggle,
                                    savedPositions = savedPositions,
                                    onControlZoneActive = { pagerScrollEnabled = !it },
                                    onRequestPip = { pipTriggered = true },
                                    hideUiOverlays = pipOverlayHidden
                                )
                            } else {
                                ZoomableImage3(
                                    uri = item.uri,
                                    onEdgeSwipe = { direction ->
                                        val nextPage = pagerState.currentPage + direction
                                        if (nextPage < 0) {
                                            Toast.makeText(context, "已经是第一张", Toast.LENGTH_SHORT).show()
                                        } else if (nextPage >= mediaItems.size) {
                                            Toast.makeText(context, "已经是最后一张", Toast.LENGTH_SHORT).show()
                                        } else {
                                            scope.launch {
                                                pagerState.animateScrollToPage(nextPage)
                                            }
                                        }
                                    },
                                    onSwipeDownToBack = {
                                        if (showInfo) showInfo = false
                                        else onBackClick()
                                    },
                                    onSwipeUpToShowInfo = { showInfo = true },
                                    onSingleTap = { if (showInfo) showInfo = false }
                                )
                            }
                        }
                    }
                }
            // ── 图片信息卡片（仅展开时插入 Column，weight 占 30%）──
            if (isInfoShown) {
                Box(
                    modifier = Modifier
                        .weight(0.3f)
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, dragAmount ->
                                if (dragAmount > 0) showInfo = false
                            }
                        }
                ) {
                    InfoCard(
                        currentItem!!,
                        onDismiss = { showInfo = false },
                        albumDisplayName = viewModel.getAlbumDisplayName(currentItem?.albumId, currentItem?.albumName),
                        onAlbumNameClick = { showAlbumRenameDialog = true }
                    )
                }
            }
            }
        }
        // ── 设置按钮（PiP 时隐藏）──
        if (!pipOverlayHidden) {
            // ── 惯性设置齿轮按钮（TopEnd，橙色）──
            Box(
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 8.dp).size(28.dp)
                    .clip(CircleShape).background(Color(0xCCFF9800))
                    .clickable { showInertiaSettings = true },
                contentAlignment = Alignment.Center
            ) { Text("⚙", color = Color.White, fontSize = 14.sp) }
        }
    }
}

// ══════════════════════════════════════
//  图片信息卡片（紧凑 4 行布局 + 重命名）
// ══════════════════════════════════════

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024f)
    bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024f * 1024f))
    else -> "%.2f GB".format(bytes / (1024f * 1024f * 1024f))
}

private fun formatDate(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp * 1000L))
}

@Composable
private fun InfoCard(
    item: com.example.rcgallery.model.MediaItem,
    onDismiss: () -> Unit,
    albumDisplayName: String,
    onAlbumNameClick: () -> Unit
) {
    val context = LocalContext.current

    // ── 文件名解析（只含前缀，扩展名不可改）──
    val dotIndex = remember(item.fileName) { item.fileName.lastIndexOf('.') }
    val baseNameOnly = remember(item.fileName) {
        if (dotIndex > 0) item.fileName.substring(0, dotIndex) else item.fileName
    }
    val extOnly = remember(item.fileName) {
        if (dotIndex > 0) item.fileName.substring(dotIndex) else ""
    }

    var showRenameDialog by remember { mutableStateOf(false) }

    // ── 文件重命名 launcher（首次操作时申请写入权限）──
    var writeGrantedUris by remember { mutableStateOf<Set<Uri>?>(null) }
    var pendingDisplayName by remember { mutableStateOf("") }
    val renameLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            writeGrantedUris = (writeGrantedUris ?: emptySet()) + item.uri
            if (pendingDisplayName.isNotEmpty()) {
                try {
                    context.contentResolver.update(item.uri, ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, pendingDisplayName)
                    }, null, null)
                    showRenameDialog = false
                } catch (e: Exception) {
                    Toast.makeText(context, "重命名失败", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(context, "需要授予修改权限", Toast.LENGTH_SHORT).show()
        }
    }

    val filePath = item.filePath.ifEmpty { albumDisplayName }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF1A1A1A),
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            // Row 1: 文件名(可点击) + 创建时间
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.fileName,
                    color = Color.White,
                    fontSize = 13.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).clickable { showRenameDialog = true }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatDate(item.dateAdded),
                    color = Color(0xFF999999),
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(6.dp))
            // Row 2: 大小 · 格式
            Text(
                text = "${formatFileSize(item.size)} · ${item.mimeType}",
                color = Color(0xFF999999),
                fontSize = 12.sp,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            // Row 3: 相册名（可点击 → 打开重命名对话框）
            Text(
                text = albumDisplayName,
                color = Color(0xFFBBBBBB),
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier.clickable { onAlbumNameClick() }
            )
            Spacer(Modifier.height(4.dp))
            // Row 4: 目录路径
            if (filePath.isNotEmpty()) {
                Text(
                    text = filePath,
                    color = Color(0xFF777777),
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }
    }

    // ── 文件重命名对话框 ──
    if (showRenameDialog) {
        var editText by remember { mutableStateOf(baseNameOnly) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("重命名文件") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        label = { Text("文件名") }
                    )
                    Text(extOnly, color = Color.Gray, fontSize = 14.sp)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val newName = editText.trim()
                    if (newName.isEmpty()) return@Button
                    pendingDisplayName = "$newName$extOnly"

                    // 已有授权 → 直接更新
                    if (writeGrantedUris?.contains(item.uri) == true) {
                        try {
                            context.contentResolver.update(item.uri, ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, pendingDisplayName)
                            }, null, null)
                            showRenameDialog = false
                        } catch (e: Exception) {
                            Toast.makeText(context, "重命名失败", Toast.LENGTH_SHORT).show()
                        }
                        return@Button
                    }

                    // 首次 → 只申请当前文件的写入权限（不批量覆盖整张专辑）
                    try {
                        val pending = MediaStore.createWriteRequest(
                            context.contentResolver, listOf(item.uri)
                        )
                        renameLauncher.launch(
                            IntentSenderRequest.Builder(pending.intentSender).build()
                        )
                    } catch (e: Exception) {
                        Toast.makeText(context, "请求授权失败", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("取消") } }
        )
    }

}

