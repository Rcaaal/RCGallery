package com.example.rcgallery.ui.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rcgallery.data.smb.SmbBrowseState
import com.example.rcgallery.data.smb.SmbDataSource
import com.example.rcgallery.data.smb.SmbDevice
import com.example.rcgallery.data.smb.SmbFileInfo
import com.example.rcgallery.data.smb.SmbRepository
import com.example.rcgallery.data.smb.SmbSubFolder
import com.example.rcgallery.data.smb.SmbThumbnailLoader
import com.example.rcgallery.ui.component.SmbConnectDialog
import com.example.rcgallery.util.AppLogger
import com.example.rcgallery.viewmodel.GalleryViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.ContentScale

/**
 * 网络浏览主界面 — 文件夹相册混合模式。
 *
 * 预览 overlay 使用 Box 在 Scaffold 之外渲染，确保覆盖全屏（包括 TopAppBar + NavigationBar）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkBrowserScreen(
    viewModel: GalleryViewModel
) {
    val smbBrowseState by viewModel.smbBrowseState.collectAsState()
    val smbDevices by viewModel.smbDevices.collectAsState()

    var showConnectDialog by remember { mutableStateOf(false) }
    var previewFileInfo by remember { mutableStateOf<SmbFileInfo?>(null) }

    Box(Modifier.fillMaxSize()) {
        // ── 主内容（包括 Scaffold 的 TopAppBar + NavigationBar）──
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        val title = when (val s = smbBrowseState) {
                            is SmbBrowseState.DeviceList -> "网络相册"
                            is SmbBrowseState.Connecting -> s.progressMessage
                            is SmbBrowseState.Error -> "出错了"
                            is SmbBrowseState.ShareList -> s.host
                            is SmbBrowseState.FolderContent -> s.folderName
                        }
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        if (smbBrowseState !is SmbBrowseState.DeviceList) {
                            TextButton(onClick = { viewModel.smbGoBack() }) {
                                Text("← ${if (smbBrowseState is SmbBrowseState.FolderContent) "上级" else "返回"}")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (val s = smbBrowseState) {
                    is SmbBrowseState.DeviceList -> {
                        DeviceListContent(
                            devices = smbDevices,
                            onAddDevice = { showConnectDialog = true },
                            onDeviceClick = { viewModel.smbConnect(it.host) },
                            onRemoveDevice = { deviceId -> viewModel.smbRemoveDevice(deviceId) }
                        )
                    }
                    is SmbBrowseState.Connecting -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(16.dp))
                                Text(s.progressMessage, style = MaterialTheme.typography.bodyMedium)
                                if (s.progressMessage.contains("/")) {
                                    Spacer(Modifier.height(8.dp))
                                    Text("首次扫描较慢，请耐心等待",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    is SmbBrowseState.Error -> {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text("出错了", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(8.dp))
                            Text(s.message, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { viewModel.smbResetState(); showConnectDialog = true }) {
                                Text("重试")
                            }
                        }
                    }
                    is SmbBrowseState.ShareList -> {
                        ShareGridContent(
                            shares = s.shares,
                            onShareClick = { share -> viewModel.smbOpenFolder(share.path, share.name) }
                        )
                    }
                    is SmbBrowseState.FolderContent -> {
                        FolderMixedContent(
                            subFolders = s.subFolders,
                            mediaFiles = s.mediaFiles,
                            onFolderClick = { folder -> viewModel.smbOpenFolder(folder.path, folder.name) },
                            onFileClick = { file ->
                                AppLogger.d("SMB", "preview file: ${file.name} isVideo=${file.isVideo} size=${file.size}")
                                previewFileInfo = file
                            }
                        )
                    }
                }
            }
        }

        // ── 顶层 overlay（在 Scaffold 之上，覆盖全屏包括 TopAppBar + NavigationBar）──

        if (showConnectDialog) {
            SmbConnectDialog(
                onDismiss = { showConnectDialog = false },
                onConnect = { host -> showConnectDialog = false; viewModel.smbConnect(host) }
            )
        }

        previewFileInfo?.let { file ->
            SmbPreviewOverlay(
                fileInfo = file,
                onDismiss = { previewFileInfo = null }
            )
        }
    }
}

// ══════════════════════════════════════
//  设备列表
// ══════════════════════════════════════

@Composable
private fun DeviceListContent(
    devices: List<SmbDevice>,
    onAddDevice: () -> Unit,
    onDeviceClick: (SmbDevice) -> Unit,
    onRemoveDevice: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (devices.isEmpty()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有添加网络设备", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("点击下方按钮，添加局域网内共享的电脑", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices, key = { it.id }) { device ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onDeviceClick(device) },
                        elevation = CardDefaults.cardElevation(2.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("💻", fontSize = 28.sp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(device.displayName.ifEmpty { device.host }, fontWeight = FontWeight.Bold)
                                Text(device.host, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { onRemoveDevice(device.id) }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onAddDevice, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
            Text("+ 添加设备")
        }
    }
}

// ══════════════════════════════════════
//  共享列表
// ══════════════════════════════════════

@Composable
private fun ShareGridContent(
    shares: List<com.example.rcgallery.data.smb.SmbShare>,
    onShareClick: (com.example.rcgallery.data.smb.SmbShare) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("选择共享文件夹", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(shares, key = { it.path }) { share ->
            Card(Modifier.fillMaxWidth().clickable { onShareClick(share) },
                shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(2.dp)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("📁", fontSize = 32.sp)
                    Spacer(Modifier.width(16.dp))
                    Text(share.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}

// ══════════════════════════════════════
//  混合内容视图（RecyclerView）
// ══════════════════════════════════════

private const val SM_GRID_COLUMNS = 3
private const val SM_GAP_DP = 4

@Composable
private fun FolderMixedContent(
    subFolders: List<SmbSubFolder>,
    mediaFiles: List<SmbFileInfo>,
    onFolderClick: (SmbSubFolder) -> Unit,
    onFileClick: (SmbFileInfo) -> Unit
) {
    val scope = rememberCoroutineScope()

    if (subFolders.isEmpty() && mediaFiles.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("此文件夹中没有媒体内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val adapter = remember {
        FolderMixedAdapter(
            subFolders = subFolders,
            mediaFiles = mediaFiles,
            onFolderClick = onFolderClick,
            onFileClick = onFileClick,
            scope = scope
        )
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            RecyclerView(ctx).apply {
                layoutManager = GridLayoutManager(ctx, SM_GRID_COLUMNS).apply {
                    spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(position: Int): Int = 1
                    }
                }
                this.adapter = adapter
                clipToPadding = false
                setPadding(4, 4, 4, 4)
            }
        },
        update = { rv ->
            val changed = adapter.subFolders !== subFolders || adapter.mediaFiles !== mediaFiles
            if (changed) {
                adapter.subFolders = subFolders
                adapter.mediaFiles = mediaFiles
                adapter.notifyDataSetChanged()
            }
        }
    )
}

/**
 * 单个 RecyclerView 适配器，混合显示文件夹卡片 + 媒体缩略图。
 */
private class FolderMixedAdapter(
    var subFolders: List<SmbSubFolder>,
    var mediaFiles: List<SmbFileInfo>,
    private val onFolderClick: (SmbSubFolder) -> Unit,
    private val onFileClick: (SmbFileInfo) -> Unit,
    val scope: CoroutineScope
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_FOLDER = 0
        private const val TYPE_MEDIA = 1
    }

    val folderCount get() = subFolders.size
    val mediaCount get() = mediaFiles.size

    override fun getItemCount() = folderCount + mediaCount

    override fun getItemViewType(position: Int): Int {
        return if (position < folderCount) TYPE_FOLDER else TYPE_MEDIA
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_FOLDER) {
            FolderVH.create(parent, onFolderClick, scope)
        } else {
            MediaVH.create(parent, onFileClick, scope)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is FolderVH -> holder.bind(subFolders[position], position)
            is MediaVH -> holder.bind(mediaFiles[position - folderCount], position)
        }
    }
}

// ── 文件夹相册卡片 ViewHolder ──

private class FolderVH private constructor(
    itemView: View,
    private val coverIv: ImageView,
    private val nameTv: TextView,
    private val countTv: TextView,
    private val onFolderClick: (SmbSubFolder) -> Unit,
    private val scope: CoroutineScope
) : RecyclerView.ViewHolder(itemView) {

    init {
        itemView.setOnClickListener {
            val folder = it.tag as? SmbSubFolder ?: return@setOnClickListener
            AppLogger.d("SMB", "folder click: ${folder.name}")
            onFolderClick(folder)
        }
    }

    companion object {
        fun create(parent: ViewGroup, onFolderClick: (SmbSubFolder) -> Unit, scope: CoroutineScope): FolderVH {
            val ctx = parent.context
            val d = ctx.resources.displayMetrics.density
            val margin = (6 * d).toInt()
            val gapPx = (SM_GAP_DP * d).toInt()

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(gapPx, gapPx, gapPx, gapPx)
            }

            // 封面（正方形）
            val coverFrame = object : FrameLayout(ctx) {
                override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                    super.onMeasure(widthSpec, widthSpec)
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0
                )
                background = android.graphics.drawable.GradientDrawable().apply {
                    setShape(android.graphics.drawable.GradientDrawable.RECTANGLE)
                    setCornerRadius(8 * d)
                    setColor(0xFF2A2A2A.toInt())
                }
                clipToOutline = true
            }
            val iv = ImageView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            coverFrame.addView(iv)

            // 文件夹图标覆盖层（空，保留做占位）
            val folderIcon = ImageView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    (32 * d).toInt(), (32 * d).toInt(), Gravity.CENTER
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(0x00000000.toInt())
            }
            coverFrame.addView(folderIcon)
            root.addView(coverFrame)

            // 名称
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

            // 计数
            val countTv = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(margin, (2 * d).toInt(), margin, margin) }
                setTextSize(11f)
                setTextColor(android.graphics.Color.GRAY)
            }
            root.addView(countTv)

            // 点击在 bind 中处理（用 tag 存 SmbSubFolder 对象）
            return FolderVH(root, iv, nameTv, countTv, onFolderClick, scope)
        }
    }

    fun bind(folder: SmbSubFolder, pos: Int) {
        itemView.tag = folder
        nameTv.text = folder.name
        countTv.text = "${folder.mediaCount} 项"

        val path = folder.coverPath
        // 同一封面不重复清除
        if (coverIv.tag != path) {
            coverIv.setImageDrawable(null)
            coverIv.setBackgroundColor(0xFF2A2A2A.toInt())
            coverIv.tag = path

            if (path.isNotEmpty()) {
                scope.launch {
                    val bm = SmbThumbnailLoader.load(url = path, maxPx = 300)
                    if (bm != null && coverIv.tag == path) {
                        coverIv.setImageBitmap(bm)
                    }
                }
            }
        }
    }
}

// ── 媒体缩略图 ViewHolder ──

private class MediaVH private constructor(
    itemView: View,
    private val iv: ImageView,
    private val playIcon: TextView,
    private val scope: CoroutineScope,
    private val onFileClick: (SmbFileInfo) -> Unit
) : RecyclerView.ViewHolder(itemView) {

    init {
        itemView.setOnClickListener {
            val file = it.tag as? SmbFileInfo ?: return@setOnClickListener
            AppLogger.d("SMB", "click media: ${file.name} path=${file.path}")
            onFileClick(file)
        }
    }

    companion object {
        fun create(parent: ViewGroup, onFileClick: (SmbFileInfo) -> Unit, scope: CoroutineScope): MediaVH {
            val ctx = parent.context
            val d = ctx.resources.displayMetrics.density
            val gapPx = (SM_GAP_DP * d).toInt()

            val root = object : FrameLayout(ctx) {
                override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                    super.onMeasure(widthSpec, widthSpec)
                }
            }.apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
                setPadding(gapPx, gapPx, gapPx, gapPx)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setShape(android.graphics.drawable.GradientDrawable.RECTANGLE)
                    setCornerRadius(4 * d)
                    setColor(0xFF1A1A1A.toInt())
                }
                clipToOutline = true
            }

            val iv = ImageView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            root.addView(iv)

            // 视频播放图标叠加层
            val playIcon = TextView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
                text = "▶"
                setTextSize(24f)
                setTextColor(android.graphics.Color.WHITE)
                visibility = android.view.View.GONE
            }
            root.addView(playIcon)

            return MediaVH(root, iv, playIcon, scope, onFileClick)
        }
    }

    private var currentPath: String? = null

    fun bind(file: SmbFileInfo, pos: Int) {
        itemView.tag = file
        val path = file.path
        val isVideo = file.isVideo

        AppLogger.d("SMB-THUMB", "bind pos=$pos name=${file.name} isVideo=$isVideo path=$path")

        // 设置视频播放图标（必须在每次 bind 时设置，RecyclerView 回收会重置）
        playIcon.visibility = if (isVideo) android.view.View.VISIBLE else android.view.View.GONE

        // 同一路径不重复清除图片（防止通知刷新的闪烁）
        if (path == currentPath) return

        currentPath = path
        iv.setImageDrawable(null)
        iv.setBackgroundColor(if (isVideo) 0xFF333333.toInt() else 0xFF1A1A1A.toInt())
        iv.tag = path

        // 为图片和视频都加载缩略图（视频用 partial read + MediaMetadataRetriever）
        scope.launch {
            val context = itemView.context
            AppLogger.d("SMB-THUMB", "load() called: isVideo=$isVideo path=$path")
            val bm = SmbThumbnailLoader.load(url = path, fileSize = file.size, maxPx = 300, context = context)
            if (bm != null && iv.tag == path) {
                AppLogger.d("SMB-THUMB", "setImageBitmap: isVideo=$isVideo path=$path")
                iv.setImageBitmap(bm)
            }
        }
    }
}

// ══════════════════════════════════════
//  全屏图片/视频预览 overlay
// ══════════════════════════════════════

@Composable
private fun SmbPreviewOverlay(
    fileInfo: SmbFileInfo,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (fileInfo.isVideo) {
            SmbVideoPlayer(fileInfo, onDismiss)
        } else {
            SmbImageViewer(fileInfo)
        }

        // 顶部关闭按钮
        TextButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
        ) {
            Text("← 返回", color = Color.White)
        }
    }
}

@Composable
private fun SmbImageViewer(fileInfo: SmbFileInfo) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isError by remember { mutableStateOf(false) }

    LaunchedEffect(fileInfo.path) {
        AppLogger.d("SMB", "preview start: ${fileInfo.name} size=${fileInfo.size}")
        val repo = SmbRepository.getInstance()
        val bytes = repo.readBytes(fileInfo.path).getOrNull()
        if (bytes == null) {
            AppLogger.d("SMB-WARN", "preview readBytes failed: ${fileInfo.name}")
            isError = true
            return@LaunchedEffect
        }
        // 计算采样率以适配屏幕
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        val sample = if (boundsOpts.outWidth > 0 && boundsOpts.outHeight > 0) {
            var s = 1
            while (boundsOpts.outWidth / s > 1080 || boundsOpts.outHeight / s > 1080) { s *= 2 }
            s
        } else { 1 }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            // 全屏预览用 ARGB_8888 保证色彩质量
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (bm != null) {
            AppLogger.d("SMB", "preview done: ${fileInfo.name} ${bm.width}x${bm.height} sample=$sample")
        } else {
            AppLogger.d("SMB-WARN", "preview decode failed: ${fileInfo.name}")
            isError = true
        }
        bitmap = bm
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> androidx.compose.foundation.Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = fileInfo.name,
                modifier = Modifier.fillMaxSize().clickable(enabled = false, onClick = {}),
                contentScale = ContentScale.Fit
            )
            isError -> Text("无法加载此图片", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
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

/**
 * SMB 视频播放器 — 通过 SmbDataSource 直接流式播放，无需等待复制。
 *
 * ### 架构
 * ExoPlayer → ProgressiveMediaSource.Factory → SmbDataSource (8MB 预读缓冲)
 *
 * - 点开视频：ExoPlayer 立即调用 SmbDataSource.open() 打开 SMB 文件
 * - 第一次 read()：同步读 8MB 到内部缓冲区（~200ms @ 40MB/s）
 * - 后续 read()：从缓冲区返回，零网络 I/O
 * - 8MB 耗尽 → 再同步读 8MB
 *
 * ### 对比 CacheDataSource 方案
 * CacheDataSource 层缓存粒度太小，ExoPlayer 频繁因数据不足而缓冲。
 * 去掉中间层，SmbDataSource 直接管理 8MB 大缓冲区，减少 SMB 交互次数。
 *
 * ### 中断安全
 * [SmbDataSource.close()] 立即关闭底层 SMB 连接，
 * 确保 [ExoPlayer.release()] 不会因 SMB 读取阻塞而超时崩溃。
 *
 * ### TextureView 修复黑屏
 * Compose overlay 中 SurfaceView 渲染在不可见层 → 改用 texture_view。
 */
@Composable
private fun SmbVideoPlayer(
    fileInfo: SmbFileInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var playbackState by remember { mutableIntStateOf(Player.STATE_IDLE) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .setLoadControl(DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    30_000,    // minBufferMs
                    60_000,    // maxBufferMs
                    3_000,     // bufferForPlaybackMs: 缓冲 3s 即可开始（8MB ≈ 10-30s）
                    5_000      // bufferForPlaybackAfterRebufferMs
                )
                .build()
            )
            .build().apply {
                val mediaSource = ProgressiveMediaSource.Factory(SmbDataSource.Factory())
                    .createMediaSource(MediaItem.fromUri(Uri.parse(fileInfo.path)))
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        playbackState = state
                        if (state == Player.STATE_READY) {
                            AppLogger.d("SMB-VIDEO", "Player READY")
                        } else if (state == Player.STATE_BUFFERING) {
                            AppLogger.d("SMB-VIDEO", "Player BUFFERING")
                        }
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        AppLogger.e("SMB-VIDEO", "playback error: ${error.message}", error)
                        hasError = true
                        errorMessage = error.message ?: "播放失败"
                    }
                })
            }
    }

    // ── 清理 ──
    DisposableEffect(fileInfo.path) {
        onDispose {
            AppLogger.d("SMB-VIDEO", "onDispose: stop & release")
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    // ── 渲染 ──
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            hasError -> {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("⚠️", fontSize = 40.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("视频加载失败", color = Color.White,
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    if (errorMessage.isNotEmpty()) {
                        Text(errorMessage, color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onDismiss) { Text("返回") }
                }
            }
            playbackState == Player.STATE_READY -> {
                // 使用 TextureView（修复 SurfaceView 在 Compose overlay 中黑屏）
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val pvAttrs = ctx.resources.getXml(
                            com.example.rcgallery.R.xml.player_view_texture
                        ).let { parser ->
                            parser.next()
                            parser.nextTag()
                            android.util.Xml.asAttributeSet(parser)
                        }
                        PlayerView(ctx, pvAttrs).apply {
                            this.player = exoPlayer
                            useController = true
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    update = { it.player = exoPlayer }
                )
            }
            else -> {
                // 缓冲/加载中 — 显示 "点击返回" 按钮
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Text("正在从网络加载视频...",
                            color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
