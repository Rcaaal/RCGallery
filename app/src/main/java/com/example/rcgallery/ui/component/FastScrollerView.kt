package com.example.rcgallery.ui.component

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

/**
 * 可拖拽的快速滑动条，叠加在 RecyclerView 右侧。
 *
 * - 固定大小的药丸形滑块，手指可按住拖拽
 * - 深色系设计：细轨道 + 半透明白色滑块
 * - 静止 1.5 秒后自动淡出，滚动或触摸时淡入
 */
class FastScrollerView(
    context: Context,
    private val recyclerView: RecyclerView
) : View(context) {

    // ── 视觉尺寸（dp → px）──
    private val density = context.resources.displayMetrics.density
    private val trackWidthPx = (2 * density).toInt()
    private val thumbWidthPx = (20 * density).toInt()
    private val thumbHeightPx = (36 * density).toInt()
    private val touchRangePx = (32 * density).toInt()
    private val thumbRadiusPx = thumbWidthPx / 2f

    // ── Paint ──
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(50, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(180, 255, 255, 255)
        style = Paint.Style.FILL
    }
    // 拖拽时更亮
    private val thumbActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(220, 255, 255, 255)
        style = Paint.Style.FILL
    }

    // ── 滑块位置 ──
    private var thumbCenterY = 0f
    private var isDragging = false
    private var dragOffsetY = 0f

    // ── 淡入淡出 ──
    private val hideHandler = Handler(Looper.getMainLooper())
    private val HIDE_DELAY_MS = 1500L
    private val FADE_DURATION_MS = 300L
    private var currentAlpha = 0f
    private var alphaAnimator: ValueAnimator? = null

    // ── 缓存 RectF 避免 GC ──
    private val trackRect = RectF()
    private val thumbRect = RectF()

    init {
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (!isDragging) updateThumb()
                showWithTimeout()
            }
        })
        // 初始隐藏
        alpha = 0f
    }

    /**
     * 当列表数据变更后调用，刷新 thumb 位置。
     */
    fun refresh() {
        updateThumb()
    }

    // ══════════════════════════════════════
    //  显示/隐藏 控制
    // ══════════════════════════════════════

    private fun showWithTimeout() {
        cancelHide()
        fadeTo(1f)
        hideHandler.postDelayed({ fadeTo(0f) }, HIDE_DELAY_MS)
    }

    private fun fadeTo(target: Float) {
        alphaAnimator?.cancel()
        if (alpha == target) return
        alphaAnimator = ValueAnimator.ofFloat(alpha, target).apply {
            duration = FADE_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                alpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun cancelHide() {
        hideHandler.removeCallbacksAndMessages(null)
    }

    // ══════════════════════════════════════
    //  thumb 位置计算（固定大小）
    // ══════════════════════════════════════

    private fun updateThumb() {
        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
        if (height == 0 || width == 0) return

        val totalItems = lm.itemCount
        val visibleItems = lm.childCount
        if (totalItems <= 0 || visibleItems <= 0) {
            visibility = GONE
            return
        }

        // 全部可见 → 隐藏
        if (visibleItems >= totalItems) {
            visibility = GONE
            return
        }
        visibility = VISIBLE

        // thumb 中心位置（按进度走）
        val scrollRange = totalItems - visibleItems
        val scrollOffset = lm.findFirstVisibleItemPosition().coerceIn(0, scrollRange)
        val progress = if (scrollRange > 0) scrollOffset.toFloat() / scrollRange else 0f
        thumbCenterY = progress * (height - thumbHeightPx) + thumbHeightPx / 2f

        invalidate()
    }

    // ══════════════════════════════════════
    //  绘制
    // ══════════════════════════════════════

    override fun onDraw(canvas: Canvas) {
        // 滑块中心不在有效范围则跳过
        if (thumbCenterY <= 0f) return
        val viewHeight = height.toFloat()
        if (viewHeight <= 0f) return

        // ── 轨道（右侧细线，居中对齐滑块宽度）──
        val thumbMidX = width - thumbWidthPx / 2f
        val trackLeft = thumbMidX - trackWidthPx / 2f
        trackRect.set(trackLeft, 0f, trackLeft + trackWidthPx, viewHeight)
        canvas.drawRoundRect(trackRect, trackWidthPx / 2f, trackWidthPx / 2f, trackPaint)

        // ── 滑块（固定大小药丸形）──
        val thumbLeft = (width - thumbWidthPx).toFloat()
        val thumbTop = thumbCenterY - thumbHeightPx / 2f
        thumbRect.set(thumbLeft, thumbTop, thumbLeft + thumbWidthPx, thumbTop + thumbHeightPx)
        canvas.drawRoundRect(thumbRect, thumbRadiusPx, thumbRadiusPx, if (isDragging) thumbActivePaint else thumbPaint)
    }

    // ══════════════════════════════════════
    //  触摸处理
    // ══════════════════════════════════════

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (visibility != VISIBLE) return false
                if (event.x < width - touchRangePx) return false
                isDragging = true
                dragOffsetY = event.y - thumbCenterY
                doDrag(event.y)
                showWithTimeout()
                recyclerView.parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return false
                doDrag(event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun doDrag(fingerY: Float) {
        val viewHeight = height.toFloat()
        if (viewHeight <= 0f) return
        val totalItems = recyclerView.layoutManager?.itemCount ?: return
        if (totalItems <= 0) return

        thumbCenterY = (fingerY - dragOffsetY)
            .coerceIn(thumbHeightPx / 2f, viewHeight - thumbHeightPx / 2f)

        val progress = (thumbCenterY - thumbHeightPx / 2f) / (viewHeight - thumbHeightPx)
        val targetPos = (progress * (totalItems - 1)).roundToInt()
            .coerceIn(0, totalItems - 1)
        recyclerView.layoutManager?.scrollToPosition(targetPos)
        invalidate()
    }
}
