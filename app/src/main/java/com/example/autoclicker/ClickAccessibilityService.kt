package com.example.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

class ClickAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: ClickAccessibilityService? = null

        fun stopService() {
            instance?.let {
                it.stopClickingInternal()
                it.removeFloatingWindow()
                it.removeLocateOverlayInternal()
                it.disableSelf()
            }
            instance = null
        }

        fun isRunning(): Boolean = instance != null

        fun isClicking(): Boolean = instance?.isClickingNow == true

        fun stopClicking() {
            instance?.stopClickingInternal()
        }

        fun getClickedCount(): Long = instance?.clickedCount ?: 0L

        fun startClickingWithParams(interval: Long, isInfinite: Boolean, count: Long) {
            instance?.startClickingWithParamsInternal(interval, isInfinite, count)
        }

        fun startRushBuyClicking(x: Int, y: Int, interval: Long, count: Long) {
            instance?.startRushBuyClickingInternal(x, y, interval, count)
        }

        fun showLocateOverlay(x: Int, y: Int) {
            instance?.showLocateOverlayInternal(x, y)
        }

        fun removeLocateOverlay() {
            instance?.removeLocateOverlayInternal()
        }

        fun getLocatedCoordinates(): Pair<Int, Int>? = instance?.locatedCoordinates

        /** 显示悬浮倒计时 */
        fun showFloatingTime(triggerTime: Long) {
            instance?.showFloatingTimeInternal(triggerTime)
        }

        /** 更新悬浮倒计时 */
        fun updateFloatingTime(remaining: Long) {
            instance?.updateFloatingTimeInternal(remaining)
        }

        /** 移除悬浮倒计时 */
        fun removeFloatingTime() {
            instance?.removeFloatingTimeInternal()
        }

        /** 在指定坐标执行模拟点击（供 OCR 服务调用） */
        fun performClickAt(x: Float, y: Float) {
            instance?.performClickAt(x, y)
        }

        /** OCR 模式是否可用（服务运行中） */
        fun isOcrReady(): Boolean = instance != null
    }

    // 连点器悬浮球
    private var floatingView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    // 定位悬浮窗
    private var locateView: View? = null
    private var locateParams: WindowManager.LayoutParams? = null
    private var locatedCoordinates: Pair<Int, Int>? = null

    // 悬浮倒计时
    private var floatingTimeView: View? = null
    private var floatingTimeParams: WindowManager.LayoutParams? = null
    private var floatingTimeTextView: TextView? = null
    private var triggerTimeMs: Long = 0L

    // 通用
    private var windowManager: WindowManager? = null
    private var handler: Handler? = null
    private var clickRunnable: Runnable? = null

    private var isClickingNow = false
    private var intervalMs: Long = 100
    private var isInfiniteMode = true
    private var targetCount: Long = 0
    private var clickedCount: Long = 0

    // 抢购模式
    private var rushBuyX: Int = 0
    private var rushBuyY: Int = 0
    private var isRushBuyMode = false

    // 连点器悬浮球触摸
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var touchDownTime = 0L

    // 定位悬浮窗触摸
    private var locateInitialX = 0
    private var locateInitialY = 0
    private var locateInitialTouchX = 0f
    private var locateInitialTouchY = 0f
    private var locateTouchDownTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val prefs = getSharedPreferences("auto_clicker_prefs", Context.MODE_PRIVATE)
        intervalMs = prefs.getLong("click_interval", 100L)
        isInfiniteMode = prefs.getBoolean("click_infinite", true)
        targetCount = prefs.getLong("click_count", 100L)

        handler = Handler(Looper.getMainLooper())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onDestroy() {
        super.onDestroy()
        stopClickingInternal()
        removeFloatingWindow()
        removeLocateOverlayInternal()
        removeFloatingTimeInternal()
        instance = null
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ==================== 连点器悬浮球 ====================

    private fun showFloatingWindow() {
        if (floatingView != null) return

        val sizePx = dpToPx(60)
        val ball = View(this).apply {
            background = createCircleDrawable(0xCC000000.toInt())
        }

        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (windowManager?.defaultDisplay?.width ?: 1080) / 2 - sizePx / 2
            y = (windowManager?.defaultDisplay?.height ?: 1920) / 2 - sizePx / 2
        }

        layoutParams = params

        ball.setOnTouchListener { _, event ->
            handleFloatingBallTouch(event)
            true
        }

        floatingView = ball
        try {
            windowManager?.addView(ball, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleFloatingBallTouch(event: MotionEvent): Boolean {
        val params = layoutParams ?: return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                touchDownTime = System.currentTimeMillis()
            }

            MotionEvent.ACTION_MOVE -> {
                params.x = initialX + (event.rawX - initialTouchX).toInt()
                params.y = initialY + (event.rawY - initialTouchY).toInt()
                try {
                    windowManager?.updateViewLayout(floatingView, params)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                val duration = System.currentTimeMillis() - touchDownTime

                if (distance < 10f && duration < 300) {
                    if (isClickingNow) {
                        stopClickingInternal()
                    } else {
                        startClickingInternal()
                    }
                }
            }
        }
        return true
    }

    /** 连点器模式：单击悬浮球开始连点 */
    private fun startClickingInternal() {
        if (isClickingNow) return
        isClickingNow = true
        isRushBuyMode = false
        clickedCount = 0

        val prefs = getSharedPreferences("auto_clicker_prefs", Context.MODE_PRIVATE)
        intervalMs = prefs.getLong("click_interval", 100L)
        isInfiniteMode = prefs.getBoolean("click_infinite", true)
        targetCount = prefs.getLong("click_count", 100L)

        updateBallColor(0xCCFF0000.toInt())

        clickRunnable = object : Runnable {
            override fun run() {
                if (!isClickingNow) return

                val params = layoutParams ?: return
                val view = floatingView ?: return
                val clickX = (params.x + view.width / 2).toFloat()
                val clickY = (params.y + view.height / 2).toFloat()
                performClickAt(clickX, clickY)

                clickedCount++
                if (!isInfiniteMode && clickedCount >= targetCount) {
                    stopClickingInternal()
                    return
                }
                handler?.postDelayed(this, intervalMs)
            }
        }
        handler?.post(clickRunnable!!)
    }

    /** 连点器入口：显示悬浮球 */
    private fun startClickingWithParamsInternal(interval: Long, isInfinite: Boolean, count: Long) {
        stopClickingInternal()
        intervalMs = interval
        isInfiniteMode = isInfinite
        targetCount = count

        val prefs = getSharedPreferences("auto_clicker_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("click_interval", interval)
            .putBoolean("click_infinite", isInfinite)
            .putLong("click_count", count)
            .apply()

        if (floatingView == null) {
            showFloatingWindow()
        }
    }

    // ==================== 抢购模式坐标点击 ====================

    private fun startRushBuyClickingInternal(x: Int, y: Int, interval: Long, count: Long) {
        stopClickingInternal()
        isClickingNow = true
        isRushBuyMode = true
        clickedCount = 0
        rushBuyX = x
        rushBuyY = y
        intervalMs = interval
        targetCount = count
        isInfiniteMode = false

        clickRunnable = object : Runnable {
            override fun run() {
                if (!isClickingNow) return

                performClickAt(rushBuyX.toFloat(), rushBuyY.toFloat())
                clickedCount++

                if (clickedCount >= targetCount) {
                    stopClickingInternal()
                    return
                }
                handler?.postDelayed(this, intervalMs)
            }
        }
        handler?.post(clickRunnable!!)
    }

    // ==================== 定位悬浮窗 ====================

    private fun showLocateOverlayInternal(x: Int, y: Int) {
        removeLocateOverlayInternal()
        locatedCoordinates = null

        val sizePx = dpToPx(80)

        val container = FrameLayout(this)

        // 外圈
        val ring = View(this).apply {
            background = createRingDrawable()
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx, Gravity.CENTER)
        }
        container.addView(ring)

        // 中心点
        val centerSize = dpToPx(8)
        val centerDot = View(this).apply {
            background = createCircleDrawable(0xFFFF9800.toInt())
            layoutParams = FrameLayout.LayoutParams(centerSize, centerSize, Gravity.CENTER)
        }
        container.addView(centerDot)

        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x - sizePx / 2
            this.y = y - sizePx / 2
        }

        locateParams = params

        container.setOnTouchListener { _, event ->
            handleLocateTouch(event)
            true
        }

        locateView = container
        try {
            windowManager?.addView(container, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleLocateTouch(event: MotionEvent): Boolean {
        val params = locateParams ?: return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                locateInitialX = params.x
                locateInitialY = params.y
                locateInitialTouchX = event.rawX
                locateInitialTouchY = event.rawY
                locateTouchDownTime = System.currentTimeMillis()
            }

            MotionEvent.ACTION_MOVE -> {
                params.x = locateInitialX + (event.rawX - locateInitialTouchX).toInt()
                params.y = locateInitialY + (event.rawY - locateInitialTouchY).toInt()
                try {
                    windowManager?.updateViewLayout(locateView, params)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - locateInitialTouchX
                val dy = event.rawY - locateInitialTouchY
                val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                val duration = System.currentTimeMillis() - locateTouchDownTime

                if (distance < 10f && duration < 300) {
                    // 单击确认坐标
                    val sizePx = dpToPx(80)
                    locatedCoordinates = Pair(params.x + sizePx / 2, params.y + sizePx / 2)
                    removeLocateOverlayInternal()
                }
            }
        }
        return true
    }

    private fun removeLocateOverlayInternal() {
        try {
            locateView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        locateView = null
        locateParams = null
    }

    // ==================== 悬浮倒计时 ====================

    private fun showFloatingTimeInternal(triggerTime: Long) {
        removeFloatingTimeInternal()
        triggerTimeMs = triggerTime

        val container = FrameLayout(this)
        val tv = TextView(this).apply {
            text = "--:--:-- | ⏱--:--:--.---"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(20, 10, 20, 10)
            val bg = GradientDrawable().apply {
                setColor(0xCC333333.toInt())
                cornerRadius = 20f
            }
            background = bg
        }
        floatingTimeTextView = tv

        val wrap = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        wrap.gravity = Gravity.CENTER
        container.addView(tv, wrap)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 60
        }

        // 拖动
        var dragInitialX = 0
        var dragInitialY = 0
        var dragTouchX = 0f
        var dragTouchY = 0f
        var isDrag = false

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragInitialX = params.x
                    dragInitialY = params.y
                    dragTouchX = event.rawX
                    dragTouchY = event.rawY
                    isDrag = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragTouchX
                    val dy = event.rawY - dragTouchY
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDrag = true
                    params.x = dragInitialX + dx.toInt()
                    params.y = dragInitialY + dy.toInt()
                    try { windowManager?.updateViewLayout(container, params) } catch (_: Exception) {}
                }
            }
            isDrag
        }

        floatingTimeParams = params
        floatingTimeView = container
        try {
            windowManager?.addView(container, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateFloatingTimeInternal(remaining: Long) {
        val tv = floatingTimeTextView ?: return
        val totalMs = remaining
        val secs = totalMs / 1000
        val ms = (totalMs % 1000)
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        // 当前时间（含毫秒）
        val now = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
            .format(java.util.Date())
        try {
            tv.text = String.format("%s | ⏱%02d:%02d:%02d.%03d", now, h, m, s, ms)
        } catch (_: Exception) {}
    }

    private fun removeFloatingTimeInternal() {
        try {
            floatingTimeView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        floatingTimeView = null
        floatingTimeParams = null
        floatingTimeTextView = null
    }

    // ==================== 通用方法 ====================

    private fun stopClickingInternal() {
        isClickingNow = false
        isRushBuyMode = false
        clickRunnable?.let { handler?.removeCallbacks(it) }
        clickRunnable = null
        removeFloatingWindow()
    }

    /** 在指定坐标执行模拟点击手势 */
    private fun performClickAt(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 1L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        try {
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateBallColor(color: Int) {
        try {
            floatingView?.background = createCircleDrawable(color)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeFloatingWindow() {
        try {
            floatingView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        floatingView = null
        layoutParams = null
    }

    private fun createCircleDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun createRingDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x00000000) // 透明填充
            setStroke(dpToPx(3), 0xFFFF9800.toInt()) // 橙色描边
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
