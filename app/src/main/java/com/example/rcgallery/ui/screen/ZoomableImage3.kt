package com.example.rcgallery.ui.screen

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import coil.load
import com.example.rcgallery.ui.component.InertiaSettings
import com.example.rcgallery.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.absoluteValue
import kotlin.math.min
import kotlin.math.max
import kotlin.math.roundToInt

/** 图片加载质量档位。Preview 用于邻页低采样，Full 用于当前页高质量。 */
enum class LoadTier { Full, Preview }

private const val DOUBLE_TAP_ZOOM = 2.5f
private const val EDGE_THRESHOLD_PX = 3f
private const val DOUBLE_TAP_TIME_MS = 300L

private data class InertiaParams(
    val targetX: Float,
    val targetY: Float,
    val durationMs: Int
)

/**
 * 计算 FIT_CENTER 的实际渲染尺寸和偏移。
 * graphicsLayer 缩放的是整个 AndroidView（boxSize），FIT_CENTER 会让内容
 * 在 View 内居中有留白。边界计算需要减去这些留白，确保内容边缘=屏幕边缘。
 */
private data class FitRender(
    val renderedWidth: Float,
    val renderedHeight: Float,
    val offsetX: Float,   // FIT_CENTER 产生的水平留白偏移
    val offsetY: Float    // FIT_CENTER 产生的垂直留白偏移
)

private fun computeFitRender(boxSize: IntSize, intrinsicSize: Size): FitRender {
    if (intrinsicSize.width <= 0f || intrinsicSize.height <= 0f) {
        return FitRender(boxSize.width.toFloat(), boxSize.height.toFloat(), 0f, 0f)
    }
    val fitScale = min(boxSize.width / intrinsicSize.width, boxSize.height / intrinsicSize.height)
    val rw = intrinsicSize.width * fitScale
    val rh = intrinsicSize.height * fitScale
    return FitRender(rw, rh, (boxSize.width - rw) / 2f, (boxSize.height - rh) / 2f)
}

/** 缩放 S 倍后，内容左/右边缘与屏幕边缘对齐所需的最大水平偏移。 */
private fun maxScrollX(viewW: Float, fit: FitRender, scale: Float): Float {
    return ((viewW * (scale - 1f) / 2f) - (fit.offsetX * scale)).coerceAtLeast(0f)
}

/** 缩放 S 倍后，内容上/下边缘与屏幕边缘对齐所需的最大垂直偏移。 */
private fun maxScrollY(viewH: Float, fit: FitRender, scale: Float): Float {
    return ((viewH * (scale - 1f) / 2f) - (fit.offsetY * scale)).coerceAtLeast(0f)
}

/**
 * 可缩放图片。统一手势处理，不 consume 未缩放的单指事件 → HorizontalPager 正常翻页；
 * 双击/双指缩放/缩放后拖拽 则 consume。
 *
 * @param loadTier 加载质量档位：Full 用 1920px 降采样，Preview 用 480px 降采样
 */
@Composable
fun ZoomableImage3(
    uri: Uri,
    loadTier: LoadTier = LoadTier.Full,
    onEdgeSwipe: (direction: Int) -> Unit,
    onSwipeDownToBack: () -> Unit = {},
    onSwipeUpToShowInfo: () -> Unit = {},
    onSingleTap: () -> Unit = {}
) {
    val context = LocalContext.current

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var inertiaState by remember { mutableStateOf<InertiaParams?>(null) }
    // 原始尺寸（从 OnHeaderDecodedListener 的 info.size 取），手势计算专用
    var rawIntrinsicSize by remember { mutableStateOf(Size.Zero) }
    var drawable by remember { mutableStateOf<Drawable?>(null) }
    var hasDecodeError by remember { mutableStateOf(false) }

    // ── rememberUpdatedState：确保 pointerInput 内在回调重组时不捕获陈旧值 ──
    val currentOnEdgeSwipe by rememberUpdatedState(onEdgeSwipe)
    val currentOnSwipeDownToBack by rememberUpdatedState(onSwipeDownToBack)
    val currentOnSwipeUpToShowInfo by rememberUpdatedState(onSwipeUpToShowInfo)
    val currentOnSingleTap by rememberUpdatedState(onSingleTap)

    // ── LaunchedEffect 解码（自动取消：uri/loadTier 变化或页面离开 → 协程取消）──
    LaunchedEffect(uri, loadTier) {
        drawable = null  // 立即清旧图，防止 stale image 闪烁
        hasDecodeError = false
        try {
            val result = withContext(Dispatchers.IO) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                var rawSize = Size.Zero
                val maxDim = if (loadTier == LoadTier.Full) 1920 else 480
                val d = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                    rawSize = Size(info.size.width.toFloat(), info.size.height.toFloat())
                    val sample = maxOf(1, maxOf(info.size.width, info.size.height) / maxDim)
                    decoder.setTargetSampleSize(sample)
                    AppLogger.d("Zoom", "decode tier=$loadTier raw=${info.size.width}x${info.size.height} sample=$sample")
                }
                Pair(d, rawSize)
            }
            rawIntrinsicSize = result.second
            drawable = result.first
        } catch (e: Exception) {
            AppLogger.d("Zoom", "decode error uri=${uri.lastPathSegment} err=${e.message}")
            // 标记错误，update 中用 Coil fallback
            hasDecodeError = true
        }
    }

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
            // key 用 rawIntrinsicSize（仅在首次解码时 set 一次），不随档位变化 → pointerInput 不会因升级重启
            .pointerInput(rawIntrinsicSize) {
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
                            val fit = computeFitRender(size, rawIntrinsicSize)
                            val mx = maxScrollX(size.width.toFloat(), fit, newScale)
                            val my = maxScrollY(size.height.toFloat(), fit, newScale)
                            offsetX = ((size.width / 2f - downX) * (newScale - 1f) / newScale).coerceIn(-mx, mx)
                            offsetY = ((size.height / 2f - downY) * (newScale - 1f) / newScale).coerceIn(-my, my)
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
                    var verticalDominant = false  // 一旦 Y 方向变为主导，consume 事件阻止 Pager 翻页

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
                                val fit = computeFitRender(size, rawIntrinsicSize)
                                val mx = maxScrollX(size.width.toFloat(), fit, ns)
                                val my = maxScrollY(size.height.toFloat(), fit, ns)
                                AppLogger.d("Zoom", "pinch scale=$ns mx=$mx my=$my size=$size intrinsic=$rawIntrinsicSize fitOff=(${fit.offsetX},${fit.offsetY})")
                                offsetX = offsetX.coerceIn(-mx, mx)
                                offsetY = offsetY.coerceIn(-my, my)
                            }
                            event.changes.forEach { it.consume() }
                            frameCount = 0
                            hadMovement = true
                        } else if (zoomed) {
                            // ── 已缩放 + 单指拖拽：consume ──
                            val fit = computeFitRender(size, rawIntrinsicSize)
                            val maxX = maxScrollX(size.width.toFloat(), fit, scale)
                            val maxY = maxScrollY(size.height.toFloat(), fit, scale)
                            if (frameCount % 5 == 0) {
                                AppLogger.d("Zoom", "drag scale=$scale maxX=$maxX maxY=$maxY off=(${offsetX.roundToInt()},${offsetY.roundToInt()}) pan=(${pan.x.roundToInt()},${pan.y.roundToInt()}) fitOff=(${fit.offsetX},${fit.offsetY})")
                            }

                            if (offsetX >= maxX - EDGE_THRESHOLD_PX && pan.x > InertiaSettings.edgeSwipeMinPx && pan.x.absoluteValue >= pan.y.absoluteValue * 1.5f) {
                                didEdgeSwipe = true; currentOnEdgeSwipe(-1); break
                            } else if (offsetX <= -maxX + EDGE_THRESHOLD_PX && pan.x < -InertiaSettings.edgeSwipeMinPx && pan.x.absoluteValue >= pan.y.absoluteValue * 1.5f) {
                                didEdgeSwipe = true; currentOnEdgeSwipe(1); break
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
                            // ── 未缩放 + 单指 ──
                            smoothX = smoothX * 0.5f + pan.x * 0.5f
                            smoothY = smoothY * 0.5f + pan.y * 0.5f
                            frameCount++
                            if (pan.x != 0f || pan.y != 0f) hadMovement = true
                            if (frameCount >= 3 && smoothY.absoluteValue >= smoothX.absoluteValue * 1.5f) {
                                verticalDominant = true
                            }
                            if (verticalDominant) {
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    // ── 下滑返回（未缩放）──
                    val swipeThresh = InertiaSettings.swipeVelocityThreshold
                    if (scale <= 1f && frameCount > 0 &&
                        smoothY > swipeThresh &&
                        smoothY.absoluteValue >= smoothX.absoluteValue * 1.5f) {
                        AppLogger.d("Zoom", "swipe down back V(${smoothX.roundToInt()},${smoothY.roundToInt()})")
                        currentOnSwipeDownToBack()
                        return@awaitEachGesture
                    }

                    // ── 上划显示图片信息（未缩放）──
                    if (scale <= 1f && frameCount > 0 &&
                        smoothY < -swipeThresh &&
                        smoothY.absoluteValue >= smoothX.absoluteValue * 1.5f) {
                        AppLogger.d("Zoom", "swipe up info V(${smoothX.roundToInt()},${smoothY.roundToInt()})")
                        currentOnSwipeUpToShowInfo()
                        return@awaitEachGesture
                    }

                    // ── 记录 lastTapTime ──
                    if (!hadMovement) {
                        lastTapTime = now
                        currentOnSingleTap()
                    } else {
                        lastTapTime = 0L
                    }

                    // ── 惯性 ──
                    if (scale > 1f && !didEdgeSwipe && frameCount > 0) {
                        val fit = computeFitRender(size, rawIntrinsicSize)
                        val maxX = maxScrollX(size.width.toFloat(), fit, scale)
                        val maxY = maxScrollY(size.height.toFloat(), fit, scale)

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
                if (hasDecodeError) {
                    // ImageDecoder 失败时用 Coil 兜底加载
                    iv.load(uri) { crossfade(false) }
                } else {
                    iv.setImageDrawable(drawable)
                    val ad = drawable
                    if (ad is AnimatedImageDrawable && !ad.isRunning) {
                        ad.start()
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
