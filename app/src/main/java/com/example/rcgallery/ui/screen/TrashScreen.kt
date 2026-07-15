package com.example.rcgallery.ui.screen

import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.rcgallery.model.TrashEntry
import com.example.rcgallery.util.AppLogger
import com.example.rcgallery.viewmodel.GalleryViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ══════════════════════════════════════
//  回收站分类 Tab
// ══════════════════════════════════════

private enum class TrashTab { ALL, IMAGE, VIDEO }

private const val MAX_AGE_DAYS = 30L

/**
 * 回收站全屏覆盖层。
 * 支持多选、分类筛选、批量操作、清空、过期倒计时。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBackClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val viewModel: GalleryViewModel = viewModel(activity)
    val entries by viewModel.trashEntries.collectAsStateWithLifecycle()

    // ── 多选状态 ──
    var isMultiSelectMode by remember { mutableStateOf(false) }
    val selectedUrisState = remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedUris by selectedUrisState  // composable 通过委托访问
    var activeTab by remember { mutableStateOf(TrashTab.ALL) }

    // ── Tab 切换时自动退出多选模式，防止选中项残留 ──
    LaunchedEffect(activeTab) {
        if (isMultiSelectMode) {
            isMultiSelectMode = false
            selectedUris = emptySet()
        }
    }

    // ── 单条目对话框状态（保留现有流程）──
    var selectedEntry by remember { mutableStateOf<TrashEntry?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }

    // ── 批量操作对话框状态 ──
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var showRestoreAllConfirm by remember { mutableStateOf(false) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    // 待批量删除的条目（用于 IntentSender 回调后操作）
    val pendingBatchEntries = remember { mutableStateListOf<TrashEntry>() }
    var directDeletedBeforeRequest by remember { mutableIntStateOf(0) }
    var directFailedBeforeRequest by remember { mutableIntStateOf(0) }

    // ── Tab 过滤 ──
    val filteredEntries = remember(entries, activeTab) {
        when (activeTab) {
            TrashTab.ALL -> entries
            TrashTab.IMAGE -> entries.filter { !it.isVideo }
            TrashTab.VIDEO -> entries.filter { it.isVideo }
        }
    }
    val imageCount = remember(entries) { entries.count { !it.isVideo } }
    val videoCount = remember(entries) { entries.count { it.isVideo } }

    // ── 网格状态（滑多选需要读取 layoutInfo）──
    val gridState = rememberLazyGridState()

    // ── 多选模式拦截网格滚动（防止拖拽时列表跟随滚动）──
    val scrollBlockConnection = remember(isMultiSelectMode) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (isMultiSelectMode) return available  // 消耗所有滚动事件
                return Offset.Zero
            }
        }
    }

    // 滑多选拖拽起点索引
    var dragStartIndex by remember { mutableIntStateOf(-1) }

    // ── 触摸坐标 → 网格索引 ──
    fun findItemIndex(touchX: Float, touchY: Float): Int? {
        val info = gridState.layoutInfo
        // LazyGridItemInfo: offset = IntOffset(x, y), size = IntSize(width, height)
        return info.visibleItemsInfo.firstOrNull { item ->
            touchY >= item.offset.y && touchY <= item.offset.y + item.size.height &&
            touchX >= item.offset.x && touchX <= item.offset.x + item.size.width
        }?.index
    }

    // ── 返回键处理：多选模式先退出多选 ──
    BackHandler {
        if (isMultiSelectMode) {
            isMultiSelectMode = false
            selectedUris = emptySet()
        } else {
            onBackClick()
        }
    }

    // ── 删除请求 launcher（单条目和批量共用）──
    val deleteRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        try {
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                if (pendingBatchEntries.isNotEmpty()) {
                    // 批量/清空删除
                    val batchSize = pendingBatchEntries.size
                    viewModel.batchPermanentlyDeleteConfirmed(
                        pendingBatchEntries.map { it.uri to it.originalAlbumId }
                    )
                    pendingBatchEntries.clear()
                    val totalDeleted = directDeletedBeforeRequest + batchSize
                    val suffix = if (directFailedBeforeRequest > 0) "，${directFailedBeforeRequest} 项失败" else ""
                    Toast.makeText(context, "已永久删除 $totalDeleted 项$suffix", Toast.LENGTH_SHORT).show()
                } else {
                    // 单条目删除
                    val entry = selectedEntry
                    if (entry != null) {
                        viewModel.permanentlyDeleteConfirmed(entry.uri, entry.originalAlbumId)
                        Toast.makeText(context, "已永久删除", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                if (directDeletedBeforeRequest > 0) {
                    Toast.makeText(
                        context,
                        "已永久删除 $directDeletedBeforeRequest 项，其余项目未获得授权",
                        Toast.LENGTH_SHORT
                    ).show()
                } else if (pendingBatchEntries.isEmpty() && selectedEntry == null) {
                    // 用户取消了系统弹窗，不做额外操作
                } else {
                    Toast.makeText(context, "删除失败：未获得授权", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            AppLogger.e("Trash", "permanent delete callback error", e)
            Toast.makeText(context, "删除操作异常", Toast.LENGTH_SHORT).show()
        } finally {
            selectedEntry = null
            selectedUris = emptySet()
            isMultiSelectMode = false
            isDeleting = false
            pendingBatchEntries.clear()
            directDeletedBeforeRequest = 0
            directFailedBeforeRequest = 0
        }
    }

    // 退出 TrashScreen 时清理 pending 状态
    DisposableEffect(Unit) {
        onDispose {
            showDeleteConfirm = false
            isDeleting = false
            pendingBatchEntries.clear()
        }
    }

    // ── 辅助函数 ──

    fun exitMultiSelect() {
        isMultiSelectMode = false
        selectedUris = emptySet()
    }

    fun toggleSelection(uri: String) {
        selectedUris = if (uri in selectedUris) selectedUris - uri
                       else selectedUris + uri
        if (selectedUris.isEmpty()) {
            isMultiSelectMode = false  // 全取消后自动退出多选模式
        }
    }

    fun selectAll() {
        selectedUris = filteredEntries.map { it.uri }.toSet()
    }

    fun isMediaStoreEntry(entry: TrashEntry): Boolean {
        val uri = Uri.parse(entry.uri)
        val albumDirectory = entry.filePath.takeIf { it.isNotBlank() }
            ?.let { File(it).parent }
            .orEmpty()
        return !viewModel.isSystemHiddenAlbum(albumDirectory) &&
            uri.scheme.equals("content", ignoreCase = true) &&
            uri.authority == MediaStore.AUTHORITY
    }

    fun isFileEntry(entry: TrashEntry): Boolean {
        val uri = Uri.parse(entry.uri)
        val albumDirectory = entry.filePath.takeIf { it.isNotBlank() }
            ?.let { File(it).parent }
            .orEmpty()
        return uri.scheme.equals("file", ignoreCase = true) ||
            viewModel.isSystemHiddenAlbum(albumDirectory)
    }

    fun finishWithoutSystemRequest(deletedCount: Int, failedCount: Int) {
        val message = if (failedCount == 0) {
            "已永久删除 $deletedCount 项"
        } else {
            "已永久删除 $deletedCount 项，$failedCount 项失败"
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        selectedEntry = null
        selectedUris = emptySet()
        isMultiSelectMode = false
        isDeleting = false
    }

    fun deleteEntriesPermanently(targetEntries: List<TrashEntry>) {
        val fileEntries = targetEntries.filter(::isFileEntry)
        val mediaStoreEntries = targetEntries.filter(::isMediaStoreEntry)
        val unsupportedCount = targetEntries.size - fileEntries.size - mediaStoreEntries.size

        fun continueWithMediaStore(directDeleted: Int, directFailed: Int) {
            val failedBeforeRequest = directFailed + unsupportedCount
            if (mediaStoreEntries.isEmpty()) {
                finishWithoutSystemRequest(directDeleted, failedBeforeRequest)
                return
            }

            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    pendingBatchEntries.clear()
                    pendingBatchEntries.addAll(mediaStoreEntries)
                    directDeletedBeforeRequest = directDeleted
                    directFailedBeforeRequest = failedBeforeRequest
                    val pending = MediaStore.createDeleteRequest(
                        context.contentResolver,
                        mediaStoreEntries.map { Uri.parse(it.uri) }
                    )
                    deleteRequestLauncher.launch(
                        IntentSenderRequest.Builder(pending.intentSender).build()
                    )
                } catch (e: Exception) {
                    AppLogger.e("Trash", "request MediaStore delete failed", e)
                    pendingBatchEntries.clear()
                    finishWithoutSystemRequest(directDeleted, failedBeforeRequest + mediaStoreEntries.size)
                }
            } else {
                val deletedMediaEntries = mediaStoreEntries.filter { entry ->
                    runCatching {
                        context.contentResolver.delete(Uri.parse(entry.uri), null, null) > 0
                    }.onFailure {
                        AppLogger.e("Trash", "MediaStore delete failed: ${entry.fileName}", it)
                    }.getOrDefault(false)
                }
                if (deletedMediaEntries.isNotEmpty()) {
                    viewModel.batchPermanentlyDeleteConfirmed(
                        deletedMediaEntries.map { it.uri to it.originalAlbumId }
                    )
                }
                finishWithoutSystemRequest(
                    directDeleted + deletedMediaEntries.size,
                    failedBeforeRequest + mediaStoreEntries.size - deletedMediaEntries.size
                )
            }
        }

        if (fileEntries.isEmpty()) {
            continueWithMediaStore(0, 0)
        } else {
            viewModel.permanentlyDeleteFileEntries(fileEntries) { deleted, failed ->
                continueWithMediaStore(deleted, failed)
            }
        }
    }

    // ── 对话框 ──

    // 单条目永久删除确认（现有流程）
    if (showDeleteConfirm && selectedEntry != null) {
        val entry = selectedEntry!!
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("永久删除") },
            text = {
                Text("文件「${entry.fileName}」将被从设备中彻底删除，无法恢复。\n确定要继续吗？")
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isDeleting) return@Button
                        isDeleting = true
                        showDeleteConfirm = false
                        deleteEntriesPermanently(listOf(entry))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } }
        )
    }

    // 清空回收站确认
    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text("清空回收站") },
            text = {
                Text("确定要清空回收站吗？\n共 ${entries.size} 个文件将被永久删除，无法恢复。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearAllConfirm = false
                        if (entries.isEmpty()) return@Button
                        deleteEntriesPermanently(entries)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { showClearAllConfirm = false }) { Text("取消") } }
        )
    }

    // 全部还原确认
    if (showRestoreAllConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreAllConfirm = false },
            title = { Text("全部还原") },
            text = {
                Text("确定要还原回收站中所有 ${entries.size} 个文件吗？\n文件将恢复到原始相册。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestoreAllConfirm = false
                        if (entries.isEmpty()) return@Button
                        viewModel.batchRestoreFromTrash(entries.map { it.uri })
                        viewModel.loadAlbums()
                        Toast.makeText(context, "已还原 ${entries.size} 项", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) { Text("全部还原") }
            },
            dismissButton = { TextButton(onClick = { showRestoreAllConfirm = false }) { Text("取消") } }
        )
    }

    // 批量永久删除确认
    if (showBatchDeleteConfirm && selectedUris.isNotEmpty()) {
        val count = selectedUris.size
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text("批量永久删除") },
            text = {
                Text("确定要永久删除选中的 $count 个文件吗？\n此操作无法恢复。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBatchDeleteConfirm = false
                        val toDelete = entries.filter { it.uri in selectedUris }
                        if (toDelete.isEmpty()) return@Button
                        deleteEntriesPermanently(toDelete)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showBatchDeleteConfirm = false }) { Text("取消") } }
        )
    }

    // ── 单条目操作对话框（非多选模式，现有流程）──
    if (!isMultiSelectMode && selectedEntry != null && !showDeleteConfirm) {
        val entry = selectedEntry!!
        val deleteTimeStr = remember(entry.deleteTime) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date(entry.deleteTime))
        }
        val remainingDays = remember(entry.deleteTime) {
            val now = System.currentTimeMillis()
            val elapsed = now - entry.deleteTime
            val maxMs = MAX_AGE_DAYS * 24 * 60 * 60 * 1000L
            if (elapsed >= maxMs) 0L else (maxMs - elapsed) / 86400000L
        }

        AlertDialog(
            onDismissRequest = { selectedEntry = null },
            title = { Text(entry.fileName, maxLines = 2) },
            text = {
                Column {
                    Text("删除时间：$deleteTimeStr", fontSize = 13.sp, color = Color.Gray)
                    if (entry.originalAlbumName != null) {
                        Text("来源相册：${entry.originalAlbumName}", fontSize = 13.sp, color = Color.Gray)
                    }
                    if (entry.isVideo) {
                        Text("类型：视频", fontSize = 13.sp, color = Color.Gray)
                    }
                    Text(
                        text = if (remainingDays > 0) "剩余自动清理：${remainingDays} 天" else "即将自动清理",
                        fontSize = 12.sp,
                        color = if (remainingDays > 3) Color(0xFF999999) else Color(0xFFFF9800)
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel.restoreFromTrash(entry.uri)
                            viewModel.loadAlbums()
                            selectedEntry = null
                            Toast.makeText(context, "已恢复", Toast.LENGTH_SHORT).show()
                        }
                    ) { Text("恢复") }
                    Button(
                        onClick = { showDeleteConfirm = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("永久删除") }
                }
            },
            dismissButton = { TextButton(onClick = { selectedEntry = null }) { Text("取消") } }
        )
    }

    // ══════════════════════════════════════
    //  主 UI
    // ══════════════════════════════════════

    Surface(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    windowInsets = WindowInsets(0, 0, 0, 0),  // 外层 Scaffold/overlay 已处理状态栏 insets
                    title = {
                        Text(
                            if (isMultiSelectMode) "已选 ${selectedUris.size} 项"
                            else "回收站 (${entries.size})",
                            maxLines = 1,
                            color = Color.White
                        )
                    },
                    navigationIcon = {
                        if (isMultiSelectMode) {
                            TextButton(onClick = { exitMultiSelect() }) {
                                Text("取消", color = Color.White)
                            }
                        } else {
                            TextButton(onClick = onBackClick) {
                                Text("← 返回", color = Color.White)
                            }
                        }
                    },
                    actions = {
                        if (isMultiSelectMode) {
                            TextButton(onClick = { selectAll() }) {
                                Text(
                                    if (selectedUris.size == filteredEntries.size) "取消全选" else "全选",
                                    color = Color.White
                                )
                            }
                        } else {
                            // 全部还原 + 清空回收站按钮（有条目时显示）
                            if (entries.isNotEmpty()) {
                                IconButton(onClick = { showRestoreAllConfirm = true }) {
                                    Icon(
                                        painter = androidx.compose.ui.res.painterResource(com.example.rcgallery.R.drawable.ic_restore),
                                        contentDescription = "全部还原",
                                        tint = Color(0xFF4CAF50)
                                    )
                                }
                                IconButton(onClick = { showClearAllConfirm = true }) {
                                    Icon(
                                        painter = androidx.compose.ui.res.painterResource(com.example.rcgallery.R.drawable.ic_trash),
                                        contentDescription = "清空回收站",
                                        tint = Color(0xFFFF5252)
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A1A))
                )
            },
            bottomBar = {
                if (isMultiSelectMode && selectedUris.isNotEmpty()) {
                    BottomAppBar(
                        containerColor = Color(0xFF1A1A1A),
                        tonalElevation = 8.dp
                    ) {
                        Text(
                            "已选 ${selectedUris.size} 项",
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                val toRestore = entries.filter { it.uri in selectedUris }
                                val uris = toRestore.map { it.uri }
                                viewModel.batchRestoreFromTrash(uris)
                                viewModel.loadAlbums()
                                exitMultiSelect()
                                Toast.makeText(context, "已恢复 ${uris.size} 项", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("还原 (${selectedUris.size})", color = Color(0xFF4CAF50))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                showBatchDeleteConfirm = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("永久删除 (${selectedUris.size})", fontSize = 13.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                }
            },
            containerColor = Color.Black
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                // ── 主体：TabRow + 网格（可滚动区域，weight 1 填满剩余空间）──
                Column(modifier = Modifier.weight(1f)) {
                // ── 分类 Tab ──
                TabRow(
                    selectedTabIndex = activeTab.ordinal,
                    containerColor = Color(0xFF1A1A1A),
                    contentColor = Color.White,
                    divider = { HorizontalDivider(color = Color(0xFF333333)) }
                ) {
                    Tab(
                        selected = activeTab == TrashTab.ALL,
                        onClick = { activeTab = TrashTab.ALL }
                    ) {
                        Text(
                            "全部",
                            fontSize = 14.sp,
                            fontWeight = if (activeTab == TrashTab.ALL) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(vertical = 10.dp)
                        )
                    }
                    Tab(
                        selected = activeTab == TrashTab.IMAGE,
                        onClick = { activeTab = TrashTab.IMAGE }
                    ) {
                        Text(
                            "图片 ($imageCount)",
                            fontSize = 14.sp,
                            fontWeight = if (activeTab == TrashTab.IMAGE) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(vertical = 10.dp)
                        )
                    }
                    Tab(
                        selected = activeTab == TrashTab.VIDEO,
                        onClick = { activeTab = TrashTab.VIDEO }
                    ) {
                        Text(
                            "视频 ($videoCount)",
                            fontSize = 14.sp,
                            fontWeight = if (activeTab == TrashTab.VIDEO) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(vertical = 10.dp)
                        )
                    }
                }

                // ── 网格区域 + 批量按钮覆盖层（Box 防止 fillMaxSize 挤压按钮）──
                Box(modifier = Modifier.weight(1f)) {
                if (entries.isEmpty()) {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("回收站是空的", color = Color.Gray, fontSize = 16.sp)
                    }
                } else if (filteredEntries.isEmpty()) {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("没有符合条件的文件", color = Color.Gray, fontSize = 16.sp)
                    }
                } else {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(4),
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBlockConnection)
                            .pointerInput(isMultiSelectMode, filteredEntries) {
                                if (!isMultiSelectMode) return@pointerInput
                                var lastDragIndex = -1
                                detectDragGestures(
                                    onDragStart = { pos ->
                                        val idx = findItemIndex(pos.x, pos.y) ?: return@detectDragGestures
                                        val entry = filteredEntries.getOrNull(idx) ?: return@detectDragGestures
                                        dragStartIndex = idx
                                        lastDragIndex = idx
                                        // 从当前项开始选中（替换先前选中，拖拽期间以手指范围为精确选择）
                                        selectedUrisState.value = setOf(entry.uri)
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        val idx = findItemIndex(change.position.x, change.position.y) ?: return@detectDragGestures
                                        if (idx == lastDragIndex) return@detectDragGestures
                                        lastDragIndex = idx
                                        // 选中 dragStart 到 current 之间的所有项（前后滑动都能取消超出范围的选择）
                                        val minIdx = minOf(dragStartIndex, idx)
                                        val maxIdx = maxOf(dragStartIndex, idx)
                                        val rangeUris = (minIdx..maxIdx).mapNotNull { i ->
                                            filteredEntries.getOrNull(i)?.uri
                                        }.toSet()
                                        selectedUrisState.value = rangeUris
                                        if (!isMultiSelectMode) isMultiSelectMode = true
                                    },
                                    onDragEnd = { lastDragIndex = -1 },
                                    onDragCancel = { lastDragIndex = -1 }
                                )
                            },
                        contentPadding = PaddingValues(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(filteredEntries, key = { it.uri }) { entry ->
                            TrashGridItem(
                                entry = entry,
                                isSelected = entry.uri in selectedUris,
                                isMultiSelectMode = isMultiSelectMode,
                                onClick = {
                                    if (isMultiSelectMode) {
                                        toggleSelection(entry.uri)
                                    } else {
                                        selectedEntry = entry
                                    }
                                }
                            )
                        }
                    }
                }

                    // ── 批量选择按钮（覆盖在网格左下角）──
                    if (!isMultiSelectMode && entries.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 12.dp, bottom = 12.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = Color(0xFF4CAF50),
                                onClick = {
                                    isMultiSelectMode = true
                                    selectedUris = emptySet()
                                }
                            ) {
                                Text(
                                    "批量选择",
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }   // ← Box end
            }       // ← inner Column end
            }
        }
    }
}

// ══════════════════════════════════════
//  网格条目（含多选 checkbox + 过期标签）
// ══════════════════════════════════════

@Composable
private fun TrashGridItem(
    entry: TrashEntry,
    isSelected: Boolean,
    isMultiSelectMode: Boolean,
    onClick: () -> Unit
) {
    // 过期天数计算
    val remainingDays = remember(entry.deleteTime) {
        val now = System.currentTimeMillis()
        val elapsed = now - entry.deleteTime
        val maxMs = MAX_AGE_DAYS * 24 * 60 * 60 * 1000L
        if (elapsed >= maxMs) 0L else (maxMs - elapsed) / 86400000L
    }

    val expiryLabel = when {
        remainingDays <= 0 -> "已过期"
        remainingDays <= 1 -> "今天"
        remainingDays <= 7 -> "${remainingDays}天"
        else -> null  // 超过 7 天不显示
    }
    val expiryColor = when {
        remainingDays <= 0 -> Color(0xFFFF5252)
        remainingDays <= 1 -> Color(0xFFFF9800)
        else -> Color(0xFFFFEB3B)
    }

    val borderColor = if (isSelected) Color(0xFF4CAF50) else Color.Transparent
    val borderWidth = if (isSelected) 3.dp else 0.dp

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(if (isSelected) Color(0xFF2A5A2A) else Color(0xFF2A2A2A))
            .clickable { onClick() }
    ) {
        // 缩略图
        AsyncImage(
            model = entry.uri,
            contentDescription = entry.fileName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // 多选模式下的选中边框
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x804CAF50))  // 半透明绿色覆盖
                    .clip(RoundedCornerShape(4.dp))
            )
        }

        // 多选模式下的 Checkbox（左上角）
        if (isMultiSelectMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(3.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) Color(0xFF4CAF50) else Color(0xCC333333))
                    .then(
                        Modifier
                            .wrapContentSize(Alignment.Center)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            // 非多选模式：右上角红色圆点（保留现有设计）
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color.Red)
            )
        }

        // 过期倒计时标签（右下角）
        if (expiryLabel != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(2.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            ) {
                Text(
                    text = expiryLabel,
                    color = expiryColor,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }

        // 视频标记（左下角）
        if (entry.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(2.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            ) {
                Text("VIDEO", color = Color.White, fontSize = 8.sp, maxLines = 1)
            }
        }
    }
}
