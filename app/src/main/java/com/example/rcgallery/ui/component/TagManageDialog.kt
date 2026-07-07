package com.example.rcgallery.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rcgallery.data.db.TagEntity

/**
 * TAG 管理对话框——用于添加/移除 TAG，支持输入搜索已有 TAG 及新建。
 *
 * @param existingTags 当前已选 TAG 列表
 * @param allTags 所有可用的 TAG 列表
 * @param recentTags 最近使用的 TAG（可空）
 * @param readOnlyTagIds 继承自相册的 TAG ID，不可手动移除（不显示 ✕）
 * @param onAddTag 添加 TAG 回调（传入 TAG 名称）
 * @param onRemoveTag 移除 TAG 回调（传入 TAG ID）
 * @param onDismiss 关闭对话框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagManageDialog(
    title: String = "管理标签",
    existingTags: List<TagEntity>,
    allTags: List<TagEntity>,
    recentTags: List<TagEntity> = emptyList(),
    readOnlyTagIds: Set<Long> = emptySet(),
    onAddTag: (String) -> Unit,
    onRemoveTag: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val existingIds = remember(existingTags) { existingTags.map { it.id }.toSet() }

    // 客户端过滤
    val suggestions = remember(searchQuery, allTags) {
        if (searchQuery.isBlank()) emptyList()
        else allTags.filter { it.name.contains(searchQuery, ignoreCase = true) && it.id !in existingIds }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.heightIn(max = 400.dp)) {
                // ── 输入框 ──
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("输入标签名称...", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))

                // ── 当前TAG（横向可滚动）──
                if (existingTags.isNotEmpty()) {
                    Text("当前标签", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        existingTags.forEach { tag ->
                            val isReadOnly = tag.id in readOnlyTagIds
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isReadOnly) MaterialTheme.colorScheme.surfaceVariant
                                        else MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.height(28.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .align(Alignment.CenterVertically)
                                        .padding(start = 8.dp, end = if (isReadOnly) 8.dp else 4.dp)
                                ) {
                                    Text(
                                        tag.name,
                                        fontSize = 12.sp,
                                        color = if (isReadOnly) MaterialTheme.colorScheme.onSurfaceVariant
                                                else MaterialTheme.colorScheme.onPrimaryContainer,
                                        maxLines = 1
                                    )
                                    if (!isReadOnly) {
                                        Spacer(Modifier.width(4.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(16.dp)
                                                .clickable { onRemoveTag(tag.id) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("✕", fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // ── 搜索建议 ──
                if (searchQuery.isNotBlank()) {
                    if (suggestions.isNotEmpty()) {
                        Text("搜索结果", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        suggestions.take(8).forEach { tag ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable {
                                    onAddTag(tag.name)
                                    searchQuery = ""
                                }
                            ) {
                                Text(tag.name, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        // 无匹配 → 新建
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().clickable {
                                onAddTag(searchQuery.trim())
                                searchQuery = ""
                            }
                        ) {
                            Text("新建标签「${searchQuery.trim()}」", fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // 最近使用
                if (searchQuery.isBlank() && recentTags.isNotEmpty()) {
                    Text("最近使用", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    recentTags.filter { it.id !in existingIds }.take(10).forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { onAddTag(tag.name) }
                        ) {
                            Text(tag.name, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                // 全部 TAG（收起/展开）
                if (searchQuery.isBlank() && allTags.isNotEmpty()) {
                    var showAll by remember { mutableStateOf(false) }
                    TextButton(onClick = { showAll = !showAll }) {
                        Text(if (showAll) "收起" else "查看全部标签", fontSize = 12.sp)
                    }
                    if (showAll) {
                        allTags.filter { it.id !in existingIds }.forEach { tag ->
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp).clickable { onAddTag(tag.name) }
                            ) {
                                Text(tag.name, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        modifier = Modifier.heightIn(max = 500.dp)
    )
}
