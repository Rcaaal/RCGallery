package com.example.rcgallery.ui.screen

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.rcgallery.ui.component.DevOverlay
import com.example.rcgallery.ui.component.FastScrollerView
import com.example.rcgallery.ui.component.InertiaSettingsPanel
import com.example.rcgallery.ui.component.SettingsOverlay
import com.example.rcgallery.ui.component.FloatingJumpButton
import com.example.rcgallery.ui.component.FloatingMultiSelectButtons
import com.example.rcgallery.ui.component.ClipboardBadge
import com.example.rcgallery.ui.component.AlbumPickDialog
import com.example.rcgallery.ui.component.AutoFocusRenameTextField
import com.example.rcgallery.viewmodel.PasteMode
import com.example.rcgallery.ui.component.FpsMonitor
import com.example.rcgallery.ui.component.FpsMonitorEnabled
import com.example.rcgallery.ui.component.TagManageDialog
import com.example.rcgallery.data.db.TagEntity
import com.example.rcgallery.util.AppLogger
import com.example.rcgallery.viewmodel.GalleryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaGridScreen(
    albumId: String = "",
    albumName: String = "",
    albumDirectoryPath: String = "",  // 相册目录路径（用于读取相册 TAG）
    onMediaClick: (Int) -> Unit = {},
    onBackClick: () -> Unit = {},
    onGoHome: () -> Unit = {}    // 直接回到 AlbumGrid 主页
) {
    val activity = LocalContext.current as ComponentActivity
    val viewModel: GalleryViewModel = viewModel(activity)
    val mediaItems by viewModel.mediaItems.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val starredMediaUris by viewModel.starredMediaUris.collectAsStateWithLifecycle()
    val mediaTags by viewModel.mediaTags.collectAsStateWithLifecycle()
    val albumTags by viewModel.albumTags.collectAsStateWithLifecycle()
    val mediaPersistentRules by viewModel.mediaPersistentRules.collectAsStateWithLifecycle()
    val mediaTempFilter by viewModel.mediaTempFilter.collectAsStateWithLifecycle()
    var showMediaFilterPage by remember { mutableStateOf(false) }
    val hasActiveMediaFilter = mediaPersistentRules.any { it.enabled } || mediaTempFilter.isActive

    // ── 相册 TAG 管理 ──
    val currentAlbumTags = remember(albumDirectoryPath, albumTags) {
        if (albumDirectoryPath.isNotEmpty()) albumTags[albumDirectoryPath] ?: emptyList()
        else emptyList()
    }
    var showAlbumTagDialog by remember { mutableStateOf(false) }

    // ── 媒体项 TAG 管理 ──
    var tagDialogMediaItem by remember { mutableStateOf<com.example.rcgallery.model.MediaItem?>(null) }
    var mediaTagRefreshTrigger by remember { mutableIntStateOf(0) }
    val allTags by viewModel.allTags.collectAsStateWithLifecycle()

    // ── Grid 模式多选状态 ──
    var isMediaMultiSelect by remember { mutableStateOf(false) }
    var selectedMediaUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 已完成拖拽的选中集（独立于 Compose state，用于拖拽中不触发闪烁）
    var committedSelection by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 拖拽是否正在进行中（用于隐藏浮动按钮避免挡手势）
    var isDragInProgress by remember { mutableStateOf(false) }
    var showMediaBatchTagDialog by remember { mutableStateOf(false) }
    var showAlbumPickDialog by remember { mutableStateOf(false) }
    // 暂存"选择目标相册"时选中的文件，确认后才加入中转站
    var pendingPickItems by remember { mutableStateOf<List<com.example.rcgallery.model.MediaItem>>(emptyList()) }
    fun exitMediaMultiSelect() {
        isMediaMultiSelect = false
        selectedMediaUris = emptySet()
        committedSelection = emptySet()
        isDragInProgress = false
    }
    fun toggleMediaSelection(item: com.example.rcgallery.model.MediaItem) {
        val uri = item.uri.toString()
        selectedMediaUris = if (uri in selectedMediaUris) selectedMediaUris - uri
                           else selectedMediaUris + uri
        // 同步 committedSelection（tap 切换不经过拖拽流程，也需要保持同步）
        committedSelection = selectedMediaUris
    }

    // ── 相册切换时防止旧数据闪烁：加载完成前不展示 RecyclerView ──
    var isLoadingAlbum by remember(albumId) { mutableStateOf(true) }
    LaunchedEffect(albumId) {
        isLoadingAlbum = true
        viewModel.loadMedia(albumId = albumId)
        // 等加载完成（true → false）后显示网格，防竞态
        viewModel.isLoading.first { it }
        viewModel.isLoading.first { !it }
        isLoadingAlbum = false
    }

    // ── 设置面板 / 日志（复用组件 SettingsOverlay）──

    // ── Preview overlay 状态（代替 navigation push，防止 RecyclerView 销毁）──
    var selectedPhotoIndex by remember { mutableIntStateOf(-1) }
    var selectedPhotoItem by remember { mutableStateOf<com.example.rcgallery.model.MediaItem?>(null) }
    if (selectedPhotoIndex >= 0) {
        BackHandler { selectedPhotoIndex = -1 }
    }

    // 音量：共享 PlaybackSettingsViewModel（MainActivity 作用域）

    // ── 显示模式（持久化：退出重进保留）──
    val mediaPrefs = activity.getSharedPreferences("rcgallery_prefs", android.content.Context.MODE_PRIVATE)
    var mediaDisplayMode by remember {
        val saved = mediaPrefs.getString("media_display_mode", "") ?: ""
        mutableStateOf(when {
            saved == "list" -> MediaDisplayMode.List
            saved.startsWith("grid_") -> {
                val cols = saved.removePrefix("grid_").toIntOrNull() ?: DEFAULT_MEDIA_GRID_COLUMNS
                MediaDisplayMode.Grid(cols)
            }
            else -> MediaDisplayMode.Grid(DEFAULT_MEDIA_GRID_COLUMNS)
        })
    }

    // ── 媒体排序模式（持久化）──
    var mediaSortMode by remember {
        val saved = mediaPrefs.getString("media_sort_mode", "") ?: ""
        mutableStateOf(when (saved) {
            "name" -> MediaSortMode.NAME
            "size" -> MediaSortMode.SIZE
            "image_size" -> MediaSortMode.IMAGE_SIZE
            "video_size" -> MediaSortMode.VIDEO_SIZE
            else -> MediaSortMode.DATE
        })
    }

    // ── 相册重命名 ──
    var showAlbumRenameDialog by remember { mutableStateOf(false) }
    val renameContext = LocalContext.current
    val manageStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        Toast.makeText(renameContext, "授权成功，请重新点击标题进行改名", Toast.LENGTH_SHORT).show()
    }
    if (showAlbumRenameDialog && albumId.isNotEmpty()) {
        val currentName = albumName.ifEmpty { "未知" }
        var editText by remember { mutableStateOf(currentName) }
        fun confirmAlbumRename() {
            val newName = editText.trim()
            if (newName.isEmpty()) return
            showAlbumRenameDialog = false
            if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) {
                viewModel.renameNow(albumId, newName) { ok ->
                    Toast.makeText(
                        renameContext,
                        if (ok) "相册已重命名" else "重命名失败，请重试",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                try {
                    manageStorageLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:${renameContext.packageName}")
                        }
                    )
                } catch (e: Exception) {
                    Toast.makeText(renameContext, "无法跳转授权页面", Toast.LENGTH_SHORT).show()
                }
            }
        }
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
                    AutoFocusRenameTextField(
                        initialText = currentName,
                        onValueChange = { editText = it },
                        onDone = { confirmAlbumRename() },
                        label = "新相册名"
                    )
                }
            },
            confirmButton = {
                Button(onClick = { confirmAlbumRename() }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { showAlbumRenameDialog = false }) { Text("取消") } }
        )
    }

    // ── 媒体类型过滤 ──
    val availableTypes = remember(mediaItems) {
        mutableSetOf<MediaFilterType>().apply {
            if (mediaItems.any { it.isImage && !it.isGif }) add(MediaFilterType.IMAGE)
            if (mediaItems.any { it.isVideo }) add(MediaFilterType.VIDEO)
            if (mediaItems.any { it.isGif }) add(MediaFilterType.GIF)
        }.toSet()
    }
    var activeFilters by remember { mutableStateOf<Set<MediaFilterType>>(emptySet()) }
    val filteredItems = remember(mediaItems, activeFilters) {
        if (activeFilters.isEmpty()) mediaItems
        else mediaItems.filter { item ->
            activeFilters.any { type ->
                when (type) {
                    MediaFilterType.IMAGE -> item.isImage && !item.isGif
                    MediaFilterType.VIDEO -> item.isVideo
                    MediaFilterType.GIF -> item.isGif
                }
            }
        }
    }
    // 星标排序：星标永久置顶 + 按当前排序模式排列
    val sortedItems = remember(filteredItems, starredMediaUris, mediaSortMode) {
        filteredItems.sortedWith(
            compareByDescending<com.example.rcgallery.model.MediaItem> { it.uri.toString() in starredMediaUris }
                .then(when (mediaSortMode) {
                    MediaSortMode.DATE -> compareByDescending { it.dateAdded }
                    MediaSortMode.NAME -> compareBy { it.fileName }
                    MediaSortMode.SIZE -> compareByDescending { it.size }
                    MediaSortMode.IMAGE_SIZE -> compareByDescending<com.example.rcgallery.model.MediaItem> { it.isImage }
                        .thenByDescending { it.size }
                    MediaSortMode.VIDEO_SIZE -> compareByDescending<com.example.rcgallery.model.MediaItem> { it.isVideo }
                        .thenByDescending { it.size }
                })
        )
    }

    // ── 标签筛选（持久规则 + 临时筛选）—— 在 sortedItems 之后过滤 ──
    val tagFilteredItems = remember(sortedItems, mediaTempFilter, mediaPersistentRules, mediaTags, albumTags) {
        sortedItems.filter { item ->
            val mTags = mediaTags[item.filePath]?.map { it.name } ?: emptyList()
            val aTags = albumTags[albumDirectoryPath]?.map { it.name } ?: emptyList()
            !viewModel.shouldHideMedia(item.filePath, mTags, aTags)
        }
    }
    // 列表数据版本号：tagFilteredItems 变化时递增，用于多选拖拽检测旧 position 过期
    var mediaDatasetVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(tagFilteredItems) { mediaDatasetVersion++ }

    // ── RecyclerView 引用（给 FloatingJumpButton 用）──
    val mediaRvRef = remember { mutableStateOf<RecyclerView?>(null) }

    // ── 日志面板 ──
    var showLogDialog by remember { mutableStateOf(false) }
    if (showLogDialog) {
        Box(Modifier.fillMaxSize().clickable { showLogDialog = false }) {
            DevOverlay(initialShow = true)
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            var showInertiaSettings by remember { mutableStateOf(false) }
            if (isLoadingAlbum) {
                // 加载中：全黑占位，无文字无 loading
                Box(Modifier.fillMaxSize().background(Color.Black))
            } else {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = if (isMediaMultiSelect) "已选 ${selectedMediaUris.size} 项"
                                           else if (albumName.isNotEmpty()) albumName else "所有文件",
                                    maxLines = 1,
                                    modifier = if (!isMediaMultiSelect) Modifier.clickable { showAlbumRenameDialog = true } else Modifier
                                )
                            },
                            navigationIcon = {
                                if (isMediaMultiSelect) {
                                    TextButton(onClick = { exitMediaMultiSelect() }) {
                                        Text("取消")
                                    }
                                } else {
                                    TextButton(onClick = onBackClick) { Text("← 返回") }
                                }
                            },
                            windowInsets = WindowInsets(0, 0, 0, 0),  // 外层 Scaffold 已处理状态栏 insets
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                            actions = {
                                if (!isMediaMultiSelect) {
                                var showGearMenu by remember { mutableStateOf(false) }
                                Box(modifier = Modifier.padding(end = 4.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xCCFF9800))
                                            .clickable { showGearMenu = true },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(com.example.rcgallery.R.drawable.ic_settings),
                                            contentDescription = "设置",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showGearMenu,
                                        onDismissRequest = { showGearMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("设置") },
                                            onClick = { showGearMenu = false; showInertiaSettings = true }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("日志") },
                                            onClick = { showGearMenu = false; showLogDialog = true }
                                        )
                                    }
                                }
                                } else {
                                    // 多选模式：全选/取消全选
                                    val allSelected = tagFilteredItems.isNotEmpty() &&
                                        tagFilteredItems.all { it.uri.toString() in selectedMediaUris }
                                    TextButton(onClick = {
                                        if (allSelected) {
                                            // 取消全选
                                            selectedMediaUris = emptySet()
                                            committedSelection = emptySet()
                                        } else {
                                            // 全选
                                            val allUris = tagFilteredItems.map { it.uri.toString() }.toSet()
                                            selectedMediaUris = allUris
                                            committedSelection = allUris
                                        }
                                    }) {
                                        Text(
                                            if (allSelected) "取消全选" else "全选",
                                            fontSize = 13.sp
                                        )
                                    }
                                }   // ← if (!isMediaMultiSelect)
                            }   // ← actions
                        )   // ← TopAppBar(
                    },
                    bottomBar = {},
                ) { padding ->
                    if (mediaItems.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                            Text("这个相册是空的", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else if (filteredItems.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                            Text("没有符合条件的文件", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Box(Modifier.fillMaxSize()) {
                        AndroidView(
                            factory = { ctx ->
                                val rv = RecyclerView(ctx)
                                val initialMode = mediaDisplayMode
                                val spanCount = when (initialMode) {
                                    is MediaDisplayMode.Grid -> initialMode.columns
                                    is MediaDisplayMode.List -> 1
                                }
                                rv.layoutManager = GridLayoutManager(ctx, spanCount)
                                rv.clipToPadding = false
                                rv.setPadding(0, (40 * ctx.resources.displayMetrics.density).toInt(), 0, 0)

                                // ── 滑动手势多选（替代 adapter 的 onLongClick）──
                                val dragState = object {
                                    var dragStartIdx = -1
                                    var dragStartUri = ""       // 稳定锚点 URI
                                    var dragStartVersion = 0    // 长按时的数据版本
                                    var isDragging = false
                                    var longPressConsumed = false  // 长按触发后拦截 click
                                    var notifyMin = -1             // 本次拖拽通知范围起点
                                    var notifyMax = -1             // 本次拖拽通知范围终点
                                }
                                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                                val longPressMs = android.view.ViewConfiguration.getLongPressTimeout().toLong()
                                var downX = 0f; var downY = 0f

                                rv.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
                                    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                                        when (e.actionMasked) {
                                            MotionEvent.ACTION_DOWN -> {
                                                dragState.isDragging = false
                                                dragState.dragStartIdx = -1
                                                dragState.longPressConsumed = false
                                                downX = e.x; downY = e.y
                                                handler.removeCallbacksAndMessages(null)
                                                handler.postDelayed({
                                                    val child = rv.findChildViewUnder(downX, downY) ?: return@postDelayed
                                                    val pos = rv.getChildAdapterPosition(child)
                                                    if (pos < 0) return@postDelayed
                                                    dragState.dragStartIdx = pos
                                                    dragState.isDragging = true
                                                    val item = tagFilteredItems.getOrNull(pos) ?: return@postDelayed
                                                    dragState.dragStartUri = item.uri.toString()
                                                    dragState.dragStartVersion = mediaDatasetVersion
                                                    dragState.notifyMin = -1
                                                    dragState.notifyMax = -1
                                                    dragState.longPressConsumed = true
                                                    if (!isMediaMultiSelect) {
                                                        isMediaMultiSelect = true
                                                        committedSelection = emptySet()
                                                        toggleMediaSelection(item)
                                                    } else {
                                                        // 已有多选：确保长按的 item 被选中，但不覆盖已有选择
                                                        val uri = item.uri.toString()
                                                        if (uri !in selectedMediaUris) {
                                                            selectedMediaUris = selectedMediaUris + uri
                                                            committedSelection = committedSelection + uri
                                                        }
                                                    }
                                                    isDragInProgress = true
                                                }, longPressMs)
                                                return false
                                            }
                                            MotionEvent.ACTION_MOVE -> {
                                                val slop = android.view.ViewConfiguration.get(rv.context).scaledTouchSlop
                                                if (kotlin.math.abs(e.x - downX) > slop || kotlin.math.abs(e.y - downY) > slop) {
                                                    handler.removeCallbacksAndMessages(null)
                                                }
                                                if (dragState.isDragging) return true
                                                return false
                                            }
                                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                                handler.removeCallbacksAndMessages(null)
                                                val consumed = dragState.longPressConsumed
                                                if (dragState.isDragging) {
                                                    // 长按后未拖拽就松手：清除拖拽状态（选中已由 toggle 处理）
                                                    dragState.isDragging = false
                                                    dragState.dragStartIdx = -1
                                                    isDragInProgress = false
                                                }
                                                dragState.longPressConsumed = false
                                                if (consumed) return true
                                                return false
                                            }
                                        }
                                        return false
                                    }

                                    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                                        if (!dragState.isDragging) return
                                        when (e.actionMasked) {
                                            MotionEvent.ACTION_MOVE -> {
                                                var pos: Int
                                                // 自动滚动：先于 findChildViewUnder 判断，确保边缘滚动生效
                                                val edgeThreshold = 80f
                                                val scrollSpeed = 60
                                                if (e.y < edgeThreshold) {
                                                    rv.scrollBy(0, -scrollSpeed)
                                                    val firstVisible = (rv.layoutManager as? androidx.recyclerview.widget.GridLayoutManager)
                                                        ?.findFirstVisibleItemPosition() ?: return
                                                    pos = (firstVisible - 1).coerceAtLeast(0)
                                                } else if (e.y > rv.height - edgeThreshold) {
                                                    rv.scrollBy(0, scrollSpeed)
                                                    val lastVisible = (rv.layoutManager as? androidx.recyclerview.widget.GridLayoutManager)
                                                        ?.findLastVisibleItemPosition() ?: return
                                                    pos = (lastVisible + 1).coerceAtMost(tagFilteredItems.size - 1)
                                                } else {
                                                    val child = rv.findChildViewUnder(e.x, e.y) ?: return
                                                    pos = rv.getChildAdapterPosition(child)
                                                    if (pos < 0) return
                                                }
                                                val currentAdapter = rv.adapter as? SimpleGridAdapter ?: return
                                                val items = currentAdapter.items
                                                // ── 版本变化时用 URI 重新定位起点，防列表刷新后 position 漂移 ──
                                                if (dragState.dragStartVersion != mediaDatasetVersion) {
                                                    val reFound = items.indexOfFirst { it.uri.toString() == dragState.dragStartUri }
                                                    if (reFound >= 0) {
                                                        dragState.dragStartIdx = reFound
                                                        dragState.dragStartVersion = mediaDatasetVersion
                                                    } else {
                                                        // 起点项已不在列表中（被 MOVE/删除），中止本次拖拽
                                                        dragState.isDragging = false
                                                        dragState.dragStartIdx = -1
                                                        isDragInProgress = false
                                                        return
                                                    }
                                                }
                                                // 按方向计算显示选中集
                                                val displayUris: Set<String>
                                                if (pos >= dragState.dragStartIdx) {
                                                    // 正向：选中 [dragStart, pos]
                                                    val selectUris = (dragState.dragStartIdx..pos).mapNotNull { i ->
                                                        items.getOrNull(i)?.uri?.toString()
                                                    }.toSet()
                                                    displayUris = committedSelection + selectUris
                                                } else {
                                                    // 反向：取消选中 [pos, dragStart]（含 dragStart 本身）
                                                    val deselectUris = (pos .. dragState.dragStartIdx).mapNotNull { i ->
                                                        items.getOrNull(i)?.uri?.toString()
                                                    }.toSet()
                                                    displayUris = committedSelection - deselectUris
                                                }
                                                // 通知范围 = 本次拖拽覆盖过的全部范围
                                                val curMin = minOf(dragState.dragStartIdx, pos)
                                                val curMax = maxOf(dragState.dragStartIdx, pos)
                                                dragState.notifyMin = if (dragState.notifyMin < 0) curMin else minOf(dragState.notifyMin, curMin)
                                                dragState.notifyMax = if (dragState.notifyMax < 0) curMax else maxOf(dragState.notifyMax, curMax)
                                                currentAdapter.selectedUris = displayUris
                                                currentAdapter.notifyItemRangeChanged(dragState.notifyMin, dragState.notifyMax - dragState.notifyMin + 1)
                                            }
                                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                                // 提交本次拖拽结果：读取 adapter 当前选中集
                                                val currentAdapter = rv.adapter as? SimpleGridAdapter
                                                if (currentAdapter != null) {
                                                    committedSelection = currentAdapter.selectedUris
                                                }
                                                selectedMediaUris = committedSelection
                                                dragState.isDragging = false
                                                dragState.dragStartIdx = -1
                                                dragState.notifyMin = -1
                                                dragState.notifyMax = -1
                                                isDragInProgress = false
                                            }
                                        }
                                    }
                                })

                                val adapter = SimpleGridAdapter(
                                    onClick = { item ->
                                        if (isMediaMultiSelect) {
                                            toggleMediaSelection(item)
                                            return@SimpleGridAdapter
                                        }
                                        val items = (rv.adapter as SimpleGridAdapter).items
                                        var index = items.indexOf(item)
                                        if (index < 0) {
                                            val uriStr = item.uri.toString()
                                            index = items.indexOfFirst { it.uri.toString() == uriStr }
                                        }
                                        AppLogger.d("MediaGrid", "click uri=${item.uri.lastPathSegment} index=$index items=${items.size}")
                                        if (index >= 0) {
                                            selectedPhotoIndex = index
                                        }
                                    },
                                    onToggleStar = { uriStr -> viewModel.toggleMediaStar(uriStr) },
                                    onManageTags = { item -> tagDialogMediaItem = item },
                                    context = ctx,
                                    onLongClick = null  // 由触摸监听器接管长按
                                ).apply { currentMode = mediaDisplayMode }
                                rv.adapter = adapter
                                mediaRvRef.value = rv
                                val scroller = FastScrollerView(ctx, rv)
                                FrameLayout(ctx).apply {
                                    addView(rv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                                    addView(scroller, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                                }
                            },
                            update = { container ->
                                val rv = (container as FrameLayout).getChildAt(0) as RecyclerView
                                val scroller = container.getChildAt(1) as FastScrollerView
                                val adapter = rv.adapter as SimpleGridAdapter
                                val prevMode = adapter.currentMode
                                val currentMode = mediaDisplayMode
                                val modeChanged = prevMode != currentMode
                                val oldItems = adapter.items
                                val oldStarredUris = adapter.starredUris
                                val oldMediaTagsMap = adapter.mediaTagsMap
                                val oldSelectedUris = adapter.selectedUris
                                val oldMultiSelectMode = adapter.isMultiSelectMode

                                val itemDiff = if (!modeChanged && oldItems !== tagFilteredItems) {
                                    DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                                        override fun getOldListSize() = oldItems.size
                                        override fun getNewListSize() = tagFilteredItems.size
                                        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                                            oldItems[oldItemPosition].uri == tagFilteredItems[newItemPosition].uri
                                        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                                            oldItems[oldItemPosition] == tagFilteredItems[newItemPosition]
                                    })
                                } else null

                                adapter.items = tagFilteredItems
                                adapter.starredUris = starredMediaUris
                                adapter.mediaTagsMap = mediaTags
                                adapter.selectedUris = selectedMediaUris
                                adapter.isMultiSelectMode = isMediaMultiSelect
                                if (modeChanged) {
                                    adapter.currentMode = currentMode
                                    val spanCount = when (currentMode) {
                                        is MediaDisplayMode.Grid -> currentMode.columns
                                        is MediaDisplayMode.List -> 1
                                    }
                                    val lm = rv.layoutManager as? GridLayoutManager
                                    if (lm == null || lm.spanCount != spanCount) {
                                        rv.layoutManager = GridLayoutManager(rv.context, spanCount)
                                    }
                                    adapter.notifyDataSetChanged()
                                    scroller.refresh()
                                } else {
                                    itemDiff?.dispatchUpdatesTo(adapter)
                                    if (oldMultiSelectMode != isMediaMultiSelect) {
                                        adapter.notifyItemRangeChanged(0, adapter.itemCount)
                                    } else {
                                        val changedUris = (oldStarredUris - starredMediaUris) +
                                            (starredMediaUris - oldStarredUris) +
                                            (oldSelectedUris - selectedMediaUris) +
                                            (selectedMediaUris - oldSelectedUris)
                                        val changedPaths = if (currentMode is MediaDisplayMode.List) {
                                            (oldMediaTagsMap.keys + mediaTags.keys).filterTo(HashSet()) { path ->
                                                oldMediaTagsMap[path] != mediaTags[path]
                                            }
                                        } else emptySet()
                                        if (changedUris.isNotEmpty() || changedPaths.isNotEmpty()) {
                                            tagFilteredItems.forEachIndexed { index, item ->
                                                if (item.uri.toString() in changedUris || item.filePath in changedPaths) {
                                                    adapter.notifyItemChanged(index)
                                                }
                                            }
                                        }
                                    }
                                }
                                // ── 列表刷新后对齐多选状态：清除已不存在的 URI ──
                                if (isMediaMultiSelect && selectedMediaUris.isNotEmpty()) {
                                    val currentUris = tagFilteredItems.map { it.uri.toString() }.toSet()
                                    val staleUris = selectedMediaUris - currentUris
                                    if (staleUris.isNotEmpty()) {
                                        val remaining = selectedMediaUris.intersect(currentUris)
                                        selectedMediaUris = remaining
                                        committedSelection = remaining
                                        adapter.selectedUris = remaining
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize().padding(padding)
                        )
                        // ── 悬浮工具栏（列数/排序）──
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding())
                                .align(Alignment.TopStart),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            MediaDisplayModeSelector(
                                currentMode = mediaDisplayMode,
                                onSelectMode = { mode ->
                                    mediaDisplayMode = mode
                                    mediaPrefs.edit().putString("media_display_mode", when (mode) {
                                        is MediaDisplayMode.List -> "list"
                                        is MediaDisplayMode.Grid -> "grid_${mode.columns}"
                                    }).apply()
                                },
                                hasActiveFilter = hasActiveMediaFilter,
                                onOpenFilter = { showMediaFilterPage = true }
                            )
                            Spacer(Modifier.weight(1f))
                            MediaSortSelector(
                                currentSort = mediaSortMode,
                                onSelectSort = { mode ->
                                    mediaSortMode = mode
                                    mediaPrefs.edit().putString("media_sort_mode", when (mode) {
                                        MediaSortMode.DATE -> "date"
                                        MediaSortMode.NAME -> "name"
                                        MediaSortMode.SIZE -> "size"
                                        MediaSortMode.IMAGE_SIZE -> "image_size"
                                        MediaSortMode.VIDEO_SIZE -> "video_size"
                                    }).apply()
                                }
                            )
                        }
                        // ── 多选 BackHandler ──
                        if (isMediaMultiSelect) {
                            BackHandler { exitMediaMultiSelect() }
                        }
                        // ── 批量 TAG 对话框 ──
                        if (showMediaBatchTagDialog) {
                            val batchMediaPaths = tagFilteredItems
                                .filter { it.uri.toString() in selectedMediaUris }
                                .map { it.filePath }
                            TagManageDialog(
                                title = "批量加标签 - 已选 ${batchMediaPaths.size} 个文件",
                                existingTags = emptyList(),
                                allTags = allTags,
                                onAddTag = { tagName ->
                                    batchMediaPaths.forEach { filePath ->
                                        viewModel.addMediaTag(filePath, tagName)
                                    }
                                    showMediaBatchTagDialog = false
                                    exitMediaMultiSelect()
                                },
                                onRemoveTag = { _ -> },
                                onDismiss = { showMediaBatchTagDialog = false }
                            )
                        }
                        }
                    }
                }
            }
            FpsMonitor(enabled = FpsMonitorEnabled, modifier = Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 8.dp))
            if (showInertiaSettings) InertiaSettingsPanel(
                onDismiss = { showInertiaSettings = false },
                onOpenLog = { showInertiaSettings = false; showLogDialog = true }
            )

            // ── 底部 TAG 栏 + 媒体类型过滤按钮 ──
            Column(
                modifier = Modifier.align(Alignment.BottomCenter),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // TAG 栏（自下向上排列，在过滤按钮正上方）
                if (!isMediaMultiSelect && albumDirectoryPath.isNotEmpty()) {
                    AlbumTagBarBottom(
                        tags = currentAlbumTags,
                        onAddTag = { showAlbumTagDialog = true }
                    )
                }
                // 媒体类型过滤按钮（多选模式隐藏）
                if (availableTypes.isNotEmpty() && !isMediaMultiSelect) {
                    Row(
                        modifier = Modifier
                            .padding(bottom = 12.dp)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                    availableTypes.forEach { type ->
                        val selected = type in activeFilters
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface,
                            tonalElevation = if (selected) 0.dp else 3.dp,
                            shadowElevation = if (selected) 0.dp else 4.dp,
                            onClick = {
                                activeFilters = if (selected) activeFilters - type
                                                else activeFilters + type
                            }
                        ) {
                            Text(
                                text = type.label,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                    if (activeFilters.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.error,
                            tonalElevation = 3.dp,
                            shadowElevation = 4.dp,
                            onClick = { activeFilters = emptySet() }
                        ) {
                            Text(
                                text = "重置",
                                color = MaterialTheme.colorScheme.onError,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }   // end if (availableTypes)
            }   // end Column (TAG bar + filter buttons)

            FloatingJumpButton(recyclerView = mediaRvRef.value, modifier = Modifier.align(Alignment.BottomStart))

            // ── 多选模式浮动按钮（替代 BottomAppBar）──
            if (isMediaMultiSelect && selectedMediaUris.isNotEmpty() && !isDragInProgress) {
                FloatingMultiSelectButtons(
                    selectedCount = selectedMediaUris.size,
                    onBatchTag = { showMediaBatchTagDialog = true },
                    onDeleteToTrash = {
                        val toDelete = tagFilteredItems.filter { it.uri.toString() in selectedMediaUris }
                        viewModel.moveToTrash(toDelete)
                        exitMediaMultiSelect()
                    },
                    onAddToClipboard = {
                        val items = tagFilteredItems.filter { it.uri.toString() in selectedMediaUris }
                        viewModel.addToClipboard(items)
                        exitMediaMultiSelect()
                    },
                    onPickTargetAlbum = {
                        // 暂存选中文件，等用户真正选择目标后再加入中转站
                        pendingPickItems = tagFilteredItems.filter { it.uri.toString() in selectedMediaUris }
                        exitMediaMultiSelect()
                        showAlbumPickDialog = true
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 80.dp)
                )
            }

            // ── 中转站浮动 badge（不在多选模式时显示）──
            if (!isMediaMultiSelect) {
                val clipboardItems by viewModel.clipboardItems.collectAsStateWithLifecycle()
                if (clipboardItems.isNotEmpty()) {
                    val clipboardScope = rememberCoroutineScope()
                    ClipboardBadge(
                        clipboardCount = clipboardItems.size,
                        currentAlbumDir = albumDirectoryPath.ifEmpty { null },
                        onPasteToAlbum = { mode, dir ->
                            if (mode == PasteMode.MOVE) {
                                // MOVE 走独立入口：不从 clipboard 塞入后再 paste，直接 moveItemsToAlbum
                                val job = viewModel.moveItemsToAlbum(clipboardItems, dir, albumName, albumId.ifEmpty { null })
                                // 全部移动成功后清空 clipboard，失败后保留以便重试
                                clipboardScope.launch {
                                    job.join()
                                    if (viewModel.moveFailures.value.isEmpty()) {
                                        viewModel.clearClipboard()
                                    }
                                }
                            } else {
                                viewModel.pasteToAlbum(mode, dir, albumName, albumId.ifEmpty { null })
                            }
                        },
                        onPickTargetAlbum = { showAlbumPickDialog = true },
                        onClear = { viewModel.clearClipboard() },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 80.dp)
                    )
                }
            }

            // ── Preview 全屏覆盖层（不通过 navigation，RecyclerView 保持存活）──
            if (selectedPhotoIndex >= 0) {
                PreviewScreen(
                    initialIndex = selectedPhotoIndex,
                    onBackClick = {
                        selectedPhotoIndex = -1
                    },
                    onGoHome = { selectedPhotoIndex = -1; onGoHome() },
                    items = tagFilteredItems,
                    albumId = albumId
                )
            }

            // ── 图片筛选全屏覆盖层 ──
            if (showMediaFilterPage) {
                val allTags by viewModel.allTags.collectAsStateWithLifecycle()
                MediaFilterPage(
                    allTags = allTags,
                    mediaPersistentRules = mediaPersistentRules,
                    mediaTempFilter = mediaTempFilter,
                    onBack = { showMediaFilterPage = false },
                    onToggleRule = { viewModel.toggleMediaRule(it) },
                    onSaveRule = { rule ->
                        if (mediaPersistentRules.any { it.id == rule.id }) {
                            viewModel.updateMediaRule(rule)
                        } else {
                            viewModel.addMediaRule(rule)
                        }
                    },
                    onDeleteRule = { viewModel.deleteMediaRule(it) },
                    onSetTempFilter = { viewModel.setMediaTempFilter(it) },
                    onReset = {
                        viewModel.resetMediaTempFilter()
                        mediaPersistentRules.forEach { if (it.enabled) viewModel.toggleMediaRule(it.id) }
                    }
                )
            }

            // ── TAG 管理对话框 ──

            // 相册 TAG 对话框
            if (showAlbumTagDialog && albumDirectoryPath.isNotEmpty()) {
                val scope = rememberCoroutineScope()
                var recentTagList by remember { mutableStateOf<List<TagEntity>>(emptyList()) }
                LaunchedEffect(Unit) {
                    recentTagList = viewModel.getRecentTags()
                }
                TagManageDialog(
                    title = "管理相册标签 - $albumName",
                    existingTags = currentAlbumTags,
                    allTags = allTags,
                    recentTags = recentTagList,
                    onAddTag = { name -> viewModel.addAlbumTag(albumDirectoryPath, name) },
                    onRemoveTag = { tagId -> viewModel.removeAlbumTag(albumDirectoryPath, tagId) },
                    onDismiss = { showAlbumTagDialog = false }
                )
            }

            // 媒体项 TAG 对话框
            val currentMediaItem = tagDialogMediaItem
            if (currentMediaItem != null && currentMediaItem.filePath.isNotEmpty()) {
                val scope = rememberCoroutineScope()
                var existingMediaTags by remember(currentMediaItem, mediaTagRefreshTrigger) {
                    mutableStateOf<List<TagEntity>>(emptyList())
                }
                var recentTagList by remember { mutableStateOf<List<TagEntity>>(emptyList()) }
                var inheritedTagIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
                LaunchedEffect(currentMediaItem, mediaTagRefreshTrigger) {
                    existingMediaTags = viewModel.getMediaTags(currentMediaItem.filePath)
                    recentTagList = viewModel.getRecentTags()
                    inheritedTagIds = viewModel.getInheritedTagIdsForMedia(currentMediaItem)
                }
                TagManageDialog(
                    title = "管理图片标签 - ${currentMediaItem.fileName}",
                    existingTags = existingMediaTags,
                    allTags = allTags,
                    recentTags = recentTagList,
                    readOnlyTagIds = inheritedTagIds,
                    onAddTag = { name ->
                        val job = viewModel.addMediaTag(currentMediaItem.filePath, name)
                        scope.launch {
                            job.join()
                            mediaTagRefreshTrigger++
                        }
                    },
                    onRemoveTag = { tagId ->
                        val job = viewModel.removeMediaTag(currentMediaItem.filePath, tagId)
                        scope.launch {
                            job.join()
                            mediaTagRefreshTrigger++
                        }
                    },
                    onDismiss = { tagDialogMediaItem = null }
                )
            }

            // ── 选择目标相册对话框 ──
            val folderCreateScope = rememberCoroutineScope()
            if (showAlbumPickDialog) {
                val allAlbums by viewModel.albums.collectAsStateWithLifecycle()
                val recentDirs by viewModel.recentMoveAlbums.collectAsStateWithLifecycle()
                // 排除当前相册本身，不允许"移动到自身"
                val filteredAlbums = if (albumDirectoryPath.isNotEmpty()) {
                    allAlbums.filter { it.directoryPath != albumDirectoryPath }
                } else allAlbums
                AlbumPickDialog(
                    albums = filteredAlbums,
                    recentMoveAlbums = recentDirs,
                    onDismiss = {
                        showAlbumPickDialog = false
                        // pendingPickItems 由 onAlbumSelected 或重组时清理
                    },
                    onAlbumSelected = { targetDir, targetName, mode ->
                        showAlbumPickDialog = false
                        if (mode == PasteMode.MOVE) {
                            // MOVE 走独立入口：多选路径用 pendingPickItems，中转站路径用 clipboardItems
                            val moveItems = if (pendingPickItems.isNotEmpty()) {
                                val items = pendingPickItems.toList()
                                pendingPickItems = emptyList()
                                items
                            } else {
                                viewModel.clipboardItems.value.toList()
                            }
                            viewModel.moveItemsToAlbum(moveItems, targetDir, targetName, albumId.ifEmpty { null })
                            if (isMediaMultiSelect) {
                                exitMediaMultiSelect()
                            }
                        } else {
                            // COPY 直连：多选路径用 pendingPickItems，中转站路径用 clipboardItems
                            val copyItems = if (pendingPickItems.isNotEmpty()) {
                                val items = pendingPickItems.toList()
                                pendingPickItems = emptyList()
                                items
                            } else {
                                viewModel.clipboardItems.value.toList()
                            }
                            viewModel.copyItemsToAlbum(copyItems, targetDir, targetName, albumId.ifEmpty { null })
                        }
                    },
                    onCreateFolder = { name, onResult ->
                        folderCreateScope.launch(Dispatchers.IO) {
                            val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                            val dir = File(dcim, name)
                            val path = if (dir.mkdirs() || dir.exists()) {
                                // 创建成功后刷新相册列表加速 MediaStore 注册，降低新建后点击的竞态
                                viewModel.loadAlbums()
                                dir.absolutePath
                            } else null
                            onResult(path)
                        }
                    }
                )
            }

            // ── MOVE 失败提示对话框（按失败原因分组展示准确信息）──
            val moveFailures by viewModel.moveFailures.collectAsStateWithLifecycle()
            if (moveFailures.isNotEmpty()) {
                val grouped = moveFailures.groupBy { it.reason }.mapValues { it.value.size }
                val totalFailed = moveFailures.size
                AlertDialog(
                    onDismissRequest = { viewModel.clearMoveFailures() },
                    title = { Text("移动未完全成功") },
                    text = {
                        Column {
                            Text("共 $totalFailed 个文件移动失败：",
                                style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            grouped.forEach { (reason, count) ->
                                val message = when (reason) {
                                    GalleryViewModel.MoveFailureReason.SOURCE_DELETE_FAILED ->
                                        "$count 个文件已复制到目标但删除源文件失败。可能是系统文件或权限不足，请尝试在系统设置中开启「所有文件访问权限」。"
                                    GalleryViewModel.MoveFailureReason.TARGET_VERIFICATION_FAILED ->
                                        "$count 个文件移动失败：目标文件验证未通过。请检查存储空间和文件系统状态。"
                                    GalleryViewModel.MoveFailureReason.MEDIASTORE_UPDATE_FAILED ->
                                        "$count 个文件移动失败：MediaStore 更新异常。请检查存储空间是否充足。"
                                    GalleryViewModel.MoveFailureReason.SCAN_FAILED ->
                                        "$count 个文件已复制，但媒体扫描注册失败。可尝试刷新相册列表。"
                                    GalleryViewModel.MoveFailureReason.SOURCE_NOT_FOUND ->
                                        "$count 个文件在移动前已不存在，已被自动跳过。"
                                    GalleryViewModel.MoveFailureReason.UNKNOWN_ERROR ->
                                        "$count 个文件移动时出现未知错误。"
                                }
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                            }
                        }
                    },
                    confirmButton = {
                        val hasPermissionFailures = moveFailures.any {
                            it.reason == GalleryViewModel.MoveFailureReason.SOURCE_DELETE_FAILED
                        }
                        Button(onClick = {
                            viewModel.clearMoveFailures()
                            if (hasPermissionFailures) {
                                try {
                                    val intent = android.content.Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                        data = android.net.Uri.parse("package:${activity.packageName}")
                                    }
                                    activity.startActivity(intent)
                                } catch (_: Exception) { }
                            }
                        }) {
                            Text(if (hasPermissionFailures) "去开启权限" else "知道了")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.clearMoveFailures() }) { Text("关闭") }
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
                                androidx.compose.material3.Checkbox(
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
                    dismissButton = {}  // 无取消按钮，必须三选一
                )
            }

            // ── 粘贴进度覆盖层 ──
            val pasteProgress by viewModel.pasteProgress.collectAsStateWithLifecycle()
            if (pasteProgress != null) {
                PasteProgressOverlay(pasteProgress!!)
            }
        }
    }
}

/** 粘贴进度覆盖层 */
@Composable
private fun PasteProgressOverlay(progress: com.example.rcgallery.viewmodel.GalleryViewModel.PasteProgress) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0x99000000)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier.padding(32.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val label = if (progress.mode == com.example.rcgallery.viewmodel.PasteMode.MOVE) "移动" else "复制"
                Text(
                    text = "${label}中 ${progress.current}/${progress.total}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = progress.fileName,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { progress.current.toFloat() / progress.total.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            }
        }
    }
}

/** 媒体类型过滤选项 */
private enum class MediaFilterType(val label: String) {
    IMAGE("图片"), VIDEO("视频"), GIF("GIF")
}

/** 媒体显示模式 */
private sealed class MediaDisplayMode {
    data class Grid(val columns: Int) : MediaDisplayMode()
    data object List : MediaDisplayMode()
}

/** 媒体排序模式 */
private enum class MediaSortMode(val label: String) {
    DATE("时间"),
    NAME("文件名"),
    SIZE("文件大小"),
    IMAGE_SIZE("图片大小"),
    VIDEO_SIZE("视频大小")
}

private const val DEFAULT_MEDIA_GRID_COLUMNS = 4
private const val MEDIA_GAP_DP = 2
private const val LIST_THUMB_SIZE_DP = 48

private const val VIEW_TYPE_GRID = 0
private const val VIEW_TYPE_LIST = 1

/** 星标缩放因子：根据 Grid 列数调整，列数越多星标越小 */
private fun getMediaStarScale(columns: Int): Float = when (columns) {
    2 -> 1.15f
    3 -> 1.0f
    4 -> 0.85f
    5 -> 0.7f
    else -> 1.0f
}

private infix fun MediaDisplayMode.isSameAs(other: MediaDisplayMode): Boolean = when {
    this is MediaDisplayMode.Grid && other is MediaDisplayMode.Grid -> this.columns == other.columns
    this is MediaDisplayMode.List && other is MediaDisplayMode.List -> true
    else -> false
}

private class SimpleGridAdapter(
    private val onClick: (com.example.rcgallery.model.MediaItem) -> Unit,
    private val onToggleStar: (String) -> Unit,
    private val onManageTags: (com.example.rcgallery.model.MediaItem) -> Unit,
    private val context: android.content.Context,
    private val onLongClick: ((com.example.rcgallery.model.MediaItem) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var items: List<com.example.rcgallery.model.MediaItem> = emptyList()
    var starredUris: Set<String> = emptySet()
    var currentMode: MediaDisplayMode = MediaDisplayMode.Grid(DEFAULT_MEDIA_GRID_COLUMNS)
    var mediaTagsMap: Map<String, List<TagEntity>> = emptyMap()
    var selectedUris: Set<String> = emptySet()
    var isMultiSelectMode: Boolean = false

    init { setHasStableIds(true) }

    override fun getItemCount() = items.size

    override fun getItemId(position: Int): Long = items.getOrNull(position)?.id ?: 0L

    override fun getItemViewType(position: Int): Int {
        return if (currentMode is MediaDisplayMode.List) VIEW_TYPE_LIST else VIEW_TYPE_GRID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_LIST) {
            ListVH.create(parent, onToggleStar, onClick, onManageTags, context)
        } else {
            GridVH.create(parent, onToggleStar, onClick, context, onLongClick)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        val columns = when (val mode = currentMode) {
            is MediaDisplayMode.Grid -> mode.columns
            is MediaDisplayMode.List -> 1
        }
        when (holder) {
            is GridVH -> holder.bind(item, position, starredUris, selectedUris, columns, isMultiSelectMode)
            is ListVH -> holder.bind(item, position, starredUris, mediaTagsMap, selectedUris, isMultiSelectMode)
        }
    }

    // ── Grid ViewHolder ──

    private class GridVH private constructor(
        itemView: android.view.View,
        private val iv: ImageView,
        private val tv: TextView,
        val starIv: ImageView,
        val starContainer: android.view.View,
        private val starWrapper: FrameLayout,
        private val onClick: (com.example.rcgallery.model.MediaItem) -> Unit,
        private val onLongClick: ((com.example.rcgallery.model.MediaItem) -> Unit)? = null
    ) : RecyclerView.ViewHolder(itemView) {

        private var currentItem: com.example.rcgallery.model.MediaItem? = null

        init {
            itemView.setOnClickListener {
                val item = currentItem ?: return@setOnClickListener
                onClick(item)
            }
            if (onLongClick != null) {
                itemView.setOnLongClickListener {
                    val item = currentItem ?: return@setOnLongClickListener true
                    onLongClick(item)
                    true
                }
            }
        }

        companion object {
            fun create(parent: ViewGroup, onToggleStar: (String) -> Unit, onClick: (com.example.rcgallery.model.MediaItem) -> Unit, context: android.content.Context,
                       onLongClick: ((com.example.rcgallery.model.MediaItem) -> Unit)? = null): GridVH {
                val density = context.resources.displayMetrics.density
                val gapPx = (MEDIA_GAP_DP * density).toInt()
                val frame = object : FrameLayout(context) {
                    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                        super.onMeasure(widthSpec, widthSpec)
                    }
                }.apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(gapPx / 2, gapPx / 2, gapPx / 2, gapPx / 2)
                }
                val iv = ImageView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setShape(android.graphics.drawable.GradientDrawable.RECTANGLE)
                        setCornerRadius((6 * density).toFloat())
                        setColor(android.graphics.Color.TRANSPARENT)
                    }
                    clipToOutline = true
                }
                frame.addView(iv)
                val tv = TextView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
                        setPadding(4, 0, 4, 2)
                    }
                    setTextColor(android.graphics.Color.WHITE); textSize = 10f
                    setBackgroundColor(android.graphics.Color.argb(180, 0, 0, 0))
                    visibility = android.view.View.GONE
                }
                frame.addView(tv)

                // 星标：左上角
                val starContainer = FrameLayout(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        (48 * density).toInt(),
                        (48 * density).toInt(),
                        android.view.Gravity.TOP or android.view.Gravity.START
                    )
                    isClickable = true
                    focusable = android.view.View.FOCUSABLE
                }
                val starWrapper = FrameLayout(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        (25 * density).toInt(),
                        (25 * density).toInt(),
                        android.view.Gravity.TOP or android.view.Gravity.START
                    ).apply { setMargins((3 * density).toInt(), (3 * density).toInt(), 0, 0) }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setShape(android.graphics.drawable.GradientDrawable.OVAL)
                        setColor(android.graphics.Color.argb(120, 0, 0, 0))
                    }
                }
                val starIv = ImageView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        (18 * density).toInt(),
                        (18 * density).toInt(),
                        android.view.Gravity.CENTER
                    )
                    scaleType = ImageView.ScaleType.FIT_XY
                    setImageResource(com.example.rcgallery.R.drawable.ic_star)
                }
                starWrapper.addView(starIv)
                starContainer.addView(starWrapper)
                frame.addView(starContainer)

                starContainer.setOnClickListener {
                    val uriStr = it.tag as? String ?: return@setOnClickListener
                    onToggleStar(uriStr)
                }

                // ── 多选对号：居中半透明绿色圆形 + 白色 ✓ ──
                val checkmarkContainer = FrameLayout(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        (36 * density).toInt(),
                        (36 * density).toInt(),
                        android.view.Gravity.CENTER
                    )
                    visibility = android.view.View.GONE
                    id = android.R.id.checkbox
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setShape(android.graphics.drawable.GradientDrawable.OVAL)
                        setColor(android.graphics.Color.argb(200, 76, 175, 80))
                    }
                }
                val checkmarkTv = TextView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.CENTER
                    )
                    text = "✓"
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 20f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                checkmarkContainer.addView(checkmarkTv)
                frame.addView(checkmarkContainer)

                return GridVH(frame, iv, tv, starIv, starContainer, starWrapper, onClick, onLongClick)
            }
        }

        fun bind(item: com.example.rcgallery.model.MediaItem, position: Int, starredUris: Set<String>, selectedUris: Set<String> = emptySet(), columns: Int = DEFAULT_MEDIA_GRID_COLUMNS, isMultiSelectMode: Boolean = false) {
            val previousUri = currentItem?.uri
            currentItem = item
            starContainer.tag = item.uri.toString()
            val isStarred = item.uri.toString() in starredUris
            starContainer.isSelected = isStarred
            starIv.colorFilter = android.graphics.PorterDuffColorFilter(
                if (isStarred) android.graphics.Color.rgb(255, 193, 7) else android.graphics.Color.rgb(160, 160, 160),
                android.graphics.PorterDuff.Mode.SRC_IN
            )
            if (previousUri != item.uri || iv.drawable == null) {
                iv.load(item.uri) { crossfade(false) }
            }
            if (item.isVideo) {
                val formatCode = item.fileName.substringAfterLast('.', "")
                    .takeIf { it.isNotBlank() }?.uppercase() ?: "VIDEO"
                val durationText = if (item.duration > 0)
                    "%d:%02d".format(item.duration / 1000 / 60, item.duration / 1000 % 60)
                else ""
                tv.text = if (durationText.isNotEmpty()) "$formatCode $durationText" else formatCode
                tv.visibility = android.view.View.VISIBLE
            } else if (item.isGif) {
                tv.text = "GIF"
                tv.visibility = android.view.View.VISIBLE
            } else { tv.visibility = android.view.View.GONE }
            updateStarSize(columns)
            val hideStar = columns == 4 || columns == 5
            // 多选模式（含 0 已选但模式开启中）：隐藏星标，显示对号
            val inMultiSelect = isMultiSelectMode || selectedUris.isNotEmpty()
            val isSelected = item.uri.toString() in selectedUris
            starContainer.visibility = if (inMultiSelect || hideStar) android.view.View.GONE else android.view.View.VISIBLE
            starContainer.isClickable = !hideStar
            starContainer.isEnabled = !hideStar
            val checkmark = itemView.findViewById<FrameLayout>(android.R.id.checkbox)
            if (checkmark != null) {
                checkmark.visibility = if (isSelected) android.view.View.VISIBLE else android.view.View.GONE
            }
        }

        private fun updateStarSize(columns: Int) {
            val density = itemView.resources.displayMetrics.density
            val scale = getMediaStarScale(columns)
            starContainer.layoutParams = (starContainer.layoutParams as FrameLayout.LayoutParams).apply {
                width = (48 * density * scale).toInt()
                height = (48 * density * scale).toInt()
            }
            starWrapper.layoutParams = (starWrapper.layoutParams as FrameLayout.LayoutParams).apply {
                width = (25 * density * scale).toInt()
                height = (25 * density * scale).toInt()
                setMargins((3 * density * scale).toInt(), (3 * density * scale).toInt(), 0, 0)
            }
            starIv.layoutParams = (starIv.layoutParams as FrameLayout.LayoutParams).apply {
                width = (18 * density * scale).toInt()
                height = (18 * density * scale).toInt()
            }
        }
    }

    // ── List ViewHolder ──

    private class ListVH private constructor(
        itemView: android.view.View,
        private val iv: ImageView,
        private val nameTv: TextView,
        private val infoTv: TextView,
        val starIv: ImageView,
        val starContainer: android.view.View,
        private val checkmarkContainer: FrameLayout,
        private val selectedOverlay: android.view.View,
        private val onClick: (com.example.rcgallery.model.MediaItem) -> Unit,
        private val onManageTags: (com.example.rcgallery.model.MediaItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private var currentItem: com.example.rcgallery.model.MediaItem? = null
        private val tagRow: LinearLayout = itemView.findViewWithTag("media_tag_row")
        private val tagChips: Array<TextView> = Array(8) { idx ->
            val ctx = itemView.context
            val density = ctx.resources.displayMetrics.density
            TextView(ctx).apply {
                textSize = 10f
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundDrawable(
                    android.graphics.drawable.GradientDrawable().apply {
                        setShape(android.graphics.drawable.GradientDrawable.RECTANGLE)
                        setCornerRadius(6 * density)
                        setColor(android.graphics.Color.argb(180, 100, 140, 255))
                    }
                )
                setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
                maxLines = 1
                visibility = android.view.View.GONE
                tagRow.addView(this, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, (4 * density).toInt(), 0) })
            }
        }

        init {
            itemView.setOnClickListener {
                val item = currentItem ?: return@setOnClickListener
                onClick(item)
            }
        }

        companion object {
            fun create(parent: ViewGroup, onToggleStar: (String) -> Unit, onClick: (com.example.rcgallery.model.MediaItem) -> Unit,
                       onManageTags: (com.example.rcgallery.model.MediaItem) -> Unit, context: android.content.Context): ListVH {
                val density = context.resources.displayMetrics.density
                val root = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt()) }
                }

                val thumbSize = (LIST_THUMB_SIZE_DP * density).toInt()
                val iv = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(thumbSize, thumbSize)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setShape(android.graphics.drawable.GradientDrawable.RECTANGLE)
                        setCornerRadius((6 * density).toFloat())
                        setColor(android.graphics.Color.TRANSPARENT)
                    }
                    clipToOutline = true
                }
                // 选中覆盖层（半透明蓝色 + checkmark）
                val thumbWrapper = FrameLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(thumbSize, thumbSize)
                }
                val selectedOverlay = android.view.View(context).apply {
                    layoutParams = FrameLayout.LayoutParams(thumbSize, thumbSize)
                    setBackgroundColor(android.graphics.Color.argb(100, 68, 138, 255))
                    visibility = android.view.View.GONE
                }
                val checkmarkContainer = FrameLayout(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        (24 * density).toInt(),
                        (24 * density).toInt(),
                        android.view.Gravity.CENTER
                    )
                    visibility = android.view.View.GONE
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setShape(android.graphics.drawable.GradientDrawable.OVAL)
                        setColor(android.graphics.Color.argb(220, 76, 175, 80))
                    }
                }
                val checkmarkTv = android.widget.TextView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.CENTER
                    )
                    text = "✓"
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 16f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                checkmarkContainer.addView(checkmarkTv)
                thumbWrapper.addView(iv)
                thumbWrapper.addView(selectedOverlay)
                thumbWrapper.addView(checkmarkContainer)
                row.addView(thumbWrapper)

                val textColumn = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        weight = 1f
                        setMargins((10 * density).toInt(), 0, 0, 0)
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                }

                val nameTv = TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setTextSize(14f)
                    setTextColor(android.graphics.Color.WHITE)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                textColumn.addView(nameTv)

                // ── TAG 行（纯 LinearLayout，不拦截点击，点 TAG 区域打开媒体项）──
                val tagRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, (3 * density).toInt(), 0, 0) }
                    tag = "media_tag_row"
                    visibility = android.view.View.GONE
                }
                // + 按钮（放在 TAG 行内，唯一可点击的交互元素）
                val tagAddChip = TextView(context).apply {
                    text = "+"
                    textSize = 12f
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundDrawable(
                        android.graphics.drawable.GradientDrawable().apply {
                            setShape(android.graphics.drawable.GradientDrawable.OVAL)
                            setColor(android.graphics.Color.argb(180, 100, 180, 100))
                        }
                    )
                    gravity = android.view.Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        (20 * density).toInt(),
                        (20 * density).toInt()
                    ).apply { setMargins(0, 0, (4 * density).toInt(), 0) }
                    isClickable = true
                    focusable = android.view.View.FOCUSABLE
                }
                tagRow.addView(tagAddChip)
                textColumn.addView(tagRow)

                val infoTv = TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setTextSize(12f)
                    setTextColor(android.graphics.Color.GRAY)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                textColumn.addView(infoTv)

                row.addView(textColumn)

                // 星标：右侧
                val starContainer = FrameLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        (48 * density).toInt(),
                        (48 * density).toInt()
                    ).apply {
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                    isClickable = true
                    focusable = android.view.View.FOCUSABLE
                }
                val starIv = ImageView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        (24 * density).toInt(),
                        (24 * density).toInt(),
                        android.view.Gravity.CENTER
                    )
                    scaleType = ImageView.ScaleType.FIT_XY
                    setImageResource(com.example.rcgallery.R.drawable.ic_star)
                }
                starContainer.addView(starIv)
                row.addView(starContainer)

                root.addView(row)

                // 分隔线
                val divider = android.view.View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (1 * density).toInt()
                    ).apply { setMargins((12 * density).toInt(), 0, (12 * density).toInt(), 0) }
                    setBackgroundColor(android.graphics.Color.argb(25, 255, 255, 255))
                }
                root.addView(divider)

                // 星标点击（乐观变色）
                starContainer.setOnClickListener {
                    val uriStr = starContainer.tag as? String ?: return@setOnClickListener
                    starContainer.isSelected = !starContainer.isSelected
                    starIv.colorFilter = android.graphics.PorterDuffColorFilter(
                        if (starContainer.isSelected) android.graphics.Color.rgb(255, 193, 7) else android.graphics.Color.rgb(160, 160, 160),
                        android.graphics.PorterDuff.Mode.SRC_IN
                    )
                    onToggleStar(uriStr)
                }

                return ListVH(root, iv, nameTv, infoTv, starIv, starContainer, checkmarkContainer, selectedOverlay, onClick, onManageTags)
            }
        }

        fun bind(item: com.example.rcgallery.model.MediaItem, position: Int, starredUris: Set<String>, mediaTagsMap: Map<String, List<TagEntity>> = emptyMap(), selectedUris: Set<String> = emptySet(), isMultiSelectMode: Boolean = false) {
            val previousUri = currentItem?.uri
            currentItem = item
            starContainer.tag = item.uri.toString()
            val isStarred = item.uri.toString() in starredUris
            starContainer.isSelected = isStarred
            starIv.colorFilter = android.graphics.PorterDuffColorFilter(
                if (isStarred) android.graphics.Color.rgb(255, 193, 7) else android.graphics.Color.rgb(160, 160, 160),
                android.graphics.PorterDuff.Mode.SRC_IN
            )
            // 多选模式（含 0 已选但模式开启中）：隐藏星标
            val inMultiSelect = isMultiSelectMode || selectedUris.isNotEmpty()
            val isSelected = item.uri.toString() in selectedUris
            if (inMultiSelect) {
                selectedOverlay.visibility = if (isSelected) android.view.View.VISIBLE else android.view.View.GONE
                checkmarkContainer.visibility = if (isSelected) android.view.View.VISIBLE else android.view.View.GONE
                itemView.setBackgroundColor(
                    if (isSelected) android.graphics.Color.argb(30, 68, 138, 255)
                    else android.graphics.Color.TRANSPARENT
                )
            } else {
                selectedOverlay.visibility = android.view.View.GONE
                checkmarkContainer.visibility = android.view.View.GONE
                itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
            if (previousUri != item.uri || iv.drawable == null) {
                iv.load(item.uri) { crossfade(false) }
            }
            nameTv.text = item.fileName
            infoTv.text = buildString {
                if (item.isVideo && item.duration > 0) {
                    append("%d:%02d".format(item.duration / 1000 / 60, item.duration / 1000 % 60))
                } else if (item.width > 0 && item.height > 0) {
                    append("${item.width}×${item.height}")
                } else {
                    append(com.example.rcgallery.util.FormatUtil.formatFileSize(item.size))
                }
                if (item.size > 0) {
                    append(" · ")
                    append(com.example.rcgallery.util.FormatUtil.formatFileSize(item.size))
                }
            }
            // ── TAG 行 ──
            val tags = mediaTagsMap[item.filePath] ?: emptyList()

            // + 按钮点击
            (tagRow.getChildAt(0) as? TextView)?.setOnClickListener { onManageTags(item) }

            // 预建 chip：只改 text 和 visibility，不新建 View
            tags.forEachIndexed { i, tag ->
                val chip = tagChips.getOrNull(i) ?: return@forEachIndexed
                chip.text = tag.name
                chip.visibility = android.view.View.VISIBLE
            }
            // 隐藏多余的 chip
            for (i in tags.size until tagChips.size) {
                tagChips[i].visibility = android.view.View.GONE
            }
            tagRow.visibility = android.view.View.VISIBLE
        }
    }
}

// ── 显示模式选择器 ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaDisplayModeSelector(
    currentMode: MediaDisplayMode,
    onSelectMode: (MediaDisplayMode) -> Unit,
    hasActiveFilter: Boolean = false,
    onOpenFilter: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val gridOptions = listOf(
        MediaDisplayMode.Grid(2) to "2列",
        MediaDisplayMode.Grid(3) to "3列",
        MediaDisplayMode.Grid(4) to "4列",
        MediaDisplayMode.Grid(5) to "5列",
    )
    val isListMode = currentMode is MediaDisplayMode.List
    val isSelected = { mode: MediaDisplayMode -> mode isSameAs currentMode }
    var expanded by remember { mutableStateOf(false) }

    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // ── 筛选按钮 ──
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (hasActiveFilter) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
            tonalElevation = if (hasActiveFilter) 0.dp else 3.dp,
            shadowElevation = if (hasActiveFilter) 0.dp else 4.dp,
            onClick = onOpenFilter
        ) {
            Text("☰ 筛选",
                color = if (hasActiveFilter) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        // 列数选择
        Box {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (!isListMode) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                tonalElevation = if (!isListMode) 0.dp else 3.dp,
                shadowElevation = if (!isListMode) 0.dp else 4.dp,
                onClick = { expanded = true }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isListMode) "列数" else gridOptions.first { isSelected(it.first) }.second,
                        color = if (!isListMode) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "展开",
                        modifier = Modifier.size(16.dp),
                        tint = if (!isListMode) MaterialTheme.colorScheme.onPrimaryContainer
                               else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                gridOptions.forEach { (mode, label) ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isSelected(mode)) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        },
                        onClick = {
                            onSelectMode(mode)
                            expanded = false
                        }
                    )
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (isListMode) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
            tonalElevation = if (isListMode) 0.dp else 3.dp,
            shadowElevation = if (isListMode) 0.dp else 4.dp,
            onClick = { onSelectMode(MediaDisplayMode.List) }
        ) {
            Text(
                text = "☰ 列表",
                color = if (isListMode) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

// ── 媒体排序方式选择器（同款风格下拉框）──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaSortSelector(
    currentSort: MediaSortMode,
    onSelectSort: (MediaSortMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = MediaSortMode.entries.toList()
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 4.dp,
            onClick = { expanded = true }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "${currentSort.label}",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelMedium
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "排序方式",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { sortMode ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = sortMode.label,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f)
                            )
                            if (sortMode == currentSort) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    onClick = {
                        onSelectSort(sortMode)
                        expanded = false
                    }
                )
            }
        }
    }
}

// ── 底部 TAG 栏（自下向上排列，每行最多 50% 屏幕宽度，居中）──
@Composable
private fun AlbumTagBarBottom(
    tags: List<TagEntity>,
    onAddTag: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val textPaint = remember { android.text.TextPaint() }
    val fontSizeSp = 12.sp
    val chipHozPaddingDp = 8.dp
    val plusSizeDp = 22.dp
    val chipGapDp = 4.dp
    val rowGapDp = 2.dp
    val plusSizePx = with(density) { plusSizeDp.toPx() }
    val gapPx = with(density) { chipGapDp.toPx() }
    val hozPaddingPx = with(density) { chipHozPaddingDp.toPx() }
    val textSizePx = with(density) { fontSizeSp.toPx() }

    BoxWithConstraints(modifier = modifier) {
        val halfWidthPx = with(density) { (maxWidth / 2).toPx() }

        // 将 TAG 从底部开始向上排布成行
        val rows = remember(tags, halfWidthPx) {
            textPaint.textSize = textSizePx

            val result = mutableListOf<MutableList<TagEntity>>()
            var currentRow = mutableListOf<TagEntity>()
            var currentWidth = plusSizePx + gapPx  // + 按钮始终在底部行首

            for (tag in tags) {
                val textWidth = textPaint.measureText(tag.name)
                val chipWidth = textWidth + hozPaddingPx * 2
                val needed = if (currentRow.isEmpty()) currentWidth + chipWidth
                             else currentWidth + gapPx + chipWidth

                if (needed > halfWidthPx) {
                    if (currentRow.isNotEmpty()) {
                        result.add(currentRow)
                    }
                    currentRow = mutableListOf(tag)
                    currentWidth = chipWidth
                } else {
                    currentWidth = needed
                    currentRow.add(tag)
                }
            }
            if (currentRow.isNotEmpty()) result.add(currentRow)
            // result[0] = 最底行, result[last] = 最顶行
            result
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (rows.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF64B464),
                        modifier = Modifier
                            .size(plusSizeDp)
                            .clickable { onAddTag() }
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "+",
                                fontSize = 13.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else {
                // 从上到下渲染：先渲染顶行，最后渲染底行
                for (i in rows.lastIndex downTo 0) {
                    if (i < rows.lastIndex) {
                        Spacer(Modifier.height(rowGapDp))
                    }
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                    ) {
                        // 最底行（靠近过滤按钮）显示 + 按钮在最前面
                        if (i == 0) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFF64B464),
                                modifier = Modifier
                                    .size(plusSizeDp)
                                    .clickable { onAddTag() }
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        "+",
                                        fontSize = 13.sp,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                        // TAG chips
                        rows[i].forEach { tag ->
                            Spacer(Modifier.width(chipGapDp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF6468B4)
                            ) {
                                Text(
                                    tag.name,
                                    fontSize = fontSizeSp,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(
                                        horizontal = chipHozPaddingDp,
                                        vertical = 3.dp
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
