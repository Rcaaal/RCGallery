package com.example.rcgallery.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.delay

/**
 * 左下角圆形悬浮按钮。
 *
 * - 滚动进度 0-50% → 显示 ↓，点击跳到底部
 * - 滚动进度 50-100% → 显示 ↑，点击跳到顶部
 * - RecyclerView 所有内容可见或在加载时自动隐藏
 */
@Composable
fun FloatingJumpButton(
    recyclerView: RecyclerView?,
    modifier: Modifier = Modifier
) {
    var show by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    // 轮询 RecyclerView 滚动位置
    LaunchedEffect(recyclerView) {
        while (true) {
            val rv = recyclerView
            if (rv != null) {
                val lm = rv.layoutManager as? LinearLayoutManager
                if (lm != null) {
                    val total = lm.itemCount
                    val visible = lm.childCount
                    if (total > 0 && visible > 0 && visible < total) {
                        val scrollRange = total - visible
                        val pos = lm.findFirstVisibleItemPosition().coerceIn(0, scrollRange)
                        progress = if (scrollRange > 0) pos.toFloat() / scrollRange else 0f
                        show = true
                    } else {
                        show = false
                    }
                }
            }
            delay(200)
        }
    }

    if (!show) return

    val atBottom = progress >= 0.5f

    Box(
        modifier = modifier
            .padding(start = 16.dp, bottom = 80.dp)
            .size(42.dp)
            .clip(CircleShape)
            .background(Color(0xCC333333))
            .clickable {
                val rv = recyclerView ?: return@clickable
                val lm = rv.layoutManager ?: return@clickable
                if (atBottom) {
                    lm.scrollToPosition(0)
                } else {
                    lm.scrollToPosition(lm.itemCount - 1)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (atBottom) "↑" else "↓",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
