package com.example.rcgallery.ui.component

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.rcgallery.data.db.TagEntity

/**
 * 构建相册列表模式下的 TAG 横向可滚动条。
 * 单行、不换行、超出可滑动。
 */
object AlbumTagStripBuilder {

    /**
     * 创建一个横向可滚动的 TAG 条，添加到 parent LinearLayout 中。
     * @param parent 父容器（ListVH 的 textColumn，VERTICAL 方向）
     * @param tags 该相册的所有 TAG
     * @param onTagClick TAG 点击回调
     */
    fun addTagStrip(
        parent: LinearLayout,
        tags: List<TagEntity>,
        position: Int,
        onTagClick: (pos: Int) -> Unit
    ) {
        // 移除旧的 tag strip（如有）
        val oldTag = parent.findViewWithTag<HorizontalScrollView>("tag_strip_${position}")
        if (oldTag != null) {
            // 更新已有 tag strip
            updateTagStrip(oldTag, tags)
            return
        }

        if (tags.isEmpty()) return

        val ctx = parent.context
        val density = ctx.resources.displayMetrics.density
        val hsv = HorizontalScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isHorizontalScrollBarEnabled = false
            tag = "tag_strip_${position}"
            val tagRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            tags.forEach { tag ->
                val chip = createTagChip(ctx, tag.name, density)
                chip.setOnClickListener { onTagClick(position) }
                tagRow.addView(chip)
            }
            // + 按钮
            val addChip = createAddChip(ctx, density)
            addChip.setOnClickListener { onTagClick(position) }
            tagRow.addView(addChip)

            addView(tagRow)
        }

        // 插入到父容器（在 infoTv 之前，nameTv 之后 = 子视图 index 1 之后）
        val insertIndex = if (parent.childCount >= 2) 1 else parent.childCount
        parent.addView(hsv, insertIndex)
    }

    /** 更新已有 tag strip 的内容 */
    private fun updateTagStrip(hsv: HorizontalScrollView, tags: List<TagEntity>) {
        val tagRow = hsv.getChildAt(0) as? LinearLayout ?: return
        tagRow.removeAllViews()
        val ctx = hsv.context
        val density = ctx.resources.displayMetrics.density
        tags.forEach { tag ->
            val chip = createTagChip(ctx, tag.name, density)
            tagRow.addView(chip)
        }
        // + 按钮
        val addChip = createAddChip(ctx, density)
        tagRow.addView(addChip)
    }

    private fun createTagChip(ctx: android.content.Context, name: String, density: Float): TextView {
        return TextView(ctx).apply {
            text = name
            textSize = 10f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundDrawable(
                android.graphics.drawable.GradientDrawable().apply {
                    setShape(android.graphics.drawable.GradientDrawable.RECTANGLE)
                    setCornerRadius(6 * density)
                    setColor(android.graphics.Color.argb(180, 100, 140, 255))
                }
            )
            setPadding(
                (6 * density).toInt(),
                (2 * density).toInt(),
                (6 * density).toInt(),
                (2 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, (3 * density).toInt(), (4 * density).toInt(), 0) }
            maxLines = 1
            isClickable = true
            focusable = View.FOCUSABLE
        }
    }

    private fun createAddChip(ctx: android.content.Context, density: Float): TextView {
        return TextView(ctx).apply {
            text = "+"
            textSize = 12f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundDrawable(
                android.graphics.drawable.GradientDrawable().apply {
                    setShape(android.graphics.drawable.GradientDrawable.OVAL)
                    setColor(android.graphics.Color.argb(180, 100, 180, 100))
                }
            )
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                (20 * density).toInt(),
                (20 * density).toInt()
            ).apply { setMargins(0, (3 * density).toInt(), 0, 0) }
            isClickable = true
            focusable = View.FOCUSABLE
        }
    }
}
