package com.example.rcgallery.ui.screen

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rcgallery.data.ImportedMediaOutput
import com.example.rcgallery.data.MediaImportHistoryItem
import com.example.rcgallery.data.MediaImportHistoryPlatform
import com.example.rcgallery.data.MediaImportHistoryRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MediaImportHistoryScreen(
    onDismiss: () -> Unit,
    onReimport: (String) -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(context) { MediaImportHistoryRepository(context) }
    val records by repository.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf<MediaImportHistoryPlatform?>(null) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var outputRecord by remember { mutableStateOf<MediaImportHistoryItem?>(null) }
    val visibleRecords = remember(records, filter) {
        records.filter { filter == null || it.platform == filter }
    }

    fun removeSelected() {
        val ids = selectedIds
        scope.launch {
            repository.delete(ids)
            selectedIds = emptySet()
        }
    }

    BackHandler(enabled = selectedIds.isNotEmpty()) { selectedIds = emptySet() }
    BackHandler(enabled = selectedIds.isEmpty(), onBack = onDismiss)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectedIds.isEmpty()) "下载历史" else "已选 ${selectedIds.size} 项",
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    TextButton(onClick = { if (selectedIds.isEmpty()) onDismiss() else selectedIds = emptySet() }) {
                        Text(if (selectedIds.isEmpty()) "返回" else "取消")
                    }
                },
                actions = {
                    if (selectedIds.isNotEmpty()) {
                        TextButton(onClick = ::removeSelected) { Text("删除") }
                    } else if (records.isNotEmpty()) {
                        TextButton(onClick = { scope.launch { repository.clear() } }) { Text("清空") }
                    }
                },
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            HistoryFilterRow(filter = filter, onSelect = { filter = it })
            if (visibleRecords.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (records.isEmpty()) "暂无下载历史" else "该平台暂无下载记录",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visibleRecords, key = { it.id }) { record ->
                        HistoryCard(
                            record = record,
                            selected = record.id in selectedIds,
                            selectionMode = selectedIds.isNotEmpty(),
                            onClick = {
                                if (selectedIds.isNotEmpty()) {
                                    selectedIds = toggleSelection(selectedIds, record.id)
                                } else {
                                    outputRecord = record
                                }
                            },
                            onLongClick = { selectedIds = toggleSelection(selectedIds, record.id) },
                        )
                    }
                }
            }
        }
    }

    outputRecord?.let { record ->
        HistoryOutputDialog(
            record = record,
            onDismiss = { outputRecord = null },
            onOpen = { output -> openOutput(context, output) },
            onReimport = {
                outputRecord = null
                onReimport(record.sourceUrl)
            },
        )
    }
}

@Composable
private fun HistoryFilterRow(
    filter: MediaImportHistoryPlatform?,
    onSelect: (MediaImportHistoryPlatform?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HistoryFilterChip("全部", filter == null) { onSelect(null) }
        MediaImportHistoryPlatform.entries.forEach { platform ->
            HistoryFilterChip(platform.label, filter == platform) { onSelect(platform) }
        }
    }
}

@Composable
private fun HistoryFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryCard(
    record: MediaImportHistoryItem,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
            PlatformMark(record.platform)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        record.title.ifBlank { "未命名下载" },
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (selectionMode && selected) Text("已选", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                }
                record.author?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    formatHistoryTime(record.downloadedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "成功 ${record.successCount} 项" + if (record.failedCount > 0) " · 失败 ${record.failedCount} 项" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (record.failedCount > 0) Color(0xFFFF9800) else Color(0xFF63B86A),
                )
            }
        }
    }
}

@Composable
private fun PlatformMark(platform: MediaImportHistoryPlatform) {
    val (label, color) = when (platform) {
        MediaImportHistoryPlatform.DOUYIN -> "抖" to Color(0xFF202020)
        MediaImportHistoryPlatform.BILIBILI -> "B" to Color(0xFF00A9D6)
        MediaImportHistoryPlatform.YOUTUBE -> "▶" to Color(0xFFD92D20)
        MediaImportHistoryPlatform.X -> "X" to Color(0xFF202020)
    }
    androidx.compose.material3.Surface(
        modifier = Modifier.size(38.dp),
        shape = RoundedCornerShape(8.dp),
        color = color,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun HistoryOutputDialog(
    record: MediaImportHistoryItem,
    onDismiss: () -> Unit,
    onOpen: (ImportedMediaOutput) -> Unit,
    onReimport: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(record.title.ifBlank { "下载文件" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (record.outputs.isEmpty()) {
                    Text("未记录输出文件")
                } else {
                    record.outputs.forEach { output ->
                        OutlinedButton(
                            onClick = { onOpen(output) },
                            enabled = output.uri != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(output.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onReimport) { Text("重新解析") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun toggleSelection(current: Set<Long>, id: Long): Set<Long> =
    if (id in current) current - id else current + id

private fun formatHistoryTime(time: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(time))

private fun openOutput(context: android.content.Context, output: ImportedMediaOutput) {
    val uri = output.uri?.let(Uri::parse) ?: return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "本地文件已不存在或无法打开", Toast.LENGTH_SHORT).show() }
}
