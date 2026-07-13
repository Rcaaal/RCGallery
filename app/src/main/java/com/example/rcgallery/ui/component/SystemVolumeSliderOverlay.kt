package com.example.rcgallery.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.rcgallery.viewmodel.PlaybackSettingsViewModel

/**
 * 右侧系统音量滑条覆盖层 — 与 PreviewScreen 当前音量条完全一致。
 *
 * 展开/收起跟随 VideoPlayer 控制栏显隐（[visible]），
 * 拖动直接调用 [playbackSettingsVM.setVolume] 控制系统 STREAM_MUSIC，
 * 显示位置由 [volumeLevel]（0~1）驱动。
 *
 * 用法：放在 Box 内部的覆盖层位置，自动 [align(Alignment.CenterEnd)]。
 */
@Composable
fun SystemVolumeSliderOverlay(
    visible: Boolean,
    pipOverlayHidden: Boolean,
    isActiveVideo: Boolean,
    volumeLevel: Float,
    playbackSettingsVM: PlaybackSettingsViewModel,
    modifier: Modifier = Modifier
) {
    val SLIDER_HEIGHT = 140.dp
    val SLIDER_WIDTH = 4.dp

    if (visible && !pipOverlayHidden && isActiveVideo) {
        Box(
            modifier = modifier
                .padding(end = 12.dp)
                .width(40.dp)
                .height(SLIDER_HEIGHT)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (change.pressed) {
                                val newVolume = (1f - change.position.y / size.height.toFloat()).coerceIn(0f, 1f)
                                playbackSettingsVM.setVolume(newVolume)
                                change.consume()
                            } else break
                        } while (true)
                    }
                }
        ) {
            // 轨道背景
            Box(
                modifier = Modifier
                    .width(SLIDER_WIDTH)
                    .height(SLIDER_HEIGHT)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.3f))
            ) {
                // 填充部分（从底部到当前音量）
                Box(
                    modifier = Modifier
                        .width(SLIDER_WIDTH)
                        .fillMaxHeight(volumeLevel)
                        .align(Alignment.BottomCenter)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.7f))
                )
            }
            // 滑块圆点
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .align(Alignment.Center)
                    .offset(y = (SLIDER_HEIGHT * (0.5f - volumeLevel)).coerceIn(-SLIDER_HEIGHT / 2f, SLIDER_HEIGHT / 2f))
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.7f))
            )
        }
    }
}
