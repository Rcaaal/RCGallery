package com.example.rcgallery.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.rcgallery.model.Album
import com.example.rcgallery.ui.component.DevOverlay
import com.example.rcgallery.ui.component.FastScrollerView
import com.example.rcgallery.ui.component.InertiaSettingsPanel
import com.example.rcgallery.ui.screen.TrashScreen
import com.example.rcgallery.ui.component.FloatingJumpButton
import com.example.rcgallery.ui.component.FpsMonitor
import com.example.rcgallery.ui.component.FpsMonitorEnabled
import com.example.rcgallery.util.AppLogger
import com.example.rcgallery.util.FormatUtil
import com.example.rcgallery.viewmodel.GalleryViewModel

/**
 * 相册显示模式。
 */
private sealed class AlbumDisplayMode {
    data class Grid(val columns: Int) : AlbumDisplayMode()
    data object List : AlbumDisplayMode()
}

private const val DEFAULT_GRID_COLUMNS = 3
private const val ITEM_CORNER_RADIUS_DP = 8  // 网格/列表项目中缩略图的圆角半径（dp），可调

/**
 * 给 ImageView 设置圆角裁剪（通过 GradientDrawable + clipToOutline）。
 */
private fun ImageView.setRoundedCorner(radiusDp: Int) {
    val r = resources.displayMetrics.density * radiusDp
    val bg = android.graphics.drawable.GradientDrawable().apply {
        setShape(android.graphics.drawable.GradientDrawable.RECTANGLE)
        setCornerRadius(r)
        setColor(android.graphics.Color.TRANSPARENT)
    }
    background = bg
    clipToOutline = true
}


/** 星标缩放因子：根据 Grid 列数调整，列数越多星标越小 */
private fun getAlbumStarScale(columns: Int): Float = when (columns) {
    2 -> 1.15f
    3 -> 1.0f
    4 -> 0.85f
    5 -> 0.7f
    else -> 1.0f
}

private infix fun AlbumDisplayMode.isSameAs(other: AlbumDisplayMode): Boolean = when {
    this is AlbumDisplayMode.Grid && other is AlbumDisplayMode.Grid -> this.columns == other.columns
    this is AlbumDisplayMode.List && other is AlbumDisplayMode.List -> true
    else -> false
}

/** 相册排序模式 */
private enum class AlbumSortMode(val label: String) {
    DATE("创建时间"),
    NAME("相册名"),
    SIZE("相册大小"),
    IMAGE_COUNT("图片数量"),
    VIDEO_COUNT("视频数量")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumGridScreen(
    onSearchClick: () -> Unit = {},
    onAlbumActiveChanged: (Boolean) -> Unit = {}  // true=相册打开, false=退出相册
) {
    val context = LocalContext.current
    val viewModel: GalleryViewModel = viewModel(context as ComponentActivity)
    val rawAlbums by viewModel.albums.collectAsStateWithLifecycle()
    val starredIds by viewModel.starredBucketIds.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    // ── 相册显示模式 & 排序模式（持久化，需在 remember(albums) 之前声明）──
    val prefs = context.getSharedPreferences("rcgallery_prefs", android.content.Context.MODE_PRIVATE)
    var displayMode by remember {
        val saved = prefs.getString("album_display_mode", "") ?: ""
        mutableStateOf(when {
            saved == "list" -> AlbumDisplayMode.List
            saved.startsWith("grid_") -> {
                val cols = saved.removePrefix("grid_").toIntOrNull() ?: DEFAULT_GRID_COLUMNS
                AlbumDisplayMode.Grid(cols)
            }
            else -> AlbumDisplayMode.Grid(DEFAULT_GRID_COLUMNS)
        })
    }
    var albumSortMode by remember {
        val saved = prefs.getString("album_sort_mode", "") ?: ""
        mutableStateOf(when (saved) {
            "name" -> AlbumSortMode.NAME
            "size" -> AlbumSortMode.SIZE
            "image_count" -> AlbumSortMode.IMAGE_COUNT
            "video_count" -> AlbumSortMode.VIDEO_COUNT
            else -> AlbumSortMode.DATE
        })
    }

    // 本地排序：星标相册永久置顶，再按当前排序模式排列
    val albums = remember(rawAlbums, starredIds, albumSortMode) {
        rawAlbums.sortedWith(
            compareByDescending<Album> { it.bucketId in starredIds }
                .then(when (albumSortMode) {
                    AlbumSortMode.DATE -> compareByDescending { it.dateAdded }
                    AlbumSortMode.NAME -> compareBy { it.bucketName }
                    AlbumSortMode.SIZE -> compareByDescending { it.totalSize }
                    AlbumSortMode.IMAGE_COUNT -> compareByDescending { it.imageCount }
                    AlbumSortMode.VIDEO_COUNT -> compareByDescending { it.videoCount }
                })
        )
    }

    // ── MediaGrid overlay 状态（代替 navigation push，LazyVerticalGrid 保持存活）──
    var selectedAlbumId by remember { mutableStateOf<String?>(null) }
    var selectedAlbumName by remember { mutableStateOf("") }
    // albums（来自 ViewModel）变化时联动更新相册名
    // 优先按 bucketId 匹配；如果 bucketId 变了（如改名后 MediaStore 重新扫描），按相册名回退
    LaunchedEffect(albums, selectedAlbumId) {
        val id = selectedAlbumId
        if (id != null) {
            val found = albums.find { it.bucketId == id }
            if (found != null) {
                selectedAlbumName = found.bucketName
            } else {
                // bucketId 变了：尝试用当前名字匹配（改名后 MediaStore 分配新 bucketId）
                val byName = albums.find { it.bucketName == selectedAlbumName }
                if (byName != null) {
                    selectedAlbumName = byName.bucketName
                    selectedAlbumId = byName.bucketId
                }
                // 都找不到则保持现状
            }
        }
    }
    if (selectedAlbumId != null) {
        BackHandler { selectedAlbumId = null }
    }

    // ── 设置面板 / 日志（复用组件 SettingsOverlay）──
    // ── 回收站状态 ──
    val trashCount by viewModel.trashCount.collectAsStateWithLifecycle()
    var showTrash by remember { mutableStateOf(false) }
    if (showTrash) {
        BackHandler { showTrash = false }
    }
    // ── 日志面板 ──
    var showLogDialog by remember { mutableStateOf(false) }
    if (showLogDialog) {
        Box(Modifier.fillMaxSize().clickable { showLogDialog = false }) {
            DevOverlay(initialShow = true)
        }
    }
    // ── 设置面板 ──
    var showInertiaSettings by remember { mutableStateOf(false) }
    if (showInertiaSettings) InertiaSettingsPanel(
        onDismiss = { showInertiaSettings = false },
        onOpenLog = { showInertiaSettings = false; showLogDialog = true }
    )

    // ── 相册重命名状态 ──
    var showAlbumRenameDialog by remember { mutableStateOf(false) }
    var renameTargetAlbum by remember { mutableStateOf<Album?>(null) }
    val renameLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        Toast.makeText(context, "授权成功，请重新长按相册进行改名", Toast.LENGTH_SHORT).show()
    }
    if (showAlbumRenameDialog && renameTargetAlbum != null) {
        val target = renameTargetAlbum!!
        val currentName = target.bucketName.ifEmpty { "未知" }
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
                    renameTargetAlbum = null
                    if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) {
                        viewModel.renameNow(target.bucketId, newName) { ok ->
                            if (ok) {
                                Toast.makeText(context, "相册已重命名", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "重命名失败，请重试", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        try {
                            renameLauncher.launch(
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
            dismissButton = { TextButton(onClick = { showAlbumRenameDialog = false; renameTargetAlbum = null }) { Text("取消") } }
        )
    }

    // ── 权限状态 ──
    var hasPermission by remember {
        mutableStateOf(checkPermission(context))
    }

    // ── RecyclerView 引用（给 FloatingJumpButton 用）──
    val albumRvRef = remember { mutableStateOf<RecyclerView?>(null) }

    // ── 权限请求 launcher ──
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        hasPermission = granted.values.all { it }
        if (hasPermission) {
            viewModel.loadAlbums()
        }
    }

    // ── 首次加载 ──
    LaunchedEffect(hasPermission) {
        AppLogger.d("AlbumGrid", "LaunchedEffect hasPermission=$hasPermission albums.size=${albums.size}")
        if (hasPermission && albums.isEmpty()) {
            viewModel.loadAlbums()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            if (!hasPermission) {
                NoPermissionContent(onRequestPermission = {
                    val permissions = getRequiredPermissions()
                    permissionLauncher.launch(permissions)
                })
            } else if (isLoading && albums.isEmpty()) {
                LoadingContent()
            } else if (albums.isEmpty()) {
                LoadingContent()
            } else {
                AlbumGridContent(
                    albums = albums,
                    starredIds = starredIds,
                    onAlbumClick = { album ->
                        AppLogger.d("AlbumGrid", "click album=${album.bucketName} id=${album.bucketId} count=${album.count}")
                        selectedAlbumId = album.bucketId
                        selectedAlbumName = album.bucketName
                        // 同步通知上层隐藏底部栏（不用 LaunchedEffect，防延迟一帧）
                        onAlbumActiveChanged(true)
                    },
                    onAlbumLongClick = { album ->
                        renameTargetAlbum = album
                        showAlbumRenameDialog = true
                    },
                    onToggleStar = { bucketId -> viewModel.toggleStar(bucketId) },
                    onRefresh = { viewModel.loadAlbums() },
                    displayMode = displayMode,
                    onSelectMode = { mode ->
                        displayMode = mode
                        prefs.edit().putString("album_display_mode", when (mode) {
                            is AlbumDisplayMode.List -> "list"
                            is AlbumDisplayMode.Grid -> "grid_${mode.columns}"
                        }).apply()
                    },
                    albumRvRef = albumRvRef,
                    albumSortMode = albumSortMode,
                    onSelectSort = { mode ->
                        albumSortMode = mode
                        prefs.edit().putString("album_sort_mode", when (mode) {
                            AlbumSortMode.NAME -> "name"
                            AlbumSortMode.DATE -> "date"
                            AlbumSortMode.SIZE -> "size"
                            AlbumSortMode.IMAGE_COUNT -> "image_count"
                            AlbumSortMode.VIDEO_COUNT -> "video_count"
                        }).apply()
                    },
                    onOpenSettings = { showInertiaSettings = true },
                    onOpenLog = { showLogDialog = true },
                    onOpenTrash = {
                        showTrash = true
                        viewModel.loadTrashEntries()
                    }
                )
            }
            FpsMonitor(enabled = FpsMonitorEnabled, modifier = Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 8.dp))
            FloatingJumpButton(recyclerView = albumRvRef.value, modifier = Modifier.align(Alignment.BottomStart))

            // ── MediaGrid 全屏覆盖层（不通过 navigation，LazyVerticalGrid 保持存活）──
            if (selectedAlbumId != null) {
                MediaGridScreen(
                    albumId = selectedAlbumId!!,
                    albumName = selectedAlbumName,
                    onBackClick = {
                        selectedAlbumId = null
                        onAlbumActiveChanged(false)  // 同步通知上层恢复底部栏
                    },
                    onGoHome = {
                        selectedAlbumId = null
                        onAlbumActiveChanged(false)
                    }
                )
            }
            // ── 回收站全屏覆盖层 ──
            if (showTrash) {
                TrashScreen(
                    onBackClick = { showTrash = false }
                )
            }
        }
    }
}

// ══════════════════════════════════════
//  子组件
// ══════════════════════════════════════

@Composable
private fun NoPermissionContent(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "需要访问您的媒体文件",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "RCGallery 需要读取照片和视频权限才能展示相册内容。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRequestPermission) {
                Text("授予权限")
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyContent(onRefresh: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "没有找到相册",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "请确认手机中有照片或视频文件。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(onClick = onRefresh) {
                Text("刷新")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumGridContent(
    albums: List<Album>,
    starredIds: Set<String>,
    onAlbumClick: (Album) -> Unit,
    onAlbumLongClick: (Album) -> Unit,
    onToggleStar: (String) -> Unit,
    onRefresh: () -> Unit,
    displayMode: AlbumDisplayMode,
    onSelectMode: (AlbumDisplayMode) -> Unit,
    albumSortMode: AlbumSortMode,
    onSelectSort: (AlbumSortMode) -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenLog: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    albumRvRef: MutableState<RecyclerView?>
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RCGallery") },
                windowInsets = WindowInsets(0, 0, 0, 0),  // 外层 Scaffold 已处理状态栏 insets
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    // ── 齿轮菜单按钮 ──
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
                                onClick = { showGearMenu = false; onOpenSettings() }
                            )
                            DropdownMenuItem(
                                text = { Text("日志") },
                                onClick = { showGearMenu = false; onOpenLog() }
                            )
                        }
                    }

                    // ── 回收站按钮 ──
                    Icon(
                        painter = painterResource(com.example.rcgallery.R.drawable.ic_trash),
                        contentDescription = "回收站",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(22.dp)
                            .clickable { onOpenTrash() }
                    )
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // ── RecyclerView 内容区域（填满全屏）──
            AndroidView(
                factory = { ctx ->
                    val adapter = AlbumGridAdapter(
                        items = albums,
                        onClick = onAlbumClick,
                        onLongClick = onAlbumLongClick,
                        onToggleStar = onToggleStar
                    )
                    val rv = RecyclerView(ctx).apply {
                        layoutManager = when (displayMode) {
                            is AlbumDisplayMode.Grid -> GridLayoutManager(ctx, displayMode.columns)
                            is AlbumDisplayMode.List -> GridLayoutManager(ctx, 1)
                        }
                        this.adapter = adapter
                        addItemDecoration(AlbumGridSpacing(ctx))
                        clipToPadding = false
                        setPadding(0, (44 * ctx.resources.displayMetrics.density).toInt(), 0, 0)
                    }
                    albumRvRef.value = rv
                    val scroller = FastScrollerView(ctx, rv)
                    FrameLayout(ctx).apply {
                        addView(rv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        addView(scroller, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    }
                },
                update = { container ->
                    val rv = (container as FrameLayout).getChildAt(0) as RecyclerView
                    val scroller = container.getChildAt(1) as FastScrollerView
                    val adapter = rv.adapter as AlbumGridAdapter
                    val prevMode = adapter.currentMode
                    adapter.items = albums
                    adapter.starredIds = starredIds
                    if (prevMode != displayMode) {
                        adapter.currentMode = displayMode
                        val spanCount = when (displayMode) {
                            is AlbumDisplayMode.Grid -> displayMode.columns
                            is AlbumDisplayMode.List -> 1
                        }
                        val lm = rv.layoutManager as? GridLayoutManager
                        if (lm == null || lm.spanCount != spanCount) {
                            rv.layoutManager = GridLayoutManager(rv.context, spanCount)
                        }
                        adapter.notifyDataSetChanged()
                        scroller.refresh()
                    } else {
                        // items 或星标变化：星标变化会触发排序重排（星标项跳到顶部），
                        // payload bind 在 position i 读的是新 item、ViewHolder 却是旧 item 封面 → 错误中间态
                        // 直接用 DataSetChanged，不浪费 payload
                        adapter.notifyDataSetChanged()
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            // ── 显示模式 + 排序 悬浮工具栏（在 RecyclerView 上方）──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp)
                    .align(Alignment.TopStart),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DisplayModeSelector(
                    currentMode = displayMode,
                    onSelectMode = onSelectMode
                )
                Spacer(Modifier.weight(1f))
                AlbumSortSelector(
                    currentSort = albumSortMode,
                    onSelectSort = onSelectSort
                )
            }
        }
    }
}

// ══════════════════════════════════════
//  RecyclerView Adapter — Grid / List 模式
// ══════════════════════════════════════


private class AlbumGridAdapter(
    var items: List<Album>,
    private val onClick: (Album) -> Unit,
    private val onLongClick: (Album) -> Unit,
    private val onToggleStar: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var currentMode: AlbumDisplayMode = AlbumDisplayMode.Grid(3)
    var starredIds: Set<String> = emptySet()

    init { setHasStableIds(true) }

    override fun getItemCount() = items.size

    override fun getItemId(position: Int): Long = items.getOrNull(position)?.bucketId?.hashCode()?.toLong() ?: 0L

    override fun getItemViewType(position: Int): Int {
        return if (currentMode is AlbumDisplayMode.List) VIEW_TYPE_LIST else VIEW_TYPE_GRID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_LIST) {
            ListVH.create(parent, onClick, onLongClick, onToggleStar)
        } else {
            GridVH.create(parent, onClick, onLongClick, onToggleStar)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        val columns = when (val mode = currentMode) {
            is AlbumDisplayMode.Grid -> mode.columns
            is AlbumDisplayMode.List -> 1
        }
        when (holder) {
            is GridVH -> holder.bind(item, position, starredIds, columns)
            is ListVH -> holder.bind(item, position, starredIds)
        }
    }

}

private const val VIEW_TYPE_GRID = 0
private const val VIEW_TYPE_LIST = 1

/**
 * RecyclerView item spacing — Grid 模式均匀分布，List 模式仅垂直间距。
 */
private class AlbumGridSpacing(context: android.content.Context) : RecyclerView.ItemDecoration() {
    private val gapPx = (4 * context.resources.displayMetrics.density).toInt()

    override fun getItemOffsets(outRect: android.graphics.Rect, view: android.view.View, parent: RecyclerView, state: RecyclerView.State) {
        val lm = parent.layoutManager as? GridLayoutManager ?: return
        val spanCount = lm.spanCount
        if (spanCount <= 1) {
            // List 模式：垂直间隙
            outRect.set(0, 0, 0, gapPx / 2)
            return
        }
        val lp = view.layoutParams as? GridLayoutManager.LayoutParams ?: return
        val spanIndex = lp.spanIndex
        outRect.left = gapPx * spanIndex / spanCount
        outRect.right = gapPx * (spanCount - 1 - spanIndex) / spanCount
        outRect.top = gapPx / 2
        outRect.bottom = gapPx / 2
    }
}

// ── Grid ViewHolder ──
// ┌──────────────────┐
// │   封面缩略图      │  ImageView (正方形)
// ├──────────────────┤
// │ 相册名称          │  TextView
// │ N 项             │  TextView
// └──────────────────┘

private class GridVH private constructor(
    itemView: android.view.View,
    private val starContainer: FrameLayout,
    private val starWrapper: FrameLayout,
    private val starIv: ImageView
) : RecyclerView.ViewHolder(itemView) {

    companion object {
        fun create(parent: ViewGroup, onClick: (Album) -> Unit, onLongClick: (Album) -> Unit, onToggleStar: (String) -> Unit): GridVH {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            val margin = (6 * density).toInt()

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            // 正方形封面 FrameLayout（代替原来的 ImageView，用于叠加星标）
            val coverFrame = object : FrameLayout(ctx) {
                override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                    super.onMeasure(widthSpec, widthSpec)
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0
                )
            }
            val iv = ImageView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                id = android.R.id.icon
                setRoundedCorner(ITEM_CORNER_RADIUS_DP)
            }
            coverFrame.addView(iv)

            // ── 星标：左上（半透明黑底圆形 + 星标正居中）──
            val starContainer = FrameLayout(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    (48 * density).toInt(),
                    (48 * density).toInt(),
                    android.view.Gravity.TOP or android.view.Gravity.START
                ).apply {
                    setMargins((4 * density).toInt(), (4 * density).toInt(), 0, 0)
                }
                isClickable = true
                focusable = View.FOCUSABLE
            }
            // 小 FrameLayout 包裹圆形背景 + 星标图标（25dp，定位在容器左上角）
            val starWrapper = FrameLayout(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    (25 * density).toInt(),
                    (25 * density).toInt(),
                    android.view.Gravity.TOP or android.view.Gravity.START
                ).apply {
                    setMargins((3 * density).toInt(), (3 * density).toInt(), 0, 0)
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    setShape(android.graphics.drawable.GradientDrawable.OVAL)
                    setColor(android.graphics.Color.argb(120, 0, 0, 0))
                }
            }
            val starIv = ImageView(ctx).apply {
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
            coverFrame.addView(starContainer)

            root.addView(coverFrame)

            // ── 星标点击：使用 starContainer 自身的 tag（bucketId），
            //    不依赖 root.tag，防止 RecyclerView 重绑时 position 被覆盖 ──
            starContainer.setOnClickListener {
                val bucketId = starContainer.tag as? String ?: return@setOnClickListener
                onToggleStar(bucketId)
            }

            val nameTv = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(margin, margin, margin, 0) }
                setTextSize(12f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            root.addView(nameTv)

            val countTv = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(margin, (2 * density).toInt(), margin, margin) }
                setTextSize(11f)
                setTextColor(android.graphics.Color.GRAY)
            }
            root.addView(countTv)

            root.setOnClickListener {
                val pos = root.tag as? Int ?: return@setOnClickListener
                if (pos >= 0) {
                    val rv = root.parent as? RecyclerView ?: return@setOnClickListener
                    (rv.adapter as? AlbumGridAdapter)?.items?.getOrNull(pos)?.let { onClick(it) }
                }
            }
            root.setOnLongClickListener {
                val pos = root.tag as? Int ?: return@setOnLongClickListener true
                if (pos >= 0) {
                    val rv = root.parent as? RecyclerView ?: return@setOnLongClickListener true
                    (rv.adapter as? AlbumGridAdapter)?.items?.getOrNull(pos)?.let { onLongClick(it) }
                }
                true
            }
            return GridVH(root, starContainer, starWrapper, starIv)
        }
    }

    fun bind(item: Album, pos: Int, starredIds: Set<String>, columns: Int = 3) {
        itemView.tag = pos
        starContainer.tag = item.bucketId
        val iv = itemView.findViewById<ImageView>(android.R.id.icon)
        iv.load(item.coverUri) { size(180); crossfade(false) }
        val root = itemView as LinearLayout
        (root.getChildAt(1) as TextView).text = item.bucketName
        (root.getChildAt(2) as TextView).text = "${item.count} 项"
        val isStarred = item.bucketId in starredIds
        starIv.colorFilter = android.graphics.PorterDuffColorFilter(
            if (isStarred) android.graphics.Color.rgb(255, 193, 7) else android.graphics.Color.rgb(160, 160, 160),
            android.graphics.PorterDuff.Mode.SRC_IN
        )
        // 根据列数缩放星标大小
        updateStarSize(columns)
    }

    /** 动态调整星标三个层级的尺寸，与 Grid 列数适配 */
    private fun updateStarSize(columns: Int) {
        val density = itemView.resources.displayMetrics.density
        val scale = getAlbumStarScale(columns)
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
// ┌─────────────────────────────────────────────────┐
// │ ┌──────┐  相册名称                              │
// │ │ 封面  │  123.4 MB · N 图片 · M 视频 · K GIF   │
// │ │ 缩略图│  /path/to/album                       │
// │ └──────┘                                        │
// ├─────────────────────────────────────────────────┤
// │                   分隔线                         │
// └─────────────────────────────────────────────────┘

private class ListVH private constructor(
    itemView: android.view.View,
    private val starContainer: FrameLayout,
    private val starIv: ImageView
) : RecyclerView.ViewHolder(itemView) {

    companion object {
        fun create(parent: ViewGroup, onClick: (Album) -> Unit, onLongClick: (Album) -> Unit, onToggleStar: (String) -> Unit): ListVH {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            // ── 内容行（封面 + 文字 + 星标）──
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt()) }
            }
            val thumbSize = (64 * density).toInt()
            val iv = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(thumbSize, thumbSize)
                scaleType = ImageView.ScaleType.CENTER_CROP
                id = android.R.id.icon
                setRoundedCorner(ITEM_CORNER_RADIUS_DP)
            }
            row.addView(iv)

            val textColumn = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    weight = 1f
                    setMargins((12 * density).toInt(), 0, 0, 0)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
            }

            val nameTv = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setTextSize(14f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            textColumn.addView(nameTv)

            val infoTv = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setTextSize(12f)
                setTextColor(android.graphics.Color.GRAY)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                id = android.R.id.text1
            }
            textColumn.addView(infoTv)

            val pathTv = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setTextSize(11f)
                setTextColor(android.graphics.Color.DKGRAY)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                id = android.R.id.text2
            }
            textColumn.addView(pathTv)

            row.addView(textColumn)

            // ── 星标：方形大触控区域（48dp，图标 24dp 居中）──
            val starContainer = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (48 * density).toInt(),
                    (48 * density).toInt()
                ).apply {
                    setMargins((8 * density).toInt(), 0, (3 * density).toInt(), 0)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                isClickable = true
                focusable = View.FOCUSABLE
            }
            val starIv = ImageView(ctx).apply {
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

            // ── 分隔线 ──
            val divider = android.view.View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (1 * density).toInt()
                ).apply { setMargins((12 * density).toInt(), 0, (12 * density).toInt(), 0) }
                setBackgroundColor(android.graphics.Color.argb(25, 255, 255, 255))
            }
            root.addView(divider)

            // ── 星标点击：使用 starContainer 自身的 tag（bucketId），不依赖 root.tag ──
            starContainer.setOnClickListener {
                val bucketId = starContainer.tag as? String ?: return@setOnClickListener
                onToggleStar(bucketId)
            }

            root.setOnClickListener {
                val pos = root.tag as? Int ?: return@setOnClickListener
                if (pos >= 0) {
                    val rv = root.parent as? RecyclerView ?: return@setOnClickListener
                    (rv.adapter as? AlbumGridAdapter)?.items?.getOrNull(pos)?.let { onClick(it) }
                }
            }
            root.setOnLongClickListener {
                val pos = root.tag as? Int ?: return@setOnLongClickListener true
                if (pos >= 0) {
                    val rv = root.parent as? RecyclerView ?: return@setOnLongClickListener true
                    (rv.adapter as? AlbumGridAdapter)?.items?.getOrNull(pos)?.let { onLongClick(it) }
                }
                true
            }
            return ListVH(root, starContainer, starIv)
        }
    }

    fun bind(item: Album, pos: Int, starredIds: Set<String>) {
        itemView.tag = pos
        starContainer.tag = item.bucketId
        val row = (itemView as LinearLayout).getChildAt(0) as LinearLayout
        val iv = row.getChildAt(0) as ImageView
        val textColumn = row.getChildAt(1) as LinearLayout
        val nameTv = textColumn.getChildAt(0) as TextView
        val infoTv = textColumn.getChildAt(1) as TextView
        val pathTv = textColumn.getChildAt(2) as TextView

        iv.load(item.coverUri) { size(160); crossfade(false) }
        nameTv.text = item.bucketName
        infoTv.text = buildString {
            append(FormatUtil.formatFileSize(item.totalSize))
            append(" · ${item.imageCount} 图片")
            if (item.videoCount > 0) append(" · ${item.videoCount} 视频")
            if (item.gifCount > 0) append(" · ${item.gifCount} GIF")
        }
        pathTv.text = item.directoryPath
        pathTv.visibility = if (item.directoryPath.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        val isStarred = item.bucketId in starredIds
        starIv.colorFilter = android.graphics.PorterDuffColorFilter(
            if (isStarred) android.graphics.Color.rgb(255, 193, 7) else android.graphics.Color.rgb(160, 160, 160),
            android.graphics.PorterDuff.Mode.SRC_IN
        )
    }
}

// ── 显示模式选择器

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplayModeSelector(
    currentMode: AlbumDisplayMode,
    onSelectMode: (AlbumDisplayMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val gridOptions = listOf(
        AlbumDisplayMode.Grid(2) to "2列",
        AlbumDisplayMode.Grid(3) to "3列",
        AlbumDisplayMode.Grid(4) to "4列",
        AlbumDisplayMode.Grid(5) to "5列",
    )
    val isListMode = currentMode is AlbumDisplayMode.List
    val isSelected = { mode: AlbumDisplayMode -> mode isSameAs currentMode }
    var expanded by remember { mutableStateOf(false) }

    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // 列数选择 — 同款 Surface chip 风格
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
        // 列表模式单独保留为按钮
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (isListMode) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
            tonalElevation = if (isListMode) 0.dp else 3.dp,
            shadowElevation = if (isListMode) 0.dp else 4.dp,
            onClick = { onSelectMode(AlbumDisplayMode.List) }
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

// ── 排序方式选择器（同款风格下拉框）──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumSortSelector(
    currentSort: AlbumSortMode,
    onSelectSort: (AlbumSortMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = AlbumSortMode.entries.toList()
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

// ══════════════════════════════════════
//  工具函数
// ══════════════════════════════════════

private fun checkPermission(context: android.content.Context): Boolean {
    val permissions = getRequiredPermissions()
    return permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

private fun getRequiredPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}
