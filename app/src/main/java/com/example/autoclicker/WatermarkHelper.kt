package com.example.autoclicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * 水印工具类：在 Activity 根布局上叠加斜纹水印
 *
 * ====== 水印配置区 ======
 * 修改以下两个常量即可控制水印行为：
 *   ENABLED  - true 开启水印，false 关闭水印
 *   TEXT     - 水印显示的文字内容
 * ========================
 */
object WatermarkHelper {

    /** 水印开关：true=开启，false=关闭 */
    private const val ENABLED = true

    /** 水印文字内容 */
    private const val TEXT = "pandie"

    fun shouldApply(): Boolean = ENABLED

    fun getWatermarkText(): String = TEXT

    /**
     * 给 Activity 应用水印，需在 setContentView 之后调用
     */
    fun apply(activity: androidx.appcompat.app.AppCompatActivity) {
        if (!ENABLED) return

        val rootView = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        // 移除旧水印
        val old = rootView.findViewWithTag<WatermarkView>("watermark_overlay")
        old?.let { rootView.removeView(it) }

        val watermark = WatermarkView(activity, TEXT).apply {
            tag = "watermark_overlay"
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        watermark.isClickable = false
        rootView.addView(watermark)
    }

    /**
     * 移除当前 Activity 上的水印
     */
    fun remove(activity: androidx.appcompat.app.AppCompatActivity) {
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val old = rootView.findViewWithTag<WatermarkView>("watermark_overlay")
        old?.let { rootView.removeView(it) }
    }
}

/**
 * 自定义水印 View，绘制 45° 斜纹重复文字
 */
class WatermarkView @JvmOverloads constructor(
    context: Context,
    private val watermarkText: String = "Watermark",
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A000000") // 10% 不透明黑色
        textSize = 36f
        textAlign = Paint.Align.LEFT
    }

    private val spacingX = 300
    private val spacingY = 200
    private val angle = -30f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.save()
        canvas.rotate(angle, width / 2f, height / 2f)

        val diagonal = Math.sqrt((width * width + height * height).toDouble()).toFloat()
        val startX = -diagonal
        val startY = -diagonal
        val endX = diagonal * 2
        val endY = diagonal * 2

        var y = startY
        while (y < endY) {
            var x = startX
            while (x < endX) {
                canvas.drawText(watermarkText, x, y, paint)
                x += spacingX
            }
            y += spacingY
        }

        canvas.restore()
    }
}
