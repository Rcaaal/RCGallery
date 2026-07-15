package com.example.rcgallery.ui.screen

import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rcgallery.data.smb.SmbBrowseState
import com.example.rcgallery.data.smb.SmbDevice
import com.example.rcgallery.data.smb.SmbFileInfo
import com.example.rcgallery.data.smb.SmbSubFolder
import com.example.rcgallery.data.smb.SmbThumbnailLoader
import com.example.rcgallery.ui.component.SmbConnectDialog
import com.example.rcgallery.ui.component.AlbumPickDialog
import com.example.rcgallery.util.AppLogger
import com.example.rcgallery.viewmodel.GalleryViewModel
import com.example.rcgallery.viewmodel.PasteMode
import com.example.rcgallery.viewmodel.BaiduNetdiskViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 网络浏览主界面 — 文件夹相册混合模式。
 *
 * 预览 overlay 使用 Box 在 Scaffold 之外渲染，确保覆盖全屏（包括 TopAppBar + NavigationBar）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkBrowserScreen(
    viewModel: GalleryViewModel,
    baiduViewModel: BaiduNetdiskViewModel
) {
    val smbBrowseState by viewModel.smbBrowseState.collectAsState()
    val smbDevices by viewModel.smbDevices.collectAsState()
    val localAlbums by viewModel.albums.collectAsStateWithLifecycle()
    val recentMoveAlbums by viewModel.recentMoveAlbums.collectAsStateWithLifecycle()
    val smbLocalTransferFailures by viewModel.smbLocalTransferFailures.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showConnectDialog by remember { mutableStateOf(false) }
    var showBaiduNetdisk by remember { mutableStateOf(false) }
    // 预览状态：(当前索引, 全部媒体文件列表)
    var previewState by remember { mutableStateOf<Pair<Int, List<SmbFileInfo>>?>(null) }

    // ── SMB 多选状态（与本地 MediaGridScreen 模式一致）──
    var isSmbMultiSelect by remember { mutableStateOf(false) }
    var selectedSmbPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showSmbDeleteConfirm by remember { mutableStateOf(false) }
    // 跟踪当前文件夹的 mediaFiles（供 actions 中全选用）
    var currentSmbMediaFiles by remember { mutableStateOf<List<SmbFileInfo>>(emptyList()) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showLocalTargetPicker by remember { mutableStateOf(false) }
    var pendingLocalTransferItems by remember { mutableStateOf<List<SmbFileInfo>>(emptyList()) }
    var localTransferFromClipboard by remember { mutableStateOf(false) }
    fun exitSmbMultiSelect() {
        isSmbMultiSelect = false
        selectedSmbPaths = emptySet()
    }
    fun toggleSmbSelection(file: SmbFileInfo) {
        val path = file.path
        selectedSmbPaths = if (path in selectedSmbPaths) selectedSmbPaths - path else selectedSmbPaths + path
        if (selectedSmbPaths.isEmpty()) isSmbMultiSelect = false
    }

    // ── SMB 中转站状态 ──
    val smbClipboardItems by viewModel.smbClipboardItems.collectAsState()

    // ── SMB 操作历史 ──
    val smbOperationHistory by viewModel.smbOperationHistory.collectAsState()
    var showHistoryPage by remember { mutableStateOf(false) }

    if (showBaiduNetdisk) {
        BaiduNetdiskScreen(
            viewModel = baiduViewModel,
            onDismiss = { showBaiduNetdisk = false }
        )
        return
    }

    // 初始化 SMB 缩略图磁盘缓存
    LaunchedEffect(Unit) { SmbThumbnailLoader.init(context) }

    // 系统侧滑返回 — 多选时优先退出多选
    BackHandler(enabled = isSmbMultiSelect) { exitSmbMultiSelect() }
    // 系统侧滑返回 — 关闭历史页面或导航上级
    BackHandler(enabled = !isSmbMultiSelect && showHistoryPage) { showHistoryPage = false }
    BackHandler(enabled = !isSmbMultiSelect && !showHistoryPage && smbBrowseState !is SmbBrowseState.DeviceList) {
        viewModel.smbGoBack()
    }

    Box(Modifier.fillMaxSize()) {
        // ── 主内容（包括 Scaffold 的 TopAppBar + NavigationBar）──
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        val title = if (isSmbMultiSelect) "已选 ${selectedSmbPaths.size} 项"
                                    else when (val s = smbBrowseState) {
                                        is SmbBrowseState.DeviceList -> "网络相册"
                                        is SmbBrowseState.Connecting -> s.progressMessage
                                        is SmbBrowseState.Error -> "出错了"
                                        is SmbBrowseState.ShareList -> s.host
                                        is SmbBrowseState.FolderContent -> s.folderName
                                    }
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        if (isSmbMultiSelect) {
                            TextButton(onClick = { exitSmbMultiSelect() }) { Text("取消") }
                        } else if (smbBrowseState !is SmbBrowseState.DeviceList) {
                            TextButton(onClick = { viewModel.smbGoBack() }) {
                                Text("← ${if (smbBrowseState is SmbBrowseState.FolderContent) "上级" else "返回"}")
                            }
                        }
                    },
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    actions = {
                        if (isSmbMultiSelect) {
                            val allSelected = currentSmbMediaFiles.isNotEmpty() &&
                                currentSmbMediaFiles.all { it.path in selectedSmbPaths }
                            TextButton(onClick = {
                                if (allSelected) {
                                    selectedSmbPaths = emptySet()
                                    isSmbMultiSelect = false
                                } else {
                                    selectedSmbPaths = currentSmbMediaFiles.map { it.path }.toSet()
                                }
                            }) {
                                Text(if (allSelected) "取消全选" else "全选", fontSize = 13.sp)
                            }
                        } else if (smbBrowseState is SmbBrowseState.FolderContent) {
                            TextButton(onClick = { showNewFolderDialog = true }) {
                                Text("+新建", fontSize = 13.sp)
                            }
                            TextButton(onClick = { showHistoryPage = true }) {
                                Text("📋", fontSize = 16.sp)
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (val s = smbBrowseState) {
                    is SmbBrowseState.DeviceList -> {
                        DeviceListContent(
                            devices = smbDevices,
                            onBaiduClick = { showBaiduNetdisk = true },
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
                        Box(Modifier.fillMaxSize()) {
                            // 跟踪当前文件夹文件列表供 actions 全选用
                            currentSmbMediaFiles = s.mediaFiles
                            FolderMixedContent(
                                subFolders = s.subFolders,
                                mediaFiles = s.mediaFiles,
                                onFolderClick = { folder -> viewModel.smbOpenFolder(folder.path, folder.name) },
                                onFileClick = { idx, files ->
                                    val file = files.getOrNull(idx)
                                    if (isSmbMultiSelect && file != null) {
                                        toggleSmbSelection(file)
                                    } else if (file != null) {
                                        AppLogger.d("SMB", "preview file [$idx]: ${file.name}")
                                        previewState = idx to files
                                    }
                                },
                                isMultiSelect = isSmbMultiSelect,
                                selectedPaths = selectedSmbPaths,
                                onLongPress = { file ->
                                    if (!isSmbMultiSelect) isSmbMultiSelect = true
                                    toggleSmbSelection(file)
                                }
                            )

                            // ── 多选模式浮动按钮（竖排两按钮：加入中转站 + 删除）──
                            if (isSmbMultiSelect && selectedSmbPaths.isNotEmpty()) {
                                val selectedFiles = s.mediaFiles.filter { it.path in selectedSmbPaths }
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(end = 12.dp, bottom = 80.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    horizontalAlignment = Alignment.End
                                ) {
                                    // ── 加入中转站 ──
                                    SmbActionButton(
                                        text = "加入中转站 (${selectedSmbPaths.size})",
                                        containerColor = Color(0xCCFF9800),
                                        onClick = {
                                            viewModel.smbAddToClipboard(selectedFiles)
                                            exitSmbMultiSelect()
                                        }
                                    )
                                    SmbActionButton(
                                        text = "选择本地文件夹 (${selectedSmbPaths.size})",
                                        containerColor = Color(0xCC2E7D32),
                                        onClick = {
                                            pendingLocalTransferItems = selectedFiles
                                            localTransferFromClipboard = false
                                            showLocalTargetPicker = true
                                        }
                                    )
                                    // ── 批量删除 ──
                                    SmbActionButton(
                                        text = "删除 (${selectedSmbPaths.size})",
                                        containerColor = MaterialTheme.colorScheme.error,
                                        onClick = { showSmbDeleteConfirm = true }
                                    )
                                }
                            }

                            // ── SMB 中转站 badge ──
                            if (!isSmbMultiSelect && smbClipboardItems.isNotEmpty()) {
                                SmbClipboardBadge(
                                    count = smbClipboardItems.size,
                                    onPasteCopy = {
                                        viewModel.smbPasteToFolder(PasteMode.COPY, s.currentPath)
                                    },
                                    onPasteMove = {
                                        viewModel.smbPasteToFolder(PasteMode.MOVE, s.currentPath)
                                    },
                                    onChooseLocalTarget = {
                                        pendingLocalTransferItems = smbClipboardItems
                                        localTransferFromClipboard = true
                                        showLocalTargetPicker = true
                                    },
                                    onClear = { viewModel.smbClearClipboard() },
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(end = 16.dp, bottom = 16.dp)
                                )
                            }
                        }
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

        previewState?.let { (idx, files) ->
            if (idx in files.indices) {
                SmbPreviewScreen(
                    initialIndex = idx,
                    items = files,
                    onDismiss = { previewState = null },
                    onFileRenamed = { oldPath, newPath, newName ->
                        viewModel.smbApplyFileRename(oldPath, newPath, newName)
                    }
                )
            } else {
                previewState = null
            }
        }

        // ── SMB 操作历史页面 overlay ──
        if (showHistoryPage) {
            SmbHistoryPage(
                records = smbOperationHistory,
                onDismiss = { showHistoryPage = false },
                onExport = { viewModel.exportSmbOperationHistory(context) }
            )
        }

        // ── 删除确认对话框 ──
        if (showSmbDeleteConfirm) {
            val selectedFiles = remember(selectedSmbPaths) {
                val s = smbBrowseState
                if (s is SmbBrowseState.FolderContent) s.mediaFiles.filter { it.path in selectedSmbPaths }
                else emptyList()
            }
            AlertDialog(
                onDismissRequest = { showSmbDeleteConfirm = false },
                title = { Text("确认删除") },
                text = { Text("确定删除选中的 ${selectedSmbPaths.size} 个文件？\n此操作不可恢复。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showSmbDeleteConfirm = false
                            viewModel.smbDeleteSelected(selectedFiles)
                            exitSmbMultiSelect()
                        }
                    ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { showSmbDeleteConfirm = false }) { Text("取消") }
                }
            )
        }

        // ── 新建文件夹对话框 ──
        if (showNewFolderDialog) {
            var folderName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showNewFolderDialog = false },
                title = { Text("新建文件夹") },
                text = {
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = { folderName = it },
                        label = { Text("文件夹名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = folderName.isNotBlank(),
                        onClick = {
                            showNewFolderDialog = false
                            val state = smbBrowseState
                            if (state is SmbBrowseState.FolderContent) {
                                viewModel.smbCreateFolder(state.currentPath, folderName.trim())
                            }
                        }
                    ) { Text("创建") }
                },
                dismissButton = {
                    TextButton(onClick = { showNewFolderDialog = false }) { Text("取消") }
                }
            )
        }

        if (showLocalTargetPicker) {
            AlbumPickDialog(
                albums = localAlbums,
                recentMoveAlbums = recentMoveAlbums,
                onDismiss = {
                    showLocalTargetPicker = false
                    pendingLocalTransferItems = emptyList()
                    localTransferFromClipboard = false
                },
                onAlbumSelected = { targetDir, targetName, mode ->
                    val transferItems = pendingLocalTransferItems
                    val consumeClipboard = localTransferFromClipboard
                    showLocalTargetPicker = false
                    pendingLocalTransferItems = emptyList()
                    localTransferFromClipboard = false
                    if (transferItems.isNotEmpty()) {
                        viewModel.smbTransferToLocal(
                            items = transferItems,
                            targetDir = targetDir,
                            targetName = targetName,
                            mode = mode,
                            consumeClipboard = consumeClipboard
                        )
                        if (!consumeClipboard) exitSmbMultiSelect()
                    }
                },
                onCreateFolder = { name, onResult ->
                    scope.launch {
                        val path = withContext(Dispatchers.IO) {
                            val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                            val directory = File(dcim, name)
                            if (directory.mkdirs() || directory.exists()) directory.absolutePath else null
                        }
                        if (path != null) viewModel.loadAlbums()
                        onResult(path)
                    }
                }
            )
        }

        if (smbLocalTransferFailures.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { viewModel.clearSmbLocalTransferFailures() },
                title = { Text("部分文件未完成") },
                text = {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(smbLocalTransferFailures.take(8)) { failure ->
                            Column {
                                Text(failure.fileName, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(
                                    if (failure.localCopyCreated) {
                                        "本地副本已保留；SMB 源文件未确认删除。${failure.message}"
                                    } else {
                                        failure.message
                                    },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.clearSmbLocalTransferFailures() }) {
                        Text("知道了")
                    }
                }
            )
        }

        // ── 粘贴进度覆盖层 ──
        val smbPasteProgress by viewModel.pasteProgress.collectAsStateWithLifecycle()
        if (smbPasteProgress != null) {
            val progress = smbPasteProgress ?: return@Box
            SmbPasteProgressOverlay(progress)
        }
    }
}

/** 粘贴进度覆盖层（SMB） */
@Composable
private fun SmbPasteProgressOverlay(progress: com.example.rcgallery.viewmodel.GalleryViewModel.PasteProgress) {
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
                val label = if (progress.mode == PasteMode.MOVE) "移动" else "复制"
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

// ══════════════════════════════════════
//  设备列表
// ══════════════════════════════════════

@Composable
private fun DeviceListContent(
    devices: List<SmbDevice>,
    onBaiduClick: () -> Unit,
    onAddDevice: () -> Unit,
    onDeviceClick: (SmbDevice) -> Unit,
    onRemoveDevice: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onBaiduClick),
            elevation = CardDefaults.cardElevation(2.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("☁", fontSize = 30.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("百度网盘", fontWeight = FontWeight.Bold)
                    Text("账号授权 · 云端图片与视频", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("进入 ›", color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(10.dp))
        if (devices.isEmpty()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("还没有添加网络设备", style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text("点击下方按钮，添加局域网内共享的电脑", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
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
//  混合内容视图（RecyclerView）— 列表/网格双模式
// ══════════════════════════════════════

private const val SM_GRID_COLUMNS = 3
private const val SM_GAP_DP = 4

/** SMB 文件夹内容排序模式 */
private enum class SmbSortMode(val label: String) {
    NAME("名称"),
    SIZE("大小"),
    TYPE("类型"),
    DATE("修改时间")
}

@Composable
private fun FolderMixedContent(
    subFolders: List<SmbSubFolder>,
    mediaFiles: List<SmbFileInfo>,
    onFolderClick: (SmbSubFolder) -> Unit,
    onFileClick: (Int, List<SmbFileInfo>) -> Unit,
    // ── 多选参数 ──
    isMultiSelect: Boolean = false,
    selectedPaths: Set<String> = emptySet(),
    onLongPress: ((SmbFileInfo) -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    var isListMode by remember { mutableStateOf(true) } // 默认列表模式

    // ── 排序状态 ──
    var sortMode by remember { mutableStateOf(SmbSortMode.NAME) }
    var ascending by remember { mutableStateOf(true) }

    if (subFolders.isEmpty() && mediaFiles.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("此文件夹中没有媒体内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    // latestMediaFiles 每次重组时更新为最新的 mediaFiles，
    // 这样 remember 内的 onFileClick 就能始终读到当前文件夹的文件列表
    var latestMediaFiles by remember { mutableStateOf(mediaFiles) }
    latestMediaFiles = mediaFiles

    // ── 排序后的列表（文件夹走各自的 sort，不混排）──
    val sortedSubFolders = remember(subFolders, sortMode, ascending) {
        val sorted = subFolders.sortedWith(when (sortMode) {
            SmbSortMode.NAME -> compareBy { it.name.lowercase() }
            SmbSortMode.SIZE -> compareBy { it.mediaCount }
            SmbSortMode.TYPE -> compareBy { 0 }
            SmbSortMode.DATE -> compareByDescending { it.lastModified }
        })
        if (ascending) sorted else sorted.reversed()
    }
    val sortedMediaFiles = remember(mediaFiles, sortMode, ascending) {
        val sorted = mediaFiles.sortedWith(when (sortMode) {
            SmbSortMode.NAME -> compareBy { it.name.lowercase() }
            SmbSortMode.SIZE -> compareBy { it.size }
            SmbSortMode.TYPE -> compareBy<SmbFileInfo> { if (it.isVideo) 1 else 0 }
                .thenBy { it.name.lowercase() }
            SmbSortMode.DATE -> compareByDescending { it.lastModified }
        })
        if (ascending) sorted else sorted.reversed()
    }

    // latestSortedMediaFiles 跟踪排序后的列表，
    // 让 remember 内的 onFileClick 读到正确排序后的文件列表
    var latestSortedMediaFiles by remember { mutableStateOf(sortedMediaFiles) }
    latestSortedMediaFiles = sortedMediaFiles

    val adapter = remember {
        FolderMixedAdapter(
            subFolders = sortedSubFolders,
            mediaFiles = sortedMediaFiles,
            onFolderClick = onFolderClick,
            onFileClick = { idx, _ ->
                val file = latestSortedMediaFiles.getOrNull(idx)
                if (isMultiSelect && file != null) {
                    // Multi-select toggle handled by onLongPress callback
                } else {
                    onFileClick(idx, latestSortedMediaFiles)
                }
            },
            scope = scope,
            isListMode = isListMode,
            isMultiSelect = isMultiSelect,
            selectedPaths = selectedPaths,
            onLongPress = onLongPress
        )
    }

    // ── 排序选择器下拉状态 ──
    var sortExpanded by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                RecyclerView(ctx).apply {
                    layoutManager = if (isListMode) {
                        androidx.recyclerview.widget.LinearLayoutManager(ctx)
                    } else {
                        GridLayoutManager(ctx, SM_GRID_COLUMNS).apply {
                            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                                override fun getSpanSize(position: Int): Int = 1
                            }
                        }
                    }
                    this.adapter = adapter
                    clipToPadding = false
                    setPadding(4, (48 * resources.displayMetrics.density).toInt(), 4, 4)
                }
            },
            update = { rv ->
                // 模式切换：重新设置 LayoutManager + notifyDataSetChanged
                if (adapter.isListMode != isListMode) {
                    adapter.isListMode = isListMode
                    rv.layoutManager = if (isListMode) {
                        androidx.recyclerview.widget.LinearLayoutManager(rv.context)
                    } else {
                        GridLayoutManager(rv.context, SM_GRID_COLUMNS).apply {
                            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                                override fun getSpanSize(position: Int): Int = 1
                            }
                        }
                    }
                    adapter.notifyDataSetChanged()
                }
                if (adapter.subFolders !== sortedSubFolders || adapter.mediaFiles !== sortedMediaFiles) {
                    adapter.subFolders = sortedSubFolders
                    adapter.mediaFiles = sortedMediaFiles
                    adapter.notifyDataSetChanged()
                }
                // 多选状态更新
                if (adapter.isMultiSelect != isMultiSelect || adapter.selectedPaths != selectedPaths) {
                    adapter.isMultiSelect = isMultiSelect
                    adapter.selectedPaths = selectedPaths
                    adapter.notifyDataSetChanged()
                }
            }
        )

        // ── 顶部工具栏：排序 + 切换 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 6.dp)
                .align(Alignment.TopStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // ── 排序模式下拉 ──
            Box {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    shadowElevation = 4.dp,
                    onClick = { sortExpanded = true }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(sortMode.label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium)
                        Text(" ▼", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                    }
                }
                DropdownMenu(
                    expanded = sortExpanded,
                    onDismissRequest = { sortExpanded = false }
                ) {
                    SmbSortMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(mode.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                    if (mode == sortMode) {
                                        Text("✓", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                                    }
                                }
                            },
                            onClick = { sortMode = mode; sortExpanded = false }
                        )
                    }
                }
            }

            // ── 升序/降序切换 ──
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 4.dp,
                onClick = { ascending = !ascending }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        if (ascending) "↑ 升序" else "↓ 降序",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // ── 列表/网格切换 ──
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 4.dp,
                onClick = { isListMode = !isListMode }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        if (isListMode) "⊞ 网格" else "⊟ 列表",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

/**
 * 单个 RecyclerView 适配器，混合显示文件夹卡片 + 媒体缩略图。
 * 支持列表/网格双模式。
 */
private class FolderMixedAdapter(
    var subFolders: List<SmbSubFolder>,
    var mediaFiles: List<SmbFileInfo>,
    private val onFolderClick: (SmbSubFolder) -> Unit,
    private val onFileClick: (Int, SmbFileInfo) -> Unit,
    val scope: CoroutineScope,
    @Volatile var isListMode: Boolean = true,
    // ── 多选 ──
    @Volatile var isMultiSelect: Boolean = false,
    @Volatile var selectedPaths: Set<String> = emptySet(),
    var onLongPress: ((SmbFileInfo) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_FOLDER_GRID = 0
        private const val TYPE_MEDIA_GRID = 1
        private const val TYPE_FOLDER_LIST = 2
        private const val TYPE_MEDIA_LIST = 3
    }

    val folderCount get() = subFolders.size
    val mediaCount get() = mediaFiles.size

    override fun getItemCount() = folderCount + mediaCount

    override fun getItemViewType(position: Int): Int {
        val isFolder = position < folderCount
        return if (isListMode) {
            if (isFolder) TYPE_FOLDER_LIST else TYPE_MEDIA_LIST
        } else {
            if (isFolder) TYPE_FOLDER_GRID else TYPE_MEDIA_GRID
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_FOLDER_GRID -> FolderVH.create(parent, onFolderClick, scope)
            TYPE_MEDIA_GRID -> MediaVH.create(parent, onFileClick, scope, onLongPress)
            TYPE_FOLDER_LIST -> FolderListVH.create(parent, onFolderClick)
            TYPE_MEDIA_LIST -> MediaListVH.create(parent, onFileClick, scope, onLongPress)
            else -> throw IllegalArgumentException("unknown viewType=$viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is FolderVH -> holder.bind(subFolders[position], position)
            is MediaVH -> holder.bind(mediaFiles[position - folderCount], position, isMultiSelect, selectedPaths)
            is FolderListVH -> holder.bind(subFolders[position])
            is MediaListVH -> holder.bind(mediaFiles[position - folderCount], position - folderCount, isMultiSelect, selectedPaths)
        }
    }
}

// ── 网格模式：文件夹卡片 ──

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

            val folderIcon = ImageView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    (32 * d).toInt(), (32 * d).toInt(), Gravity.CENTER
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(0x00000000.toInt())
            }
            coverFrame.addView(folderIcon)
            root.addView(coverFrame)

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
                ).apply { setMargins(margin, (2 * d).toInt(), margin, margin) }
                setTextSize(11f)
                setTextColor(android.graphics.Color.GRAY)
            }
            root.addView(countTv)

            return FolderVH(root, iv, nameTv, countTv, onFolderClick, scope)
        }
    }

    private var currentCoverPath: String? = null
    private var coverLoadJob: kotlinx.coroutines.Job? = null

    fun bind(folder: SmbSubFolder, pos: Int) {
        itemView.tag = folder
        nameTv.text = folder.name
        countTv.text = "${folder.mediaCount} 项"

        val path = folder.coverPath
        if (coverIv.tag != path) {
            // 取消上次协程
            coverLoadJob?.cancel()
            coverIv.setImageDrawable(null)
            coverIv.setBackgroundColor(0xFF2A2A2A.toInt())
            coverIv.tag = path

            if (path.isNotEmpty()) {
                coverLoadJob = scope.launch {
                    val bm = SmbThumbnailLoader.load(url = path, maxPx = 300)
                    if (bm != null && coverIv.tag == path) {
                        coverIv.setImageBitmap(bm)
                    }
                }
            }
        }
    }
}

// ── 网格模式：媒体缩略图 ──

private class MediaVH private constructor(
    itemView: View,
    private val iv: ImageView,
    private val playIcon: TextView,
    private val selectedOverlay: FrameLayout,
    private val scope: CoroutineScope,
    private val onFileClick: (Int, SmbFileInfo) -> Unit
) : RecyclerView.ViewHolder(itemView) {

    private var bindPos: Int = 0
    private var currentItem: SmbFileInfo? = null

    init {
        itemView.setOnClickListener {
            val file = it.tag as? SmbFileInfo ?: return@setOnClickListener
            AppLogger.d("SMB", "click media: ${file.name} path=${file.path}")
            onFileClick(bindPos, file)
        }
        itemView.setOnLongClickListener {
            val file = currentItem ?: return@setOnLongClickListener true
            (itemView.parent as? RecyclerView)?.adapter?.let { adp ->
                if (adp is FolderMixedAdapter) {
                    adp.onLongPress?.invoke(file)
                }
            }
            true
        }
    }

    companion object {
        fun create(parent: ViewGroup, onFileClick: (Int, SmbFileInfo) -> Unit, scope: CoroutineScope, onLongPress: ((SmbFileInfo) -> Unit)? = null): MediaVH {
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

            // 选中状态覆盖层（绿色渐变背景 + 白色勾 ✓）
            val selectedOverlay = FrameLayout(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                visibility = android.view.View.GONE
                setBackgroundColor(android.graphics.Color.argb(80, 76, 175, 80)) // green tint
            }
            // 勾 ✓ 圆圈
            val checkmarkContainer = FrameLayout(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    (36 * d).toInt(), (36 * d).toInt(), Gravity.CENTER
                )
                background = android.graphics.drawable.GradientDrawable().apply {
                    setShape(android.graphics.drawable.GradientDrawable.OVAL)
                    setColor(android.graphics.Color.argb(220, 76, 175, 80))
                }
            }
            val checkTv = TextView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
                text = "✓"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 20f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            checkmarkContainer.addView(checkTv)
            selectedOverlay.addView(checkmarkContainer)
            root.addView(selectedOverlay)

            return MediaVH(root, iv, playIcon, selectedOverlay, scope, onFileClick)
        }
    }

    private var currentPath: String? = null
    private var loadJob: kotlinx.coroutines.Job? = null

    fun bind(file: SmbFileInfo, pos: Int, isMultiSelect: Boolean = false, selectedPaths: Set<String> = emptySet()) {
        bindPos = pos
        currentItem = file
        itemView.tag = file
        val path = file.path
        val isVideo = file.isVideo

        AppLogger.d("SMB-THUMB", "bind pos=$pos name=${file.name} isVideo=$isVideo path=$path")
        playIcon.visibility = if (isVideo) android.view.View.VISIBLE else android.view.View.GONE

        // ── 多选状态 ──
        if (isMultiSelect) {
            val isSel = file.path in selectedPaths
            itemView.alpha = if (isSel) 1.0f else 0.6f
            selectedOverlay.visibility = if (isSel) android.view.View.VISIBLE else android.view.View.GONE
        } else {
            itemView.alpha = 1.0f
            selectedOverlay.visibility = android.view.View.GONE
        }

        if (path == currentPath) return

        // 取消上次协程，防止快速滚动时大量协程堆积
        loadJob?.cancel()
        currentPath = path
        iv.setImageDrawable(null)
        iv.setBackgroundColor(if (isVideo) 0xFF333333.toInt() else 0xFF1A1A1A.toInt())
        iv.tag = path

        loadJob = scope.launch {
            val context = itemView.context
            val bm = SmbThumbnailLoader.load(url = path, fileSize = file.size, maxPx = 300, context = context)
            if (bm != null && iv.tag == path) {
                iv.setImageBitmap(bm)
            } else if (bm == null && iv.tag == path && currentPath == path) {
                currentPath = null
            }
        }
    }
}

// ── 列表模式：文件夹行 ──

private class FolderListVH private constructor(
    itemView: View,
    private val nameTv: TextView,
    private val countTv: TextView,
    private val onFolderClick: (SmbSubFolder) -> Unit
) : RecyclerView.ViewHolder(itemView) {

    init {
        itemView.setOnClickListener {
            val folder = it.tag as? SmbSubFolder ?: return@setOnClickListener
            AppLogger.d("SMB", "folder click: ${folder.name}")
            onFolderClick(folder)
        }
    }

    companion object {
        fun create(parent: ViewGroup, onFolderClick: (SmbSubFolder) -> Unit): FolderListVH {
            val ctx = parent.context
            val d = ctx.resources.displayMetrics.density
            val root = LinearLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(0xFF1A1A1A.toInt())
            }

            val icon = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (36 * d).toInt(), (36 * d).toInt()
                )
                text = "📁"
                textSize = 22f
                gravity = Gravity.CENTER
            }
            root.addView(icon)

            val nameTv = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { setMargins((8 * d).toInt(), 0, (8 * d).toInt(), 0) }
                setTextSize(14f)
                setTextColor(android.graphics.Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            root.addView(nameTv)

            val countTv = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setTextSize(12f)
                setTextColor(android.graphics.Color.GRAY)
            }
            root.addView(countTv)

            return FolderListVH(root, nameTv, countTv, onFolderClick)
        }
    }

    fun bind(folder: SmbSubFolder) {
        itemView.tag = folder
        nameTv.text = folder.name
        countTv.text = "${folder.mediaCount} 项"
    }
}

// ── 列表模式：媒体行（小缩略图 64dp）──

private class MediaListVH private constructor(
    itemView: View,
    private val iv: ImageView,
    private val nameTv: TextView,
    private val sizeTv: TextView,
    private val playLabel: TextView,
    private val selectedOverlay: FrameLayout,
    private val scope: CoroutineScope,
    private val onFileClick: (Int, SmbFileInfo) -> Unit
) : RecyclerView.ViewHolder(itemView) {

    private var bindPos: Int = 0
    private var currentItem: SmbFileInfo? = null

    init {
        itemView.setOnClickListener {
            val file = it.tag as? SmbFileInfo ?: return@setOnClickListener
            AppLogger.d("SMB", "click media: ${file.name} path=${file.path}")
            onFileClick(bindPos, file)
        }
        itemView.setOnLongClickListener {
            val file = currentItem ?: return@setOnLongClickListener true
            (itemView.parent as? RecyclerView)?.adapter?.let { adp ->
                if (adp is FolderMixedAdapter) {
                    adp.onLongPress?.invoke(file)
                }
            }
            true
        }
    }

    companion object {
        fun create(parent: ViewGroup, onFileClick: (Int, SmbFileInfo) -> Unit, scope: CoroutineScope, onLongPress: ((SmbFileInfo) -> Unit)? = null): MediaListVH {
            val ctx = parent.context
            val d = ctx.resources.displayMetrics.density
            val thumbPx = (56 * d).toInt()
            val root = LinearLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding((4 * d).toInt(), (4 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            // 缩略图容器
            val thumbFrame = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(thumbPx, thumbPx).apply {
                    setMargins((4 * d).toInt(), 0, (8 * d).toInt(), 0)
                }
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
            thumbFrame.addView(iv)
            val playLabel = TextView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
                text = "▶"
                setTextSize(16f)
                setTextColor(android.graphics.Color.WHITE)
                visibility = android.view.View.GONE
            }
            thumbFrame.addView(playLabel)

            // 选中状态覆盖层
            val selectedOverlay = FrameLayout(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                visibility = android.view.View.GONE
                setBackgroundColor(android.graphics.Color.argb(80, 76, 175, 80))
            }
            val checkmarkContainer = FrameLayout(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    (28 * d).toInt(), (28 * d).toInt(), Gravity.CENTER
                )
                background = android.graphics.drawable.GradientDrawable().apply {
                    setShape(android.graphics.drawable.GradientDrawable.OVAL)
                    setColor(android.graphics.Color.argb(220, 76, 175, 80))
                }
            }
            val checkTv = TextView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
                text = "✓"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            checkmarkContainer.addView(checkTv)
            selectedOverlay.addView(checkmarkContainer)
            thumbFrame.addView(selectedOverlay)

            root.addView(thumbFrame)

            // 文件名
            val nameTv = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { setMargins(0, 0, (8 * d).toInt(), 0) }
                setTextSize(13f)
                setTextColor(android.graphics.Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            root.addView(nameTv)

            // 文件大小
            val sizeTv = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setTextSize(11f)
                setTextColor(android.graphics.Color.GRAY)
            }
            root.addView(sizeTv)

            return MediaListVH(root, iv, nameTv, sizeTv, playLabel, selectedOverlay, scope, onFileClick)
        }
    }

    private var currentPath: String? = null
    private var loadJob: kotlinx.coroutines.Job? = null

    fun bind(file: SmbFileInfo, pos: Int = 0, isMultiSelect: Boolean = false, selectedPaths: Set<String> = emptySet()) {
        bindPos = pos
        currentItem = file
        itemView.tag = file
        val path = file.path
        val isVideo = file.isVideo

        nameTv.text = file.name
        sizeTv.text = formatFileSize(file.size)
        playLabel.visibility = if (isVideo) android.view.View.VISIBLE else android.view.View.GONE

        // ── 多选状态 ──
        if (isMultiSelect) {
            val isSel = file.path in selectedPaths
            itemView.alpha = if (isSel) 1.0f else 0.6f
            selectedOverlay.visibility = if (isSel) android.view.View.VISIBLE else android.view.View.GONE
        } else {
            itemView.alpha = 1.0f
            selectedOverlay.visibility = android.view.View.GONE
        }

        if (path == currentPath) return
        // 取消上次协程，防止快速滚动时大量协程堆积
        loadJob?.cancel()
        currentPath = path
        iv.setImageDrawable(null)
        iv.setBackgroundColor(0xFF1A1A1A.toInt())
        iv.tag = path

        // 列表模式用小缩略图（64dp → maxPx=120），加载更快
        loadJob = scope.launch {
            val context = itemView.context
            val bm = SmbThumbnailLoader.load(url = path, fileSize = file.size, maxPx = 120, context = context)
            if (bm != null && iv.tag == path) {
                iv.setImageBitmap(bm)
            } else if (bm == null && iv.tag == path && currentPath == path) {
                currentPath = null
            }
        }
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}

// ══════════════════════════════════════
//  SMB 多选 UI 组件
// ══════════════════════════════════════

/**
 * SMB 多选模式单个悬浮操作按钮。
 * 与本地 MediaGridScreen 的 ActionButton 风格一致。
 */
@Composable
private fun SmbActionButton(
    text: String,
    containerColor: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        onClick = onClick,
        modifier = Modifier.height(34.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxHeight().padding(horizontal = 10.dp)
        ) {
            Text(
                text = text,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

/**
 * SMB 中转站浮动 badge。
 * 显示在中转站有内容时，圆形红底白字计数器。
 * 点击弹出菜单：复制到当前文件夹、移动到当前文件夹、清空。
 */
@Composable
private fun SmbClipboardBadge(
    count: Int,
    onPasteCopy: () -> Unit,
    onPasteMove: () -> Unit,
    onChooseLocalTarget: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (count <= 0) return

    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        // 圆形 badge
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0xFFE53935))
                .clickable { expanded = true },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = count.toString(),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        // 弹出菜单
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("粘贴到当前文件夹 (复制)", fontSize = 14.sp) },
                onClick = {
                    expanded = false
                    onPasteCopy()
                }
            )
            DropdownMenuItem(
                text = { Text("粘贴到当前文件夹 (移动)", fontSize = 14.sp) },
                onClick = {
                    expanded = false
                    onPasteMove()
                }
            )
            DropdownMenuItem(
                text = { Text("选择本地目标文件夹", fontSize = 14.sp) },
                onClick = {
                    expanded = false
                    onChooseLocalTarget()
                }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("清空中转站", fontSize = 14.sp, color = Color.Gray) },
                onClick = {
                    expanded = false
                    onClear()
                }
            )
        }
    }
}
