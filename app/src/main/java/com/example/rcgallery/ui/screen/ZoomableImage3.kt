package com.example.rcgallery.ui.screen

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.widget.ImageView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import coil.load
import com.example.rcgallery.ui.component.InertiaSettings
import com.example.rcgallery.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

private const val DOUBLE_TAP_ZOOM = 2.5f
private const val EDGE_THRESHOLD_PX = 3f
private const val DOUBLE_TAP_TIME_MS = 300L
private const val SWIPE_DOWN_MIN_VELOCITY = 5f

private data class InertiaParams(
    val targetX: Float,
    val targetY: Float,
    val durationMs: Int
)

private fun calcImageFitHeight(boxSize: IntSize, intrinsicSize: Size): Float {
    if (intrinsicSize.width <= 0f || intrinsicSize.height <= 0f) return boxSize.height.toFloat()
    return boxSize.width.toFloat() * intrinsicSize.height / intrinsicSize.width
}

/**
 * 可缩放图片。统一手势处理，不 consume 未缩放的单指事件 → HorizontalPager 正常翻页；
 * 双击/双指缩放/缩放后拖拽 则 consume。
 */
@Composable
fun ZoomableImage3(
    uri: Uri,
    onEdgeSwipe: (direction: Int) -> Unit,
    onSwipeDownToBack: () -> Unit = {},
    onSwipeUpToShowInfo: () -> Unit = {}
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var inertiaState by remember { mutableStateOf<InertiaParams?>(null) }
    var intrinsicSize by remember { mutableStateOf(Size.Zero) }
    val scope = rememberCoroutineScope()

    val animSpec = inertiaState?.let { state ->
        tween<Float>(durationMillis = state.durationMs, easing = LinearEasing)
    } ?: snap()
    val renderOffsetX by animateFloatAsState(
        targetValue = inertiaState?.targetX ?: offsetX,
        animationSpec = animSpec,
        label = "panX"
    )
    val renderOffsetY by animateFloatAsState(
        targetValue = inertiaState?.targetY ?: offsetY,
        animationSpec = animSpec,
        label = "panY"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(intrinsicSize) {
                awaitEachGesture {
                    // 等待按下，requireUnconsumed=false 不 consume → HorizontalPager 原文传递
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downX = down.position.x
                    val downY = down.position.y
                    val now = System.currentTimeMillis()

                    // 截停惯性
                    if (inertiaState != null) {
                        offsetX = renderOffsetX
                        offsetY = renderOffsetY
                        inertiaState = null
                        lastTapTime = 0L
                        AppLogger.d("Inertia", "interrupted @ (${offsetX.roundToInt()},${offsetY.roundToInt()})")
                    }

                    // 双击检测
                    if (now - lastTapTime < DOUBLE_TAP_TIME_MS) {
                        AppLogger.d("Zoom", "double tap")
                        lastTapTime = 0L
                        if (scale > 1.5f) {
                            scale = 1f; offsetX = 0f; offsetY = 0f
                        } else {
                            val newScale = DOUBLE_TAP_ZOOM
                            scale = newScale
                            val imgH = calcImageFitHeight(size, intrinsicSize)
                            val maxX = size.width * (newScale - 1f) / 2f
                            val maxY = ((imgH * newScale - size.height) / 2f).coerceAtLeast(0f)
                            offsetX = ((size.width / 2f - downX) * (newScale - 1f) / newScale).coerceIn(-maxX, maxX)
                            offsetY = ((size.height / 2f - downY) * (newScale - 1f) / newScale).coerceIn(-maxY, maxY)
                        }
                        // 消费后续所有事件
                        do {
                            val e = awaitPointerEvent(PointerEventPass.Main)
                            e.changes.forEach { it.consume() }
                        } while (e.changes.any { it.pressed })
                        return@awaitEachGesture
                    }

                    var didEdgeSwipe = false
                    var smoothX = 0f
                    var smoothY = 0f
                    var frameCount = 0
                    var hadMovement = false

                    do {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val count = event.changes.size
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val zoomed = scale > 1f

                        if (count >= 2) {
                            // ── 双指缩放：始终 consume ──
                            val ns = (scale * zoom).coerceIn(1f, 5f)
                            scale = ns
                            if (ns <= 1f) {
                                offsetX = 0f; offsetY = 0f
                            } else {
                                val imgH = calcImageFitHeight(size, intrinsicSize)
                                val mx = size.width * (ns - 1f) / 2f
                                val my = ((imgH * ns - size.height) / 2f).coerceAtLeast(0f)
                                offsetX = offsetX.coerceIn(-mx, mx)
                                offsetY = offsetY.coerceIn(-my, my)
                            }
                            event.changes.forEach { it.consume() }
                            frameCount = 0
                            hadMovement = true
                        } else if (zoomed) {
                            // ── 已缩放 + 单指拖拽：consume ──
                            val maxX = size.width * (scale - 1f) / 2f
                            val imgH = calcImageFitHeight(size, intrinsicSize)
                            val maxY = ((imgH * scale - size.height) / 2f).coerceAtLeast(0f)

                            // 方案 C：边缘翻页仅当 X 为主轴向时才触发（|X| >= |Y| * 1.5），
                            // 斜向到边缘允许 Y 继续滑动
                            if (offsetX >= maxX - EDGE_THRESHOLD_PX && pan.x > InertiaSettings.edgeSwipeMinPx && pan.x.absoluteValue >= pan.y.absoluteValue * 1.5f) {
                                didEdgeSwipe = true; onEdgeSwipe(-1); break
                            } else if (offsetX <= -maxX + EDGE_THRESHOLD_PX && pan.x < -InertiaSettings.edgeSwipeMinPx && pan.x.absoluteValue >= pan.y.absoluteValue * 1.5f) {
                                didEdgeSwipe = true; onEdgeSwipe(1); break
                            } else {
                                offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                                offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                                smoothX = smoothX * 0.5f + pan.x * 0.5f
                                smoothY = smoothY * 0.5f + pan.y * 0.5f
                                frameCount++
                                if (pan.x != 0f || pan.y != 0f) hadMovement = true
                                event.changes.forEach { it.consume() }
                            }
                        } else {
                            // ── 未缩放 + 单指：不 consume → 让 HorizontalPager 翻页 ──
                            smoothX = smoothX * 0.5f + pan.x * 0.5f
                            smoothY = smoothY * 0.5f + pan.y * 0.5f
                            frameCount++
                            if (pan.x != 0f || pan.y != 0f) hadMovement = true
                        }
                    } while (event.changes.any { it.pressed })

                    // ── 下滑返回（未缩放）──
                    // 要求 Y 是强主导方向（|Y| >= |X| * 1.5），防止斜向 45° 误触发
                    if (scale <= 1f && frameCount > 0 &&
                        smoothY > SWIPE_DOWN_MIN_VELOCITY &&
                        smoothY.absoluteValue >= smoothX.absoluteValue * 1.5f) {
                        AppLogger.d("Zoom", "swipe down back V(${smoothX.roundToInt()},${smoothY.roundToInt()})")
                        onSwipeDownToBack()
                        return@awaitEachGesture
                    }

                    // ── 上划显示图片信息（未缩放）──
                    if (scale <= 1f && frameCount > 0 &&
                        smoothY < -SWIPE_DOWN_MIN_VELOCITY &&
                        smoothY.absoluteValue >= smoothX.absoluteValue * 1.5f) {
                        AppLogger.d("Zoom", "swipe up info V(${smoothX.roundToInt()},${smoothY.roundToInt()})")
                        onSwipeUpToShowInfo()
                        return@awaitEachGesture
                    }

                    // ── 记录 lastTapTime：有位移的不是 tap，清除双击计时 ──
                    if (!hadMovement) {
                        lastTapTime = now
                    } else {
                        lastTapTime = 0L
                    }

                    // ── 惯性（方案 C：X/Y 独立计算，不再二选一取主轴向）──
                    if (scale > 1f && !didEdgeSwipe && frameCount > 0) {
                        val maxX = size.width * (scale - 1f) / 2f
                        val imgH = calcImageFitHeight(size, intrinsicSize)
                        val maxY = ((imgH * scale - size.height) / 2f).coerceAtLeast(0f)

                        val targetX = (offsetX + smoothX * InertiaSettings.speedMultiplierX / InertiaSettings.decay).coerceIn(-maxX, maxX)
                        val targetY = (offsetY + smoothY * InertiaSettings.speedMultiplierY / InertiaSettings.decay).coerceIn(-maxY, maxY)

                        val maxDist = maxOf((targetX - offsetX).absoluteValue, (targetY - offsetY).absoluteValue)
                        if (maxDist >= 2f) {
                            val durationMs = (maxDist * InertiaSettings.durationMultiplierX)
                                .coerceIn(15f, 500f).toInt()
                            AppLogger.d("Inertia", "V(${smoothX.roundToInt()},${smoothY.roundToInt()}) T(${targetX.roundToInt()},${targetY.roundToInt()}) ${durationMs}ms")
                            inertiaState = InertiaParams(targetX, targetY, durationMs)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
            },
            update = { iv ->
                val loadUri = uri.toString()
                if (iv.tag != loadUri) {
                    iv.tag = loadUri
                    scope.launch {
                        try {
                            val drawable = withContext(Dispatchers.IO) {
                                val source = ImageDecoder.createSource(iv.context.contentResolver, uri)
                                ImageDecoder.decodeDrawable(source)
                            }
                            // 已回主线程 — 检查 URI 是否已变（防异步竞争）
                            if (iv.tag != loadUri) return@launch
                            iv.setImageDrawable(drawable)
                            if (drawable is AnimatedImageDrawable && !drawable.isRunning) {
                                drawable.start()
                            }
                            if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                                intrinsicSize = Size(
                                    drawable.intrinsicWidth.toFloat(),
                                    drawable.intrinsicHeight.toFloat()
                                )
                            }
                        } catch (_: Exception) {
                            if (iv.tag != loadUri) return@launch
                            iv.load(uri) { crossfade(false) }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize().graphicsLayer(
                scaleX = scale, scaleY = scale,
                translationX = renderOffsetX, translationY = renderOffsetY
            )
        )
    }
}
