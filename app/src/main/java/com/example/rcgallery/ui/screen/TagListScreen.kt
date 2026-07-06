package com.example.rcgallery.ui.screen

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rcgallery.data.db.TagEntity
import com.example.rcgallery.model.Album
import com.example.rcgallery.model.MediaItem
import com.example.rcgallery.viewmodel.GalleryViewModel

/**
 * 标签浏览页面——展示所有 TAG 及其标记的相册和文件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagListScreen(
    viewModel: GalleryViewModel,
    onAlbumClick: (Album) -> Unit = {},
    onMediaClick: (MediaItem) -> Unit = {},
    onBackClick: () -> Unit = {}
) {
    val allTags by viewModel.allTags.collectAsState()
    var selectedTag by remember { mutableStateOf<TagEntity?>(null) }
    var tagAlbums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var tagMedia by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(selectedTag) {
        val tag = selectedTag
        if (tag != null) {
            tagAlbums = viewModel.getAlbumsForTag(tag.id)
            // Use a separate method to get media for tag
            tagMedia = emptyList() // Simplified: not loading media across albums here
        } else {
            tagAlbums = emptyList()
            tagMedia = emptyList()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectedTag != null) "标签: ${selectedTag!!.name}" else "标签浏览") },
                navigationIcon = {
                    TextButton(onClick = {
                        if (selectedTag != null) selectedTag = null
                        else onBackClick()
                    }) { Text(if (selectedTag != null) "← 返回" else "← 关闭") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (selectedTag == null) {
                // ── TAG 列表 ──
                if (allTags.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无标签", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        items(allTags) { tag ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable { selectedTag = tag }
                            ) {
                                Text(
                                    tag.name,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(12.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            } else {
                // ── 某个 TAG 的目标列表（相册 + 文件）──
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    // 相册标题
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text("相册", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp))
                    }
                    if (tagAlbums.isEmpty()) {
                        item {
                            Text("暂无匹配相册", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        items(tagAlbums) { album ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clickable { onAlbumClick(album) }
                            ) {
                                Text(
                                    "📁 ${album.bucketName}  (${album.count}项)",
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
