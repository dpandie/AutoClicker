package com.example.autoclicker

import android.content.Context
import android.content.Intent
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
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class RushBuyActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageView
    private lateinit var etTargetX: EditText
    private lateinit var etTargetY: EditText
    private lateinit var btnLocate: Button
    private lateinit var etRushInterval: EditText
    private lateinit var etRushCount: EditText
    private lateinit var etAdvanceTime: EditText
    private lateinit var switchScheduled: Switch
    private lateinit var layoutScheduledTime: LinearLayout
    private lateinit var etScheduledHour: EditText
    private lateinit var etScheduledMinute: EditText
    private lateinit var etScheduledSecond: EditText
    private lateinit var tvCountdown: TextView
    private lateinit var tvRushStatus: TextView
    private lateinit var tvRushClickCount: TextView
    private lateinit var btnRushStart: Button
    private lateinit var btnRushStop: Button
    private lateinit var switchShowFloatingTime: Switch

    private val handler = Handler(Looper.getMainLooper())
    private var countdownRunnable: Runnable? = null
    private var statusRunnable: Runnable? = null
    private var isScheduledMode = false
    private var showFloatingTime = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rush_buy)

        btnBack = findViewById(R.id.btnBack)
        etTargetX = findViewById(R.id.etTargetX)
        etTargetY = findViewById(R.id.etTargetY)
        btnLocate = findViewById(R.id.btnLocate)
        etRushInterval = findViewById(R.id.etRushInterval)
        etRushCount = findViewById(R.id.etRushCount)
        etAdvanceTime = findViewById(R.id.etAdvanceTime)
        switchScheduled = findViewById(R.id.switchScheduled)
        layoutScheduledTime = findViewById(R.id.layoutScheduledTime)
        etScheduledHour = findViewById(R.id.etScheduledHour)
        etScheduledMinute = findViewById(R.id.etScheduledMinute)
        etScheduledSecond = findViewById(R.id.etScheduledSecond)
        tvCountdown = findViewById(R.id.tvCountdown)
        tvRushStatus = findViewById(R.id.tvRushStatus)
        tvRushClickCount = findViewById(R.id.tvRushClickCount)
        btnRushStart = findViewById(R.id.btnRushStart)
        btnRushStop = findViewById(R.id.btnRushStop)
        switchShowFloatingTime = findViewById(R.id.switchShowFloatingTime)

        // 先注册监听器，再加载设置（确保 loadSettings 设置 isChecked 时能触发 visibility 更新）
        switchScheduled.setOnCheckedChangeListener { _, isChecked ->
            layoutScheduledTime.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        switchShowFloatingTime.setOnCheckedChangeListener { _, isChecked ->
            showFloatingTime = isChecked
            if (!isChecked) {
                ClickAccessibilityService.removeFloatingTime()
            }
        }

        loadSettings()

        btnBack.setOnClickListener { finish() }

        // 定位按钮 - 显示定位悬浮窗
        btnLocate.setOnClickListener {
            if (!checkPermissions()) return@setOnClickListener
            val currentX = getTargetX()
            val currentY = getTargetY()
            ClickAccessibilityService.showLocateOverlay(currentX, currentY)
            Toast.makeText(this, "请拖动准星到目标位置，单击确认", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        }

        // 立即抢购
        btnRushStart.setOnClickListener {
            if (!checkPermissions()) return@setOnClickListener
            saveSettings()

            if (switchScheduled.isChecked) {
                startScheduledRush()
            } else {
                startImmediateRush()
            }
        }

        // 停止
        btnRushStop.setOnClickListener {
            cancelScheduled()
            ClickAccessibilityService.stopClicking()
            updateUI()
        }
    }

    override fun onResume() {
        super.onResume()
        loadCoordinatesFromService()
        updateUI()
        startStatusUpdate()
    }

    override fun onPause() {
        super.onPause()
        stopStatusUpdate()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelScheduled()
        stopStatusUpdate()
    }

    /** 检查权限 */
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

    /** 从服务读取定位坐标 */
    private fun loadCoordinatesFromService() {
        val coords = ClickAccessibilityService.getLocatedCoordinates()
        if (coords != null) {
            etTargetX.setText(coords.first.toString())
            etTargetY.setText(coords.second.toString())
            ClickAccessibilityService.removeLocateOverlay()
        }
    }

    /** 读取保存的设置 */
    private fun loadSettings() {
        val prefs = getSharedPreferences("rush_buy_prefs", Context.MODE_PRIVATE)
        etTargetX.setText(prefs.getInt("target_x", 540).toString())
        etTargetY.setText(prefs.getInt("target_y", 960).toString())
        etRushInterval.setText(prefs.getLong("rush_interval", 50L).toString())
        etRushCount.setText(prefs.getLong("rush_count", 10L).toString())
        etAdvanceTime.setText(prefs.getLong("advance_time", 0L).toString())
        switchScheduled.isChecked = prefs.getBoolean("scheduled_enabled", false)

        // 触发时间默认值：当前时间 + 5 分钟（每次打开都刷新）
        val defaultCal = Calendar.getInstance().apply { add(Calendar.MINUTE, 5) }
        val hour = defaultCal.get(Calendar.HOUR_OF_DAY)
        val minute = defaultCal.get(Calendar.MINUTE)
        val second = defaultCal.get(Calendar.SECOND)
        etScheduledHour.setText(hour.toString())
        etScheduledMinute.setText(String.format("%02d", minute))
        etScheduledSecond.setText(String.format("%02d", second))

        // 悬浮时间开关
        showFloatingTime = prefs.getBoolean("show_floating_time", true)
        switchShowFloatingTime.isChecked = showFloatingTime
    }

    /** 保存设置 */
    private fun saveSettings() {
        val prefs = getSharedPreferences("rush_buy_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("target_x", getTargetX())
            .putInt("target_y", getTargetY())
            .putLong("rush_interval", getRushInterval())
            .putLong("rush_count", getRushCount())
            .putLong("advance_time", getAdvanceTime())
            .putBoolean("scheduled_enabled", switchScheduled.isChecked)
            .putInt("scheduled_hour", getScheduledHour())
            .putInt("scheduled_minute", getScheduledMinute())
            .putInt("scheduled_second", getScheduledSecond())
            .putBoolean("show_floating_time", showFloatingTime)
            .apply()
    }

    private fun getTargetX(): Int = etTargetX.text.toString().trim().toIntOrNull()?.coerceIn(0, 4000) ?: 540
    private fun getTargetY(): Int = etTargetY.text.toString().trim().toIntOrNull()?.coerceIn(0, 4000) ?: 960
    private fun getRushInterval(): Long = etRushInterval.text.toString().trim().toLongOrNull()?.coerceIn(1L, 60000L) ?: 50L
    private fun getRushCount(): Long = etRushCount.text.toString().trim().toLongOrNull()?.coerceIn(1L, 99999L) ?: 10L
    private fun getAdvanceTime(): Long = etAdvanceTime.text.toString().trim().toLongOrNull()?.coerceIn(0L, 5000L) ?: 0L
    private fun getScheduledHour(): Int = etScheduledHour.text.toString().trim().toIntOrNull()?.coerceIn(0, 23) ?: 10
    private fun getScheduledMinute(): Int = etScheduledMinute.text.toString().trim().toIntOrNull()?.coerceIn(0, 59) ?: 0
    private fun getScheduledSecond(): Int = etScheduledSecond.text.toString().trim().toIntOrNull()?.coerceIn(0, 59) ?: 0

    /** 立即抢购 */
    private fun startImmediateRush() {
        val x = getTargetX()
        val y = getTargetY()
        val interval = getRushInterval()
        val count = getRushCount()
        val advance = getAdvanceTime()

        isScheduledMode = false
        tvCountdown.visibility = View.GONE
        tvRushStatus.text = "准备中..."
        tvRushStatus.setTextColor(getColor(R.color.btn_danger))

        handler.postDelayed({
            ClickAccessibilityService.startRushBuyClicking(x, y, interval, count)
            updateUI()
        }, advance)
    }

    /** 定时抢购 */
    private fun startScheduledRush() {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, getScheduledHour())
            set(Calendar.MINUTE, getScheduledMinute())
            set(Calendar.SECOND, getScheduledSecond())
            set(Calendar.MILLISECOND, 0)
        }

        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        val scheduledTimeMillis = calendar.timeInMillis
        val advance = getAdvanceTime()
        val triggerTime = scheduledTimeMillis - advance

        isScheduledMode = true
        tvCountdown.visibility = View.VISIBLE

        // 显示悬浮倒计时
        if (showFloatingTime) {
            ClickAccessibilityService.showFloatingTime(triggerTime)
        }

        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val timeStr = sdf.format(calendar.time)
        tvRushStatus.text = "定时等待中..."
        tvRushStatus.setTextColor(getColor(R.color.text_secondary))

        Toast.makeText(this, "将在 $timeStr 自动抢购（提前 ${advance}ms）", Toast.LENGTH_LONG).show()

        countdownRunnable = object : Runnable {
            override fun run() {
                val remaining = triggerTime - System.currentTimeMillis()
                if (remaining <= 0) {
                    val x = getTargetX()
                    val y = getTargetY()
                    val interval = getRushInterval()
                    val count = getRushCount()
                    ClickAccessibilityService.startRushBuyClicking(x, y, interval, count)
                    isScheduledMode = false
                    tvCountdown.visibility = View.GONE
                    ClickAccessibilityService.removeFloatingTime()
                    updateUI()
                    return
                }

                val secs = remaining / 1000
                val h = secs / 3600
                val m = (secs % 3600) / 60
                val s = secs % 60
                tvCountdown.text = String.format("倒计时: %02d:%02d:%02d", h, m, s)
                // 更新悬浮时间
                if (showFloatingTime) {
                    ClickAccessibilityService.updateFloatingTime(remaining)
                }
                handler.postDelayed(this, 200)
            }
        }
        handler.post(countdownRunnable!!)
        updateUI()
    }

    /** 取消定时 */
    private fun cancelScheduled() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
        isScheduledMode = false
        tvCountdown.visibility = View.GONE
        ClickAccessibilityService.removeFloatingTime()
    }

    /** 更新 UI */
    private fun updateUI() {
        val isClicking = ClickAccessibilityService.isClicking()
        if (isClicking) {
            btnRushStart.isEnabled = false
            btnRushStop.isEnabled = true
            tvRushStatus.text = "抢购进行中！"
            tvRushStatus.setTextColor(getColor(R.color.btn_danger))
        } else if (isScheduledMode) {
            btnRushStart.isEnabled = false
            btnRushStop.isEnabled = true
        } else {
            btnRushStart.isEnabled = true
            btnRushStop.isEnabled = false
            tvRushStatus.text = "就绪"
            tvRushStatus.setTextColor(getColor(R.color.text_secondary))
        }
        updateClickCount()
    }

    /** 更新点击计数 */
    private fun updateClickCount() {
        val count = ClickAccessibilityService.getClickedCount()
        if (count > 0) {
            tvRushClickCount.visibility = View.VISIBLE
            tvRushClickCount.text = "已点击 $count 次"
        } else {
            tvRushClickCount.visibility = View.GONE
        }
    }

    /** 开始定时刷新状态 */
    private fun startStatusUpdate() {
        stopStatusUpdate()
        statusRunnable = object : Runnable {
            override fun run() {
                updateClickCount()
                if (!ClickAccessibilityService.isClicking() && !isScheduledMode) {
                    updateUI()
                }
                handler.postDelayed(this, 200)
            }
        }
        handler.post(statusRunnable!!)
    }

    /** 停止定时刷新 */
    private fun stopStatusUpdate() {
        statusRunnable?.let { handler.removeCallbacks(it) }
        statusRunnable = null
    }
}
