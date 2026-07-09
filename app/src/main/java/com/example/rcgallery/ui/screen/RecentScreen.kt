package com.example.rcgallery.ui.screen

import androidx.activity.compose.BackHandler
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rcgallery.model.Album
import com.example.rcgallery.model.MediaItem
import com.example.rcgallery.ui.component.GalleryThumbnail
import com.example.rcgallery.util.AppLogger
import com.example.rcgallery.viewmodel.GalleryViewModel
import com.example.rcgallery.viewmodel.GalleryViewModel.MoveFileEntry
import com.example.rcgallery.viewmodel.GalleryViewModel.MoveRecord
import com.example.rcgallery.viewmodel.PasteMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 筛选类型 */
private enum class RecentFilter(val label: String) {
    ALBUM("相册"), IMAGE("图片"), VIDEO("视频")
}

private val RECENT_FILTERS = RecentFilter.entries.toList()
private const val GRID_COLUMNS = 4

/**
 * 最近浏览页面——查看最近查看过的相册/图片/视频及最近移动/复制记录。
 * 顶部 [最近浏览] [最近新增] [最近移动] 切换（最近新增暂不实现）。
 * 三种内容切换参考标签页的 [相册] [图片] [视频] chips。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentScreen(
    viewModel: GalleryViewModel,
    onOverlayChanged: (Boolean) -> Unit = {}
) {
    val recentAlbums by viewModel.recentAlbums.collectAsStateWithLifecycle()
    val recentImages by viewModel.recentImages.collectAsStateWithLifecycle()
    val recentVideos by viewModel.recentVideos.collectAsStateWithLifecycle()
    val moveRecords by viewModel.moveRecords.collectAsStateWithLifecycle()
    val recordUndoableMap by viewModel.recordUndoableMap.collectAsStateWithLifecycle()

    var activeTab by remember { mutableIntStateOf(0) }  // 0=最近浏览, 1=最近新增, 2=最近移动
    var activeFilter by remember { mutableStateOf(RecentFilter.ALBUM) }

    // ── Overlay 状态 ──
    var selectedAlbum by remember { mutableStateOf<Album?>(null) }
    var selectedMediaIndex by remember { mutableIntStateOf(-1) }
    // ── 撤销确认对话框 ──
    var confirmUndoRecord by remember { mutableStateOf<MoveRecord?>(null) }
    var undoingIds by remember { mutableStateOf(setOf<String>()) }
    // 拍平列表供 PreviewScreen 快照
    val flatItems = remember(activeFilter, recentImages, recentVideos) {
        when (activeFilter) {
            RecentFilter.IMAGE -> recentImages
            RecentFilter.VIDEO -> recentVideos
            else -> emptyList()
        }
    }
    val indexMap = remember(flatItems) {
        flatItems.withIndex().associate { (idx, item) -> item to idx }
    }

    if (selectedAlbum != null) BackHandler { selectedAlbum = null; onOverlayChanged(false) }
    if (selectedMediaIndex >= 0) BackHandler { selectedMediaIndex = -1; onOverlayChanged(false) }

    // 通知 MainActivity 隐藏/显示底部导航栏
    LaunchedEffect(selectedAlbum, selectedMediaIndex) {
        onOverlayChanged(selectedAlbum != null || selectedMediaIndex >= 0)
    }

    // 进入页面时刷新数据
    LaunchedEffect(Unit) {
        viewModel.loadRecentAlbums()
        viewModel.loadRecentImages()
        viewModel.loadRecentVideos()
    }

    Column(Modifier.fillMaxSize()) {
        // ═══ ① 顶部 Tab 切换 [最近浏览] [最近新增] ═══
        Surface(tonalElevation = 2.dp, shadowElevation = 4.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val tabs = listOf("最近浏览", "最近新增", "最近移动")
                tabs.forEachIndexed { idx, label ->
                    val selected = activeTab == idx
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface,
                        tonalElevation = if (selected) 0.dp else 3.dp,
                        onClick = { activeTab = idx }
                    ) {
                        Text(
                            text = label,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        // ═══ ② 筛选器 chips [相册] [图片] [视频]（仅最近浏览显示）═══
        if (activeTab != 2) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                RECENT_FILTERS.forEach { type ->
                    val selected = activeFilter == type
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface,
                        tonalElevation = if (selected) 0.dp else 3.dp,
                        onClick = { activeFilter = type }
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
            }
        }

        // ═══ ③ 内容区域 ═══
        // 当 overlay 显示时隐藏滚动内容，防止 Compose 手势拦截 VideoPlayer seek 拖拽
        val showContentOverlay = selectedAlbum != null || selectedMediaIndex >= 0
        Box(Modifier.fillMaxSize().weight(1f)) {
            if (showContentOverlay) {
                // 占位，不渲染任何可滚动手势区域
            } else if (activeTab == 1) {
                // "最近新增"暂不实现
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("即将推出", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
                return@Box
            } else if (activeTab == 2) {
                MoveRecordList(
                    records = moveRecords,
                    undoableMap = recordUndoableMap,
                    undoingIds = undoingIds,
                    onUndo = { record ->
                        if (record.id in undoingIds) return@MoveRecordList
                        confirmUndoRecord = record
                    }
                )
                return@Box
            }

            when (activeFilter) {
                RecentFilter.ALBUM -> {
                    if (recentAlbums.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("暂无浏览记录", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                            contentPadding = PaddingValues(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            gridItems(recentAlbums, key = { it.bucketId }) { album ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    tonalElevation = 2.dp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clickable { selectedAlbum = album }
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 4.dp, vertical = 6.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text("📁", fontSize = 20.sp)
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            album.bucketName,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "${album.count} 项",
                                            fontSize = 9.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                RecentFilter.IMAGE -> {
                    if (recentImages.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("暂无浏览记录", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(GRID_COLUMNS),
                            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                            contentPadding = PaddingValues(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            gridItems(recentImages, key = { it.id }) { item ->
                                val idx = indexMap[item] ?: 0
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { selectedMediaIndex = idx }
                                ) {
                                    GalleryThumbnail(
                                        uri = item.uri,
                                        contentDescription = item.fileName,
                                        targetSize = 200
                                    )
                                }
                            }
                        }
                    }
                }

                RecentFilter.VIDEO -> {
                    if (recentVideos.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("暂无浏览记录", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                            contentPadding = PaddingValues(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(recentVideos, key = { it.id }) { item ->
                                val idx = indexMap[item] ?: 0
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    tonalElevation = 1.dp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedMediaIndex = idx }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("📹", fontSize = 18.sp)
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                item.fileName,
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                formatVideoDuration(item.duration),
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ═══ 撤销确认对话框 ═══
    confirmUndoRecord?.let { record ->
        val actionLabel = if (record.mode == PasteMode.MOVE.name) "移回" else "删除"
        AlertDialog(
            onDismissRequest = { confirmUndoRecord = null },
            title = { Text("撤销操作") },
            text = {
                val recordType = if (record.mode == PasteMode.MOVE.name) "移动" else "复制"
                Text("确定要${actionLabel} ${record.successCount}/${record.totalCount} 个文件吗？\n这将撤销此次${recordType}操作。")
            },
            confirmButton = {
                Button(onClick = {
                    val id = record.id
                    confirmUndoRecord = null
                    undoingIds = undoingIds + id
                    viewModel.undoMoveRecord(record)
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { confirmUndoRecord = null }) { Text("取消") }
            }
        )
    }

    // ═══ ④ Overlays ═══
    // 相册 overlay
    val album = selectedAlbum
    if (album != null) {
        MediaGridScreen(
            albumId = album.bucketId,
            albumName = album.bucketName,
            onBackClick = { selectedAlbum = null },
            onGoHome = { selectedAlbum = null }
        )
    }

    // 媒体 overlay
    val mediaIdx = selectedMediaIndex
    if (mediaIdx >= 0) {
        PreviewScreen(
            initialIndex = mediaIdx,
            onBackClick = { selectedMediaIndex = -1 },
            onGoHome = { selectedMediaIndex = -1 },
            items = flatItems
        )
    }
}

// ═══════════════════════════════════════════════
// 辅助函数
// ═══════════════════════════════════════════════

/** 格式化视频时长（毫秒 → MM:SS） */
private fun formatVideoDuration(durationMs: Long): String {
    if (durationMs <= 0) return "00:00"
    val totalSec = durationMs / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}

// ═══════════════════════════════════════════════
//  最近移动记录列表
// ═══════════════════════════════════════════════

@Composable
private fun MoveRecordList(
    records: List<MoveRecord>,
    undoableMap: Map<String, Boolean>,
    undoingIds: Set<String>,
    onUndo: (MoveRecord) -> Unit,
    modifier: Modifier = Modifier
) {
    if (records.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无移动记录", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        }
        return
    }

    val expandedIds = remember { mutableStateMapOf<String, Boolean>() }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(records, key = { it.id }) { record ->
            MoveRecordCard(
                record = record,
                isUndoable = undoableMap[record.id] ?: false,
                isExpanded = expandedIds[record.id] ?: false,
                isUndoing = record.id in undoingIds,
                onToggle = { expandedIds[record.id] = !(expandedIds[record.id] ?: false) },
                onUndo = { onUndo(record) }
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/** 格式化文件大小 */
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
        else -> "${"%.2f".format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
    }
}

/** 相对时间文字 */
private fun timeAgoText(timestamp: Long): String {
    val minutes = ((System.currentTimeMillis() - timestamp) / 60000).toInt()
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes}分钟前"
        minutes < 1440 -> "${minutes / 60}小时前"
        else -> "${minutes / 1440}天前"
    }
}

/** 格式化为完整日期时间 */
private fun formatDateTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

/** 单条移动/复制记录卡片 */
@Composable
private fun MoveRecordCard(
    record: MoveRecord,
    isUndoable: Boolean,
    isExpanded: Boolean,
    isUndoing: Boolean,
    onToggle: () -> Unit,
    onUndo: () -> Unit
) {
    val isMoved = record.mode == PasteMode.MOVE.name
    val isUndoRecord = record.isUndoRecord
    val context = LocalContext.current

    Surface(
        shape = RoundedCornerShape(10.dp),
        tonalElevation = if (isUndoRecord) 0.dp else 1.dp,
        color = if (isUndoRecord) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.surface
    ) {
        Column {
            // ── Header ──
            Surface(
                shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
                tonalElevation = if (isExpanded) 0.dp else 1.dp,
                modifier = Modifier.fillMaxWidth().clickable { onToggle() },
                color = Color.Transparent
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 操作标签
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (isUndoRecord) Color.Gray
                                else if (isMoved) Color(0xCCFF9800)
                                else Color(0xFF4CAF50)
                    ) {
                        Text(
                            text = if (isUndoRecord) "撤销" else if (isMoved) "MOVE" else "COPY",
                            fontSize = 9.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    // 源 → 目标 路径
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = record.sourceAlbumName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Text(
                                text = " → ",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = record.targetAlbumName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                        Text(
                            text = "${timeAgoText(record.timestamp)} · ${record.successCount}/${record.totalCount} 文件",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // 撤销按钮
                    if (!isUndoRecord) {
                        Button(
                            onClick = onUndo,
                            enabled = isUndoable && !isUndoing,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isMoved) Color(0xCCFF9800) else Color(0xFF4CAF50),
                                disabledContainerColor = Color.Gray.copy(alpha = 0.3f)
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(
                                if (isUndoing) "..." else "撤销",
                                fontSize = 11.sp,
                                color = if (isUndoable && !isUndoing) Color.White
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = if (isExpanded) "▲" else "▼",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── 展开详情 ──
            if (isExpanded) {
                Surface(
                    shape = RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp),
                    tonalElevation = 0.dp,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        // 来源 → 目标
                        Row {
                            Text(
                                text = "来源: ${record.sourceAlbumName}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row {
                            Text(
                                text = "目标: ${record.targetAlbumName}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        // 成功数
                        val stateColor = if (record.successCount == record.totalCount) Color(0xFF4CAF50) else Color(0xCCFF9800)
                        Text(
                            text = "成功 ${record.successCount}/${record.totalCount} 个文件",
                            fontSize = 10.sp,
                            color = stateColor
                        )
                        // 时间
                        Text(
                            text = formatDateTime(record.timestamp),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))

                        // 分隔线
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        Spacer(Modifier.height(4.dp))

                        // 分离图片和视频
                        val imageFiles = record.files.filter { !it.mimeType.startsWith("video") }
                        val videoFiles = record.files.filter { it.mimeType.startsWith("video") }

                        // ── 图片缩略图网格 ──
                        if (imageFiles.isNotEmpty()) {
                            Text(
                                text = "图片（${imageFiles.size}）",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            val imageRows = imageFiles.chunked(4)
                            imageRows.forEach { row ->
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    row.forEach { entry ->
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .aspectRatio(1f)
                                                .padding(2.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                        ) {
                                            // 优先读缓存缩略图，miss 回退到 targetPath
                                            val thumbFile = File(context.cacheDir, "move_thumbnails/${record.id}/${entry.fileName}")
                                            val thumbUri = remember(entry, record.id) {
                                                if (thumbFile.exists()) Uri.fromFile(thumbFile)
                                                else Uri.fromFile(File(entry.targetPath))
                                            }
                                            GalleryThumbnail(
                                                uri = thumbUri,
                                                contentDescription = entry.fileName,
                                                targetSize = 120
                                            )
                                        }
                                    }
                                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }

                        // ── 视频列表 ──
                        if (videoFiles.isNotEmpty()) {
                            Text(
                                text = "视频（${videoFiles.size}）",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            videoFiles.forEach { entry ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("📹", fontSize = 12.sp)
                                    Spacer(Modifier.width(6.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = entry.fileName,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Text(
                                        text = formatFileSize(entry.size),
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
