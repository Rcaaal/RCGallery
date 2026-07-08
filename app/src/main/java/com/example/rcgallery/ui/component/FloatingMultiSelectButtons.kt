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
    onBatchTag: () -> Unit,
    onDeleteToTrash: () -> Unit,
    onAddToClipboard: () -> Unit,
    onPickTargetAlbum: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.End
    ) {
        // ── 批量加标签 ──
        ActionButton(
            text = "批量加标签 ($selectedCount)",
            containerColor = Color(0xFF555555),
            onClick = onBatchTag
        )
        // ── 删除到回收站 ──
        ActionButton(
            text = "删除到回收站 ($selectedCount)",
            containerColor = MaterialTheme.colorScheme.error,
            onClick = onDeleteToTrash
        )
        // ── 加入中转站 ──
        ActionButton(
            text = "加入中转站 ($selectedCount)",
            containerColor = Color(0xCCFF9800),
            onClick = onAddToClipboard
        )
        // ── 选择目标相册 ──
        ActionButton(
            text = "选择目标相册 ($selectedCount)",
            containerColor = Color(0xFF448AFF),
            onClick = onPickTargetAlbum
        )
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
