package com.example.rcgallery.ui.screen

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.rcgallery.ui.component.DevOverlay
import com.example.rcgallery.ui.component.FastScrollerView
import com.example.rcgallery.ui.component.InertiaSettingsPanel
import com.example.rcgallery.ui.component.SettingsOverlay
import com.example.rcgallery.ui.component.FloatingJumpButton
import com.example.rcgallery.ui.component.FpsMonitor
import com.example.rcgallery.ui.component.FpsMonitorEnabled
import com.example.rcgallery.util.AppLogger
import com.example.rcgallery.viewmodel.GalleryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaGridScreen(
    albumId: String = "",
    albumName: String = "",
    onMediaClick: (Int) -> Unit = {},
    onBackClick: () -> Unit = {},
    onGoHome: () -> Unit = {}    // 直接回到 AlbumGrid 主页
) {
    val activity = LocalContext.current as ComponentActivity
    val viewModel: GalleryViewModel = viewModel(activity)
    val mediaItems by viewModel.mediaItems.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val starredMediaUris by viewModel.starredMediaUris.collectAsStateWithLifecycle()

    // ── 相册切换时防止旧数据闪烁：加载完成前不展示 RecyclerView ──
    // 改名后 albumId 变化但 mediaItems 已存在（renameNow 已更新），不显示 loading
    var isLoadingAlbum by remember { mutableStateOf(true) }
    LaunchedEffect(albumId) {
        AppLogger.d("MediaGrid", "LaunchedEffect albumId=[$albumId]  items.size=${mediaItems.size}")
        if (mediaItems.isEmpty()) {
            isLoadingAlbum = true
        }
        viewModel.loadMedia(albumId = albumId)
    }
    LaunchedEffect(isLoading) {
        if (!isLoading) isLoadingAlbum = false
    }

    // ── 设置面板 / 日志（复用组件 SettingsOverlay）──

    // ── Preview overlay 状态（代替 navigation push，防止 RecyclerView 销毁）──
    var selectedPhotoIndex by remember { mutableIntStateOf(-1) }
    var selectedPhotoItem by remember { mutableStateOf<com.example.rcgallery.model.MediaItem?>(null) }
    if (selectedPhotoIndex >= 0) {
        BackHandler { selectedPhotoIndex = -1 }
    }

    // 音量开关：相册级别记忆（退出相册后自动重置）
    var volumeEnabled by remember { mutableStateOf(false) }

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
                    if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) {
                        viewModel.renameNow(albumId, newName) { ok ->
                            if (ok) {
                                Toast.makeText(renameContext, "相册已重命名", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(renameContext, "重命名失败，请重试", Toast.LENGTH_SHORT).show()
                            }
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
                }) { Text("确认") }
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
                // 相册切换/首次加载→显示 loading，防止旧数据闪烁
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = if (albumName.isNotEmpty()) albumName else "所有文件",
                                    maxLines = 1,
                                    modifier = Modifier.clickable { showAlbumRenameDialog = true }
                                )
                            },
                            navigationIcon = { TextButton(onClick = onBackClick) { Text("← 返回") } },
                            windowInsets = WindowInsets(0, 0, 0, 0),  // 外层 Scaffold 已处理状态栏 insets
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                            actions = {
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
                            }
                        )
                    }
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
                                val adapter = SimpleGridAdapter(
                                    onClick = { item ->
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
                                    context = ctx
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
                                adapter.items = sortedItems
                                adapter.starredUris = starredMediaUris
                                if (prevMode != currentMode) {
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
                                    adapter.notifyDataSetChanged()
                                    scroller.refresh()
                                }
                            },
                            modifier = Modifier.fillMaxSize().padding(padding)
                        )
                        // ── 显示模式 + 排序 悬浮工具栏（在 RecyclerView 上方）──
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
                                }
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
                        }
                        }
                    }
                }
            FpsMonitor(enabled = FpsMonitorEnabled, modifier = Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 8.dp))
            if (showInertiaSettings) InertiaSettingsPanel(
                onDismiss = { showInertiaSettings = false },
                onOpenLog = { showInertiaSettings = false; showLogDialog = true }
            )

            // ── 媒体类型过滤按钮（底部居中，略抬上）──
            if (availableTypes.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
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
            }

            FloatingJumpButton(recyclerView = mediaRvRef.value, modifier = Modifier.align(Alignment.BottomStart))

            // ── Preview 全屏覆盖层（不通过 navigation，RecyclerView 保持存活）──
            if (selectedPhotoIndex >= 0) {
                PreviewScreen(
                    initialIndex = selectedPhotoIndex,
                    onBackClick = {
                        selectedPhotoIndex = -1
                    },
                    onGoHome = { selectedPhotoIndex = -1; onGoHome() },
                    volumeEnabled = volumeEnabled,
                    onVolumeToggle = { volumeEnabled = !volumeEnabled },
                    items = sortedItems
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
    private val context: android.content.Context
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var items: List<com.example.rcgallery.model.MediaItem> = emptyList()
    var starredUris: Set<String> = emptySet()
    var currentMode: MediaDisplayMode = MediaDisplayMode.Grid(DEFAULT_MEDIA_GRID_COLUMNS)

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int): Int {
        return if (currentMode is MediaDisplayMode.List) VIEW_TYPE_LIST else VIEW_TYPE_GRID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_LIST) {
            ListVH.create(parent, onToggleStar, onClick, context)
        } else {
            GridVH.create(parent, onToggleStar, onClick, context)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        val columns = when (val mode = currentMode) {
            is MediaDisplayMode.Grid -> mode.columns
            is MediaDisplayMode.List -> 1
        }
        when (holder) {
            is GridVH -> holder.bind(item, position, starredUris, columns)
            is ListVH -> holder.bind(item, position, starredUris)
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
        private val onClick: (com.example.rcgallery.model.MediaItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private var currentItem: com.example.rcgallery.model.MediaItem? = null

        init {
            itemView.setOnClickListener {
                val item = currentItem ?: return@setOnClickListener
                onClick(item)
            }
        }

        companion object {
            fun create(parent: ViewGroup, onToggleStar: (String) -> Unit, onClick: (com.example.rcgallery.model.MediaItem) -> Unit, context: android.content.Context): GridVH {
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

                return GridVH(frame, iv, tv, starIv, starContainer, starWrapper, onClick)
            }
        }

        fun bind(item: com.example.rcgallery.model.MediaItem, position: Int, starredUris: Set<String>, columns: Int = DEFAULT_MEDIA_GRID_COLUMNS) {
            currentItem = item
            starContainer.tag = item.uri.toString()
            val isStarred = item.uri.toString() in starredUris
            starContainer.isSelected = isStarred
            starIv.colorFilter = android.graphics.PorterDuffColorFilter(
                if (isStarred) android.graphics.Color.rgb(255, 193, 7) else android.graphics.Color.rgb(160, 160, 160),
                android.graphics.PorterDuff.Mode.SRC_IN
            )
            if (item.isVideo) iv.load(item.uri) { crossfade(true) } else iv.load(item.uri) { crossfade(true) }
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
        private val onClick: (com.example.rcgallery.model.MediaItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private var currentItem: com.example.rcgallery.model.MediaItem? = null

        init {
            itemView.setOnClickListener {
                val item = currentItem ?: return@setOnClickListener
                onClick(item)
            }
        }

        companion object {
            fun create(parent: ViewGroup, onToggleStar: (String) -> Unit, onClick: (com.example.rcgallery.model.MediaItem) -> Unit, context: android.content.Context): ListVH {
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
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt()) }
                }

                val thumbSize = (LIST_THUMB_SIZE_DP * density).toInt()
                val iv = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(thumbSize, thumbSize)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                row.addView(iv)

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

                return ListVH(root, iv, nameTv, infoTv, starIv, starContainer, onClick)
            }
        }

        fun bind(item: com.example.rcgallery.model.MediaItem, position: Int, starredUris: Set<String>) {
            currentItem = item
            starContainer.tag = item.uri.toString()
            val isStarred = item.uri.toString() in starredUris
            starContainer.isSelected = isStarred
            starIv.colorFilter = android.graphics.PorterDuffColorFilter(
                if (isStarred) android.graphics.Color.rgb(255, 193, 7) else android.graphics.Color.rgb(160, 160, 160),
                android.graphics.PorterDuff.Mode.SRC_IN
            )
            iv.load(item.uri) { crossfade(true) }
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
        }
    }
}

// ── 显示模式选择器 ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaDisplayModeSelector(
    currentMode: MediaDisplayMode,
    onSelectMode: (MediaDisplayMode) -> Unit,
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
                    modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
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
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
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
                modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            ) {
                Text(
                    text = "排序: ${currentSort.label}",
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
