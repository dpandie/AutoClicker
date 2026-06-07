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

        fun showFloatingTime(triggerTime: Long) {
            instance?.showFloatingTimeInternal(triggerTime)
        }

        fun updateFloatingTime(remaining: Long) {
            instance?.updateFloatingTimeInternal(remaining)
        }

        fun removeFloatingTime() {
            instance?.removeFloatingTimeInternal()
        }

        fun performClickAt(x: Float, y: Float) {
            instance?.performClickAt(x, y)
        }

        fun isOcrReady(): Boolean = instance != null
    }

    // ==================== 悬浮球相关 ====================
    private var floatingView: View? = null
    private var floatingBallSizePx = 0

    // 停止按钮（连点运行时显示）
    private var stopBtnView: View? = null

    // ==================== 定位悬浮窗 ====================
    private var locateView: View? = null
    private var locateParams: WindowManager.LayoutParams? = null
    private var locatedCoordinates: Pair<Int, Int>? = null
    private var locateBallSizePx = 0

    // ==================== 悬浮倒计时 ====================
    private var floatingTimeView: View? = null
    private var floatingTimeParams: WindowManager.LayoutParams? = null
    private var floatingTimeTextView: TextView? = null
    private var triggerTimeMs: Long = 0L

    // ==================== 通用 ====================
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

    // 触摸拖拽状态
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var touchDownTime = 0L
    private var isLongPressTriggered = false

    // 长按删除相关
    private val longPressTimeout = 800L
    private val longPressRunnable = Runnable {
        isLongPressTriggered = true
        removeFloatingWindow()
    }

    // 定位悬浮窗触摸
    private var locateInitialX = 0
    private var locateInitialY = 0
    private var locateInitialTouchX = 0f
    private var locateInitialTouchY = 0f
    private var locateTouchDownTime = 0L

    // ==================== 生命周期 ====================

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

    // ==================== 悬浮球（三态交互）====================
    //
    // 空闲态：悬浮球可拖动 + 可点击（点击进入连点态）
    // 连点态：悬浮球 FLAG_NOT_TOUCHABLE（让 dispatchGesture 穿透）+ 显示停止按钮
    // 停止态：恢复空闲态
    //

    private fun showFloatingWindow() {
        if (floatingView != null) return

        floatingBallSizePx = dpToPx(60)
        val ball = FloatingButtonView(this).apply {
            number = 1
            isActive = false
        }

        val params = createOverlayParams(floatingBallSizePx, floatingBallSizePx).apply {
            x = (windowManager?.defaultDisplay?.width ?: 1080) / 2 - floatingBallSizePx / 2
            y = (windowManager?.defaultDisplay?.height ?: 1920) / 2 - floatingBallSizePx / 2
        }

        ball.setOnTouchListener { v, event ->
            handleBallTouch(v, event)
        }

        floatingView = ball
        try { windowManager?.addView(ball, params) } catch (e: Exception) { e.printStackTrace() }
    }

    private fun handleBallTouch(view: View, event: MotionEvent): Boolean {
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return false
        val action = event.actionMasked

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = lp.x
                initialY = lp.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                touchDownTime = System.currentTimeMillis()
                isLongPressTriggered = false
                handler?.postDelayed(longPressRunnable, longPressTimeout)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                // 移动超过阈值则取消长按
                if (dist > 10f) {
                    handler?.removeCallbacks(longPressRunnable)
                }
                if (!isLongPressTriggered) {
                    lp.x = initialX + dx.toInt()
                    lp.y = initialY + dy.toInt()
                    try { windowManager?.updateViewLayout(view, lp) } catch (_: Exception) {}
                }
            }

            MotionEvent.ACTION_UP -> {
                handler?.removeCallbacks(longPressRunnable)
                if (isLongPressTriggered) {
                    isLongPressTriggered = false
                    return true
                }
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                val dur = System.currentTimeMillis() - touchDownTime

                if (dist < 15f && dur < 500) {
                    // 点击 → 开始连点
                    startClickingInternal()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                handler?.removeCallbacks(longPressRunnable)
                isLongPressTriggered = false
            }
        }
        return true
    }

    // ==================== 连点器核心 ====================

    private fun startClickingInternal() {
        if (isClickingNow) return
        isClickingNow = true
        isRushBuyMode = false
        clickedCount = 0

        val prefs = getSharedPreferences("auto_clicker_prefs", Context.MODE_PRIVATE)
        intervalMs = prefs.getLong("click_interval", 100L)
        isInfiniteMode = prefs.getBoolean("click_infinite", true)
        targetCount = prefs.getLong("click_count", 100L)

        // 1. 球变红
        (floatingView as? FloatingButtonView)?.isActive = true

        // 2. 球设为不可触摸（让 dispatchGesture 穿透到下层）
        setBallTouchable(false)

        // 3. 显示停止按钮
        showStopButton()

        // 4. 开始连点循环
        clickRunnable = object : Runnable {
            override fun run() {
                if (!isClickingNow) return

                val view = floatingView ?: return
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                val w = if (view.width > 0) view.width else floatingBallSizePx
                val h = if (view.height > 0) view.height else floatingBallSizePx
                val clickX = (loc[0] + w / 2).toFloat()
                val clickY = (loc[1] + h / 2).toFloat()
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

    private fun stopClickingInternal() {
        isClickingNow = false
        isRushBuyMode = false
        clickRunnable?.let { handler?.removeCallbacks(it) }
        clickRunnable = null

        // 恢复球为空闲态
        (floatingView as? FloatingButtonView)?.isActive = false
        setBallTouchable(true)
        removeStopButton()
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

    // ==================== 停止按钮 ====================

    private fun showStopButton() {
        if (stopBtnView != null) return
        val size = dpToPx(36)

        val btn = TextView(this).apply {
            text = "■"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            val bg = GradientDrawable().apply {
                setColor(0xCCFF4444.toInt())
                cornerRadius = 8f * resources.displayMetrics.density
            }
            background = bg
        }

        // 停止按钮放在球的右侧
        val ballLp = floatingView?.layoutParams as? WindowManager.LayoutParams
        val x = (ballLp?.x ?: 0) + floatingBallSizePx + dpToPx(8)
        val y = (ballLp?.y ?: 0) + (floatingBallSizePx - size) / 2

        val params = createOverlayParams(size, size).apply {
            this.x = x
            this.y = y
        }

        btn.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                stopClickingInternal()
            }
            true
        }

        stopBtnView = btn
        try { windowManager?.addView(btn, params) } catch (_: Exception) {}
    }

    private fun removeStopButton() {
        try { stopBtnView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        stopBtnView = null
    }

    private fun setBallTouchable(touchable: Boolean) {
        val view = floatingView ?: return
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        if (touchable) {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try { windowManager?.updateViewLayout(view, lp) } catch (_: Exception) {}
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

        locateBallSizePx = dpToPx(80)

        val ball = FloatingButtonView(this).apply {
            number = 1
            ringColor = 0xFFFF9800.toInt()  // 橙色边框，定位风格
            textColor = 0xFFFF9800.toInt()
            isActive = false
        }

        val params = createOverlayParams(locateBallSizePx, locateBallSizePx).apply {
            this.x = x - locateBallSizePx / 2
            this.y = y - locateBallSizePx / 2
        }

        locateParams = params

        ball.setOnTouchListener { _, event ->
            handleLocateTouch(event)
            true
        }

        locateView = ball
        try { windowManager?.addView(ball, params) } catch (e: Exception) { e.printStackTrace() }
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
                try { windowManager?.updateViewLayout(locateView, params) } catch (e: Exception) { e.printStackTrace() }
            }

            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - locateInitialTouchX
                val dy = event.rawY - locateInitialTouchY
                val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                val duration = System.currentTimeMillis() - locateTouchDownTime

                if (distance < 10f && duration < 300) {
                    // 点击确认 → 记录中心坐标并移除
                    val loc = IntArray(2)
                    locateView?.getLocationOnScreen(loc)
                    val w = locateView?.width?.takeIf { it > 0 } ?: locateBallSizePx
                    val h = locateView?.height?.takeIf { it > 0 } ?: locateBallSizePx
                    locatedCoordinates = Pair(loc[0] + w / 2, loc[1] + h / 2)
                    removeLocateOverlayInternal()
                }
            }
        }
        return true
    }

    private fun removeLocateOverlayInternal() {
        try { locateView?.let { windowManager?.removeView(it) } } catch (e: Exception) { e.printStackTrace() }
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
        try { windowManager?.addView(container, params) } catch (e: Exception) { e.printStackTrace() }
    }

    private fun updateFloatingTimeInternal(remaining: Long) {
        val tv = floatingTimeTextView ?: return
        val totalMs = remaining
        val secs = totalMs / 1000
        val ms = (totalMs % 1000)
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        val now = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
            .format(java.util.Date())
        try {
            tv.text = String.format("%s | ⏱%02d:%02d:%02d.%03d", now, h, m, s, ms)
        } catch (_: Exception) {}
    }

    private fun removeFloatingTimeInternal() {
        try { floatingTimeView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        floatingTimeView = null
        floatingTimeParams = null
        floatingTimeTextView = null
    }

    // ==================== 通用方法 ====================

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

    private fun removeFloatingWindow() {
        handler?.removeCallbacks(longPressRunnable)
        removeStopButton()
        try { floatingView?.let { windowManager?.removeView(it) } } catch (e: Exception) { e.printStackTrace() }
        floatingView = null
        isLongPressTriggered = false
    }

    /** 创建标准 overlay LayoutParams */
    private fun createOverlayParams(width: Int, height: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
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
            setColor(0x00000000)
            setStroke(dpToPx(3), 0xFFFF9800.toInt())
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
