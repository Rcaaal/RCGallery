package com.example.rcgallery.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rcgallery.viewmodel.PasteMode

/**
 * 中转站浮动 badge。
 *
 * 显示在中转站有内容时，圆形红底白字计数器。
 * 点击弹出菜单：复制/移动到当前相册、选择目标相册、解散中转站。
 *
 * @param clipboardCount 中转站中媒体数量
 * @param currentAlbumDir 当前正在浏览的相册目录路径，null=不在相册中
 * @param onPasteToAlbum 复制/移动到当前相册，参数为 (模式, 目标相册目录)
 * @param onPickTargetAlbum 打开目标相册选择对话框
 * @param onClear 解散中转站（只清空临时列表，不操作真实文件）
 */
@Composable
fun ClipboardBadge(
    clipboardCount: Int,
    currentAlbumDir: String?,
    onPasteToAlbum: (PasteMode, String) -> Unit,
    onPickTargetAlbum: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (clipboardCount <= 0) return

    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        // 圆形 badge
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0xFFE53935))
                .clickable { expanded = true },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = clipboardCount.toString(),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }

        // 弹出菜单
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            if (currentAlbumDir != null) {
                DropdownMenuItem(
                    text = { Text("复制到当前相册", fontSize = 14.sp) },
                    onClick = {
                        expanded = false
                        onPasteToAlbum(PasteMode.COPY, currentAlbumDir)
                    }
                )
                DropdownMenuItem(
                    text = { Text("移动到当前相册", fontSize = 14.sp) },
                    onClick = {
                        expanded = false
                        onPasteToAlbum(PasteMode.MOVE, currentAlbumDir)
                    }
                )
                HorizontalDivider()
            }
            DropdownMenuItem(
                text = { Text("选择目标相册...", fontSize = 14.sp) },
                onClick = {
                    expanded = false
                    onPickTargetAlbum()
                }
            )
            DropdownMenuItem(
                text = { Text("解散中转站", fontSize = 14.sp, color = Color.Gray) },
                onClick = {
                    expanded = false
                    onClear()
                }
            )
        }
    }
}
