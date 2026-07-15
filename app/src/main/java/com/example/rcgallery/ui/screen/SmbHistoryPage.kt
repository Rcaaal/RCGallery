package com.example.rcgallery.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.example.rcgallery.data.smb.SmbFileOperationRecord
import com.example.rcgallery.util.AppLogger
import com.example.rcgallery.util.FormatUtil
import java.text.SimpleDateFormat
import java.util.*

/**
 * SMB 操作历史页面（全屏 overlay）。
 *
 * 显示所有 SMB 文件操作历史记录，按时间降序排列。
 * 支持导出全部记录为 JSON 并通过系统分享发送。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmbHistoryPage(
    records: List<SmbFileOperationRecord>,
    onDismiss: () -> Unit,
    onExport: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (records.isEmpty()) "操作历史" else "操作历史 (${records.size})",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onDismiss) {
                        Text("← 关闭")
                    }
                },
                actions = {
                    if (records.isNotEmpty()) {
                        TextButton(onClick = {
                            AppLogger.d("SMB-History", "export button clicked, ${records.size} records")
                            onExport()
                        }) {
                            Text("导出", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (records.isEmpty()) {
                // ── 空状态 ──
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "暂无操作记录",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "在 SMB 文件夹中复制或移动文件后，\n记录将显示在这里",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            lineHeight = 20.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(records, key = { it.id }) { record ->
                        HistoryRecordCard(record)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRecordCard(record: SmbFileOperationRecord) {
    val isCopy = record.mode == "COPY"
    val dateStr = remember(record.timestamp) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        sdf.format(Date(record.timestamp))
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        ) {
            // ── 第一行：时间 + 模式 + 文件数状态 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 左侧：时间
                Text(
                    text = dateStr,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 中间：模式 badge
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (isCopy) Color(0xFF2196F3) else Color(0xCCFF9800)
                ) {
                    Text(
                        text = if (isCopy) "📋 复制" else "📦 移动",
                        fontSize = 11.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                // 右侧：成功/总数
                val allOk = record.successCount == record.fileCount
                Text(
                    text = "${record.successCount}/${record.fileCount}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (allOk) Color(0xFF4CAF50) else Color(0xFFFF9800)
                )
            }

            Spacer(Modifier.height(6.dp))

            // ── 第二行：源文件夹 → 目标文件夹 ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "源: ",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Text(
                    text = "${record.sourceHost}/${record.sourceFolderName}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "目标: ",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Text(
                    text = "${record.targetFolderName}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }

            // ── 可选的完整路径（调试用），只显示长路径时自动截断 ──
            if (record.sourcePath.length > 30 || record.targetPath.length > 30) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = FormatUtil.formatDisplayPath(record.sourcePath),
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "→ ${FormatUtil.formatDisplayPath(record.targetPath)}",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
