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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rcgallery.PipState
import com.example.rcgallery.data.db.TagEntity
import com.example.rcgallery.ui.component.InertiaSettings
import com.example.rcgallery.ui.component.SettingsOverlay
import com.example.rcgallery.ui.component.TagManageDialog
import com.example.rcgallery.ui.component.VideoPlayer
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
    volumeEnabled: Boolean = false,
    onVolumeToggle: () -> Unit = {},
    items: List<com.example.rcgallery.model.MediaItem> = emptyList()  // 由 MediaGridScreen 传入快照，不从 ViewModel 收集（防异步 loadMedia 替换导致 index 错位）
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val viewModel: GalleryViewModel = viewModel(activity)
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
        AppLogger.d("Preview", "page=${pagerState.currentPage} total=${mediaItems.size} uri=${currentItem?.uri?.lastPathSegment ?: "?"}")
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
                                    volumeEnabled = volumeEnabled,
                                    onVolumeToggle = onVolumeToggle,
                                    savedPositions = savedPositions,
                                    onControlZoneActive = { pagerScrollEnabled = !it },
                                    onRequestPip = { pipTriggered = true },
                                    hideUiOverlays = pipOverlayHidden,
                                    keepControllerVisible = showInfo && item.isVideo,
                                    onShowInfoClick = { showInfo = true },
                                    onMoveToTrash = {
                                        val trashItem = mediaItems.getOrNull(pagerState.currentPage)
                                        if (trashItem != null) {
                                            if (showInfo) showInfo = false
                                            viewModel.moveToTrash(trashItem)
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
                                                    viewModel.restoreFromTrash(trashItem.uri.toString())
                                                    viewModel.addMediaItemBack(trashItem)
                                                    // 撤销后恢复本地快照
                                                    mediaItems = (listOf(trashItem) + mediaItems)
                                                        .sortedByDescending { it.dateAdded }
                                                }
                                                if (wasLastPage) {
                                                    onBackClick()
                                                }
                                            }
                                        }
                                    }
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
        SettingsOverlay(gearModifier = Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 48.dp), visible = !pipOverlayHidden)

        // ── 媒体 TAG 管理对话框 ──
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
                text = "${FormatUtil.formatFileSize(item.size)} · ${item.mimeType}",
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
            // Row 4: 目录路径（自动换行，不限制行数）
            if (filePath.isNotEmpty()) {
                Text(
                    text = filePath,
                    color = Color(0xFF777777),
                    fontSize = 11.sp,
                    softWrap = true
                )
            }
            // TAG 行（首个 + 号，最多两行自动换行）
            Spacer(Modifier.height(4.dp))
            MediaTagRow(
                tags = mediaTags,
                onAddClick = onManageTags,
                onTagClick = onManageTags
            )
            // Row 5: 永久删除按钮（右下角）
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDeleteClick,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(com.example.rcgallery.R.drawable.ic_trash),
                        contentDescription = "永久删除",
                        tint = Color(0xFFFF5252),
                        modifier = Modifier.size(18.dp)
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


// ══════════════════════════════════════
//  媒体 TAG 行（首个 + 号，最多两行自动换行，溢出后显示展开）
// ══════════════════════════════════════

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@androidx.compose.runtime.Composable
private fun MediaTagRow(
    tags: List<TagEntity>,
    onAddClick: () -> Unit,
    onTagClick: () -> Unit
) {
    var showExpanded by remember { mutableStateOf(false) }

    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        maxLines = if (showExpanded) Int.MAX_VALUE else 2
    ) {
        // + 号按钮
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color(0xFF33AA33))
                .clickable { onAddClick() },
            contentAlignment = Alignment.Center
        ) {
            Text("+", color = Color.White, fontSize = 12.sp)
        }

        tags.forEach { tag ->
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                color = Color(0xFF3366AA),
                modifier = Modifier.height(22.dp).clickable { onTagClick() }
            ) {
                Box(Modifier.fillMaxHeight().padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                    Text(
                        tag.name,
                        color = Color.White,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }
        }

        // 溢出 → 展开按钮
        if (tags.size >= 6 && !showExpanded) {
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                color = Color(0xFF555555),
                modifier = Modifier.height(22.dp).clickable { showExpanded = true }
            ) {
                Box(Modifier.fillMaxHeight().padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                    Text("展开", color = Color.White, fontSize = 10.sp)
                }
            }
        }
    }
}
