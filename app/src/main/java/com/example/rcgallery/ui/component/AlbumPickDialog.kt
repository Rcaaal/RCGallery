package com.example.rcgallery.ui.component

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rcgallery.model.Album
import com.example.rcgallery.viewmodel.PasteMode

/** 相册排序模式 */
private enum class AlbumSort(val label: String) {
    NAME("名称"),
    DATE("日期"),
    COUNT("文件数"),
    RECENT("最近移动过")
}

/**
 * 目标相册选择对话框。
 *
 * 从中转站或单张图片选择目标相册进行复制/移动操作。
 * 支持排序（名称/日期/文件数/最近移动过）和搜索过滤。
 */
@Composable
fun AlbumPickDialog(
    albums: List<Album>,
    recentMoveAlbumDirs: List<String>,
    onDismiss: () -> Unit,
    onAlbumSelected: (targetDir: String, targetName: String, mode: PasteMode) -> Unit,
    onCreateFolder: ((folderName: String, onResult: (dirPath: String?) -> Unit) -> Unit)? = null
) {
    // ── 排序状态 ──
    var activeSort by remember { mutableStateOf(AlbumSort.NAME) }
    var searchQuery by remember { mutableStateOf("") }

    // ── 排序后和搜索后的相册列表 ──
    val recentDirs = remember { recentMoveAlbumDirs.toSet() }
    val sortedAlbums = remember(albums, activeSort, recentMoveAlbumDirs) {
        val sorted = when (activeSort) {
            AlbumSort.NAME -> albums.sortedBy { it.bucketName }
            AlbumSort.DATE -> albums.sortedByDescending { it.dateAdded }
            AlbumSort.COUNT -> albums.sortedByDescending { it.count }
            AlbumSort.RECENT -> {
                // 最近移动过的相册排在前面
                val recentSet = recentMoveAlbumDirs.toSet()
                albums.sortedByDescending { it.directoryPath in recentSet }
            }
        }
        // 星标相册始终排在最前面
        sorted
    }

    // ── 确认选择（复制/移动前先弹确认对话框）──
    var confirmTarget by remember { mutableStateOf<Album?>(null) }

    // ── 搜索过滤 ──
    val filteredAlbums = remember(sortedAlbums, searchQuery) {
        if (searchQuery.isBlank()) sortedAlbums
        else sortedAlbums.filter { it.bucketName.contains(searchQuery, ignoreCase = true) }
    }

    // ── 新建文件夹状态 ──
    var showCreateFolder by remember { mutableStateOf(false) }

    // ── 确认对话框 ──
    if (confirmTarget != null) {
        val target = confirmTarget!!
        AlertDialog(
            onDismissRequest = { confirmTarget = null },
            title = { Text(target.bucketName) },
            text = {
                Column {
                    Text("选择操作方式：", fontSize = 14.sp, color = Color.Gray)
                    Spacer(Modifier.height(4.dp))
                    Text("文件数：${target.count}", fontSize = 13.sp)
                    if (target.directoryPath in recentDirs) {
                        Text("⚡ 最近移动过", fontSize = 12.sp, color = Color(0xCCFF9800))
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    confirmTarget = null
                    onDismiss()
                    onAlbumSelected(target.directoryPath, target.bucketName, PasteMode.COPY)
                }) {
                    Text("复制到此")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmTarget = null }) {
                        Text("取消")
                    }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = {
                            confirmTarget = null
                            onDismiss()
                            onAlbumSelected(target.directoryPath, target.bucketName, PasteMode.MOVE)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xCCFF9800)
                        )
                    ) {
                        Text("移动到此")
                    }
                }
            }
        )
    }

    // ── 主对话框 ──
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择目标相册", fontWeight = FontWeight.Medium) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                // 搜索框
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索相册...", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    )
                )

                Spacer(Modifier.height(8.dp))

                // 排序 chips
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AlbumSort.entries.forEach { sort ->
                        val selected = activeSort == sort
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            onClick = { activeSort = sort },
                            modifier = Modifier.height(28.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 10.dp)
                            ) {
                                Text(
                                    sort.label,
                                    fontSize = 11.sp,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 相册列表
                if (filteredAlbums.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("无匹配相册", fontSize = 13.sp, color = Color.Gray)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(filteredAlbums, key = { it.bucketId }) { album ->
                            val isRecent = album.directoryPath in recentDirs
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isRecent) Color(0x10FF9800) else Color.Transparent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { confirmTarget = album },
                                tonalElevation = 0.dp
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "📁",
                                        fontSize = 16.sp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            album.bucketName,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            "${album.count} 项",
                                            fontSize = 11.sp,
                                            color = Color.Gray
                                        )
                                    }
                                    if (isRecent) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = Color(0x30FF9800)
                                        ) {
                                            Text(
                                                "⚡最近",
                                                fontSize = 10.sp,
                                                color = Color(0xCCFF9800),
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Text("›", fontSize = 16.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                }

                // ── 新建文件夹 ──
                if (onCreateFolder != null) {
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0x10FF9800),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showCreateFolder = true },
                        tonalElevation = 0.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📂", fontSize = 16.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "+ 新建文件夹",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xCCFF9800)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )

    // ── 新建文件夹对话框 ──
    if (showCreateFolder) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFolder = false },
            title = { Text("新建文件夹", fontWeight = FontWeight.Medium) },
            text = {
                Column {
                    Text("输入新文件夹名称：", fontSize = 13.sp, color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = { folderName = it },
                        singleLine = true,
                        placeholder = { Text("文件夹名称", fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = folderName.trim()
                        if (name.isEmpty()) return@Button
                        showCreateFolder = false
                        onCreateFolder?.invoke(name) { dirPath ->
                            if (dirPath != null) {
                                confirmTarget = Album(
                                    bucketId = "new_${dirPath.hashCode()}",
                                    bucketName = name,
                                    coverUri = Uri.EMPTY,
                                    count = 0,
                                    directoryPath = dirPath
                                )
                            }
                        }
                    }
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolder = false }) { Text("取消") }
            }
        )
    }
}
