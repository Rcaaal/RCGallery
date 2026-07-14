package com.example.rcgallery.ui.screen

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rcgallery.data.db.TagEntity
import com.example.rcgallery.model.Album
import com.example.rcgallery.model.MediaItem
import com.example.rcgallery.ui.component.AlbumPickDialog
import com.example.rcgallery.ui.component.FloatingMultiSelectButtons
import com.example.rcgallery.ui.component.GalleryThumbnail
import com.example.rcgallery.ui.component.TagManageDialog
import com.example.rcgallery.viewmodel.GalleryViewModel
import com.example.rcgallery.viewmodel.PasteMode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** 筛选类型 */
private enum class FilterType(val label: String) {
    ALBUM("相册"), IMAGE("图片"), VIDEO("视频")
}

private val FILTER_TYPES = FilterType.entries.toList()

/**
 * TAG 搜索浏览页面——搜索/选中 TAG → AND 匹配结果展示，支持相册/图片/视频筛选。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TagListScreen(
    viewModel: GalleryViewModel,
    onBackClick: () -> Unit = {},
    onOverlayChanged: (Boolean) -> Unit = {}
) {
    val allTags by viewModel.allTags.collectAsState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // ── 搜索与 TAG 选择状态 ──
    var searchQuery by remember { mutableStateOf("") }
    val selectedTags = remember { mutableStateListOf<TagEntity>() }
    val sortedTags = remember(allTags) { allTags.sortedBy { it.name } }

    // ── 自动补全候选 ──
    val suggestions = remember(searchQuery, allTags, selectedTags) {
        if (searchQuery.isBlank()) emptyList()
        else {
            val selectedIds = selectedTags.map { it.id }.toSet()
            allTags.filter { it.name.contains(searchQuery, ignoreCase = true) && it.id !in selectedIds }
        }
    }

    var showSuggestions by remember { mutableStateOf(false) }

    // ── TAG 选择区展开 ──
    var isTagsExpanded by remember { mutableStateOf(false) }

    // ── 删除模式 ──
    var isDeleteMode by remember { mutableStateOf(false) }

    // ── 筛选类型 ──
    var activeFilter by remember { mutableStateOf(FilterType.ALBUM) }

    // ── 多选状态 ──
    var isTagMediaMultiSelect by remember { mutableStateOf(false) }
    var tagSelectedMediaUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showTagBatchTagDialog by remember { mutableStateOf(false) }
    var showTagPickDialog by remember { mutableStateOf(false) }
    var tagPendingPickItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    fun exitTagMediaMultiSelect() {
        isTagMediaMultiSelect = false
        tagSelectedMediaUris = emptySet()
        showTagBatchTagDialog = false
        showTagPickDialog = false
        tagPendingPickItems = emptyList()
    }
    fun toggleTagMediaSelection(item: MediaItem) {
        val uri = item.uri.toString()
        tagSelectedMediaUris = if (uri in tagSelectedMediaUris) tagSelectedMediaUris - uri
                               else tagSelectedMediaUris + uri
    }

    // ── 搜索结果 ──
    var searchAlbums by remember { mutableStateOf<List<Album>>(emptyList()) }
    val searchMedia by viewModel.tagSearchResults.collectAsState()
    val allAlbums by viewModel.albums.collectAsStateWithLifecycle()
    val recentMoveAlbums by viewModel.recentMoveAlbums.collectAsStateWithLifecycle()
    var isSearching by remember { mutableStateOf(false) }
    var tagSearchRefreshVersion by remember { mutableIntStateOf(0) }
    // ── 媒体删除后自动刷新：overlay 关闭重新搜索 ──
    var hasSearchRun by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 选中 TAG 变化时自动搜索
    LaunchedEffect(selectedTags.toList(), tagSearchRefreshVersion) {
        val tags = selectedTags.toList()
        if (tags.isEmpty()) {
            searchAlbums = emptyList()
            viewModel.searchMediaByTags(emptyList())
            return@LaunchedEffect
        }
        isSearching = true
        val tagNames = tags.map { it.name }
        searchAlbums = viewModel.getAlbumsByTagNames(tagNames)
        viewModel.searchMediaByTags(tagNames)
        isSearching = false
        hasSearchRun = true
    }

    // ── Overlay 状态 ──
    var selectedAlbum by remember { mutableStateOf<Album?>(null) }
    var selectedMediaIndex by remember { mutableIntStateOf(-1) }
    if (selectedAlbum != null) {
        BackHandler { selectedAlbum = null }
    }
    if (selectedMediaIndex >= 0) {
        BackHandler { selectedMediaIndex = -1 }
    }
    if (isTagMediaMultiSelect && selectedAlbum == null && selectedMediaIndex < 0) {
        BackHandler { exitTagMediaMultiSelect() }
    }

    // 通知父层显示/隐藏底部导航栏
    LaunchedEffect(selectedAlbum, selectedMediaIndex) {
        onOverlayChanged(selectedAlbum != null || selectedMediaIndex >= 0)
    }

    // ── 媒体删除后自动刷新：overlay 关闭重新搜索 ──
    LaunchedEffect(selectedMediaIndex) {
        if (hasSearchRun && selectedMediaIndex < 0) {
            viewModel.refreshTagSearch()
        }
        if (selectedMediaIndex >= 0) hasSearchRun = true
    }

    // ── 辅助函数 ──
    fun isTagSelected(tag: TagEntity): Boolean = tag.id in selectedTags.map { it.id }.toSet()

    fun toggleTag(tag: TagEntity) {
        if (isTagSelected(tag)) {
            selectedTags.removeAll { it.id == tag.id }
        } else {
            selectedTags.add(tag)
        }
        searchQuery = ""
        showSuggestions = false
    }

    // ── 布局 ──
    // 根据当前筛选类型预先过滤出拍平列表（同时用于分组和预览 overlay 的索引）
    val flatFilteredMedia = remember(activeFilter, searchMedia) {
        when (activeFilter) {
            FilterType.IMAGE -> searchMedia.filter { it.isImage && !it.isGif }
            FilterType.VIDEO -> searchMedia.filter { it.isVideo }
            else -> searchMedia
        }
    }
    // Use the same stable bucket identity as the local album screen. MediaStore can
    // expose differently-cased display names for records that belong to one bucket.
    val albumNamesById = remember(allAlbums) { allAlbums.associate { it.bucketId to it.bucketName } }
    val mediaGroups = remember(flatFilteredMedia, albumNamesById) {
        flatFilteredMedia
            .groupBy { it.tagAlbumGroupKey() }
            .map { (groupKey, items) ->
                TagMediaGroup(
                    key = groupKey,
                    displayName = items.firstNotNullOfOrNull { item ->
                        item.albumId?.let(albumNamesById::get)
                    } ?: items.firstNotNullOfOrNull { it.albumName } ?: "未分类",
                    items = items
                )
            }
    }
    // 每个分组的折叠状态
    val collapsedGroups = remember { mutableStateMapOf<String, Boolean>() }
    val albumGridState = rememberLazyGridState()
    val imageListState = rememberLazyListState()
    val videoListState = rememberLazyListState()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ═══ 多选模式：顶部工具栏 ═══
            if (isTagMediaMultiSelect) {
                Surface(
                    tonalElevation = 2.dp,
                    shadowElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { exitTagMediaMultiSelect() }) {
                            Text("取消")
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "已选 ${tagSelectedMediaUris.size} 项",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.weight(1f))
                        val allSelected = flatFilteredMedia.isNotEmpty() &&
                            flatFilteredMedia.all { it.uri.toString() in tagSelectedMediaUris }
                        TextButton(onClick = {
                            if (allSelected) {
                                tagSelectedMediaUris = emptySet()
                            } else {
                                tagSelectedMediaUris = flatFilteredMedia.map { it.uri.toString() }.toSet()
                            }
                        }) {
                            Text(if (allSelected) "取消全选" else "全选", fontSize = 13.sp)
                        }
                    }
                }
            }

            // ═══ ① 搜索栏 ═══
            if (!isTagMediaMultiSelect) {
            Surface(
                tonalElevation = 2.dp,
                shadowElevation = 4.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
                    // 搜索输入框
                    Box {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                showSuggestions = it.isNotBlank()
                            },
                            placeholder = { Text("输入 TAG 名称...", fontSize = 14.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            trailingIcon = {
                                if (searchQuery.isNotBlank()) {
                                    TextButton(
                                        onClick = {
                                            searchQuery = ""
                                            showSuggestions = false
                                        }
                                    ) { Text("清除", fontSize = 12.sp) }
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            )
                        )

                        // 自动补全下拉
                        DropdownMenu(
                            expanded = showSuggestions && suggestions.isNotEmpty(),
                            onDismissRequest = { showSuggestions = false },
                            modifier = Modifier.fillMaxWidth(0.9f)
                        ) {
                            suggestions.forEach { tag ->
                                DropdownMenuItem(
                                    text = { Text(tag.name, fontSize = 14.sp) },
                                    onClick = {
                                        toggleTag(tag)
                                        showSuggestions = false
                                    }
                                )
                            }
                        }
                    }

                    // 已选 TAG chips（蓝色）
                    if (selectedTags.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            selectedTags.forEach { tag ->
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = Color(0xFF448AFF),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .align(Alignment.CenterVertically)
                                            .padding(start = 10.dp, end = 6.dp)
                                    ) {
                                        Text(tag.name, fontSize = 12.sp, color = Color.White, maxLines = 1)
                                        Spacer(Modifier.width(4.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(16.dp)
                                                .clickable { toggleTag(tag) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "删除标签",
                                                modifier = Modifier.size(14.dp),
                                                tint = Color.White
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

            // ═══ ② TAG 选择区（1/4 区域，椭圆 chips） ═══
            if (!isTagMediaMultiSelect && sortedTags.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    // 展开/收缩按钮
                    val displayCount = sortedTags.size
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { isTagsExpanded = !isTagsExpanded },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = if (isTagsExpanded) "▲ 收起" else "▼ 全部标签 ($displayCount)",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        // 删除模式按钮
                        TextButton(
                            onClick = { isDeleteMode = !isDeleteMode },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = if (isDeleteMode) "✓ 完成" else "✕ 删除标签",
                                fontSize = 12.sp,
                                color = if (isDeleteMode) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // TAG chips — FlowRow 自动换行，最多 3 行
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        maxLines = if (isTagsExpanded) Int.MAX_VALUE else 3
                    ) {
                        sortedTags.forEach { tag ->
                            val selected = isTagSelected(tag)
                            val bgColor = if (selected) Color(0xFF448AFF) else Color(0xFF8E8E8E)
                            if (isDeleteMode) {
                                // 删除模式：chip 带 ✕ 按钮
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = Color(0xFFE53935).copy(alpha = 0.85f),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .padding(start = 10.dp, end = 4.dp)
                                    ) {
                                        Text(tag.name, fontSize = 12.sp, color = Color.White, maxLines = 1)
                                        Spacer(Modifier.width(4.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(16.dp)
                                                .clickable { viewModel.deleteTag(tag.id) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "删除标签",
                                                modifier = Modifier.size(14.dp),
                                                tint = Color.White
                                            )
                                        }
                                    }
                                }
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = bgColor.copy(alpha = 0.8f),
                                    modifier = Modifier
                                        .height(28.dp)
                                        .clickable { toggleTag(tag) }
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxHeight()
                                            .padding(horizontal = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            tag.name,
                                            fontSize = 12.sp,
                                            color = Color.White,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ═══ ③ 筛选器 chips ═══
            if (!isTagMediaMultiSelect) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FILTER_TYPES.forEach { type ->
                    val selected = activeFilter == type
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface,
                        tonalElevation = if (selected) 0.dp else 3.dp,
                        shadowElevation = if (selected) 0.dp else 4.dp,
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
                if (selectedTags.isNotEmpty()) {
                    Spacer(Modifier.weight(1f))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.error,
                        tonalElevation = 3.dp,
                        shadowElevation = 4.dp,
                        onClick = {
                            selectedTags.clear()
                            searchAlbums = emptyList()
                            viewModel.searchMediaByTags(emptyList())
                        }
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

            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
            }

            // ═══ ④ 结果区域（3/4 剩余空间） ═══
            // 当 overlay 显示时隐藏滚动内容，防止 Compose 手势拦截 VideoPlayer seek 拖拽
            val showContentOverlay = selectedAlbum != null || selectedMediaIndex >= 0
            Box(Modifier.fillMaxSize().weight(1f)) {
                if (showContentOverlay) {
                    // 占位，不渲染任何可滚动手势区域
                } else {
                when {
                    isSearching -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                    selectedTags.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("请选择或搜索 TAG", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        }
                    }
                    // ─── 相册筛选：4列网格 ───
                    activeFilter == FilterType.ALBUM -> {
                        if (searchAlbums.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("无匹配相册", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                            }
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(4),
                                state = albumGridState,
                                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                                contentPadding = PaddingValues(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                gridItems(searchAlbums, key = { it.bucketId }) { album ->
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        tonalElevation = 2.dp,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .clickable {
                                                keyboardController?.hide()
                                                focusManager.clearFocus()
                                                showSuggestions = false
                                                viewModel.recordAlbumView(album.bucketId, album.bucketName, album.directoryPath)
                                                selectedAlbum = album
                                            }
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
                    // ─── 图片/视频筛选：分组卡片 + 4列缩略图网格 + 折叠 ───
                    activeFilter == FilterType.IMAGE -> {
                        if (flatFilteredMedia.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("无匹配图片", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                            }
                        } else {
                            GroupedMediaList(
                                groups = mediaGroups,
                                collapsedGroups = collapsedGroups,
                                lazyListState = imageListState,
                                onMediaClick = { item ->
                                    val idx = flatFilteredMedia.indexOf(item)
                                    if (idx >= 0) {
                                        keyboardController?.hide()
                                        focusManager.clearFocus()
                                        showSuggestions = false
                                        selectedMediaIndex = idx
                                    }
                                },
                                isMultiSelectMode = isTagMediaMultiSelect,
                                selectedUris = tagSelectedMediaUris,
                                onMediaLongClick = { item ->
                                    if (!isTagMediaMultiSelect) {
                                        isTagMediaMultiSelect = true
                                        tagSelectedMediaUris = setOf(item.uri.toString())
                                    }
                                },
                                onMediaTapInMultiSelect = { item ->
                                    toggleTagMediaSelection(item)
                                }
                            )
                        }
                    }
                    activeFilter == FilterType.VIDEO -> {
                        if (flatFilteredMedia.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("无匹配视频", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                            }
                        } else {
                            GroupedMediaList(
                                groups = mediaGroups,
                                collapsedGroups = collapsedGroups,
                                lazyListState = videoListState,
                                onMediaClick = { item ->
                                    val idx = flatFilteredMedia.indexOf(item)
                                    if (idx >= 0) {
                                        keyboardController?.hide()
                                        focusManager.clearFocus()
                                        showSuggestions = false
                                        selectedMediaIndex = idx
                                    }
                                },
                                isMultiSelectMode = isTagMediaMultiSelect,
                                selectedUris = tagSelectedMediaUris,
                                onMediaLongClick = { item ->
                                    if (!isTagMediaMultiSelect) {
                                        isTagMediaMultiSelect = true
                                        tagSelectedMediaUris = setOf(item.uri.toString())
                                    }
                                },
                                onMediaTapInMultiSelect = { item ->
                                    toggleTagMediaSelection(item)
                                }
                            )
                        }
                    }
                }
                }
            }
        }

        // ── 多选浮动按钮 ──
        if (isTagMediaMultiSelect && tagSelectedMediaUris.isNotEmpty()) {
            FloatingMultiSelectButtons(
                selectedCount = tagSelectedMediaUris.size,
                onBatchTag = { showTagBatchTagDialog = true },
                onDeleteToTrash = {
                    val toDelete = flatFilteredMedia.filter { it.uri.toString() in tagSelectedMediaUris }
                    viewModel.moveToTrash(toDelete)
                    exitTagMediaMultiSelect()
                },
                onAddToClipboard = {
                    val items = flatFilteredMedia.filter { it.uri.toString() in tagSelectedMediaUris }
                    viewModel.addToClipboard(items)
                    exitTagMediaMultiSelect()
                },
                onPickTargetAlbum = {
                    tagPendingPickItems = flatFilteredMedia.filter { it.uri.toString() in tagSelectedMediaUris }
                    // Close the selection UI without clearing the items needed by the picker.
                    isTagMediaMultiSelect = false
                    tagSelectedMediaUris = emptySet()
                    showTagBatchTagDialog = false
                    showTagPickDialog = true
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 16.dp)
            )
        }

        // ═══ ⑤ Overlays ═══
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
                items = flatFilteredMedia
            )
        }

        // ── 批量 TAG 对话框 ──
        if (showTagBatchTagDialog) {
            val batchMediaPaths = flatFilteredMedia
                .filter { it.uri.toString() in tagSelectedMediaUris }
                .map { it.filePath }
            TagManageDialog(
                title = "批量加标签 - 已选 ${batchMediaPaths.size} 个文件",
                existingTags = emptyList(),
                allTags = allTags,
                onAddTag = { tagName ->
                    batchMediaPaths.forEach { filePath ->
                        viewModel.addMediaTag(filePath, tagName)
                    }
                    showTagBatchTagDialog = false
                    exitTagMediaMultiSelect()
                },
                onRemoveTag = { _ -> },
                onDismiss = { showTagBatchTagDialog = false }
            )
        }

        // ── 选择目标相册对话框 ──
        if (showTagPickDialog) {
            AlbumPickDialog(
                albums = allAlbums,
                recentMoveAlbums = recentMoveAlbums,
                onAlbumSelected = { targetDir, targetName, mode ->
                    val items = tagPendingPickItems.toList()
                    tagPendingPickItems = emptyList()
                    showTagPickDialog = false
                    if (items.isNotEmpty()) {
                        val operation = if (mode == PasteMode.MOVE) {
                            viewModel.moveItemsToAlbum(items, targetDir, targetName, null)
                        } else {
                            viewModel.copyItemsToAlbum(items, targetDir, targetName, null)
                        }
                        scope.launch {
                            operation.join()
                            tagSearchRefreshVersion++
                        }
                    }
                },
                onCreateFolder = { name, onResult ->
                    scope.launch {
                        val path = withContext(Dispatchers.IO) {
                            val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                            val dir = File(dcim, name)
                            if ((dir.isDirectory || dir.mkdirs()) && dir.isDirectory) {
                                dir.absolutePath
                            } else null
                        }
                        if (path != null) viewModel.loadAlbums()
                        onResult(path)
                    }
                },
                onDismiss = {
                    showTagPickDialog = false
                    tagPendingPickItems = emptyList()
                }
            )
        }
    }
}

// ═══════════════════════════════════════════════
// 分组媒体列表（相册名 header + 每行独立懒加载）
// ═══════════════════════════════════════════════
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupedMediaList(
    groups: List<TagMediaGroup>,
    collapsedGroups: MutableMap<String, Boolean>,
    lazyListState: LazyListState,
    onMediaClick: (MediaItem) -> Unit,
    isMultiSelectMode: Boolean = false,
    selectedUris: Set<String> = emptySet(),
    onMediaLongClick: ((MediaItem) -> Unit)? = null,
    onMediaTapInMultiSelect: ((MediaItem) -> Unit)? = null
) {
    val collapsedSnapshot = groups.associate { it.key to (collapsedGroups[it.key] ?: false) }
    val sections = remember(groups, collapsedSnapshot) {
        groups.map { group ->
            GroupedMediaSection(
                groupKey = group.key,
                albumName = group.displayName,
                items = group.items,
                isCollapsed = collapsedSnapshot[group.key] ?: false,
                rows = if (collapsedSnapshot[group.key] == true) emptyList()
                else group.items.chunked(GRID_COLUMNS)
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
        sections.forEach { section ->
            val groupKey = section.groupKey
            val albumName = section.albumName
            val items = section.items
            val isCollapsed = section.isCollapsed
            val rows = section.rows

            // ── 相册名 header（顶部圆角，点击展开/折叠） ──
            item(key = "h_$groupKey", contentType = "tag_group_header") {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    tonalElevation = 1.dp,
                    shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { collapsedGroups[groupKey] = !isCollapsed }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isCollapsed) "▶" else "▼",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            albumName,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${items.size} 项",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── 每行 4 个缩略图（独立 lazy item，仅渲染可见行） ──
            rows.forEachIndexed { rowIdx, row ->
                val isLastRow = rowIdx == rows.lastIndex
                item(key = "r_${groupKey}_$rowIdx", contentType = "tag_media_row") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.surface,
                                shape = if (isLastRow)
                                    RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp)
                                else
                                    RoundedCornerShape(0.dp)
                            )
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                            row.forEach { item ->
                                val isSelected = item.uri.toString() in selectedUris
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .padding(2.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (isSelected) Color(0xFF448AFF).copy(alpha = 0.15f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                        )
                                        .then(
                                            if (isMultiSelectMode) {
                                                Modifier.combinedClickable(
                                                    onClick = { onMediaTapInMultiSelect?.invoke(item) },
                                                    onLongClick = {}  // 多选模式不处理长按
                                                )
                                            } else {
                                                Modifier.combinedClickable(
                                                    onClick = { onMediaClick(item) },
                                                    onLongClick = { onMediaLongClick?.invoke(item) }
                                                )
                                            }
                                        )
                                ) {
                                    Box(modifier = Modifier.matchParentSize()) {
                                        GalleryThumbnail(
                                            uri = item.uri,
                                            contentDescription = item.fileName
                                        )
                                        if (isSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .matchParentSize()
                                                    .background(Color.Black.copy(alpha = 0.25f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "已选",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(28.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            // 填充最后一行空位
                            repeat(GRID_COLUMNS - row.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        // 底部留白
        item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

private data class TagMediaGroup(
    val key: String,
    val displayName: String,
    val items: List<MediaItem>
)

private data class GroupedMediaSection(
    val groupKey: String,
    val albumName: String,
    val items: List<MediaItem>,
    val isCollapsed: Boolean,
    val rows: List<List<MediaItem>>
)

private fun MediaItem.tagAlbumGroupKey(): String {
    albumId?.takeIf { it.isNotBlank() }?.let { return "bucket:$it" }

    val directory = filePath
        .substringBeforeLast('/', missingDelimiterValue = "")
        .replace("/sdcard/", "/storage/emulated/0/", ignoreCase = true)
        .trimEnd('/')
    if (directory.isNotBlank()) return "path:${directory.lowercase(Locale.ROOT)}"

    return albumName?.takeIf { it.isNotBlank() }
        ?.let { "name:${it.lowercase(Locale.ROOT)}" }
        ?: "media:${uri}"
}

/** 网格列数 */
private const val GRID_COLUMNS = 4
