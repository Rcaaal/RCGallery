package com.example.rcgallery.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设置面板 + 日志面板的封装。
 *
 * 三屏（AlbumGrid / MediaGrid / Preview）均有相同的惯性设置和日志入口，
 * 抽取为公共组件消除重复。
 *
 * @param gearModifier 齿轮按钮的 Modifier（位置由调用方决定）
 * @param visible 是否显示齿轮按钮（Preview 在 PiP 时隐藏）
 * @param gearSize 齿轮按钮尺寸，默认 28.dp
 * @param gearBackground 齿轮按钮背景色，默认橙色半透明
 */
@Composable
fun SettingsOverlay(
    gearModifier: Modifier = Modifier,
    visible: Boolean = true,
    gearSize: Dp = 28.dp,
    gearBackground: Color = Color(0xCCFF9800)
) {
    var showInertiaSettings by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    if (showInertiaSettings) InertiaSettingsPanel(
        onDismiss = { showInertiaSettings = false },
        onOpenLog = { showInertiaSettings = false; showLogDialog = true }
    )
    if (showLogDialog) {
        DevLogDialog(onDismiss = { showLogDialog = false })
    }

    if (visible) {
        Box(
            modifier = gearModifier
                .size(gearSize)
                .clip(CircleShape)
                .background(gearBackground)
                .clickable { showInertiaSettings = true },
            contentAlignment = Alignment.Center
        ) { Icon(
                painter = painterResource(com.example.rcgallery.R.drawable.ic_settings),
                contentDescription = "设置",
                tint = Color.White,
                modifier = Modifier.size((gearSize * 0.57f).coerceAtMost(20.dp))
            ) }
    }
}
