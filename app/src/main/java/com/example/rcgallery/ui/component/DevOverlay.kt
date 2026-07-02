package com.example.rcgallery.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rcgallery.util.AppLogger

/**
 * 调试日志浮层：小绿点 → 点击弹出日志弹窗 → 一键复制。
 * 放在每页的 Box 顶层 [Alignment.TopStart] 或 [Alignment.TopEnd]。
 */
@Composable
fun DevOverlay(
    modifier: Modifier = Modifier,
    tagFilter: String? = null,
    initialShow: Boolean = false
) {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(initialShow) }
    var logs by remember { mutableStateOf(if (initialShow) AppLogger.getLogs(tagFilter) else "") }

    // ── 悬浮小绿点 ──
    Box(
        modifier = modifier
            .size(28.dp)
            .shadow(4.dp, CircleShape)
            .background(Color(0xCC4CAF50), CircleShape)
            .clickable {
                logs = AppLogger.getLogs(tagFilter)
                showDialog = true
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "DBG",
            color = Color.White,
            fontSize = 10.sp,
            textAlign = TextAlign.Center
        )
    }

    // ── 日志弹窗 ──
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("调试日志") },
            text = {
                Text(
                    text = logs.ifEmpty { "（无日志）" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            confirmButton = {
                Button(onClick = {
                    copyToClipboard(context, logs)
                    Toast.makeText(context, "日志已复制", Toast.LENGTH_SHORT).show()
                    showDialog = false
                }) { Text("复制日志") }
            },
            dismissButton = {
                TextButton(onClick = {
                    AppLogger.clear()
                    logs = ""
                }) { Text("清空") }
            }
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("RCGallery 日志", text))
}
