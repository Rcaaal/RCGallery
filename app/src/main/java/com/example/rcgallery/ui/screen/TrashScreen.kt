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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.rcgallery.model.TrashEntry
import com.example.rcgallery.viewmodel.GalleryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 回收站全屏覆盖层。
 * 展示已快删的文件，支持恢复和永久删除。
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
    

    BackHandler { onBackClick() }

    // ── 选中条目操作对话框 ──
    var selectedEntry by remember { mutableStateOf<TrashEntry?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }

    // ── 永久删除 launcher（Android 10+ 需要 createDeleteRequest IntentSender）──
    val deleteRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        try {
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val entry = selectedEntry
                if (entry != null) {
                    viewModel.permanentlyDeleteConfirmed(entry.uri, entry.originalAlbumId)
                    Toast.makeText(context, "已永久删除", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "删除失败：未获得授权", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            com.example.rcgallery.util.AppLogger.e("Trash", "permanent delete callback error", e)
            Toast.makeText(context, "删除操作异常", Toast.LENGTH_SHORT).show()
        } finally {
            selectedEntry = null
            isDeleting = false
        }
    }

    // 退出 TrashScreen 时清理 pending 状态（防止回调访问 stale entry 或启动中状态的 launcher）
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            showDeleteConfirm = false
            isDeleting = false
        }
    }

    // 永久删除确认对话框
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
                        // 用 createDeleteRequest 物理删除（Android 10+），低版本直接删
                        if (Build.VERSION.SDK_INT >= 30) {
                            try {
                                val uri = Uri.parse(entry.uri)
                                if (uri == null || uri.toString().isBlank()) {
                                    Toast.makeText(context, "无效的文件 URI", Toast.LENGTH_SHORT).show()
                                    selectedEntry = null; isDeleting = false; return@Button
                                }
                                val pending = MediaStore.createDeleteRequest(
                                    context.contentResolver, listOf(uri)
                                )
                                deleteRequestLauncher.launch(
                                    IntentSenderRequest.Builder(pending.intentSender).build()
                                )
                            } catch (e: Exception) {
                                Toast.makeText(context, "请求删除授权失败", Toast.LENGTH_SHORT).show()
                                selectedEntry = null; isDeleting = false
                            }
                        } else {
                            // Android 9 及以下直接 contentResolver.delete
                            try {
                                context.contentResolver.delete(Uri.parse(entry.uri), null, null)
                                viewModel.permanentlyDeleteConfirmed(entry.uri, entry.originalAlbumId)
                                Toast.makeText(context, "已永久删除", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "删除失败", Toast.LENGTH_SHORT).show()
                            }
                            selectedEntry = null; isDeleting = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } }
        )
    }

    // ── 条目操作对话框 ──
    if (selectedEntry != null && !showDeleteConfirm) {
        val entry = selectedEntry!!
        val deleteTimeStr = remember(entry.deleteTime) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date(entry.deleteTime))
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
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel.restoreFromTrash(entry.uri)
                            viewModel.loadAlbums()  // 恢复后刷新相册计数
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

    Surface(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "回收站 (${entries.size})",
                            maxLines = 1,
                            color = Color.White
                        )
                    },
                    navigationIcon = {
                        TextButton(onClick = onBackClick) { Text("← 返回", color = Color.White) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A1A))
                )
            },
            containerColor = Color.Black
        ) { padding ->
            if (entries.isEmpty()) {
                Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text("回收站是空的", color = Color.Gray, fontSize = 16.sp)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(entries, key = { it.uri }) { entry ->
                        TrashGridItem(
                            entry = entry,
                            onClick = { selectedEntry = entry }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrashGridItem(
    entry: TrashEntry,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF2A2A2A))
            .clickable { onClick() }
    ) {
        // 缩略图（Coil 直接从 URI String 加载，支持 content:// 格式）
        AsyncImage(
            model = entry.uri,
            contentDescription = entry.fileName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        // 右上角红色圆点（代表"已删除"）
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(Color.Red)
        )
        // 视频标记
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
