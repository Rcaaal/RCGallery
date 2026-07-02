package com.example.rcgallery.ui.screen

import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
    onDeleted: () -> Unit = {},
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
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var showInertiaSettings by remember { mutableStateOf(false) }
    if (showInertiaSettings) InertiaSettingsPanel(onDismiss = { showInertiaSettings = false })

    if (showDeleteDialog && currentItem != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除此项？") },
            text = { Text("此操作不可撤销，将从设备中永久删除。") },
            confirmButton = {
                Button(
                    onClick = {
                        isDeleting = true; showDeleteDialog = false
                        try { context.contentResolver.delete(currentItem.uri, null, null) } catch (_: Exception) {}
                        onDeleted()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Scaffold（TopAppBar 在 PiP 时隐藏，使 PlayerView 充满屏幕）──
        Scaffold(
            topBar = {
                if (!pipOverlayHidden) {
                    TopAppBar(
                        title = {},
                        navigationIcon = { TextButton(onClick = onBackClick) { Text("← 返回", color = Color.White) } },
                        actions = {
                            if (currentItem != null) {
                                TextButton(onClick = { showDeleteDialog = true }, enabled = !isDeleting) {
                                    Text("删除", color = if (isDeleting) Color.Gray else Color(0xFFEF5350), style = MaterialTheme.typography.labelLarge)
                                }
                            }
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
                val imageWeight by animateFloatAsState(
                    targetValue = if (isInfoShown) 0.5f else 1f,
                    animationSpec = if (isInfoShown) tween(180) else snap()
                )
                Box(
                    modifier = Modifier
                        .weight(imageWeight)
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
            // ── 图片信息卡片（上划展开，推起图片）──
            AnimatedVisibility(
                visible = showInfo && currentItem != null && !currentItem.isVideo,
                enter = expandVertically(expandFrom = Alignment.Bottom, animationSpec = tween(200)) + fadeIn(animationSpec = tween(200)),
                exit = shrinkVertically(shrinkTowards = Alignment.Bottom, animationSpec = tween(150)) + fadeOut(animationSpec = tween(150))
            ) {
                if (currentItem != null) {
                    Box(Modifier.pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            if (dragAmount > 0) showInfo = false
                        }
                    }) {
                        InfoCard(currentItem, onDismiss = { showInfo = false })
                    }
                }
            }
            }
        }
        // ── 调试/设置按钮（PiP 时隐藏）──
        if (!pipOverlayHidden) {
            DevOverlay(modifier = Modifier.align(Alignment.TopStart).padding(top = 60.dp, start = 8.dp))
            // ── 惯性设置齿轮按钮（TopEnd，橙色，不与 DevOverlay 重叠）──
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
//  图片信息卡片
// ══════════════════════════════════════

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024f)
    bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024f * 1024f))
    else -> "%.2f GB".format(bytes / (1024f * 1024f * 1024f))
}

private fun formatDate(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp * 1000L))
}

private val infoIconColor = Color(0xFFBBBBBB)

@Composable
private fun InfoCard(item: com.example.rcgallery.model.MediaItem, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF1A1A1A),
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp).verticalScroll(rememberScrollState())) {
            InfoRow(icon = "📄", label = "文件名", value = item.fileName)
            Spacer(Modifier.height(12.dp))
            InfoRow(icon = "⏰", label = "创建时间", value = formatDate(item.dateAdded))
            Spacer(Modifier.height(12.dp))
            InfoRow(icon = "💾", label = "文件大小", value = formatFileSize(item.size))
            Spacer(Modifier.height(12.dp))
            InfoRow(icon = "📋", label = "格式", value = item.mimeType)
            Spacer(Modifier.height(12.dp))
            InfoRow(icon = "📂", label = "所在位置", value = item.filePath.ifEmpty { "未知" })
            Spacer(Modifier.height(12.dp))
            InfoRow(icon = "🗂", label = "所在相册", value = item.albumName ?: "未知")
        }
    }
}

@Composable
private fun InfoRow(icon: String, label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(icon, fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
        Column {
            Text(label, color = Color.Gray, fontSize = 11.sp)
            Text(value, color = Color.White, fontSize = 13.sp, lineHeight = 16.sp)
        }
    }
}
