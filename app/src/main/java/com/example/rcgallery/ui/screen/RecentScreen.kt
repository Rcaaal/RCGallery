package com.example.rcgallery.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

/** 筛选类型 */
private enum class RecentFilter(val label: String) {
    ALBUM("相册"), IMAGE("图片"), VIDEO("视频")
}

private val RECENT_FILTERS = RecentFilter.entries.toList()
private const val GRID_COLUMNS = 4

/**
 * 最近浏览页面——查看最近查看过的相册/图片/视频。
 * 顶部 [最近浏览] [最近新增] 切换（最近新增暂不实现）。
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

    var activeTab by remember { mutableIntStateOf(0) }  // 0=最近浏览, 1=最近新增
    var activeFilter by remember { mutableStateOf(RecentFilter.ALBUM) }

    // ── Overlay 状态 ──
    var selectedAlbum by remember { mutableStateOf<Album?>(null) }
    var selectedMediaIndex by remember { mutableIntStateOf(-1) }
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
                val tabs = listOf("最近浏览", "最近新增")
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

        // ═══ ② 筛选器 chips [相册] [图片] [视频] ═══
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

        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))

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
