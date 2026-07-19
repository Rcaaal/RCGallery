package com.example.rcgallery.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 右下角竖向 4 个悬浮多选操作按钮。
 *
 * 在 MediaGridScreen 多选模式下代替原来的 BottomAppBar。
 * 四个按钮：批量加标签、删除到回收站、加入中转站、选择目标相册。
 */
@Composable
fun FloatingMultiSelectButtons(
    selectedCount: Int,
    onBatchTag: (() -> Unit)? = null,
    onDeleteToTrash: (() -> Unit)? = null,
    onAddToClipboard: (() -> Unit)? = null,
    onAddToWatchLater: (() -> Unit)? = null,
    onPickTargetAlbum: (() -> Unit)? = null,
    onAddToParent: (() -> Unit)? = null,
    onMergeToFolder: (() -> Unit)? = null,
    batchTagLabel: String = "批量加标签",
    pickTargetLabel: String = "选择目标相册",
    addToParentLabel: String = "添加到父级",
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.End
    ) {
        // ── 批量加标签 ──
        onBatchTag?.let { action ->
            ActionButton(
                text = "$batchTagLabel ($selectedCount)",
                containerColor = Color(0xFF555555),
                onClick = action
            )
        }
        onAddToWatchLater?.let { action ->
            ActionButton(
                text = "稍后再看 ($selectedCount)",
                containerColor = Color(0xFF1976D2),
                onClick = action
            )
        }
        onAddToParent?.let { action ->
            ActionButton(
                text = "$addToParentLabel ($selectedCount)",
                containerColor = Color(0xFF607D8B),
                onClick = action
            )
        }
        onMergeToFolder?.let { action ->
            ActionButton(
                text = "合并至 ($selectedCount)",
                containerColor = Color(0xFF00897B),
                onClick = action
            )
        }
        // ── 删除到回收站 ──
        onDeleteToTrash?.let { action ->
            ActionButton(
                text = "删除到回收站 ($selectedCount)",
                containerColor = MaterialTheme.colorScheme.error,
                onClick = action
            )
        }
        // ── 加入中转站 ──
        onAddToClipboard?.let { action ->
            ActionButton(
                text = "加入中转站 ($selectedCount)",
                containerColor = Color(0xCCFF9800),
                onClick = action
            )
        }
        // ── 选择目标相册 ──
        onPickTargetAlbum?.let { action ->
            ActionButton(
                text = "$pickTargetLabel ($selectedCount)",
                containerColor = Color(0xFF448AFF),
                onClick = action
            )
        }
    }
}

/**
 * 单个圆角矩形悬浮按钮。
 */
@Composable
private fun ActionButton(
    text: String,
    containerColor: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        onClick = onClick,
        modifier = Modifier.height(34.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxHeight().padding(horizontal = 10.dp)
        ) {
            Text(
                text = text,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}
