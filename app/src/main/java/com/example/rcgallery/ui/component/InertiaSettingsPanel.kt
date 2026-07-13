package com.example.rcgallery.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 惯性参数调试面板（视频惯性滑动 / 图片缩放等）。
 * 长按倍速设置移到了控制面板齿轮按钮中。
 */
@Composable
fun InertiaSettingsPanel(
    onDismiss: () -> Unit,
    onOpenLog: () -> Unit = {}
) {
    var speedX by remember { mutableFloatStateOf(InertiaSettings.speedMultiplierX) }
    var speedY by remember { mutableFloatStateOf(InertiaSettings.speedMultiplierY) }
    var durX by remember { mutableFloatStateOf(InertiaSettings.durationMultiplierX) }
    var durY by remember { mutableFloatStateOf(InertiaSettings.durationMultiplierY) }
    var edgeSwipe by remember { mutableFloatStateOf(InertiaSettings.edgeSwipeMinPx) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("惯性调速") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ── X轴位移 ──
                Text("X位移: ${speedX.toInt()}×", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Slider(
                    value = speedX,
                    onValueChange = { speedX = it; InertiaSettings.speedMultiplierX = it },
                    valueRange = 1f..20f, steps = 18
                )

                // ── X轴速度 ──
                Text("X速度: %.1f ms/px".format(durX), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Slider(
                    value = durX,
                    onValueChange = { durX = it; InertiaSettings.durationMultiplierX = it },
                    valueRange = 0.05f..1.0f, steps = 18
                )

                // ── Y轴位移 ──
                Text("Y位移: ${speedY.toInt()}×", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Slider(
                    value = speedY,
                    onValueChange = { speedY = it; InertiaSettings.speedMultiplierY = it },
                    valueRange = 1f..20f, steps = 18
                )

                // ── Y轴速度 ──
                Text("Y速度: %.1f ms/px".format(durY), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Slider(
                    value = durY,
                    onValueChange = { durY = it; InertiaSettings.durationMultiplierY = it },
                    valueRange = 0.05f..1.0f, steps = 18
                )

                // ── 翻页触发阈值 ──
                Text("翻页阈值: ${edgeSwipe.toInt()} px", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Slider(
                    value = edgeSwipe,
                    onValueChange = { edgeSwipe = it; InertiaSettings.edgeSwipeMinPx = it },
                    valueRange = 5f..80f, steps = 14
                )

                HorizontalDivider()

                // ── 上划/下滑触发阈值（图片预览）──
                var swipeVel by remember { mutableFloatStateOf(InertiaSettings.swipeVelocityThreshold) }
                Text("滑动触发: ${"%.0f".format(swipeVel)} px", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Slider(
                    value = swipeVel,
                    onValueChange = { swipeVel = it; InertiaSettings.swipeVelocityThreshold = it },
                    valueRange = 1f..15f, steps = 13
                )

                HorizontalDivider()

                var showDbg by remember { mutableStateOf(InertiaSettings.showControlZoneDebug) }
                var cbPct by remember { mutableFloatStateOf(InertiaSettings.controlBarZonePercent) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("显示控制区调试框", fontSize = 13.sp)
                    Switch(checked = showDbg, onCheckedChange = { showDbg = it; InertiaSettings.showControlZoneDebug = it })
                }

                if (showDbg) {
                    Text("保护区域高度: ${cbPct.toInt()}%", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Slider(
                        value = cbPct,
                        onValueChange = { cbPct = it; InertiaSettings.controlBarZonePercent = it },
                        valueRange = 10f..50f, steps = 7
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(top = 4.dp))

                // ── 静音按钮底部偏移 ──
                var muteBtn by remember { mutableFloatStateOf(InertiaSettings.muteButtonBottomDp) }
                Text("静音按钮偏移: ${muteBtn.toInt()} dp", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Slider(
                    value = muteBtn,
                    onValueChange = { muteBtn = it; InertiaSettings.muteButtonBottomDp = it },
                    valueRange = 0f..160f, steps = 31
                )

                HorizontalDivider(modifier = Modifier.padding(top = 4.dp))

                // ── 日志入口按钮（从设置面板打开调试日志）──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    TextButton(onClick = { onOpenLog() }) { Text("日志", fontSize = 13.sp) }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("关闭") }
        }
    )
}
