package com.example.rcgallery.ui.screen

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.example.rcgallery.ui.component.FloatingJumpButton
import com.example.rcgallery.ui.component.FpsMonitor
import com.example.rcgallery.ui.component.FpsMonitorEnabled
import com.example.rcgallery.ui.component.InertiaSettingsPanel
import com.example.rcgallery.util.AppLogger
import com.example.rcgallery.viewmodel.GalleryViewModel
import kotlinx.coroutines.delay

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

private fun formatSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024f)
    bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024f * 1024f))
    else -> "%.2f GB".format(bytes / (1024f * 1024f * 1024f))
}

private infix fun AlbumDisplayMode.isSameAs(other: AlbumDisplayMode): Boolean = when {
    this is AlbumDisplayMode.Grid && other is AlbumDisplayMode.Grid -> this.columns == other.columns
    this is AlbumDisplayMode.List && other is AlbumDisplayMode.List -> true
    else -> false
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumGridScreen(
    onSearchClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: GalleryViewModel = viewModel()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    // ── MediaGrid overlay 状态（代替 navigation push，LazyVerticalGrid 保持存活）──
    var selectedAlbumId by remember { mutableStateOf<String?>(null) }
    var selectedAlbumName by remember { mutableStateOf("") }
    if (selectedAlbumId != null) {
        BackHandler { selectedAlbumId = null }
    }

    // ── 设置面板 / 日志 ──
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

    // ── 权限状态 ──
    var hasPermission by remember {
        mutableStateOf(checkPermission(context))
    }

    // ── 相册显示模式（持久化：退出重进保留）──
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

    // ── 相册重命名搬运进度 ──
    val renameProgress by viewModel.renameProgress.collectAsStateWithLifecycle()
    val renameQueue by viewModel.renameQueue.collectAsStateWithLifecycle()

    // 授权 launcher（点击[搬N]时触发）
    val renameAuthLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            AppLogger.d("AlbumGrid", "rename auth OK → executeNextTask")
            viewModel.executeNextTask(context.contentResolver)
        } else {
            AppLogger.d("AlbumGrid", "rename auth denied → cancelNextTask")
            viewModel.cancelNextTask()
        }
    }

    // 全部完成 → 5 秒后自动清除
    LaunchedEffect(renameProgress) {
        val p = renameProgress
        if (p != null && p.isDone) {
            AppLogger.d("AlbumGrid", "rename done: ${p.lastResult}")
            delay(5000L)
            viewModel.clearRenameProgress()
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
                    onAlbumClick = { album ->
                        AppLogger.d("AlbumGrid", "click album=${album.bucketName} id=${album.bucketId} count=${album.count}")
                        selectedAlbumId = album.bucketId
                        selectedAlbumName = album.bucketName
                    },
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
                    renameProgress = renameProgress
                )
            }
            FpsMonitor(enabled = FpsMonitorEnabled, modifier = Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 8.dp))
            // ── 设置齿轮按钮（替代 DevOverlay，内含日志入口）──
            Box(
                modifier = Modifier.align(Alignment.TopStart).padding(top = 60.dp, start = 8.dp).size(28.dp)
                    .clip(CircleShape).background(Color(0xCCFF9800))
                    .clickable { showInertiaSettings = true },
                contentAlignment = Alignment.Center
            ) { Text("⚙", color = Color.White, fontSize = 14.sp) }
            FloatingJumpButton(recyclerView = albumRvRef.value, modifier = Modifier.align(Alignment.BottomStart))

            // ── MediaGrid 全屏覆盖层（不通过 navigation，LazyVerticalGrid 保持存活）──
            if (selectedAlbumId != null) {
                MediaGridScreen(
                    albumId = selectedAlbumId!!,
                    albumName = selectedAlbumName,
                    onBackClick = { selectedAlbumId = null }
                )
            }
            // ── 搬运队列按钮（在所有覆盖层之上，与 FPS 同位置但更大）──
            if (renameQueue.isNotEmpty() && renameProgress?.isRunning != true) {
                AppLogger.d("AlbumGrid", "👉 showRenameBtn: queue=${renameQueue.size} isRunning=${renameProgress?.isRunning}")
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 8.dp).size(40.dp)
                        .clip(CircleShape).background(Color(0xFF4CAF50))
                        .clickable {
                            val uris = viewModel.getNextTaskUris()
                            if (uris.isNullOrEmpty()) return@clickable
                            try {
                                val pending = MediaStore.createWriteRequest(context.contentResolver, uris)
                                renameAuthLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                            } catch (e: Exception) {
                                viewModel.cancelNextTask()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) { Text("搬${renameQueue.size}", color = Color.White, fontSize = 15.sp) }
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
    onAlbumClick: (Album) -> Unit,
    onRefresh: () -> Unit,
    displayMode: AlbumDisplayMode,
    onSelectMode: (AlbumDisplayMode) -> Unit,
    albumRvRef: MutableState<RecyclerView?>,
    renameProgress: GalleryViewModel.AlbumRenameProgress?  // 搬运进度
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val p = renameProgress
                    if (p != null) {
                        if (p.isRunning) {
                            // 搬运进行中
                            Column {
                                val label = if (p.totalTasks > 1) "搬运中 (${p.currentTaskIndex + 1}/${p.totalTasks})"
                                            else "搬运中"
                                Text(label, fontSize = 14.sp, maxLines = 1)
                                if (p.currentFileName.isNotEmpty()) {
                                    Text(
                                        "${p.completedCount}/${p.totalCount}  ${p.currentFileName}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                } else {
                                    Text(
                                        "${p.completedCount}/${p.totalCount}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else if (p.isDone) {
                            Text(p.lastResult, fontSize = 14.sp)
                        } else if (p.lastResult.isNotEmpty()) {
                            Text(p.lastResult, fontSize = 14.sp)
                        } else {
                            Text("RCGallery")
                        }
                    } else {
                        Text("RCGallery")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // ── RecyclerView 内容区域（填满全屏）──
            AndroidView(
                factory = { ctx ->
                    val adapter = AlbumGridAdapter(
                        items = albums,
                        onClick = onAlbumClick
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
                    adapter.items = albums
                    val prevMode = adapter.currentMode
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
                    } else if (adapter.items !== albums) {
                        adapter.notifyDataSetChanged()
                        scroller.refresh()
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            // ── 显示模式选择器（悬浮覆盖在 RecyclerView 上方）──
            DisplayModeSelector(
                currentMode = displayMode,
                onSelectMode = onSelectMode,
                modifier = Modifier.align(Alignment.TopStart)
            )
        }
    }
}

// ══════════════════════════════════════
//  RecyclerView Adapter — Grid / List 模式
// ══════════════════════════════════════

private class AlbumGridAdapter(
    var items: List<Album>,
    private val onClick: (Album) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var currentMode: AlbumDisplayMode = AlbumDisplayMode.Grid(3)

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int): Int {
        return if (currentMode is AlbumDisplayMode.List) VIEW_TYPE_LIST else VIEW_TYPE_GRID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_LIST) {
            ListVH.create(parent, onClick)
        } else {
            GridVH.create(parent, onClick)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is GridVH -> holder.bind(item, position)
            is ListVH -> holder.bind(item, position)
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

private class GridVH private constructor(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {

    companion object {
        fun create(parent: ViewGroup, onClick: (Album) -> Unit): GridVH {
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
            // 正方形缩略图（通过 onMeasure 保持 1:1）
            val iv = object : ImageView(ctx) {
                override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                    super.onMeasure(widthSpec, widthSpec)
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                id = android.R.id.icon
                setRoundedCorner(ITEM_CORNER_RADIUS_DP)
            }
            root.addView(iv)

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
            return GridVH(root)
        }
    }

    fun bind(item: Album, pos: Int) {
        itemView.tag = pos
        val iv = itemView.findViewById<ImageView>(android.R.id.icon)
        iv.load(item.coverUri) { size(180); crossfade(false) }
        val root = itemView as LinearLayout
        (root.getChildAt(1) as TextView).text = item.bucketName
        (root.getChildAt(2) as TextView).text = "${item.count} 项"
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

private class ListVH private constructor(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {

    companion object {
        fun create(parent: ViewGroup, onClick: (Album) -> Unit): ListVH {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            // ── 内容行（封面 + 文字）──
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

            root.setOnClickListener {
                val pos = root.tag as? Int ?: return@setOnClickListener
                if (pos >= 0) {
                    val rv = root.parent as? RecyclerView ?: return@setOnClickListener
                    (rv.adapter as? AlbumGridAdapter)?.items?.getOrNull(pos)?.let { onClick(it) }
                }
            }
            return ListVH(root)
        }
    }

    fun bind(item: Album, pos: Int) {
        itemView.tag = pos
        val row = (itemView as LinearLayout).getChildAt(0) as LinearLayout
        val iv = row.getChildAt(0) as ImageView
        val textColumn = row.getChildAt(1) as LinearLayout
        val nameTv = textColumn.getChildAt(0) as TextView
        val infoTv = textColumn.getChildAt(1) as TextView
        val pathTv = textColumn.getChildAt(2) as TextView

        iv.load(item.coverUri) { size(160); crossfade(false) }
        nameTv.text = item.bucketName
        infoTv.text = buildString {
            append(formatSize(item.totalSize))
            append(" · ${item.imageCount} 图片")
            if (item.videoCount > 0) append(" · ${item.videoCount} 视频")
            if (item.gifCount > 0) append(" · ${item.gifCount} GIF")
        }
        pathTv.text = item.directoryPath
        pathTv.visibility = if (item.directoryPath.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }
}

// ── 显示模式选择器（悬浮在列表上方，Elevation + 阴影不被遮盖）──

@Composable
private fun DisplayModeSelector(
    currentMode: AlbumDisplayMode,
    onSelectMode: (AlbumDisplayMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(
            AlbumDisplayMode.Grid(2) to "▦ 2列",
            AlbumDisplayMode.Grid(3) to "▦ 3列",
            AlbumDisplayMode.Grid(4) to "▦ 4列",
            AlbumDisplayMode.Grid(5) to "▦ 5列",
            AlbumDisplayMode.List to "☰ 列表"
        ).forEach { (mode, label) ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (mode isSameAs currentMode) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                tonalElevation = if (mode isSameAs currentMode) 0.dp else 3.dp,
                shadowElevation = if (mode isSameAs currentMode) 0.dp else 4.dp,
                onClick = { onSelectMode(mode) }
            ) {
                Text(
                    text = label,
                    color = if (mode isSameAs currentMode) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
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
