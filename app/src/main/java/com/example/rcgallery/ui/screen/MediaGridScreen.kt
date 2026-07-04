package com.example.rcgallery.ui.screen

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
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

    LaunchedEffect(albumId) {
        AppLogger.d("MediaGrid", "LaunchedEffect albumId=[$albumId]  items.size=${mediaItems.size}")
        viewModel.loadMedia(albumId = albumId)
    }

    // ── 相册切换时防止旧数据闪烁：加载完成前不展示 RecyclerView ──
    var isLoadingAlbum by remember { mutableStateOf(true) }
    LaunchedEffect(albumId) {
        isLoadingAlbum = true
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
    // 星标排序：星标项排在前面
    val sortedItems = remember(filteredItems, starredMediaUris) {
        filteredItems.sortedByDescending { it.uri.toString() in starredMediaUris }
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
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
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
                        AndroidView(
                            factory = { ctx ->
                                // rv 先声明再使用
                                val rv = RecyclerView(ctx)
                                rv.layoutManager = GridLayoutManager(ctx, 4)
                                rv.clipToPadding = false
                                rv.adapter = SimpleGridAdapter(
                                    onClick = { item ->
                                        val items = (rv.adapter as SimpleGridAdapter).items
                                        var index = items.indexOf(item)
                                        if (index < 0) {
                                            // fallback: 按 URI 搜索（handle data class equals 不匹配的极端情况）
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
                                )
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
                                val prevStarred = adapter.starredUris
                                adapter.items = sortedItems
                                adapter.starredUris = starredMediaUris
                                // 仅星标变化时先用 payload 局部更新颜色（即时反馈）
                                if (prevStarred != starredMediaUris) {
                                    for (i in 0 until adapter.itemCount) {
                                        adapter.notifyItemChanged(i, MEDIA_STAR_PAYLOAD)
                                    }
                                }
                                adapter.notifyDataSetChanged()
                                scroller.refresh()
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                        )
                        }
                    }
                }
            FpsMonitor(enabled = FpsMonitorEnabled, modifier = Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 8.dp))
            // ── 齿轮图标 + 下拉菜单（设置 / 日志）──
            var showGearMenu by remember { mutableStateOf(false) }
            var showInertiaSettings by remember { mutableStateOf(false) }
            if (showInertiaSettings) InertiaSettingsPanel(
                onDismiss = { showInertiaSettings = false },
                onOpenLog = { showInertiaSettings = false; showLogDialog = true }
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 60.dp, start = 8.dp)
            ) {
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
                        onClick = {
                            showGearMenu = false
                            showInertiaSettings = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("日志") },
                        onClick = {
                            showGearMenu = false
                            showLogDialog = true
                        }
                    )
                }
            }

            // ── 媒体类型过滤按钮（底部居中，略抬上）──
            if (availableTypes.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 80.dp)
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
                    // 重置按钮（有过滤时显示）
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
                        // 删除/撤销后 _mediaItems 已被增量修改，无需 loadMedia 全量重查
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

/** Payload for media star-only partial bind */
private val MEDIA_STAR_PAYLOAD = Any()

private class SimpleGridAdapter(
    private val onClick: (com.example.rcgallery.model.MediaItem) -> Unit,
    private val onToggleStar: (String) -> Unit,
    private val context: android.content.Context
) : RecyclerView.Adapter<SimpleGridAdapter.VH>() {

    var items: List<com.example.rcgallery.model.MediaItem> = emptyList()
    var starredUris: Set<String> = emptySet()

    override fun getItemCount() = items.size

    private val density = context.resources.displayMetrics.density
    private val gapPx = (2 * density).toInt()
    private val screenWidth = context.resources.displayMetrics.widthPixels
    private val itemSize = (screenWidth - gapPx * 3) / 4

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val frame = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(itemSize, itemSize)
            setPadding(gapPx / 2, gapPx / 2, gapPx / 2, gapPx / 2)
        }
        val iv = ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(ResourcesCompat.getColor(context.resources, android.R.color.darker_gray, null))
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

        // ── 星标：左上角叠加（与相册 Grid 一致）──
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
            ).apply {
                setMargins((3 * density).toInt(), (3 * density).toInt(), 0, 0)
            }
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

        // ── 星标点击 ──
        starContainer.setOnClickListener {
            val uriStr = it.tag as? String ?: return@setOnClickListener
            onToggleStar(uriStr)
        }

        return VH(frame, iv, tv, starIv, starContainer, onClick, itemSize, context)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.starContainer.tag = item.uri.toString()
        val isStarred = item.uri.toString() in starredUris
        holder.starIv.colorFilter = android.graphics.PorterDuffColorFilter(
            if (isStarred) android.graphics.Color.rgb(255, 193, 7) else android.graphics.Color.rgb(160, 160, 160),
            android.graphics.PorterDuff.Mode.SRC_IN
        )
        holder.bind(item, position)
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            // Star-only partial bind: 不从 items[position] 读数据，
            // 从 holder.starContainer 已有 tag 读 uriStr，避免 dispatchUpdatesTo 期间 position 漂移
            val uriStr = holder.starContainer.tag as? String ?: return
            val isStarred = uriStr in starredUris
            holder.starIv.colorFilter = android.graphics.PorterDuffColorFilter(
                if (isStarred) android.graphics.Color.rgb(255, 193, 7) else android.graphics.Color.rgb(160, 160, 160),
                android.graphics.PorterDuff.Mode.SRC_IN
            )
            return
        }
        onBindViewHolder(holder, position)
    }

    class VH(
        itemView: android.view.View,
        private val iv: ImageView,
        private val tv: TextView,
        val starIv: ImageView,
        val starContainer: android.view.View,
        private val onClick: (com.example.rcgallery.model.MediaItem) -> Unit,
        private val thumbSize: Int,
        private val context: android.content.Context
    ) : RecyclerView.ViewHolder(itemView) {
        private var currentItem: com.example.rcgallery.model.MediaItem? = null
        init {
            itemView.setOnClickListener {
                val item = currentItem ?: return@setOnClickListener
                onClick(item)
            }
        }
        fun bind(item: com.example.rcgallery.model.MediaItem, position: Int) {
            currentItem = item
            if (item.isVideo) {
                iv.load(item.uri) { size(thumbSize); crossfade(true) }
            } else {
                iv.load(item.uri) { size(thumbSize); crossfade(false) }
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
        }
    }
}
