package com.example.rcgallery.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rcgallery.data.db.TagEntity
import com.example.rcgallery.model.FilterLogic
import com.example.rcgallery.model.FilterMode
import com.example.rcgallery.model.FilterScope
import com.example.rcgallery.model.TagRule
import com.example.rcgallery.model.TempFilter
import com.example.rcgallery.model.findFirstConflict
import androidx.activity.compose.BackHandler
import java.util.UUID

/**
 * 全屏筛选管理页面。
 *
 * 包含：持久规则列表（toggle 启用/禁用）、临时筛选、新建/编辑规则。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterPage(
    allTags: List<TagEntity>,
    persistentRules: List<TagRule>,
    tempFilter: TempFilter,
    onBack: () -> Unit,
    onToggleRule: (String) -> Unit,
    onSaveRule: (TagRule) -> String?,  // 返回 null=成功，非 null=冲突规则名
    onDeleteRule: (String) -> Unit,
    onSetTempFilter: (TempFilter) -> Unit,
    onReset: () -> Unit
) {
    var editingRule by remember { mutableStateOf<TagRule?>(null) }
    var showNewRule by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    // 如果有编辑或新建，切到编辑子页面
    val currentEdit = editingRule ?: if (showNewRule) TagRule(
        id = UUID.randomUUID().toString(),
        name = "",
        tagNames = emptyList(),
        logic = FilterLogic.OR,
        mode = FilterMode.HIDE,
        scope = FilterScope.ALBUM,
        enabled = false
    ) else null

    if (currentEdit != null) {
        RuleEditPage(
            rule = currentEdit,
            allTags = allTags,
            isNew = showNewRule,
            onSave = { saved ->
                val conflictName = onSaveRule(saved)
                if (conflictName == null) {
                    editingRule = null
                    showNewRule = false
                }
            },
            onDelete = { id ->
                onDeleteRule(id)
                editingRule = null
                showNewRule = false
            },
            onBack = {
                editingRule = null
                showNewRule = false
            }
        )
        return
    }

    // ── 重置二次确认弹窗 ──
    if (showResetDialog) {
        val hasPersistent = persistentRules.any { it.enabled }
        val hasTemp = tempFilter.isActive
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("重置筛选") },
            text = {
                Text(if (hasPersistent) "将清除所有生效的筛选设置？" else "将清除临时筛选设置？")
            },
            confirmButton = {
                TextButton(onClick = {
                    onReset()
                    showResetDialog = false
                }) { Text("重置全部") }
            },
            dismissButton = {
                Row {
                    if (hasPersistent && hasTemp) {
                        TextButton(onClick = {
                            onSetTempFilter(TempFilter())
                            showResetDialog = false
                        }) { Text("仅重置临时") }
                    }
                    TextButton(onClick = { showResetDialog = false }) { Text("取消") }
                }
            }
        )
    }

    // ── 主页面 ──
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("筛选") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = { showResetDialog = true }) {
                        Text("重置")
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = onBack) {
                        Text("确定")
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // ── 持久规则 ──
            Text("持久规则", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))

            if (persistentRules.isEmpty()) {
                Text("无当前筛选目标", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp))
            } else {
                persistentRules.forEach { rule ->
                    RuleListItem(
                        rule = rule,
                        onToggle = { onToggleRule(rule.id) },
                        onClick = { editingRule = rule }
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }

            // 新建规则按钮
            TextButton(
                onClick = { showNewRule = true },
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("新建规则")
            }

            Spacer(Modifier.height(24.dp))

            // ── 临时筛选（相册 + 图片列表） ──
            Text("临时筛选", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))

            TempFilterSection(
                filter = tempFilter,
                allTags = allTags,
                onFilterChange = onSetTempFilter
            )
        }
    }
}

// ══════════════════════════════════════
//  规则列表项
// ══════════════════════════════════════

@Composable
private fun RuleListItem(
    rule: TagRule,
    onToggle: () -> Unit,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(
                checked = rule.enabled,
                onCheckedChange = { onToggle() },
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(rule.name, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(rule.summary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                if (rule.enabled) "启用" else "禁用",
                fontSize = 11.sp,
                color = if (rule.enabled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ══════════════════════════════════════
//  临时筛选
// ══════════════════════════════════════

@Composable
private fun TempFilterSection(
    filter: TempFilter,
    allTags: List<TagEntity>,
    onFilterChange: (TempFilter) -> Unit
) {
    val selectedTagNames = filter.tagNames.toSet()

    // 标签选择
    Text("选择标签", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

    // 横向可滚动标签列表
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        allTags.forEach { tag ->
            val isSelected = tag.name in selectedTagNames
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.height(28.dp).clickable {
                    if (isSelected) {
                        onFilterChange(filter.copy(
                            tagNames = filter.tagNames - tag.name
                        ))
                    } else {
                        onFilterChange(filter.copy(
                            tagNames = filter.tagNames + tag.name
                        ))
                    }
                }
            ) {
                Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                    Text(tag.name, fontSize = 12.sp,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    // 逻辑选择
    Text("逻辑", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { onFilterChange(filter.copy(logic = FilterLogic.AND)) }) {
            RadioButton(
                selected = filter.logic == FilterLogic.AND,
                onClick = { onFilterChange(filter.copy(logic = FilterLogic.AND)) }
            )
            Text("AND", fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { onFilterChange(filter.copy(logic = FilterLogic.OR)) }) {
            RadioButton(
                selected = filter.logic == FilterLogic.OR,
                onClick = { onFilterChange(filter.copy(logic = FilterLogic.OR)) }
            )
            Text("OR", fontSize = 13.sp)
        }
    }

    Spacer(Modifier.height(8.dp))

    // 模式选择
    Text("模式", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { onFilterChange(filter.copy(mode = FilterMode.HIDE)) }) {
            RadioButton(
                selected = filter.mode == FilterMode.HIDE,
                onClick = { onFilterChange(filter.copy(mode = FilterMode.HIDE)) }
            )
            Text("隐藏", fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { onFilterChange(filter.copy(mode = FilterMode.SHOW_ONLY)) }) {
            RadioButton(
                selected = filter.mode == FilterMode.SHOW_ONLY,
                onClick = { onFilterChange(filter.copy(mode = FilterMode.SHOW_ONLY)) }
            )
            Text("仅显示", fontSize = 13.sp)
        }
    }
}

// ══════════════════════════════════════
//  规则编辑页面
// ══════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditPage(
    rule: TagRule,
    allTags: List<TagEntity>,
    isNew: Boolean,
    onSave: (TagRule) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit
) {
    var name by remember(rule) { mutableStateOf(rule.name) }
    var selectedTagNames by remember(rule) { mutableStateOf(rule.tagNames.toSet()) }
    var logic by remember(rule) { mutableStateOf(rule.logic) }
    var mode by remember(rule) { mutableStateOf(rule.mode) }
    var scope by remember(rule) { mutableStateOf(rule.scope) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "新建规则" else "编辑规则") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (!isNew) {
                        IconButton(onClick = { onDelete(rule.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (!isNew) {
                        OutlinedButton(
                            onClick = { onDelete(rule.id) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text("删除") }
                        Spacer(Modifier.width(12.dp))
                    }
                    Button(
                        onClick = {
                            if (name.isBlank()) {
                                errorMsg = "请输入规则名称"
                                return@Button
                            }
                            if (selectedTagNames.isEmpty()) {
                                errorMsg = "请选择至少一个标签"
                                return@Button
                            }
                            errorMsg = null
                            onSave(rule.copy(
                                name = name.trim(),
                                tagNames = selectedTagNames.toList(),
                                logic = logic,
                                mode = mode,
                                scope = scope
                            ))
                        }
                    ) { Text("保存") }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // 规则名称
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("规则名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(16.dp))

            // 标签选择
            Text("选择标签", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))

            // 所有标签横向滚动
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                allTags.forEach { tag ->
                    val isSelected = tag.name in selectedTagNames
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.height(32.dp).clickable {
                            if (isSelected) selectedTagNames = selectedTagNames - tag.name
                            else selectedTagNames = selectedTagNames + tag.name
                        }
                    ) {
                        Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                            Text(tag.name, fontSize = 13.sp,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 逻辑
            Text("逻辑", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { logic = FilterLogic.AND }) {
                    RadioButton(selected = logic == FilterLogic.AND, onClick = { logic = FilterLogic.AND })
                    Text("AND（全部命中）", fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { logic = FilterLogic.OR }) {
                    RadioButton(selected = logic == FilterLogic.OR, onClick = { logic = FilterLogic.OR })
                    Text("OR（任一命中）", fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(16.dp))

            // 模式
            Text("模式", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { mode = FilterMode.HIDE }) {
                    RadioButton(selected = mode == FilterMode.HIDE, onClick = { mode = FilterMode.HIDE })
                    Text("隐藏", fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { mode = FilterMode.SHOW_ONLY }) {
                    RadioButton(selected = mode == FilterMode.SHOW_ONLY, onClick = { mode = FilterMode.SHOW_ONLY })
                    Text("仅显示", fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(16.dp))

            // 作用域
            Text("作用域", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { scope = FilterScope.ALBUM }) {
                    RadioButton(selected = scope == FilterScope.ALBUM, onClick = { scope = FilterScope.ALBUM })
                    Text("相册", fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { scope = FilterScope.MEDIA }) {
                    RadioButton(selected = scope == FilterScope.MEDIA, onClick = { scope = FilterScope.MEDIA })
                    Text("图片", fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { scope = FilterScope.BOTH }) {
                    RadioButton(selected = scope == FilterScope.BOTH, onClick = { scope = FilterScope.BOTH })
                    Text("全部", fontSize = 13.sp)
                }
            }

            // 错误提示
            if (errorMsg != null) {
                Spacer(Modifier.height(12.dp))
                Text(errorMsg!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
        }
    }
}
