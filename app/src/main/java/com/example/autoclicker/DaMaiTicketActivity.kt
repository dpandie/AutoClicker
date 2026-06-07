package com.example.autoclicker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class DaMaiTicketActivity : AppCompatActivity() {

    // 标题栏
    private lateinit var btnBack: ImageView

    // 场次配置
    private lateinit var etSessions: EditText
    private lateinit var btnAddSession: Button

    // 价格档位配置
    private lateinit var etPrices: EditText
    private lateinit var btnAddPrice: Button

    // 观演人配置
    private lateinit var etViewers: EditText
    private lateinit var btnAddViewer: Button

    // 高级参数
    private lateinit var etRetryInterval: EditText
    private lateinit var etMaxRetries: EditText

    // 操作按钮
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    // 状态显示
    private lateinit var tvState: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvAttemptCount: TextView

    // 标签容器
    private lateinit var layoutSessionTags: LinearLayout
    private lateinit var layoutPriceTags: LinearLayout
    private lateinit var layoutViewerTags: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private var statusRunnable: Runnable? = null

    // 配置数据
    private val sessions = mutableListOf<String>()
    private val prices = mutableListOf<String>()
    private val viewers = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_damai_ticket)

        initViews()
        loadSettings()
        setupListeners()
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)

        etSessions = findViewById(R.id.etSessions)
        btnAddSession = findViewById(R.id.btnAddSession)
        layoutSessionTags = findViewById(R.id.layoutSessionTags)

        etPrices = findViewById(R.id.etPrices)
        btnAddPrice = findViewById(R.id.btnAddPrice)
        layoutPriceTags = findViewById(R.id.layoutPriceTags)

        etViewers = findViewById(R.id.etViewers)
        btnAddViewer = findViewById(R.id.btnAddViewer)
        layoutViewerTags = findViewById(R.id.layoutViewerTags)

        etRetryInterval = findViewById(R.id.etRetryInterval)
        etMaxRetries = findViewById(R.id.etMaxRetries)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        tvState = findViewById(R.id.tvState)
        tvStatus = findViewById(R.id.tvStatus)
        tvAttemptCount = findViewById(R.id.tvAttemptCount)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }

        // 添加场次
        btnAddSession.setOnClickListener {
            val text = etSessions.text.toString().trim()
            if (text.isNotEmpty() && !sessions.contains(text)) {
                sessions.add(text)
                etSessions.text.clear()
                refreshTags(layoutSessionTags, sessions, null)
            }
        }

        // 添加价格
        btnAddPrice.setOnClickListener {
            val text = etPrices.text.toString().trim()
            if (text.isNotEmpty() && !prices.contains(text)) {
                prices.add(text)
                etPrices.text.clear()
                refreshTags(layoutPriceTags, prices, null)
            }
        }

        // 添加观演人
        btnAddViewer.setOnClickListener {
            val text = etViewers.text.toString().trim()
            if (text.isNotEmpty() && !viewers.contains(text)) {
                viewers.add(text)
                etViewers.text.clear()
                refreshTags(layoutViewerTags, viewers, null)
            }
        }

        // 开始抢票
        btnStart.setOnClickListener {
            if (!checkPermissions()) return@setOnClickListener
            if (sessions.isEmpty()) {
                Toast.makeText(this, "请至少添加一个场次", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (prices.isEmpty()) {
                Toast.makeText(this, "请至少添加一个价格档位", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (viewers.isEmpty()) {
                Toast.makeText(this, "请至少添加一个观演人", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            saveSettings()
            startTicketGrab()
        }

        // 停止抢票
        btnStop.setOnClickListener {
            ClickAccessibilityService.stopTicketGrab()
            updateUI()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        startStatusUpdate()
        WatermarkHelper.apply(this)
    }

    override fun onPause() {
        super.onPause()
        stopStatusUpdate()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStatusUpdate()
    }

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

    private fun startTicketGrab() {
        val cfg = TicketGrabEngine.Config(
            sessions = sessions.toList(),
            prices = prices.toList(),
            viewers = viewers.toList(),
            retryInterval = etRetryInterval.text.toString().trim().toLongOrNull()?.coerceIn(50L, 5000L) ?: 300L,
            maxRetries = etMaxRetries.text.toString().trim().toIntOrNull()?.coerceIn(1, 9999) ?: 200
        )

        val engine = ClickAccessibilityService.getTicketGrabEngine()
        if (engine == null) {
            Toast.makeText(this, "无障碍服务未就绪", Toast.LENGTH_SHORT).show()
            return
        }

        engine.onStateChanged = { state, msg ->
            handler.post {
                tvState.text = when (state) {
                    TicketGrabEngine.State.IDLE -> "空闲"
                    TicketGrabEngine.State.ENTERING_PAGE -> "进入选票页"
                    TicketGrabEngine.State.SELECTING_SESSION -> "选择场次"
                    TicketGrabEngine.State.SELECTING_PRICE -> "选择价格"
                    TicketGrabEngine.State.SELECTING_VIEWERS -> "选择观演人"
                    TicketGrabEngine.State.SUBMITTING_ORDER -> "提交订单"
                    TicketGrabEngine.State.DONE -> "完成"
                }
                tvStatus.text = msg
            }
        }

        ClickAccessibilityService.startTicketGrab(cfg)
        updateUI()

        // 切换到大麦 APP
        val pm = packageManager
        val launchIntent = pm.getLaunchIntentForPackage("cn.damai")
        if (launchIntent != null) {
            startActivity(launchIntent)
            Toast.makeText(this, "已启动大麦 APP，抢票进行中...", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "未检测到大麦 APP，请手动打开", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateUI() {
        val engine = ClickAccessibilityService.getTicketGrabEngine()
        val isRunning = engine?.isRunning() == true

        if (isRunning) {
            btnStart.isEnabled = false
            btnStop.isEnabled = true
            val state = engine!!.state
            tvState.text = when (state) {
                TicketGrabEngine.State.IDLE -> "空闲"
                TicketGrabEngine.State.ENTERING_PAGE -> "进入选票页"
                TicketGrabEngine.State.SELECTING_SESSION -> "选择场次"
                TicketGrabEngine.State.SELECTING_PRICE -> "选择价格"
                TicketGrabEngine.State.SELECTING_VIEWERS -> "选择观演人"
                TicketGrabEngine.State.SUBMITTING_ORDER -> "提交订单"
                TicketGrabEngine.State.DONE -> "完成"
            }
            tvStatus.text = engine.statusMessage
            tvAttemptCount.text = "尝试次数: ${engine.attemptCount}"
            tvAttemptCount.visibility = View.VISIBLE
        } else {
            btnStart.isEnabled = true
            btnStop.isEnabled = false
            if (engine?.state == TicketGrabEngine.State.DONE) {
                tvState.text = "完成"
                tvStatus.text = engine.statusMessage
            } else {
                tvState.text = "空闲"
                tvStatus.text = "就绪"
            }
            tvAttemptCount.visibility = View.GONE
        }
    }

    /**
     * 刷新标签列表
     */
    private fun refreshTags(container: LinearLayout, items: List<String>, onRemove: ((String) -> Unit)?) {
        container.removeAllViews()
        for (item in items) {
            val tagView = layoutInflater.inflate(R.layout.item_tag, container, false)
            val tvTag = tagView.findViewById<TextView>(R.id.tvTag)
            val btnRemove = tagView.findViewById<ImageView>(R.id.btnRemoveTag)
            tvTag.text = item

            btnRemove.setOnClickListener {
                if (container == layoutSessionTags) {
                    sessions.remove(item)
                    refreshTags(layoutSessionTags, sessions, null)
                } else if (container == layoutPriceTags) {
                    prices.remove(item)
                    refreshTags(layoutPriceTags, prices, null)
                } else if (container == layoutViewerTags) {
                    viewers.remove(item)
                    refreshTags(layoutViewerTags, viewers, null)
                }
            }

            container.addView(tagView)
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("damai_ticket_prefs", Context.MODE_PRIVATE)

        // 场次
        val sessionStr = prefs.getString("sessions", "") ?: ""
        if (sessionStr.isNotEmpty()) {
            sessions.clear()
            sessions.addAll(sessionStr.split(",").filter { it.isNotEmpty() })
        }

        // 价格
        val priceStr = prefs.getString("prices", "") ?: ""
        if (priceStr.isNotEmpty()) {
            prices.clear()
            prices.addAll(priceStr.split(",").filter { it.isNotEmpty() })
        }

        // 观演人
        val viewerStr = prefs.getString("viewers", "") ?: ""
        if (viewerStr.isNotEmpty()) {
            viewers.clear()
            viewers.addAll(viewerStr.split(",").filter { it.isNotEmpty() })
        }

        // 高级参数
        etRetryInterval.setText(prefs.getLong("retry_interval", 300L).toString())
        etMaxRetries.setText(prefs.getInt("max_retries", 200).toString())

        // 刷新标签
        refreshTags(layoutSessionTags, sessions, null)
        refreshTags(layoutPriceTags, prices, null)
        refreshTags(layoutViewerTags, viewers, null)
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("damai_ticket_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("sessions", sessions.joinToString(","))
            .putString("prices", prices.joinToString(","))
            .putString("viewers", viewers.joinToString(","))
            .putLong("retry_interval", etRetryInterval.text.toString().trim().toLongOrNull() ?: 300L)
            .putInt("max_retries", etMaxRetries.text.toString().trim().toIntOrNull() ?: 200)
            .apply()
    }

    private fun startStatusUpdate() {
        stopStatusUpdate()
        statusRunnable = object : Runnable {
            override fun run() {
                updateUI()
                handler.postDelayed(this, 500)
            }
        }
        handler.post(statusRunnable!!)
    }

    private fun stopStatusUpdate() {
        statusRunnable?.let { handler.removeCallbacks(it) }
        statusRunnable = null
    }
}
