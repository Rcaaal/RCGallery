package com.example.rcgallery.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rcgallery.data.db.TagEntity
import com.example.rcgallery.model.Album
import com.example.rcgallery.model.MediaItem
import com.example.rcgallery.viewmodel.GalleryViewModel

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

    // ── 搜索结果 ──
    var searchAlbums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var searchMedia by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 选中 TAG 变化时自动搜索
    LaunchedEffect(selectedTags.toList()) {
        val tags = selectedTags.toList()
        if (tags.isEmpty()) {
            searchAlbums = emptyList()
            searchMedia = emptyList()
            return@LaunchedEffect
        }
        isSearching = true
        val tagNames = tags.map { it.name }
        searchAlbums = viewModel.getAlbumsByTagNames(tagNames)
        searchMedia = viewModel.getMediaByTagNames(tagNames)
        isSearching = false
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

    // 通知父层显示/隐藏底部导航栏
    LaunchedEffect(selectedAlbum, selectedMediaIndex) {
        onOverlayChanged(selectedAlbum != null || selectedMediaIndex >= 0)
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
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ═══ ① 搜索栏 ═══
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
                                        modifier = Modifier.padding(start = 10.dp, end = 6.dp)
                                    ) {
                                        Text(tag.name, fontSize = 12.sp, color = Color.White, maxLines = 1)
                                        Spacer(Modifier.width(4.dp))
                                        TextButton(
                                            onClick = { toggleTag(tag) },
                                            modifier = Modifier.size(18.dp).padding(0.dp),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text("✕", fontSize = 10.sp, color = Color.White)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ═══ ② TAG 选择区（1/4 区域，椭圆 chips） ═══
            if (sortedTags.isNotEmpty()) {
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
                                        modifier = Modifier.padding(start = 10.dp, end = 4.dp)
                                    ) {
                                        Text(tag.name, fontSize = 12.sp, color = Color.White, maxLines = 1)
                                        Spacer(Modifier.width(4.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(16.dp)
                                                .clickable { viewModel.deleteTag(tag.id) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("✕", fontSize = 10.sp, color = Color.White)
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
                                    Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
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
                            searchMedia = emptyList()
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

            // ═══ ④ 结果区域（3/4 剩余空间） ═══
            Box(Modifier.fillMaxSize().weight(1f)) {
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
                    activeFilter == FilterType.ALBUM -> {
                        if (searchAlbums.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("无匹配相册", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                                items(searchAlbums, key = { it.bucketId }) { album ->
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp)
                                            .clickable { selectedAlbum = album }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("📁", fontSize = 16.sp)
                                            Spacer(Modifier.width(8.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(album.bucketName, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                                                Text("${album.count} 项", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    activeFilter == FilterType.IMAGE -> {
                        val mediaResults = searchMedia.filter { it.isImage && !it.isGif }
                        if (mediaResults.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("无匹配图片", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                                items(mediaResults, key = { it.id }) { item ->
                                    val idx = mediaResults.indexOf(item)
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp)
                                            .clickable { selectedMediaIndex = idx }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("🖼️", fontSize = 16.sp)
                                            Spacer(Modifier.width(8.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(item.fileName, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                                                Text(item.albumName ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    activeFilter == FilterType.VIDEO -> {
                        val videoResults = searchMedia.filter { it.isVideo }
                        if (videoResults.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("无匹配视频", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                                items(videoResults, key = { it.id }) { item ->
                                    val idx = videoResults.indexOf(item)
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp)
                                            .clickable { selectedMediaIndex = idx }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("🎬", fontSize = 16.sp)
                                            Spacer(Modifier.width(8.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(item.fileName, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                                                Text(item.albumName ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            // 根据当前筛选类型决定传入的 items 列表
            val previewItems = remember(activeFilter, searchMedia) {
                when (activeFilter) {
                    FilterType.IMAGE -> searchMedia.filter { it.isImage && !it.isGif }
                    FilterType.VIDEO -> searchMedia.filter { it.isVideo }
                    else -> searchMedia
                }
            }
            PreviewScreen(
                initialIndex = mediaIdx,
                onBackClick = { selectedMediaIndex = -1 },
                onGoHome = { selectedMediaIndex = -1 },
                items = previewItems
            )
        }
    }
}
