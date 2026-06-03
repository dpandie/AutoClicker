package com.example.autoclicker

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

/**
 * 屏幕截屏前台服务
 * 负责 MediaProjection 管理、事件驱动截屏、OCR 识别和自动点击
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCapture"

        private const val CHANNEL_ID = "screen_capture_channel"
        private const val NOTIFICATION_ID = 1001

        // Intent 参数键
        const val EXTRA_TARGET_TEXT = "target_text"
        const val EXTRA_SCAN_INTERVAL = "scan_interval"
        const val EXTRA_CLICK_COUNT = "click_count"
        const val EXTRA_EXACT_MATCH = "exact_match"
        const val EXTRA_CLICK_INTERVAL = "click_interval"

        // 操作类型
        const val ACTION_START = "action_start"
        const val ACTION_STOP = "action_stop"

        // MediaProjection 授权数据必须通过静态变量传递
        @Volatile
        var projectionResultCode: Int = -1
        var projectionResultData: Intent? = null

        // 状态查询
        @Volatile
        var isRunning = false
            private set

        @Volatile
        var lastOcrText: String = ""
            private set

        @Volatile
        var lastMatchResult: OcrClickEngine.MatchResult? = null
            private set

        @Volatile
        var ocrClickCount: Long = 0
            private set

        @Volatile
        var ocrScanCount: Long = 0
            private set

        // ===== 诊断日志 =====
        @Volatile
        var diagLog: String = ""
            private set

        private fun diag(msg: String) {
            Log.d(TAG, msg)
            val ts = System.currentTimeMillis() % 100000
            diagLog = "[$ts] $msg\n" + diagLog
            // 保留最近 30 条
            val lines = diagLog.split("\n")
            if (lines.size > 31) {
                diagLog = lines.take(30).joinToString("\n")
            }
        }

        fun clearDiagLog() {
            diagLog = ""
        }

        fun stopCapture() {
            isRunning = false
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = 1

    private var targetText = ""
    private var scanInterval = 500L
    private var clickCount = 1L
    private var exactMatch = false
    private var clickInterval = 100L

    private var clickedCount = 0L
    private var isClickingAfterMatch = false
    private var isProcessing = false

    private var lastProcessTime = 0L
    private var frameAvailableCount = 0L
    private var frameNullCount = 0L
    private var bitmapNullCount = 0L

    // ===== 悬浮停止按钮 =====
    private var floatingView: View? = null
    private var windowManager: WindowManager? = null

    private val handler = Handler(Looper.getMainLooper())
    private var clickRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        diag("Service.onCreate()")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        diag("onStartCommand action=${intent?.action}")

        if (intent?.action == ACTION_STOP) {
            diag("→ ACTION_STOP, stopping self")
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_START) {
            val resultCode = projectionResultCode
            val resultData = projectionResultData
            projectionResultData = null

            targetText = intent.getStringExtra(EXTRA_TARGET_TEXT) ?: ""
            scanInterval = intent.getLongExtra(EXTRA_SCAN_INTERVAL, 500L).coerceIn(200L, 10000L)
            clickCount = intent.getLongExtra(EXTRA_CLICK_COUNT, 1L).coerceIn(1L, 99999L)
            exactMatch = intent.getBooleanExtra(EXTRA_EXACT_MATCH, false)
            clickInterval = intent.getLongExtra(EXTRA_CLICK_INTERVAL, 100L).coerceIn(50L, 60000L)

            diag("params: target=\"$targetText\" interval=${scanInterval}ms count=$clickCount exact=$exactMatch")
            diag("projection: resultCode=$resultCode data=${resultData != null}")

            // 启动前台通知（必须在 getMediaProjection 之前）
            val notification = buildNotification("OCR识别运行中...")
            startForeground(NOTIFICATION_ID, notification)
            diag("startForeground() OK")

            if (resultCode == Activity.RESULT_OK && resultData != null) {
                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

                if (mediaProjection != null) {
                    diag("✅ getMediaProjection 成功!")
                    setupVirtualDisplay()
                    isRunning = true
                    showFloatingStopButton()
                    diag("✅ 服务启动完成，等待帧...")
                } else {
                    diag("❌ getMediaProjection 返回 null! resultCode=$resultCode")
                    stopSelf()
                }
            } else {
                diag("❌ 缺少投影授权: resultCode=$resultCode, data=${resultData != null}")
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        diag("Service.onDestroy()")
        isRunning = false
        stopClickLoop()
        releaseVirtualDisplay()
        removeFloatingStopButton()
        mediaProjection?.stop()
        mediaProjection = null
        ocrScanCount = 0
        ocrClickCount = 0
        lastOcrText = ""
        lastMatchResult = null
        frameAvailableCount = 0
        frameNullCount = 0
        bitmapNullCount = 0
    }

    // ==================== 悬浮停止按钮 ====================

    private fun showFloatingStopButton() {
        if (floatingView != null) return

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val container = FrameLayout(this)
        val btn = TextView(this).apply {
            text = "■ 停止"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(24, 12, 24, 12)
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
        }

        val bg = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xDDFF4444.toInt())
            cornerRadius = 28f
        }
        btn.background = bg

        val wrap = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        wrap.gravity = Gravity.CENTER
        container.addView(btn, wrap)
        container.setPadding(4, 4, 4, 4)

        // 点击停止
        btn.setOnClickListener {
            diag("悬浮按钮 → 停止")
            stopCapture()
            val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            startService(stopIntent)
        }

        // 拖动支持
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = (container.tag as? IntArray)?.get(0) ?: 0
                    initialY = (container.tag as? IntArray)?.get(1) ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDragging = true
                    val params = container.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    container.tag = intArrayOf(params.x, params.y)
                    windowManager?.updateViewLayout(container, params)
                }
            }
            isDragging
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 100
        }

        container.tag = intArrayOf(params.x, params.y)
        windowManager?.addView(container, params)
        floatingView = container
        diag("✅ 悬浮停止按钮已显示")
    }

    private fun removeFloatingStopButton() {
        floatingView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
            floatingView = null
            diag("悬浮停止按钮已移除")
        }
    }

    // ==================== 虚拟显示器 ====================

    private fun setupVirtualDisplay() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        diag("screen: ${screenWidth}x${screenHeight} density=$screenDensity")

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        diag("ImageReader 创建: ${screenWidth}x${screenHeight} format=RGBA_8888")

        imageReader?.setOnImageAvailableListener({ reader ->
            frameAvailableCount++
            if (frameAvailableCount <= 5) {
                diag("帧到达 #${frameAvailableCount}")
            }

            if (!isRunning) {
                val img = reader.acquireLatestImage()
                img?.close()
                return@setOnImageAvailableListener
            }

            val now = System.currentTimeMillis()

            if (now - lastProcessTime >= scanInterval && !isProcessing && !isClickingAfterMatch) {
                lastProcessTime = now
                processFrame(reader)
            } else {
                val img = reader.acquireLatestImage()
                img?.close()
            }
        }, handler)

        val surface = imageReader?.surface
        diag("ImageReader.surface: ${surface != null}")

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, handler
        )

        if (virtualDisplay != null) {
            diag("✅ VirtualDisplay 创建成功")
        } else {
            diag("❌ VirtualDisplay 创建失败! mediaProjection=${mediaProjection != null}")
        }
    }

    private fun releaseVirtualDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
    }

    // ==================== 帧处理 ====================

    private fun processFrame(reader: ImageReader) {
        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image == null) {
                frameNullCount++
                if (frameNullCount <= 3) {
                    diag("⚠️ acquireLatestImage 返回 null (第${frameNullCount}次)")
                }
                return
            }

            val imgW = image.width
            val imgH = image.height
            val planes = image.planes
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride

            val bitmap = imageToBitmap(image)
            image.close()
            image = null

            if (bitmap == null) {
                bitmapNullCount++
                if (bitmapNullCount <= 3) {
                    diag("⚠️ imageToBitmap 返回 null (第${bitmapNullCount}次) imgSize=${imgW}x${imgH} pixelStride=$pixelStride rowStride=$rowStride")
                }
                return
            }

            ocrScanCount++

            if (ocrScanCount <= 3) {
                diag("帧处理 #${ocrScanCount}: bitmap=${bitmap.width}x${bitmap.height}")
            }

            isProcessing = true

            OcrClickEngine.recognizeAndMatch(bitmap, targetText, exactMatch) { result ->
                isProcessing = false
                lastOcrText = result.allText
                lastMatchResult = result

                if (ocrScanCount <= 5 || result.matched) {
                    val textPreview = if (result.allText.length > 80) result.allText.take(80) + "..." else result.allText
                    diag("OCR #${ocrScanCount}: matched=${result.matched} textBlocks=${OcrClickEngine.lastBlockCount} text=\"${textPreview}\"")
                }

                if (result.matched && !isClickingAfterMatch) {
                    diag("🎯 匹配成功! center=(${result.centerX},${result.centerY}) rect=${result.boundingRect}")
                    onTextMatched(result)
                }

                updateNotification(
                    if (result.matched) "已找到: \"${result.targetText}\""
                    else "扫描中... (第${ocrScanCount}次)"
                )
            }
        } catch (e: Exception) {
            isProcessing = false
            diag("❌ processFrame 异常: ${e.message}")
            e.printStackTrace()
        } finally {
            image?.close()
        }
    }

    /**
     * Image 转 Bitmap，处理行填充
     */
    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmapWidth = screenWidth + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(bitmapWidth, screenHeight, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)

            if (rowPadding != 0) {
                Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "imageToBitmap failed", e)
            null
        }
    }

    // ==================== 文字匹配后点击 ====================

    private fun onTextMatched(result: OcrClickEngine.MatchResult) {
        isClickingAfterMatch = true
        clickedCount = 0

        clickRunnable = object : Runnable {
            override fun run() {
                if (!isRunning || clickedCount >= clickCount) {
                    diag("点击完成: clicked=$clickedCount/$clickCount")
                    stopClickLoop()
                    return
                }

                ClickAccessibilityService.performClickAt(
                    result.centerX.toFloat(),
                    result.centerY.toFloat()
                )

                clickedCount++
                ocrClickCount++

                handler.postDelayed(this, clickInterval)
            }
        }
        handler.post(clickRunnable!!)
    }

    private fun stopClickLoop() {
        clickRunnable?.let { handler.removeCallbacks(it) }
        clickRunnable = null
        isClickingAfterMatch = false
    }

    // ==================== 通知 ====================

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OCR屏幕识别服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "OCR文字识别与自动点击服务运行通知"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoClicker OCR")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
