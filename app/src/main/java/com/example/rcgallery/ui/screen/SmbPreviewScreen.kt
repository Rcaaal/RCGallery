package com.example.rcgallery.ui.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.rcgallery.PipState
import com.example.rcgallery.data.smb.SmbDataSource
import com.example.rcgallery.data.smb.SmbFileInfo
import com.example.rcgallery.data.smb.SmbRepository
import com.example.rcgallery.data.smb.SmbThumbnailLoader
import com.example.rcgallery.ui.component.InertiaSettings
import com.example.rcgallery.ui.component.AutoFocusRenameTextField
import com.example.rcgallery.ui.component.SystemVolumeSliderOverlay
import com.example.rcgallery.ui.component.VideoPlayer
import com.example.rcgallery.viewmodel.PlaybackSettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedInputStream
import kotlin.math.absoluteValue
import kotlin.math.min

private const val TAG = "SMB-PREVIEW"

/**
 * SMB 全屏预览 — 滑动翻页 + 图片缩放 + 视频 PiP。
 *
 * 架构参照 [PreviewScreen]，但数据源为 SMB 文件。
 *
 * ### 功能
 * - HorizontalPager 左右滑动切换文件
 * - 图片：双指缩放 / 双击放大 / 边缘滑动翻页 / 下滑返回
 * - 视频：ExoPlayer + SmbDataSource 直连流式播放 + 小窗 (PiP)
 *
 * @param initialIndex 初始显示的索引
 * @param items SMB 媒体文件列表
 * @param onDismiss 关闭预览回调
 */
@Composable
fun SmbPreviewScreen(
    initialIndex: Int = 0,
    items: List<SmbFileInfo> = emptyList(),
    onDismiss: () -> Unit = {},
    /** 文件改名后通知父级同步刷新数据（oldPath, newPath, newName）。 */
    onFileRenamed: (oldPath: String, newPath: String, newName: String) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity

    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    val safeIndex = initialIndex.coerceIn(0, items.lastIndex)

    // ── 可变列表（支持删除）──
    var mutableItems by remember { mutableStateOf(items) }

    val pagerState = rememberPagerState(pageCount = { mutableItems.size }, initialPage = safeIndex)
    var pagerScrollEnabled by remember { mutableStateOf(true) }
    val savedPositions = remember { mutableMapOf<Uri, Long>() }

    // ── 删除对话框状态 ──
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // ── 文件改名状态 ──
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var isRenaming by remember { mutableStateOf(false) }

    // ── PiP ──
    var pipOverlayHidden by remember { mutableStateOf(false) }
    var pipTriggered by remember { mutableStateOf(false) }
    val playbackSettingsVM: PlaybackSettingsViewModel = viewModel(activity)
    val volumeState by playbackSettingsVM.volumeState.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()

    // ── VideoPlayer 控制栏显隐状态（控制右侧音量滑条显隐）──
    var controllerVisible by remember { mutableStateOf(false) }

    // 通知 MainActivity 隐藏/显示底部导航栏
    LaunchedEffect(Unit) { PipState.isSmbPreviewActive = true }
    DisposableEffect(Unit) { onDispose { PipState.isSmbPreviewActive = false } }

    val activeFile = mutableItems.getOrNull(pagerState.currentPage)
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleVideoActive by rememberUpdatedState(activeFile?.isVideo == true)
    val lifecyclePipProtected by rememberUpdatedState(
        PipState.isInPip || pipOverlayHidden || pipTriggered
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    if (lifecycleVideoActive && !lifecyclePipProtected) {
                        playbackSettingsVM.muteSystemOnEnter()
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    if (!lifecyclePipProtected) playbackSettingsVM.restoreSystemVolume()
                }
                Lifecycle.Event.ON_DESTROY -> playbackSettingsVM.restoreSystemVolume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (!lifecyclePipProtected) playbackSettingsVM.restoreSystemVolume()
        }
    }

    // ── 翻页音量同步：视频→静音，图片→恢复
    LaunchedEffect(pagerState.currentPage) {
        val item = mutableItems.getOrNull(pagerState.currentPage)
        if (item?.isVideo == true) {
            playbackSettingsVM.muteSystemOnEnter()
        } else {
            playbackSettingsVM.restoreSystemVolume()
        }
    }

    if (pipTriggered) {
        LaunchedEffect(Unit) {
            pipOverlayHidden = true
            withFrameNanos { }
            delay(300)
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
            }
            val entered = activity.enterPictureInPictureMode(PipState.buildPipParams())
            if (!entered) pipOverlayHidden = false
            pipTriggered = false
        }
    }

    LaunchedEffect(PipState.isInPip) {
        if (!PipState.isInPip && pipOverlayHidden) {
            pipOverlayHidden = false
        }
    }

    // ── 系统侧边返回（PiP 时禁用，防止误触关闭预览）──
    BackHandler(enabled = !pipOverlayHidden) { onDismiss() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // ── HorizontalPager（先声明，视觉在底层）──
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = pagerScrollEnabled,
            beyondViewportPageCount = 0,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 0.dp
        ) { page ->
            val file = mutableItems.getOrNull(page) ?: return@HorizontalPager
            key(file.path) {
                if (file.isVideo) {
                    VideoPlayer(
                        uri = Uri.parse(file.path),
                        isActive = page == pagerState.currentPage,
                        volumeLevel = volumeState.level,
                        savedPositions = savedPositions,
                        onToggleMute = { playbackSettingsVM.toggleMute() },
                        onControllerVisibilityChanged = { controllerVisible = it },
                        onRequestPip = { pipTriggered = true },
                        hideUiOverlays = pipOverlayHidden,
                        dataSourceFactory = SmbDataSource.Factory(),
                        onMoveToTrash = { showDeleteConfirm = true }
                    )
                } else {
                    SmbZoomableImage(
                        fileInfo = file,
                        onEdgeSwipe = { direction ->
                            val np = pagerState.currentPage + direction
                            if (np < 0 || np >= mutableItems.size) {
                                Toast.makeText(context,
                                    if (np < 0) "已经是第一张" else "已经是最后一张",
                                    Toast.LENGTH_SHORT).show()
                            } else {
                                scope.launch {
                                    pagerState.animateScrollToPage(np)
                                }
                            }
                        },
                        onSwipeDownToBack = { onDismiss() },
                        onSingleTap = {}
                    )
                }
            }
        }

        // ── 顶部导航栏（后声明，触摸优先级高于 HorizontalPager 内的 AndroidView）──
        if (!pipOverlayHidden) {
            TextButton(
                onClick = {
                    playbackSettingsVM.restoreSystemVolume()
                    onDismiss()
                },
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
            ) { Text("← 返回", color = Color.White) }

            val currentFile = mutableItems.getOrNull(pagerState.currentPage)
            Column(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "${pagerState.currentPage + 1}/${mutableItems.size}",
                    color = Color.White,
                    fontSize = 14.sp
                )
                if (currentFile != null) {
                    Text(
                        currentFile.name,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(horizontal = 48.dp)
                            .clickable {
                                val dotIdx = currentFile.name.lastIndexOf('.')
                                renameText = if (dotIdx > 0) currentFile.name.substring(0, dotIdx) else currentFile.name
                                showRenameDialog = true
                            }
                    )
                }
            }
        }

        // ── 右侧音量滑条（公共组件，同 PreviewScreen 保持一致）──
        SystemVolumeSliderOverlay(
            visible = controllerVisible,
            pipOverlayHidden = pipOverlayHidden,
            isActiveVideo = mutableItems.getOrNull(pagerState.currentPage)?.isVideo == true,
            volumeLevel = volumeState.level,
            playbackSettingsVM = playbackSettingsVM,
            modifier = Modifier.align(Alignment.CenterEnd)
        )

        // ── 删除确认对话框 ──
        if (showDeleteConfirm) {
            val currentFile = mutableItems.getOrNull(pagerState.currentPage)
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("确认删除") },
                text = { Text("确定要永久删除「${currentFile?.name ?: "文件"}」吗？\n此操作不可恢复。") },
                confirmButton = {
                    Button(
                        onClick = {
                            showDeleteConfirm = false
                            val file = currentFile ?: return@Button
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    SmbRepository.getInstance().deleteFile(file.path)
                                }
                                result.onSuccess {
                                    val idx = mutableItems.indexOfFirst { it.path == file.path }
                                    if (idx < 0) return@launch
                                    val newList = mutableItems.toMutableList().apply { removeAt(idx) }
                                    mutableItems = newList
                                    Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                                    if (newList.isEmpty()) onDismiss()
                                }.onFailure { e ->
                                    Toast.makeText(context, "删除失败: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("删除") }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
                }
            )
        }

        // ── 文件改名对话框 ──
        if (showRenameDialog) {
            val currentFile = mutableItems.getOrNull(pagerState.currentPage)
            val dotIdx = currentFile?.name?.lastIndexOf('.') ?: -1
            val extOnly = if (dotIdx > 0) currentFile!!.name.substring(dotIdx) else ""
            fun confirmSmbRename() {
                val file = currentFile ?: return
                val newName = renameText.trim()
                if (newName.isEmpty() || isRenaming) return
                isRenaming = true
                scope.launch {
                    val oldPath = file.path
                    val dirEnd = oldPath.lastIndexOf('/') + 1
                    val newPath = oldPath.substring(0, dirEnd) + newName + extOnly
                    val oldUri = Uri.parse(oldPath)
                    val savedPos = savedPositions[oldUri] ?: 0L

                    val result = withContext(Dispatchers.IO) {
                        SmbRepository.getInstance().renameFile(oldPath, newPath)
                    }
                    result.onSuccess {
                        val idx = mutableItems.indexOfFirst { it.path == oldPath }
                        if (idx >= 0) {
                            if (savedPos > 1000) savedPositions[Uri.parse(newPath)] = savedPos
                            mutableItems = mutableItems.toMutableList().apply {
                                set(idx, file.copy(name = newName + extOnly, path = newPath))
                            }
                        }
                        showRenameDialog = false
                        Toast.makeText(context, "已重命名", Toast.LENGTH_SHORT).show()
                        onFileRenamed(oldPath, newPath, newName + extOnly)
                    }.onFailure { e ->
                        Toast.makeText(context, "重命名失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    isRenaming = false
                }
            }
            AlertDialog(
                onDismissRequest = { if (!isRenaming) showRenameDialog = false },
                title = { Text("重命名文件") },
                text = {
                    AutoFocusRenameTextField(
                        initialText = renameText,
                        onValueChange = { if (!isRenaming) renameText = it },
                        onDone = { confirmSmbRename() },
                        label = "文件名",
                        suffix = extOnly,
                        enabled = !isRenaming,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { confirmSmbRename() },
                        enabled = !isRenaming
                    ) { Text(if (isRenaming) "重命名中..." else "确认") }
                },
                dismissButton = {
                    TextButton(onClick = { if (!isRenaming) showRenameDialog = false }) { Text("取消") }
                }
            )
        }
    }
}
// ══════════════════════════════════════

private const val THUMB_MAX_PX = 400
private const val TIMEOUT_MS = 8_000L

private data class SmbInertiaParams(
    val targetX: Float,
    val targetY: Float,
    val durationMs: Int
)

/**
 * 计算 FIT_CENTER 的实际渲染尺寸和偏移。
 */
private data class SmbFitRender(
    val renderedWidth: Float,
    val renderedHeight: Float,
    val offsetX: Float,
    val offsetY: Float
)

private fun computeFitRender(boxSize: IntSize, intrinsicWidth: Float, intrinsicHeight: Float): SmbFitRender {
    if (intrinsicWidth <= 0f || intrinsicHeight <= 0f) {
        return SmbFitRender(boxSize.width.toFloat(), boxSize.height.toFloat(), 0f, 0f)
    }
    val fitScale = min(boxSize.width / intrinsicWidth, boxSize.height / intrinsicHeight)
    val rw = intrinsicWidth * fitScale
    val rh = intrinsicHeight * fitScale
    return SmbFitRender(rw, rh, (boxSize.width - rw) / 2f, (boxSize.height - rh) / 2f)
}

private fun maxScrollX(viewW: Float, fit: SmbFitRender, scale: Float): Float {
    return ((viewW * (scale - 1f) / 2f) - (fit.offsetX * scale)).coerceAtLeast(0f)
}

private fun maxScrollY(viewH: Float, fit: SmbFitRender, scale: Float): Float {
    return ((viewH * (scale - 1f) / 2f) - (fit.offsetY * scale)).coerceAtLeast(0f)
}

@Composable
private fun SmbZoomableImage(
    fileInfo: SmbFileInfo,
    onEdgeSwipe: (direction: Int) -> Unit,
    onSwipeDownToBack: () -> Unit = {},
    onSingleTap: () -> Unit = {}
) {
    // ── 图片加载状态 ──
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var intrinsicSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    var isError by remember { mutableStateOf(false) }

    // ── 缩放状态 ──
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var inertiaState by remember { mutableStateOf<SmbInertiaParams?>(null) }

    // ── rememberUpdatedState 确保手势回调不捕获陈旧值 ──
    val currentOnEdgeSwipe by rememberUpdatedState(onEdgeSwipe)
    val currentOnSwipeDownToBack by rememberUpdatedState(onSwipeDownToBack)
    val currentOnSingleTap by rememberUpdatedState(onSingleTap)

    // ── 惯性动画 ──
    val animSpec = inertiaState?.let { state ->
        tween<Float>(durationMillis = state.durationMs, easing = LinearEasing)
    } ?: snap()
    val renderOffsetX by animateFloatAsState(
        targetValue = inertiaState?.targetX ?: offsetX,
        animationSpec = animSpec, label = "panX"
    )
    val renderOffsetY by animateFloatAsState(
        targetValue = inertiaState?.targetY ?: offsetY,
        animationSpec = animSpec, label = "panY"
    )

    // ── 加载图片（预览缓存 → 磁盘缓存 → SMB 流式解码）──
    LaunchedEffect(fileInfo.path) {
        // ① 预览缓存（0ms）
        val previewCache = SmbThumbnailLoader.getPreviewCacheBitmap(fileInfo.path)
        if (previewCache != null) {
            bitmap = previewCache
            if (previewCache.width > 0 && previewCache.height > 0) {
                intrinsicSize = androidx.compose.ui.geometry.Size(
                    previewCache.width.toFloat(), previewCache.height.toFloat()
                )
            }
            return@LaunchedEffect
        }

        // ② 缩略图磁盘缓存作为占位
        val diskCache = SmbThumbnailLoader.getDiskCacheBitmap(fileInfo.path)
        if (diskCache != null) {
            bitmap = diskCache
        }

        // ③ SMB 流式解码
        try {
            withTimeout(TIMEOUT_MS) {
                val repo = SmbRepository.getInstance()
                val streamResult = repo.getInputStream(fileInfo.path)
                val stream = streamResult.getOrNull() ?: return@withTimeout
                try {
                    val bm = withContext(Dispatchers.IO) {
                        val buffered = BufferedInputStream(stream, 512 * 1024)
                        buffered.mark(512 * 1024)
                        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeStream(buffered, null, boundsOpts)
                        buffered.reset()

                        var sample = 1
                        if (boundsOpts.outWidth > 0 && boundsOpts.outHeight > 0) {
                            while (boundsOpts.outWidth / sample > 1920 || boundsOpts.outHeight / sample > 1920) {
                                sample *= 2
                            }
                        }
                        val opts = BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        BitmapFactory.decodeStream(buffered, null, opts)
                    }
                    if (bm != null) {
                        SmbThumbnailLoader.savePreviewCache(fileInfo.path, bm)
                        if (bm.width > 0 && bm.height > 0) {
                            intrinsicSize = androidx.compose.ui.geometry.Size(
                                bm.width.toFloat(), bm.height.toFloat()
                            )
                        }
                        bitmap = bm
                    } else if (diskCache == null) {
                        isError = true
                    }
                } finally {
                    try { stream.close() } catch (_: Exception) { }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            if (bitmap == null) isError = true
        }
    }

    // ── 手势 + 渲染 ──
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(intrinsicSize) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downX = down.position.x; val downY = down.position.y
                    val now = System.currentTimeMillis()

                    // 截停惯性
                    if (inertiaState != null) {
                        offsetX = renderOffsetX; offsetY = renderOffsetY
                        inertiaState = null; lastTapTime = 0L
                    }

                    // 双击检测
                    if (now - lastTapTime < 300L) {
                        lastTapTime = 0L
                        if (scale > 1.5f) {
                            scale = 1f; offsetX = 0f; offsetY = 0f
                        } else {
                            val newScale = 2.5f
                            scale = newScale
                            val fit = computeFitRender(size, intrinsicSize.width, intrinsicSize.height)
                            val mx = maxScrollX(size.width.toFloat(), fit, newScale)
                            val my = maxScrollY(size.height.toFloat(), fit, newScale)
                            offsetX = ((size.width / 2f - downX) * (newScale - 1f) / newScale).coerceIn(-mx, mx)
                            offsetY = ((size.height / 2f - downY) * (newScale - 1f) / newScale).coerceIn(-my, my)
                        }
                        // consume subsequent events
                        do {
                            val e = awaitPointerEvent(PointerEventPass.Main)
                            e.changes.forEach { it.consume() }
                        } while (e.changes.any { it.pressed })
                        return@awaitEachGesture
                    }

                    var didEdgeSwipe = false
                    var smoothX = 0f; var smoothY = 0f
                    var frameCount = 0; var hadMovement = false
                    var verticalDominant = false

                    do {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val count = event.changes.size
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val zoomed = scale > 1f

                        if (count >= 2) {
                            val ns = (scale * zoom).coerceIn(1f, 5f)
                            scale = ns
                            if (ns <= 1f) {
                                offsetX = 0f; offsetY = 0f
                            } else {
                                val fit = computeFitRender(size, intrinsicSize.width, intrinsicSize.height)
                                val mx = maxScrollX(size.width.toFloat(), fit, ns)
                                val my = maxScrollY(size.height.toFloat(), fit, ns)
                                offsetX = offsetX.coerceIn(-mx, mx)
                                offsetY = offsetY.coerceIn(-my, my)
                            }
                            event.changes.forEach { it.consume() }
                            frameCount = 0; hadMovement = true
                        } else if (zoomed) {
                            val fit = computeFitRender(size, intrinsicSize.width, intrinsicSize.height)
                            val maxX = maxScrollX(size.width.toFloat(), fit, scale)
                            val maxY = maxScrollY(size.height.toFloat(), fit, scale)

                            if (offsetX >= maxX - 3f && pan.x > InertiaSettings.edgeSwipeMinPx
                                && pan.x.absoluteValue >= pan.y.absoluteValue * 1.5f) {
                                didEdgeSwipe = true; currentOnEdgeSwipe(-1); break
                            } else if (offsetX <= -maxX + 3f && pan.x < -InertiaSettings.edgeSwipeMinPx
                                && pan.x.absoluteValue >= pan.y.absoluteValue * 1.5f) {
                                didEdgeSwipe = true; currentOnEdgeSwipe(1); break
                            } else {
                                offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                                offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                                smoothX = smoothX * 0.5f + pan.x * 0.5f
                                smoothY = smoothY * 0.5f + pan.y * 0.5f
                                frameCount++
                                if (pan.x != 0f || pan.y != 0f) hadMovement = true
                                event.changes.forEach { it.consume() }
                            }
                        } else {
                            smoothX = smoothX * 0.5f + pan.x * 0.5f
                            smoothY = smoothY * 0.5f + pan.y * 0.5f
                            frameCount++
                            if (pan.x != 0f || pan.y != 0f) hadMovement = true
                            if (frameCount >= 3 && smoothY.absoluteValue >= smoothX.absoluteValue * 1.5f) {
                                verticalDominant = true
                            }
                            if (verticalDominant) {
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    // 下滑返回
                    if (scale <= 1f && frameCount > 0
                        && smoothY > InertiaSettings.swipeVelocityThreshold
                        && smoothY.absoluteValue >= smoothX.absoluteValue * 1.5f) {
                        currentOnSwipeDownToBack()
                        return@awaitEachGesture
                    }

                    // 单击
                    if (!hadMovement) {
                        lastTapTime = now
                        currentOnSingleTap()
                    } else {
                        lastTapTime = 0L
                    }

                    // 惯性
                    if (scale > 1f && !didEdgeSwipe && frameCount > 0) {
                        val fit = computeFitRender(size, intrinsicSize.width, intrinsicSize.height)
                        val maxX = maxScrollX(size.width.toFloat(), fit, scale)
                        val maxY = maxScrollY(size.height.toFloat(), fit, scale)
                        val targetX = (offsetX + smoothX * InertiaSettings.speedMultiplierX / InertiaSettings.decay)
                            .coerceIn(-maxX, maxX)
                        val targetY = (offsetY + smoothY * InertiaSettings.speedMultiplierY / InertiaSettings.decay)
                            .coerceIn(-maxY, maxY)
                        val maxDist = maxOf((targetX - offsetX).absoluteValue, (targetY - offsetY).absoluteValue)
                        if (maxDist >= 2f) {
                            val dur = (maxDist * InertiaSettings.durationMultiplierX).coerceIn(15f, 500f).toInt()
                            inertiaState = SmbInertiaParams(targetX, targetY, dur)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            bitmap != null -> {
                AndroidView(
                    factory = { ctx ->
                        ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                    },
                    update = { iv -> iv.setImageBitmap(bitmap) },
                    modifier = Modifier.fillMaxSize().graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = renderOffsetX, translationY = renderOffsetY
                    )
                )
            }
            isError -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("无法加载此图片", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { }) { Text("返回") }
                }
            }
            else -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Text("加载中...", color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
