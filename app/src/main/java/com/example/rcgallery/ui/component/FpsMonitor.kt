package com.example.rcgallery.ui.component

import android.view.Choreographer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 全局 FPS 监控开关。后续可从设置页面写入 DataStore/Preferences，
 * 这里先留作硬编码开关。
 */
var FpsMonitorEnabled: Boolean = false
    private set

/** 由设置页面调用 */
fun setFpsMonitorEnabled(enabled: Boolean) {
    FpsMonitorEnabled = enabled
}

/**
 * 实时帧率监控器 — 使用 Choreographer 回调计算 FPS。
 *
 * @param enabled false 时输出空 Box，零性能开销（不注册任何回调）。
 *                后续可从设置开关控制。
 */
@Composable
fun FpsMonitor(
    enabled: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (!enabled) return

    var fpsText by remember { mutableStateOf("-- FPS") }

    DisposableEffect(Unit) {
        val frameCallback = object : Choreographer.FrameCallback {
            private var lastTimeNanos = -1L
            private var lastLoggedFps = "-- FPS"

            override fun doFrame(frameTimeNanos: Long) {
                if (lastTimeNanos > 0) {
                    val delta = (frameTimeNanos - lastTimeNanos) / 1_000_000f
                    if (delta > 0) {
                        val fps = (1000f / delta)
                        val label = "${fps.toInt()} FPS"
                        if (label != lastLoggedFps) {
                            lastLoggedFps = label
                            fpsText = label
                        }
                    }
                }
                lastTimeNanos = frameTimeNanos
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        Choreographer.getInstance().postFrameCallback(frameCallback)

        onDispose {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
    }

    Box(
        modifier = modifier
            .shadow(4.dp, RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = fpsText,
            color = Color.Green,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
