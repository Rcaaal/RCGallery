package com.example.rcgallery.ui.screen

import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
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
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.rcgallery.ui.component.FastScrollerView
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

    // ── RecyclerView 引用（给 FloatingJumpButton 用）──
    val mediaRvRef = remember { mutableStateOf<RecyclerView?>(null) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            if (isLoadingAlbum) {
                // 相册切换/首次加载→显示 loading，防止旧数据闪烁
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(if (albumName.isNotEmpty()) albumName else "所有文件", maxLines = 1) },
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
                                val rv = RecyclerView(ctx).apply {
                                    layoutManager = GridLayoutManager(ctx, 4)
                                    adapter = SimpleGridAdapter(
                                        onClick = { item ->
                                            val index = filteredItems.indexOf(item)
                                            AppLogger.d("MediaGrid", "click uri=${item.uri.lastPathSegment} index=$index items=${filteredItems.size}")
                                            if (index >= 0) {
                                                selectedPhotoIndex = index
                                            } else {
                                                // fallback: 如果 filteredItems 中找不到（理论上不会），用 item.uri 唯一标识
                                            }
                                        },
                                        context = ctx
                                    )
                                    clipToPadding = false
                                }
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
                                adapter.submitList(filteredItems) {
                                    scroller.refresh()
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                        )
                        }
                    }
                }
            FpsMonitor(enabled = FpsMonitorEnabled, modifier = Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 8.dp))
            SettingsOverlay(gearModifier = Modifier.align(Alignment.TopStart).padding(top = 60.dp, start = 8.dp))

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
                    items = filteredItems
                )
            }
        }
    }
}

/** 媒体类型过滤选项 */
private enum class MediaFilterType(val label: String) {
    IMAGE("图片"), VIDEO("视频"), GIF("GIF")
}

private class SimpleGridAdapter(
    private val onClick: (com.example.rcgallery.model.MediaItem) -> Unit,
    private val context: android.content.Context
) : ListAdapter<com.example.rcgallery.model.MediaItem, SimpleGridAdapter.VH>(DiffCallback()) {

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
        return VH(frame, iv, tv, onClick, itemSize, context)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position), position)
    }

    private class DiffCallback : DiffUtil.ItemCallback<com.example.rcgallery.model.MediaItem>() {
        override fun areItemsTheSame(a: com.example.rcgallery.model.MediaItem, b: com.example.rcgallery.model.MediaItem) = a.uri == b.uri
        override fun areContentsTheSame(a: com.example.rcgallery.model.MediaItem, b: com.example.rcgallery.model.MediaItem) = a == b
    }

    class VH(
        itemView: android.view.View,
        private val iv: ImageView,
        private val tv: TextView,
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
                // 视频用 Coil 异步加载帧（Coil 2.7.0 内置 VideoFrameDecoder，自动缓存）
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
