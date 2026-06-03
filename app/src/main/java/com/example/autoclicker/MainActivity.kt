package com.example.autoclicker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var switchAccessibility: Switch
    private lateinit var switchOverlay: Switch
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tabClicker: LinearLayout
    private lateinit var tabRushBuy: LinearLayout

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        switchAccessibility = findViewById(R.id.switchAccessibility)
        switchOverlay = findViewById(R.id.switchOverlay)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        tabClicker = findViewById(R.id.tabClicker)
        tabRushBuy = findViewById(R.id.tabRushBuy)

        // 无障碍服务开关
        switchAccessibility.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isAccessibilityServiceEnabled()) {
                // 跳转无障碍设置
                Toast.makeText(this, "请在设置中开启 AutoClicker 服务", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else if (!isChecked && isAccessibilityServiceEnabled()) {
                // 停止无障碍服务
                ClickAccessibilityService.stopService()
            }
        }

        // 悬浮窗权限开关
        switchOverlay.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !Settings.canDrawOverlays(this)) {
                // 跳转悬浮窗权限设置
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            } else if (!isChecked && Settings.canDrawOverlays(this)) {
                // 无法通过代码撤销悬浮窗权限，引导用户手动关闭
                Toast.makeText(this, "请手动关闭悬浮窗权限", Toast.LENGTH_SHORT).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            }
        }

        // 底部工具栏 - 连点器入口
        tabClicker.setOnClickListener {
            startActivity(Intent(this, AutoClickerActivity::class.java))
        }

        // 底部工具栏 - 抢购入口
        tabRushBuy.setOnClickListener {
            startActivity(Intent(this, RushBuyActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    /** 更新权限状态显示 */
    private fun updatePermissionStatus() {
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        val overlayEnabled = Settings.canDrawOverlays(this)

        // 更新无障碍服务状态
        switchAccessibility.setOnCheckedChangeListener(null)
        switchAccessibility.isChecked = accessibilityEnabled
        tvAccessibilityStatus.text = if (accessibilityEnabled) "已开启" else "未开启"
        tvAccessibilityStatus.setTextColor(
            getColor(if (accessibilityEnabled) R.color.status_on else R.color.status_off)
        )
        // 重新绑定监听器
        switchAccessibility.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "请在设置中开启 AutoClicker 服务", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else if (!isChecked && isAccessibilityServiceEnabled()) {
                ClickAccessibilityService.stopService()
            }
        }

        // 更新悬浮窗权限状态
        switchOverlay.setOnCheckedChangeListener(null)
        switchOverlay.isChecked = overlayEnabled
        tvOverlayStatus.text = if (overlayEnabled) "已开启" else "未开启"
        tvOverlayStatus.setTextColor(
            getColor(if (overlayEnabled) R.color.status_on else R.color.status_off)
        )
        // 重新绑定监听器
        switchOverlay.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            } else if (!isChecked && Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请手动关闭悬浮窗权限", Toast.LENGTH_SHORT).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            }
        }
    }

    /** 检查无障碍服务是否已启用 */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "$packageName/${packageName}.ClickAccessibilityService"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)

        while (colonSplitter.hasNext()) {
            val component = colonSplitter.next()
            if (component.equals(serviceName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }
}
