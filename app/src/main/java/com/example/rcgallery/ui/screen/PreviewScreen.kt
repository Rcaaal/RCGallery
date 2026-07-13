package com.example.rcgallery.ui.screen

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rcgallery.PipState
import com.example.rcgallery.data.db.TagEntity
import com.example.rcgallery.ui.component.InertiaSettings
import com.example.rcgallery.ui.component.SettingsOverlay
import com.example.rcgallery.ui.component.SystemVolumeSliderOverlay
import com.example.rcgallery.ui.component.TagManageDialog
import com.example.rcgallery.ui.component.VideoPlayer
import com.example.rcgallery.ui.component.AlbumPickDialog
import com.example.rcgallery.viewmodel.PasteMode
import com.example.rcgallery.viewmodel.PlaybackSettingsViewModel
import com.example.rcgallery.util.AppLogger
import com.example.rcgallery.util.FormatUtil
import com.example.rcgallery.viewmodel.GalleryViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    initialIndex: Int = 0,
    onBackClick: () -> Unit = {},
    onGoHome: () -> Unit = {},     // 直接回到 AlbumGrid 主页
    items: List<com.example.rcgallery.model.MediaItem> = emptyList(),  // 由 MediaGridScreen 传入快照，不从 ViewModel 收集（防异步 loadMedia 替换导致 index 错位）
    albumId: String = ""  // 来源相册 ID，MOVE 后用于刷新源相册媒体列表
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val viewModel: GalleryViewModel = viewModel(activity)
    val playbackSettingsVM: PlaybackSettingsViewModel = viewModel(activity)
    val volumeState by playbackSettingsVM.volumeState.collectAsStateWithLifecycle()

    // ── 进入静音：预览页进入时自动将系统媒体音量压到 0，退出时恢复 ──
    DisposableEffect(Unit) {
        playbackSettingsVM.muteSystemOnEnter()
        onDispose { playbackSettingsVM.restoreSystemVolume() }
    }

    // ── VideoPlayer 控制栏显隐状态（控制右侧音量滑条显隐）──
    var controllerVisible by remember { mutableStateOf(false) }
    // 本地可变快照，初始值来自 items 参数。删除/改名等操作直接修改此快照，
    // 不自动从父级同步（防 ContentObserver 异步替换导致 index 错位）。
    var mediaItems by remember { mutableStateOf(items) }
    val scope = rememberCoroutineScope()

    AppLogger.d("Preview", "enter initialIndex=$initialIndex items=${items.size}")

    // ── 防崩溃守卫：items 为空时不渲染任何内容，立即退出 ──
    if (mediaItems.isEmpty()) {
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

    // ── 视频全屏 seek 状态（由 PreviewScreen 全屏层处理，不再通过 VideoPlayer overlay）──
    val SEEK_ZONE_DP = 150   // 底部 seek 区域高度(dp)
    val SEEK_THROTTLE_MS = 40L
    val screenWidth = remember { context.resources.displayMetrics.widthPixels }
    var isDraggingSeek by remember { mutableStateOf(false) }
    var seekIndicatorPosition by remember { mutableLongStateOf(0L) }
    // 每页独立的回调映射（防 pager 翻页时多个 VideoPlayer 互相覆盖，手势过程状态在 pointerInput 内局部化）
    val seekToPlayer = remember { mutableMapOf<Int, (Long) -> Unit>() }
    val getPlayerPositions = remember { mutableMapOf<Int, () -> Long>() }
    val getPlayerDurations = remember { mutableMapOf<Int, () -> Long>() }
    val speedSettingsTrigger = remember { mutableStateOf<(() -> Unit)?>(null) }

    // ── PiP 覆盖层管理 ──
    var pipOverlayHidden by remember { mutableStateOf(false) }
    var pipTriggered by remember { mutableStateOf(false) }

    // ── 图片信息面板 ──
    var showInfo by remember { mutableStateOf(false) }
    // 文件重命名版本号——每改名一次 +1，用于 key() 强制 InfoCard 刷新
    var renameVersion by remember { mutableIntStateOf(0) }
    // 返回键直接退出预览（不拦截），信息面板通过点击图片或翻页关闭

    // ── 快删 Snackbar ──
    val snackbarHostState = remember { SnackbarHostState() }

    // ── 移至回收站并翻到下一个（提取为公共函数，按钮和 VideoPlayer 共用）──
    fun moveCurrentToTrash() {
        val trashItem = mediaItems.getOrNull(pagerState.currentPage) ?: return
        if (showInfo) showInfo = false
        viewModel.moveToTrash(trashItem)
        val deletedPage = pagerState.currentPage
        val wasLastPage = deletedPage >= mediaItems.lastIndex
        mediaItems = mediaItems.filterIndexed { i, _ -> i != deletedPage }
        scope.launch {
            if (!wasLastPage && mediaItems.isNotEmpty()) {
                delay(50)
                pagerState.animateScrollToPage(deletedPage.coerceAtMost(mediaItems.lastIndex))
            }
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = "已移至回收站",
                actionLabel = "撤销",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.restoreFromTrash(trashItem.uri.toString())
                viewModel.addMediaItemBack(trashItem)
                mediaItems = (listOf(trashItem) + mediaItems)
                    .sortedByDescending { it.dateAdded }
            }
            if (wasLastPage) {
                onBackClick()
            }
        }
    }

    // ── 直接永久删除（不经过回收站）──
    var showPermanentDeleteConfirm by remember { mutableStateOf(false) }
    var isPermanentDeleting by remember { mutableStateOf(false) }
    var pendingDeleteItem by remember { mutableStateOf<com.example.rcgallery.model.MediaItem?>(null) }

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

    // ── TAG 相关 ──
    val allTags by viewModel.allTags.collectAsStateWithLifecycle()
    var currentMediaTags by remember { mutableStateOf<List<TagEntity>>(emptyList()) }
    var showMediaTagDialog by remember { mutableStateOf(false) }
    var showAlbumPickDialog by remember { mutableStateOf(false) }
    var recentTagList by remember { mutableStateOf<List<TagEntity>>(emptyList()) }
    var mediaTagRefreshTrigger by remember { mutableIntStateOf(0) }
    var inheritedTagIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    LaunchedEffect(currentItem) {
        val item = currentItem
        if (item != null) {
            currentMediaTags = viewModel.getMediaTags(item.filePath)
            recentTagList = viewModel.getRecentTags()
            inheritedTagIds = viewModel.getInheritedTagIdsForMedia(item)
        } else {
            currentMediaTags = emptyList()
            inheritedTagIds = emptySet()
        }
    }
    // 每次标签增删后刷新，对话框内立即看到变化
    LaunchedEffect(mediaTagRefreshTrigger) {
        if (mediaTagRefreshTrigger > 0) {
            val item = currentItem
            if (item != null) {
                currentMediaTags = viewModel.getMediaTags(item.filePath)
                inheritedTagIds = viewModel.getInheritedTagIdsForMedia(item)
            }
        }
    }

    // ── 直接永久删除 launcher（不经过回收站，物理删除）──
    val permanentDeleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        try {
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val item = pendingDeleteItem
                if (item != null) {
                    viewModel.removeFromMediaItems(item)
                    val page = pagerState.currentPage
                    val newSize = mediaItems.size - 1
                    mediaItems = mediaItems.filterIndexed { i, _ -> i != page }
                    showInfo = false
                    pendingDeleteItem = null
                    Toast.makeText(context, "已永久删除", Toast.LENGTH_SHORT).show()
                    // 动画翻到下一页
                    if (newSize > 0) {
                        val targetPage = page.coerceIn(0, newSize - 1)
                        scope.launch {
                            delay(50)
                            pagerState.animateScrollToPage(targetPage)
                        }
                    }
                }
            } else {
                Toast.makeText(context, "删除失败：未获得授权", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            com.example.rcgallery.util.AppLogger.e("Preview", "permanent delete callback error", e)
            Toast.makeText(context, "删除操作异常", Toast.LENGTH_SHORT).show()
        } finally {
            isPermanentDeleting = false
            showPermanentDeleteConfirm = false
            pendingDeleteItem = null
        }
    }

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
        AppLogger.d("Preview", "page=${pagerState.currentPage} uri=${currentItem?.uri?.lastPathSegment} name=${currentItem?.fileName} size=${mediaItems.size}")
        currentItem?.let { viewModel.recordViewHistory(it) }
    }

    // ── 相册重命名 ──
    var showAlbumRenameDialog by remember { mutableStateOf(false) }

    // 存储管理权限 launcher（跳转系统设置页授权）
    val manageStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // 从设置页返回，提示用户重新改名
        Toast.makeText(context, "授权成功，请重新点击相册名进行改名", Toast.LENGTH_SHORT).show()
    }

    // ── 相册重命名对话框 ──
    if (showAlbumRenameDialog && currentItem != null) {
        val currentAlbumName = currentItem?.albumName ?: "未知"
        var editText by remember { mutableStateOf(currentAlbumName) }
        AlertDialog(
            onDismissRequest = { showAlbumRenameDialog = false },
            title = { Text("重命名相册") },
            text = {
                Column {
                    val hasManageStorage = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
                    val hint = if (hasManageStorage) "相册名立即更改，文件夹同时重命名。"
                               else "首次改名需授权，将跳转至系统设置开启权限。"
                    Text(hint, color = Color(0xFF999999), fontSize = 13.sp)
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
                    if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) {
                        // 有权限 → 直接改名
                        viewModel.renameNow(bucketId, newName) { ok ->
                            if (ok) {
                                // 同步本地快照中的 albumName
                                mediaItems = mediaItems.map { item ->
                                    if (item.albumId == bucketId) item.copy(albumName = newName) else item
                                }
                                Toast.makeText(context, "相册已重命名", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "重命名失败，请重试", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        // 无权限 → 跳转系统设置
                        try {
                            manageStorageLauncher.launch(
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                            )
                        } catch (e: Exception) {
                            Toast.makeText(context, "无法跳转授权页面", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { showAlbumRenameDialog = false }) { Text("取消") } }
        )
    }

    // ── 直接永久删除确认对话框 ──
    if (showPermanentDeleteConfirm && currentItem != null) {
        val item = currentItem!!
        AlertDialog(
            onDismissRequest = { if (!isPermanentDeleting) showPermanentDeleteConfirm = false },
            title = { Text("永久删除") },
            text = {
                Column {
                    Text("文件「${item.fileName}」将被从设备中彻底删除，无法恢复。\n确定要继续吗？")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "不经过回收站，直接删除",
                        color = Color(0xFFFF5252),
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isPermanentDeleting) return@Button
                        isPermanentDeleting = true
                        pendingDeleteItem = item
                        showPermanentDeleteConfirm = false
                        // API 30+ 用 createDeleteRequest，低版本直接 contentResolver.delete
                        if (Build.VERSION.SDK_INT >= 30) {
                            try {
                                val uri = item.uri
                                if (uri.toString().isBlank()) {
                                    Toast.makeText(context, "无效的文件 URI", Toast.LENGTH_SHORT).show()
                                    isPermanentDeleting = false; pendingDeleteItem = null; return@Button
                                }
                                val pending = MediaStore.createDeleteRequest(
                                    context.contentResolver, listOf(uri)
                                )
                                permanentDeleteLauncher.launch(
                                    IntentSenderRequest.Builder(pending.intentSender).build()
                                )
                            } catch (e: Exception) {
                                Toast.makeText(context, "请求删除授权失败", Toast.LENGTH_SHORT).show()
                                isPermanentDeleting = false; pendingDeleteItem = null
                            }
                        } else {
                            try {
                                context.contentResolver.delete(item.uri, null, null)
                                viewModel.removeFromMediaItems(item)
                                val page = pagerState.currentPage
                                val newSize = mediaItems.size - 1
                                mediaItems = mediaItems.filterIndexed { i, _ -> i != page }
                                showInfo = false
                                Toast.makeText(context, "已永久删除", Toast.LENGTH_SHORT).show()
                                if (newSize > 0) {
                                    scope.launch {
                                        delay(50)
                                        pagerState.animateScrollToPage(page.coerceIn(0, newSize - 1))
                                    }
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "删除失败", Toast.LENGTH_SHORT).show()
                            }
                            isPermanentDeleting = false; pendingDeleteItem = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { if (!isPermanentDeleting) showPermanentDeleteConfirm = false }) { Text("取消") } }
        )
    }

    // ── 设置面板 / 日志（复用组件 SettingsOverlay）──

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Scaffold（TopAppBar 在 PiP 时隐藏，使 PlayerView 充满屏幕）──
        Scaffold(
            topBar = {
                if (!pipOverlayHidden) {
                    TopAppBar(
                        title = {},
                        navigationIcon = {
                            TextButton(onClick = {
                                playbackSettingsVM.restoreSystemVolume()
                                onBackClick()
                            }) { Text("← 返回", color = Color.White) }
                        },
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.3f))
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = Color.Black
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                // ── 图片/视频区域（信息面板展开时被推到上半部分）──
                val isInfoShown = showInfo && currentItem != null
                Box(
                    modifier = Modifier
                        .weight(
                            if (isInfoShown && currentItem?.isVideo == true) 0.65f
                            else if (isInfoShown) 0.7f
                            else 1f
                        )
                        .fillMaxWidth()
                        .nestedScroll(overscrollConnection)
                ) {
                    HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = pagerScrollEnabled,
                        beyondViewportPageCount = 0,
                        modifier = Modifier.fillMaxSize(),
                        pageSpacing = 0.dp
                    ) { page ->
                        val item = mediaItems.getOrNull(page)
                        key(item?.uri ?: page) {
                        if (item != null) {
                            if (item.isVideo) {
                                VideoPlayer(
                                    uri = item.uri,
                                    isActive = page == pagerState.currentPage,
                                        volumeLevel = volumeState.level,
                                    onToggleMute = { playbackSettingsVM.toggleMute() },
                                    onControllerVisibilityChanged = { controllerVisible = it },
                                    savedPositions = savedPositions,
                                    onRegisterSeekHandler = { fn -> seekToPlayer[page] = fn },
                                    onRegisterPositionProvider = { fn -> getPlayerPositions[page] = fn },
                                    onRegisterDurationProvider = { fn -> getPlayerDurations[page] = fn },
                                    onRegisterSpeedSettingsTrigger = { fn -> speedSettingsTrigger.value = fn },
                                    onRequestPip = { pipTriggered = true },
                                    hideUiOverlays = pipOverlayHidden,
                                    keepControllerVisible = showInfo && item.isVideo,
                                    onShowInfoClick = { showInfo = true },
                                    onMoveToTrash = { moveCurrentToTrash() }
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
                                    onSwipeUpToShowInfo = {
                                        if (showInfo) {
                                            // 信息栏已展开 → 上划快删
                                            val item = currentItem ?: return@ZoomableImage3
                                            showInfo = false  // 关闭信息栏，防快速连击
                                            viewModel.moveToTrash(item)
                                            val deletedPage = pagerState.currentPage
                                            val wasLastPage = deletedPage >= mediaItems.lastIndex
                                            mediaItems = mediaItems.filterIndexed { i, _ -> i != deletedPage }
                                            scope.launch {
                                                // 先翻到下一页
                                                if (!wasLastPage && mediaItems.isNotEmpty()) {
                                                    delay(50)
                                                    pagerState.animateScrollToPage(deletedPage.coerceAtMost(mediaItems.lastIndex))
                                                }
                                                // 再显示 Snackbar
                                                snackbarHostState.currentSnackbarData?.dismiss()
                                                val result = snackbarHostState.showSnackbar(
                                                    message = "已移至回收站",
                                                    actionLabel = "撤销",
                                                    duration = SnackbarDuration.Short
                                                )
                                                if (result == SnackbarResult.ActionPerformed) {
                                                    // 撤销：清索引 + 加回列表（增量，不走 loadMedia）
                                                    viewModel.restoreFromTrash(item.uri.toString())
                                                    viewModel.addMediaItemBack(item)
                                                    // 撤销后恢复本地快照
                                                    mediaItems = (listOf(item) + mediaItems)
                                                        .sortedByDescending { it.dateAdded }
                                                }
                                                // Snackbar 结束后再退出（保留 coroutine scope 给撤销用）
                                                if (wasLastPage) {
                                                    onBackClick()
                                                }
                                            }
                                        } else {
                                            showInfo = true
                                        }
                                    },
                                    onSingleTap = { if (showInfo) showInfo = false }
                                )
                            }
                        }
                    // 页面离开时清理回调映射，防残留
                    DisposableEffect(page) {
                        onDispose {
                            seekToPlayer.remove(page)
                            getPlayerPositions.remove(page)
                            getPlayerDurations.remove(page)
                        }
                    }
                    }   // ← key(uri) end
                }   // ← page lambda end
            }   // ← Box weight end
            // ── 信息卡片（展开时插入 Column，weight 占比例）──
            if (isInfoShown) {
                if (currentItem?.isVideo == true) {
                    // ── 视频信息卡片：0.65/0.05/0.3 分区 ──
                    // 关闭按钮横排 0.05（左对齐）
                    Box(
                        modifier = Modifier
                            .weight(0.05f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(com.example.rcgallery.R.drawable.ic_close),
                            contentDescription = "关闭信息",
                            tint = Color.White,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(20.dp)
                                .clickable { showInfo = false }
                        )
                    }
                    // InfoCard 0.3
                    Box(
                        modifier = Modifier
                            .weight(0.3f)
                            .fillMaxWidth()
                    ) {
                        key(renameVersion) {
                        InfoCard(
                            currentItem!!,
                            onDismiss = { showInfo = false },
                            albumDisplayName = currentItem?.albumName ?: "未知",
                            onAlbumNameClick = { showAlbumRenameDialog = true },
                            onDeleteClick = { showPermanentDeleteConfirm = true },
                            onMoveToAlbum = { showAlbumPickDialog = true },
                            onFileRenamed = { newFileName ->
                                val pageIdx = pagerState.currentPage
                                val targetUri = mediaItems.getOrNull(pageIdx)?.uri?.toString()
                                mediaItems = mediaItems.mapIndexed { i, item ->
                                    if (i == pageIdx) {
                                        val newPath = item.filePath.substringBeforeLast("/") + "/" + newFileName
                                        item.copy(fileName = newFileName, filePath = newPath)
                                    } else {
                                        item
                                    }
                                }
                                renameVersion++
                                if (targetUri != null) viewModel.renameFile(targetUri, newFileName)
                            },
                            mediaTags = currentMediaTags,
                            onManageTags = { showMediaTagDialog = true }
                        )
                        }
                    }
                } else {
                    // ── 图片信息卡片：0.3 + 无额外装饰（#249 前布局）──
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
                        key(renameVersion) {
                        InfoCard(
                            currentItem!!,
                            onDismiss = { showInfo = false },
                            albumDisplayName = currentItem?.albumName ?: "未知",
                            onAlbumNameClick = { showAlbumRenameDialog = true },
                            onDeleteClick = { showPermanentDeleteConfirm = true },
                            onMoveToAlbum = { showAlbumPickDialog = true },
                            onFileRenamed = { newFileName ->
                                val pageIdx = pagerState.currentPage
                                val targetUri = mediaItems.getOrNull(pageIdx)?.uri?.toString()
                                mediaItems = mediaItems.mapIndexed { i, item ->
                                    if (i == pageIdx) {
                                        val newPath = item.filePath.substringBeforeLast("/") + "/" + newFileName
                                        item.copy(fileName = newFileName, filePath = newPath)
                                    } else {
                                        item
                                    }
                                }
                                renameVersion++
                                if (targetUri != null) viewModel.renameFile(targetUri, newFileName)
                            },
                            mediaTags = currentMediaTags,
                            onManageTags = { showMediaTagDialog = true }
                        )
                        }
                    }
                }
            }
            }
        }

        // ── 视频底部 seek 区（全屏层，仅视频活跃时生效，Compose pointerInput 精确消费）──
        // 手势跟踪状态在 pointerInput 内部局部化，不触发顶层重组
        val isVideoSeekActive = currentItem?.isVideo == true && pagerState.currentPage == mediaItems.indexOf(currentItem)
        if (isVideoSeekActive) {
            val haptic = LocalHapticFeedback.current
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SEEK_ZONE_DP.dp)
                    .align(Alignment.BottomCenter)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val curPage = pagerState.currentPage
                            val seekHandler = seekToPlayer[curPage]
                            val durProvider = getPlayerDurations[curPage]
                            val posProvider = getPlayerPositions[curPage]
                            if (seekHandler == null || durProvider == null || posProvider == null) {
                                AppLogger.e("Preview", "seek callbacks not ready: seek=${seekHandler != null} dur=${durProvider != null} pos=${posProvider != null}")
                                return@awaitEachGesture
                            }

                            // DOWN：读取并缓存初始值（局部变量，不触发重组）
                            val basePos = posProvider()
                            val totalDur = durProvider()
                            if (totalDur < 1000L) {
                                AppLogger.d("Preview", "seek skipped — player not ready (dur=$totalDur)")
                                return@awaitEachGesture
                            }

                            AppLogger.d("Seek", "DOWN page=$curPage uri=${currentItem?.uri?.lastPathSegment} pos=$basePos dur=$totalDur seek=${seekHandler != null} posP=${posProvider != null} durP=${durProvider != null}")

                            // 只暴露给顶层的最小状态
                            isDraggingSeek = true
                            seekIndicatorPosition = basePos
                            pagerScrollEnabled = false
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                            // 局部手势跟踪变量（不参与 Compose 状态）
                            val originRawX = down.position.x
                            var lastSeekTimeMs = 0L
                            var seekStarted = false

                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (change.pressed) {
                                    if (!seekStarted) {
                                        seekStarted = true
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    val dx = change.position.x - originRawX
                                    val np = (basePos + ((dx / screenWidth) * totalDur).toLong()).coerceIn(0, totalDur)
                                    seekIndicatorPosition = np
                                    val now = SystemClock.elapsedRealtime()
                                    if (now - lastSeekTimeMs >= SEEK_THROTTLE_MS) {
                                        seekHandler(np)
                                        lastSeekTimeMs = now
                                    }
                                    change.consume()
                                } else {
                                    // UP：跳转到最终位置 + 震动反馈
                                    AppLogger.d("Seek", "UP page=$curPage uri=${currentItem?.uri?.lastPathSegment} final=${seekIndicatorPosition} currentPage=${pagerState.currentPage}")
                                    seekHandler(seekIndicatorPosition)
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    isDraggingSeek = false
                                    pagerScrollEnabled = true
                                    break
                                }
                            } while (true)
                        }
                    }
            )
        }

        // ── 浮动时间提示（独立组件，仅订阅 isDraggingSeek + seekIndicatorPosition）──
        VideoSeekIndicator(
            visible = isDraggingSeek,
            positionMs = seekIndicatorPosition,
            totalDurationMs = getPlayerDurations[pagerState.currentPage]?.invoke() ?: 1L
        )

        // ── 左侧竖排按钮组（视频播放设置 + 删除，统一 40dp 圆形半透明白底）──
        if (!pipOverlayHidden) {
            Column(
                modifier = Modifier.align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 60.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 播放速度设置按钮
                Box(
                    modifier = Modifier.size(40.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f))
                        .clickable { speedSettingsTrigger.value?.invoke() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(com.example.rcgallery.R.drawable.ic_settings),
                        contentDescription = "播放设置",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                // 删除按钮
                Box(
                    modifier = Modifier.size(40.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f))
                        .clickable { moveCurrentToTrash() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(com.example.rcgallery.R.drawable.ic_trash),
                        contentDescription = "移至回收站",
                        tint = Color(0xFFFF5252),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // ── Debug 设置面板（橙色齿轮，右上角）──
        SettingsOverlay(gearModifier = Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 48.dp), visible = !pipOverlayHidden)

        // ── 右侧音量滑条（公共组件，同 SMB 保持一致）──
        SystemVolumeSliderOverlay(
            visible = controllerVisible,
            pipOverlayHidden = pipOverlayHidden,
            isActiveVideo = mediaItems.getOrNull(pagerState.currentPage)?.isVideo == true,
            volumeLevel = volumeState.level,
            playbackSettingsVM = playbackSettingsVM,
            modifier = Modifier.align(Alignment.CenterEnd)
        )

        // ── 媒体 TAG 管理对话框──
        if (showMediaTagDialog && currentItem != null) {
            TagManageDialog(
                title = "管理标签 - ${currentItem?.fileName ?: ""}",
                existingTags = currentMediaTags,
                allTags = allTags,
                recentTags = recentTagList,
                readOnlyTagIds = inheritedTagIds,
                onAddTag = { name ->
                    viewModel.addMediaTag(currentItem!!.filePath, name)
                    mediaTagRefreshTrigger++
                },
                onRemoveTag = { tagId ->
                    viewModel.removeMediaTag(currentItem!!.filePath, tagId)
                    mediaTagRefreshTrigger++
                },
                onDismiss = { showMediaTagDialog = false }
            )
        }

        // ── 选择目标相册对话框（单张图片/视频）──
        if (showAlbumPickDialog) {
            val allAlbums by viewModel.albums.collectAsStateWithLifecycle()
            val recentDirs by viewModel.recentMoveAlbums.collectAsStateWithLifecycle()
            val singleItem = currentItem
            AlbumPickDialog(
                albums = allAlbums,
                recentMoveAlbums = recentDirs,
                onDismiss = { showAlbumPickDialog = false },
                onAlbumSelected = { targetDir, targetName, mode ->
                    showAlbumPickDialog = false
                    if (singleItem != null) {
                        if (mode == PasteMode.MOVE) {
                            // MOVE 走独立入口，不经过 clipboard 中转站
                            viewModel.moveItemsToAlbum(
                                listOf(singleItem), targetDir, targetName, albumId.ifEmpty { null }
                            )
                            // MOVE 后：从本地快照中移除、翻页、显示 Snackbar
                            val movedPage = pagerState.currentPage
                            val wasLastPage = movedPage >= mediaItems.lastIndex
                            mediaItems = mediaItems.filterIndexed { i, _ -> i != movedPage }
                            scope.launch {
                                if (!wasLastPage && mediaItems.isNotEmpty()) {
                                    delay(50)
                                    pagerState.animateScrollToPage(movedPage.coerceAtMost(mediaItems.lastIndex))
                                }
                                snackbarHostState.showSnackbar("已移动", duration = SnackbarDuration.Short)
                            }
                        } else {
                            // COPY 直连，不碰 clipboard
                            viewModel.copyItemsToAlbum(
                                listOf(singleItem), targetDir, targetName, albumId.ifEmpty { null }
                            )
                        }
                    }
                }
            )
        }

        // ── 文件冲突对话框 ──
        val fileConflict by viewModel.fileConflict.collectAsStateWithLifecycle()
        if (fileConflict != null) {
            val conflict = fileConflict ?: return@Box
            var applyToAll by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = {
                    viewModel.respondConflict(
                        GalleryViewModel.FileConflictResponse(
                            GalleryViewModel.FileConflictAction.SKIP, false
                        )
                    )
                },
                title = { Text("文件冲突") },
                text = {
                    Column {
                        Text("目标目录中已存在「${conflict.sourceFileName}」")
                        Spacer(Modifier.height(4.dp))
                        Text("如何处理此文件？(${conflict.index}/${conflict.total})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = applyToAll,
                                onCheckedChange = { applyToAll = it }
                            )
                            Text("对全部冲突应用此操作", fontSize = 13.sp)
                        }
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            viewModel.respondConflict(
                                GalleryViewModel.FileConflictResponse(GalleryViewModel.FileConflictAction.OVERWRITE, applyToAll)
                            )
                        }) { Text("覆盖") }
                        TextButton(onClick = {
                            viewModel.respondConflict(
                                GalleryViewModel.FileConflictResponse(GalleryViewModel.FileConflictAction.SKIP, applyToAll)
                            )
                        }) { Text("跳过") }
                        TextButton(onClick = {
                            viewModel.respondConflict(
                                GalleryViewModel.FileConflictResponse(GalleryViewModel.FileConflictAction.RENAME, applyToAll)
                            )
                        }) { Text("重命名") }
                    }
                },
                dismissButton = {}
            )
        }
    }
}

// ══════════════════════════════════════
//  视频 seek 浮动时间提示（独立组件，最小重组范围）
// ══════════════════════════════════════
@Composable
private fun VideoSeekIndicator(visible: Boolean, positionMs: Long, totalDurationMs: Long) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        val posMin = positionMs / 60000; val posSec = (positionMs % 60000) / 1000
        val durMin = totalDurationMs / 60000; val durSec = (totalDurationMs % 60000) / 1000
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "${posMin}:${posSec.toString().padStart(2, '0')} / ${durMin}:${durSec.toString().padStart(2, '0')}",
                modifier = Modifier
                    .background(Color(0x80000000), shape = CircleShape)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

// ══════════════════════════════════════
//  图片信息卡片（紧凑 4 行布局 + 重命名）
// ══════════════════════════════════════


private fun formatDate(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp * 1000L))
}

@Composable
private fun InfoCard(
    item: com.example.rcgallery.model.MediaItem,
    onDismiss: () -> Unit,
    albumDisplayName: String,
    onAlbumNameClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onMoveToAlbum: () -> Unit = {},
    onFileRenamed: (String) -> Unit = {},
    mediaTags: List<TagEntity> = emptyList(),
    onManageTags: () -> Unit = {}
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
                    onFileRenamed(pendingDisplayName)
                } catch (e: Exception) {
                    Toast.makeText(context, "重命名失败", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(context, "需要授予修改权限", Toast.LENGTH_SHORT).show()
        }
    }

    val filePath = item.filePath.ifEmpty { albumDisplayName }
    // 目录路径展开状态（默认折叠，maxLines=2）
    var filePathExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF1A1A1A),
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            // ── 可滚动内容区 ──
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState)
            ) {
                // 1: 文件名(可点击) + 创建时间
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
                // 2: 大小 · 格式
                Text(
                    text = "${FormatUtil.formatFileSize(item.size)} · ${item.mimeType}",
                    color = Color(0xFF999999),
                    fontSize = 12.sp,
                    maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                // 3: TAG 左 + 操作按钮右（同一行）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左侧 TAG 水平滚动条（不换行）
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // + 按钮
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF33AA33))
                                .clickable { onManageTags() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("+", color = Color.White, fontSize = 12.sp)
                        }
                        // TAG chips
                        mediaTags.forEach { tag ->
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF3366AA),
                                modifier = Modifier.height(22.dp).clickable { onManageTags() }
                            ) {
                                Box(Modifier.fillMaxHeight().padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                                    Text(tag.name, color = Color.White, fontSize = 10.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    // 右侧操作按钮
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onMoveToAlbum,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                painter = painterResource(com.example.rcgallery.R.drawable.ic_folder),
                                contentDescription = "移动到相册",
                                tint = Color(0xFF64B5F6),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = onDeleteClick,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                painter = painterResource(com.example.rcgallery.R.drawable.ic_trash),
                                contentDescription = "永久删除",
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                // 4: 相册名（可点击）
                Text(
                    text = albumDisplayName,
                    color = Color(0xFFBBBBBB),
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.clickable { onAlbumNameClick() }
                )
                Spacer(Modifier.height(4.dp))
                // 5: 目录路径（折叠 maxLines=2，点击展开/收起）
                if (filePath.isNotEmpty()) {
                    Text(
                        text = filePath,
                        color = Color(0xFF777777),
                        fontSize = 11.sp,
                        maxLines = if (filePathExpanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { filePathExpanded = !filePathExpanded }
                    )
                }
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
                            onFileRenamed(pendingDisplayName)
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
