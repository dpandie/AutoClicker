package com.example.autoclicker

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.cardview.widget.CardView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class AutoClickerActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_SCREEN_CAPTURE = 1001
    }

    // 标题栏
    private lateinit var btnBack: ImageView

    // 模式切换
    private lateinit var btnModeCoordinate: TextView
    private lateinit var btnModeOcr: TextView
    private lateinit var layoutCoordinateMode: LinearLayout
    private lateinit var layoutOcrMode: LinearLayout

    // 坐标模式控件
    private lateinit var etInterval: EditText
    private lateinit var btnIntervalMinus: TextView
    private lateinit var btnIntervalPlus: TextView
    private lateinit var switchInfinite: Switch
    private lateinit var layoutCountInput: LinearLayout
    private lateinit var etCount: EditText
    private lateinit var btnCountMinus: TextView
    private lateinit var btnCountPlus: TextView
    private lateinit var tvRunningStatus: TextView
    private lateinit var tvClickCount: TextView
    private lateinit var btnStartStop: Button
    private lateinit var tvFloatingHint: TextView

    // OCR 模式控件
    private lateinit var etTargetText: EditText
    private lateinit var switchExactMatch: Switch
    private lateinit var etOcrScanInterval: EditText
    private lateinit var etOcrClickCount: EditText
    private lateinit var etOcrClickInterval: EditText
    private lateinit var tvOcrStatus: TextView
    private lateinit var tvOcrScanCount: TextView
    private lateinit var tvOcrMatchInfo: TextView
    private lateinit var tvOcrClickCountDisplay: TextView
    private lateinit var btnOcrStartStop: Button
    private lateinit var tvOcrHint: TextView
    private lateinit var cardDiagLog: CardView
    private lateinit var tvDiagLog: TextView
    private lateinit var btnToggleDiag: TextView
    private lateinit var btnCloseDiag: TextView
    private var diagLogExpanded = false

    private var isOcrMode = false

    private val handler = Handler(Looper.getMainLooper())
    private var statusUpdateRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auto_clicker)

        initViews()
        loadSettings()
        setupModeSwitch()
        setupCoordinateMode()
        setupOcrMode()
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)

        btnModeCoordinate = findViewById(R.id.btnModeCoordinate)
        btnModeOcr = findViewById(R.id.btnModeOcr)
        layoutCoordinateMode = findViewById(R.id.layoutCoordinateMode)
        layoutOcrMode = findViewById(R.id.layoutOcrMode)

        etInterval = findViewById(R.id.etInterval)
        btnIntervalMinus = findViewById(R.id.btnIntervalMinus)
        btnIntervalPlus = findViewById(R.id.btnIntervalPlus)
        switchInfinite = findViewById(R.id.switchInfinite)
        layoutCountInput = findViewById(R.id.layoutCountInput)
        etCount = findViewById(R.id.etCount)
        btnCountMinus = findViewById(R.id.btnCountMinus)
        btnCountPlus = findViewById(R.id.btnCountPlus)
        tvRunningStatus = findViewById(R.id.tvRunningStatus)
        tvClickCount = findViewById(R.id.tvClickCount)
        btnStartStop = findViewById(R.id.btnStartStop)
        tvFloatingHint = findViewById(R.id.tvFloatingHint)

        etTargetText = findViewById(R.id.etTargetText)
        switchExactMatch = findViewById(R.id.switchExactMatch)
        etOcrScanInterval = findViewById(R.id.etOcrScanInterval)
        etOcrClickCount = findViewById(R.id.etOcrClickCount)
        etOcrClickInterval = findViewById(R.id.etOcrClickInterval)
        tvOcrStatus = findViewById(R.id.tvOcrStatus)
        tvOcrScanCount = findViewById(R.id.tvOcrScanCount)
        tvOcrMatchInfo = findViewById(R.id.tvOcrMatchInfo)
        tvOcrClickCountDisplay = findViewById(R.id.tvOcrClickCountDisplay)
        btnOcrStartStop = findViewById(R.id.btnOcrStartStop)
        tvOcrHint = findViewById(R.id.tvOcrHint)
        cardDiagLog = findViewById(R.id.cardDiagLog)
        tvDiagLog = findViewById(R.id.tvDiagLog)
        btnToggleDiag = findViewById(R.id.btnToggleDiag)
        btnCloseDiag = findViewById(R.id.btnCloseDiag)

        // 悬浮日志：切换按钮点击展开面板
        btnToggleDiag.setOnClickListener {
            diagLogExpanded = true
            cardDiagLog.visibility = View.VISIBLE
            btnToggleDiag.visibility = View.GONE
            tvDiagLog.text = ScreenCaptureService.diagLog
        }

        // 收起按钮
        btnCloseDiag.setOnClickListener {
            diagLogExpanded = false
            cardDiagLog.visibility = View.GONE
            btnToggleDiag.visibility = View.VISIBLE
        }

        // 清空诊断日志
        findViewById<TextView>(R.id.btnClearDiag).setOnClickListener {
            ScreenCaptureService.clearDiagLog()
            tvDiagLog.text = ""
        }

        btnBack.setOnClickListener { finish() }
    }

    // ==================== 模式切换 ====================

    private fun setupModeSwitch() {
        btnModeCoordinate.setOnClickListener {
            isOcrMode = false
            updateModeUI()
        }
        btnModeOcr.setOnClickListener {
            isOcrMode = true
            updateModeUI()
        }
    }

    private fun updateModeUI() {
        if (isOcrMode) {
            btnModeOcr.setTextColor(getColor(R.color.white))
            btnModeOcr.setBackgroundResource(R.drawable.btn_ocr_bg)
            btnModeCoordinate.setTextColor(getColor(R.color.text_secondary))
            btnModeCoordinate.setBackgroundResource(R.drawable.edit_bg)
            layoutCoordinateMode.visibility = View.GONE
            layoutOcrMode.visibility = View.VISIBLE
        } else {
            btnModeCoordinate.setTextColor(getColor(R.color.white))
            btnModeCoordinate.setBackgroundResource(R.drawable.btn_primary_bg)
            btnModeOcr.setTextColor(getColor(R.color.text_secondary))
            btnModeOcr.setBackgroundResource(R.drawable.edit_bg)
            layoutCoordinateMode.visibility = View.VISIBLE
            layoutOcrMode.visibility = View.GONE
        }
    }

    // ==================== 坐标模式 ====================

    private fun setupCoordinateMode() {
        btnIntervalMinus.setOnClickListener {
            val current = getInterval()
            etInterval.setText((current - 10).coerceAtLeast(1).toString())
        }
        btnIntervalPlus.setOnClickListener {
            val current = getInterval()
            etInterval.setText((current + 10).coerceAtMost(60000).toString())
        }

        switchInfinite.setOnCheckedChangeListener { _, isChecked ->
            layoutCountInput.visibility = if (isChecked) View.GONE else View.VISIBLE
        }

        btnCountMinus.setOnClickListener {
            val current = getCount()
            etCount.setText((current - 10).coerceAtLeast(1).toString())
        }
        btnCountPlus.setOnClickListener {
            val current = getCount()
            etCount.setText((current + 10).coerceAtMost(99999).toString())
        }

        btnStartStop.setOnClickListener {
            if (ClickAccessibilityService.isClicking()) {
                ClickAccessibilityService.stopClicking()
                updateCoordinateUI()
            } else {
                saveSettings()
                if (!checkPermissions()) return@setOnClickListener
                val interval = getInterval()
                val isInfiniteMode = switchInfinite.isChecked
                val count = if (isInfiniteMode) 0L else getCount()

                ClickAccessibilityService.startClickingWithParams(interval, isInfiniteMode, count)
                updateCoordinateUI()
                Toast.makeText(this, "悬浮球已出现，拖动到目标位置后单击开始连点", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ==================== OCR 模式 ====================

    private fun setupOcrMode() {
        btnOcrStartStop.setOnClickListener {
            if (ScreenCaptureService.isRunning) {
                stopOcr()
            } else {
                startOcr()
            }
        }
    }

    private fun startOcr() {
        val targetText = etTargetText.text.toString().trim()
        if (targetText.isEmpty()) {
            Toast.makeText(this, getString(R.string.ocr_empty_target), Toast.LENGTH_SHORT).show()
            return
        }

        if (!checkPermissions()) return

        // 请求屏幕录制权限
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.ocr_capture_permission), Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private fun stopOcr() {
        ScreenCaptureService.stopCapture()
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        startService(stopIntent)
        updateOcrUI()
        Toast.makeText(this, "OCR识别已停止", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                // 保存 OCR 设置
                saveOcrSettings()

                // ⚠️ 关键：通过静态变量传递 MediaProjection 授权数据
                // 不能通过 Intent.putExtra 序列化，否则 IBinder token 会丢失
                ScreenCaptureService.projectionResultCode = resultCode
                ScreenCaptureService.projectionResultData = data

                val targetText = etTargetText.text.toString().trim()
                val scanInterval = getOcrScanInterval()
                val clickCount = getOcrClickCount()
                val exactMatch = !switchExactMatch.isChecked  // switch关闭=精确匹配
                val clickInterval = getOcrClickInterval()

                // 启动截屏服务（不再传 result_data）
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_TARGET_TEXT, targetText)
                    putExtra(ScreenCaptureService.EXTRA_SCAN_INTERVAL, scanInterval)
                    putExtra(ScreenCaptureService.EXTRA_CLICK_COUNT, clickCount)
                    putExtra(ScreenCaptureService.EXTRA_EXACT_MATCH, exactMatch)
                    putExtra(ScreenCaptureService.EXTRA_CLICK_INTERVAL, clickInterval)
                }
                startForegroundService(serviceIntent)

                // 启动后自动返回桌面，避免 OCR 误识别本应用界面
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(homeIntent)
            } else {
                Toast.makeText(this, getString(R.string.ocr_capture_permission), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==================== 生命周期 ====================

    override fun onResume() {
        super.onResume()
        updateCoordinateUI()
        updateOcrUI()
        startStatusUpdate()
    }

    override fun onPause() {
        super.onPause()
        stopStatusUpdate()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStatusUpdate()
    }

    // ==================== 权限检查 ====================

    private fun checkPermissions(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_SHORT).show()
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
            return false
        }
        if (!ClickAccessibilityService.isRunning()) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return false
        }
        return true
    }

    // ==================== 设置读写 ====================

    private fun loadSettings() {
        val prefs = getSharedPreferences("auto_clicker_prefs", Context.MODE_PRIVATE)
        etInterval.setText(prefs.getLong("click_interval", 100L).toString())
        val isInfinite = prefs.getBoolean("click_infinite", true)
        val savedCount = prefs.getLong("click_count", 100L)
        switchInfinite.isChecked = isInfinite
        etCount.setText(savedCount.toString())
        layoutCountInput.visibility = if (isInfinite) View.GONE else View.VISIBLE

        // OCR 设置
        val ocrPrefs = getSharedPreferences("ocr_prefs", Context.MODE_PRIVATE)
        etTargetText.setText(ocrPrefs.getString("target_text", ""))
        switchExactMatch.isChecked = !ocrPrefs.getBoolean("exact_match", false)
        etOcrScanInterval.setText(ocrPrefs.getLong("scan_interval", 500L).toString())
        etOcrClickCount.setText(ocrPrefs.getLong("click_count", 1L).toString())
        etOcrClickInterval.setText(ocrPrefs.getLong("click_interval", 100L).toString())
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("auto_clicker_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("click_interval", getInterval())
            .putBoolean("click_infinite", switchInfinite.isChecked)
            .putLong("click_count", getCount())
            .apply()
    }

    private fun saveOcrSettings() {
        val prefs = getSharedPreferences("ocr_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("target_text", etTargetText.text.toString().trim())
            .putBoolean("exact_match", !switchExactMatch.isChecked)
            .putLong("scan_interval", getOcrScanInterval())
            .putLong("click_count", getOcrClickCount())
            .putLong("click_interval", getOcrClickInterval())
            .apply()
    }

    // ==================== 参数获取 ====================

    private fun getInterval(): Long {
        val str = etInterval.text.toString().trim()
        return if (str.isNotEmpty()) str.toLong().coerceIn(1L, 60000L) else 100L
    }

    private fun getCount(): Long {
        val str = etCount.text.toString().trim()
        return if (str.isNotEmpty()) str.toLong().coerceIn(1L, 99999L) else 100L
    }

    private fun getOcrScanInterval(): Long {
        val str = etOcrScanInterval.text.toString().trim()
        return if (str.isNotEmpty()) str.toLong().coerceIn(200L, 10000L) else 500L
    }

    private fun getOcrClickCount(): Long {
        val str = etOcrClickCount.text.toString().trim()
        return if (str.isNotEmpty()) str.toLong().coerceIn(1L, 99999L) else 1L
    }

    private fun getOcrClickInterval(): Long {
        val str = etOcrClickInterval.text.toString().trim()
        return if (str.isNotEmpty()) str.toLong().coerceIn(50L, 60000L) else 100L
    }

    // ==================== UI 更新 ====================

    private fun updateCoordinateUI() {
        val isClicking = ClickAccessibilityService.isClicking()
        if (isClicking) {
            btnStartStop.text = getString(R.string.btn_stop)
            btnStartStop.setBackgroundResource(R.drawable.btn_danger_bg)
            tvRunningStatus.text = "正在连点中..."
            tvRunningStatus.setTextColor(getColor(R.color.btn_danger))
            tvFloatingHint.visibility = View.VISIBLE
        } else {
            btnStartStop.text = getString(R.string.btn_start)
            btnStartStop.setBackgroundResource(R.drawable.btn_primary_bg)
            tvRunningStatus.text = "未运行"
            tvRunningStatus.setTextColor(getColor(R.color.text_secondary))
            tvFloatingHint.visibility = View.GONE
        }
        updateClickCountDisplay()
    }

    private fun updateClickCountDisplay() {
        val clickedCount = ClickAccessibilityService.getClickedCount()
        if (clickedCount > 0) {
            tvClickCount.visibility = View.VISIBLE
            tvClickCount.text = "已点击 $clickedCount 次"
        } else {
            tvClickCount.visibility = View.GONE
        }
    }

    private fun updateOcrUI() {
        val isRunning = ScreenCaptureService.isRunning
        if (isRunning) {
            btnOcrStartStop.text = getString(R.string.ocr_stop)
            btnOcrStartStop.setBackgroundResource(R.drawable.btn_danger_bg)
            tvOcrStatus.text = getString(R.string.ocr_running)
            tvOcrStatus.setTextColor(getColor(R.color.btn_danger))

            val scanCount = ScreenCaptureService.ocrScanCount
            tvOcrScanCount.visibility = View.VISIBLE
            tvOcrScanCount.text = "已扫描 $scanCount 次"

            val matchResult = ScreenCaptureService.lastMatchResult
            if (matchResult?.matched == true) {
                tvOcrMatchInfo.visibility = View.VISIBLE
                tvOcrMatchInfo.text = "已匹配: \"${matchResult.targetText}\" 坐标(${matchResult.centerX}, ${matchResult.centerY})"
            } else {
                tvOcrMatchInfo.visibility = View.GONE
            }

            val clickCountVal = ScreenCaptureService.ocrClickCount
            if (clickCountVal > 0) {
                tvOcrClickCountDisplay.visibility = View.VISIBLE
                tvOcrClickCountDisplay.text = "已点击 $clickCountVal 次"
            } else {
                tvOcrClickCountDisplay.visibility = View.GONE
            }

            // 显示诊断入口按钮
            if (diagLogExpanded) {
                cardDiagLog.visibility = View.VISIBLE
                tvDiagLog.text = ScreenCaptureService.diagLog
            } else {
                btnToggleDiag.visibility = View.VISIBLE
            }
        } else {
            btnOcrStartStop.text = getString(R.string.ocr_start)
            btnOcrStartStop.setBackgroundResource(R.drawable.btn_ocr_bg)
            tvOcrStatus.text = "未运行"
            tvOcrStatus.setTextColor(getColor(R.color.text_secondary))
            tvOcrScanCount.visibility = View.GONE
            tvOcrMatchInfo.visibility = View.GONE
            tvOcrClickCountDisplay.visibility = View.GONE

            // 停止时保留诊断日志
            if (ScreenCaptureService.diagLog.isNotBlank()) {
                if (diagLogExpanded) {
                    cardDiagLog.visibility = View.VISIBLE
                    tvDiagLog.text = ScreenCaptureService.diagLog
                } else {
                    btnToggleDiag.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun startStatusUpdate() {
        stopStatusUpdate()
        statusUpdateRunnable = object : Runnable {
            override fun run() {
                updateClickCountDisplay()
                updateOcrUI()
                if (!ClickAccessibilityService.isClicking()) {
                    updateCoordinateUI()
                }
                handler.postDelayed(this, 300)
            }
        }
        handler.post(statusUpdateRunnable!!)
    }

    private fun stopStatusUpdate() {
        statusUpdateRunnable?.let { handler.removeCallbacks(it) }
        statusUpdateRunnable = null
    }
}
