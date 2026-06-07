package com.example.autoclicker

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 抢票引擎 — 基于 Accessibility Service 节点树直接操作 APP 控件
 *
 * 状态机：IDLE → ENTERING_PAGE → SELECTING_SESSION → SELECTING_PRICE
 *        → SELECTING_VIEWERS → SUBMITTING_ORDER → DONE
 */
class TicketGrabEngine(private val service: AccessibilityService) {

    enum class State {
        IDLE,               // 空闲，未启动
        ENTERING_PAGE,      // 不断点击"立即"/"购票"进入选票页
        SELECTING_SESSION,  // 选择场次
        SELECTING_PRICE,    // 选择价格档位
        SELECTING_VIEWERS,  // 勾选观演人
        SUBMITTING_ORDER,   // 提交订单
        DONE                // 完成
    }

    data class Config(
        val sessions: List<String>,     // 场次关键词列表，如 ["05-31", "06-01"]
        val prices: List<String>,       // 价格档位关键词列表，如 ["1555", "355"]
        val viewers: List<String>,      // 观演人姓名关键词列表，如 ["张三", "李四"]
        val retryInterval: Long = 300L, // 每轮操作间隔(ms)
        val maxRetries: Int = 200       // 最大重试轮数
    )

    // ==================== 公开 API ====================

    var state: State = State.IDLE
        private set

    var config: Config? = null
        private set

    var attemptCount: Int = 0
        private set

    var statusMessage: String = "就绪"
        private set

    var onStateChanged: ((State, String) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private var currentSessionIndex = 0
    private var currentPriceIndex = 0
    private var isRunning = false

    fun start(cfg: Config) {
        if (isRunning) return
        config = cfg
        isRunning = true
        retryCount = 0
        currentSessionIndex = 0
        currentPriceIndex = 0
        attemptCount = 0
        ClickAccessibilityService.showFloatingLog()
        transitionTo(State.ENTERING_PAGE, "等待进入选票页...")
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        transitionTo(State.IDLE, "已停止")
        ClickAccessibilityService.removeFloatingLog()
    }

    fun isRunning(): Boolean = isRunning && state != State.IDLE && state != State.DONE

    /**
     * 由 ClickAccessibilityService.onAccessibilityEvent 调用
     */
    fun onEvent(event: AccessibilityEvent) {
        if (!isRunning) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handleState()
            }
        }
    }

    // ==================== 状态机驱动 ====================

    private fun handleState() {
        if (!isRunning) return

        when (state) {
            State.ENTERING_PAGE -> handleEnteringPage()
            State.SELECTING_SESSION -> handleSelectingSession()
            State.SELECTING_PRICE -> handleSelectingPrice()
            State.SELECTING_VIEWERS -> handleSelectingViewers()
            State.SUBMITTING_ORDER -> handleSubmittingOrder()
            State.DONE, State.IDLE -> { /* no-op */ }
        }
    }

    /**
     * Phase 1: 不断点击"立即"按钮，直到页面出现含"票档"的节点
     */
    private fun handleEnteringPage() {
        val rootNode = rootInActiveWindow ?: return

        // 检查是否已进入选票页面 — 查找含"票档"文字的节点
        if (findNodeByTextContains(rootNode, "票档") != null) {
            currentSessionIndex = 0
            transitionTo(State.SELECTING_SESSION, "正在选择场次...")
            rootNode.recycle()
            return
        }

        // 还没进入，点击"立即"按钮
        if (clickNodeByText(rootNode, "立即") || clickNodeByText(rootNode, "购票")) {
            attemptCount++
            updateStatus("点击进入选票页 (第${attemptCount}次)")
        }

        rootNode.recycle()
        checkRetryLimit()
    }

    /**
     * Phase 2: 遍历场次，跳过无票场次
     */
    private fun handleSelectingSession() {
        val cfg = config ?: return stop()
        if (currentSessionIndex >= cfg.sessions.size) {
            // 所有场次都遍历完了，重新开始
            currentSessionIndex = 0
            retryCount++
            if (checkRetryLimit()) return
        }

        val rootNode = rootInActiveWindow ?: return
        val session = cfg.sessions[currentSessionIndex]

        // 查找场次节点
        val sessionNode = findNodeByTextContains(rootNode, session)
        if (sessionNode == null) {
            rootNode.recycle()
            attemptCount++
            updateStatus("未找到场次: $session")
            currentSessionIndex++
            return
        }

        // 检查场次是否无票 — 场次节点的兄弟/子节点含"无票"或"缺货登记"
        if (isSoldOut(sessionNode)) {
            updateStatus("场次 $session 无票，跳过")
            currentSessionIndex++
            rootNode.recycle()
            return
        }

        // 点击选择该场次
        if (performClick(sessionNode)) {
            currentPriceIndex = 0
            transitionTo(State.SELECTING_PRICE, "选择价格档位...")
        } else {
            // 如果无法直接点击，尝试点击文字
            clickNodeByTextContains(rootNode, session)
            currentPriceIndex = 0
            transitionTo(State.SELECTING_PRICE, "选择价格档位...")
        }

        rootNode.recycle()
    }

    /**
     * Phase 3: 遍历价格档位，跳过缺货的
     */
    private fun handleSelectingPrice() {
        val cfg = config ?: return stop()
        if (currentPriceIndex >= cfg.prices.size) {
            // 该场次所有价格都缺货，换下一个场次
            currentSessionIndex++
            transitionTo(State.SELECTING_SESSION, "该场次价格均缺货，换场次...")
            return
        }

        val rootNode = rootInActiveWindow ?: return
        val price = cfg.prices[currentPriceIndex]

        val priceNode = findNodeByTextContains(rootNode, price)
        if (priceNode == null) {
            currentPriceIndex++
            rootNode.recycle()
            return
        }

        // 检查是否缺货
        if (isSoldOut(priceNode)) {
            updateStatus("价格 $price 缺货，跳过")
            currentPriceIndex++
            rootNode.recycle()
            return
        }

        // 点击选择该价格
        if (performClick(priceNode) || clickNodeByTextContains(rootNode, price)) {
            attemptCount++
            updateStatus("选择价格: $price")

            // 设置票数（点击"+"按钮 N-1 次，N = 观演人数）
            handler.postDelayed({
                setTicketCount(cfg.viewers.size)
                // 点击"确定"
                handler.postDelayed({
                    clickConfirm(rootNode)
                    transitionTo(State.SELECTING_VIEWERS, "选择观演人...")
                }, 200)
            }, 200)
        }

        rootNode.recycle()
    }

    /**
     * Phase 4: 勾选观演人
     */
    private fun handleSelectingViewers() {
        val cfg = config ?: return stop()
        val rootNode = rootInActiveWindow ?: return

        var allSelected = true
        for (viewerName in cfg.viewers) {
            val viewerNode = findNodeByTextContains(rootNode, viewerName)
            if (viewerNode != null) {
                // 尝试找到可点击的复选框（通常在 parent 的第 4 个 child）
                val clicked = clickViewerCheckbox(viewerNode)
                if (!clicked) {
                    // 备用方案：直接点击文字
                    performClick(viewerNode)
                }
                updateStatus("勾选观演人: $viewerName")
            } else {
                allSelected = false
            }
        }

        // 勾选完毕，进入提交
        if (allSelected) {
            transitionTo(State.SUBMITTING_ORDER, "提交订单...")
        } else {
            // 即使部分没找到，也尝试提交
            transitionTo(State.SUBMITTING_ORDER, "提交订单...")
        }

        rootNode.recycle()
    }

    /**
     * Phase 5: 提交订单
     */
    private fun handleSubmittingOrder() {
        val rootNode = rootInActiveWindow ?: return

        val clicked = clickNodeByText(rootNode, "提交订单")
        if (clicked) {
            attemptCount++
            updateStatus("已点击提交订单")
        }

        // 处理弹窗"我知道了"
        clickNodeByText(rootNode, "我知道了")
        clickNodeById(rootNode, "damai_theme_dialog_confirm_btn")

        // 检查是否出现支付相关界面（说明成功）
        if (findNodeByTextContains(rootNode, "支付") != null ||
            findNodeByTextContains(rootNode, "收银台") != null) {
            transitionTo(State.DONE, "抢票成功！请尽快完成支付")
            isRunning = false
            // 不立即移除日志窗，保留成功消息供用户查看
            handler.postDelayed({
                ClickAccessibilityService.removeFloatingLog()
            }, 5000)
            rootNode.recycle()
            return
        }

        // 如果出现"我知道了"弹窗，说明失败，重新开始
        if (findNodeByTextContains(rootNode, "我知道了") != null) {
            currentSessionIndex = 0
            retryCount++
            transitionTo(State.ENTERING_PAGE, "重新抢票...")
        }

        rootNode.recycle()
        checkRetryLimit()
    }

    // ==================== 辅助方法 ====================

    private val rootInActiveWindow: AccessibilityNodeInfo?
        get() = service.rootInActiveWindow

    /**
     * 查找包含指定文字的节点（模糊匹配）
     */
    fun findNodeByTextContains(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        return nodes.firstOrNull()
    }

    /**
     * 查找完全匹配指定文字的节点
     */
    fun findNodeByTextExact(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        return nodes.firstOrNull { it.text?.toString() == text }
    }

    /**
     * 按 resource-id 查找节点
     */
    fun findNodeById(root: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        return nodes.firstOrNull()
    }

    /**
     * 点击包含指定文字的节点
     */
    fun clickNodeByTextContains(root: AccessibilityNodeInfo, text: String): Boolean {
        val node = findNodeByTextContains(root, text) ?: return false
        return performClick(node)
    }

    /**
     * 点击完全匹配指定文字的节点
     */
    fun clickNodeByText(root: AccessibilityNodeInfo, text: String): Boolean {
        val node = findNodeByTextExact(root, text) ?: return false
        return performClick(node)
    }

    /**
     * 点击指定 resource-id 的节点
     */
    fun clickNodeById(root: AccessibilityNodeInfo, viewId: String): Boolean {
        val node = findNodeById(root, viewId) ?: return false
        return performClick(node)
    }

    /**
     * 对节点执行点击操作（优先 ACTION_CLICK，不可点击则向上找可点击的父节点）
     */
    fun performClick(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        // 最多向上找 5 层
        for (i in 0..5) {
            if (target == null) return false
            if (target.isClickable) {
                return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            target = target.parent
        }
        return false
    }

    /**
     * 判断节点对应的项目是否已售罄
     * 检查兄弟/子节点中是否含有"无票"或"缺货登记"文字
     */
    fun isSoldOut(node: AccessibilityNodeInfo): Boolean {
        val parent = node.parent ?: return false

        // 遍历父节点的所有子节点，查找售罄标记
        for (i in 0 until parent.childCount) {
            val child = parent.getChild(i) ?: continue
            val text = child.text?.toString() ?: continue
            if (text.contains("无票") || text.contains("缺货登记") || text.contains("缺货")) {
                return true
            }
            // 进一步检查子节点的子节点
            for (j in 0 until child.childCount) {
                val grandChild = child.getChild(j) ?: continue
                val gcText = grandChild.text?.toString() ?: continue
                if (gcText.contains("无票") || gcText.contains("缺货登记") || gcText.contains("缺货")) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * 设置票数 = 观演人数（通过点击"+"按钮）
     */
    private fun setTicketCount(viewerCount: Int) {
        val rootNode = rootInActiveWindow ?: return
        if (viewerCount <= 1) return

        // 查找含"1张"的节点，其父节点的第3个子节点通常是"+"按钮
        val ticketNode = findNodeByTextContains(rootNode, "1张") ?: return
        val parent = ticketNode.parent ?: return

        // 尝试找到"+"按钮
        for (i in 0 until parent.childCount) {
            val child = parent.getChild(i) ?: continue
            val text = child.text?.toString() ?: continue
            if (text.contains("+") || child.contentDescription?.contains("增加") == true) {
                // 点击 N-1 次
                for (k in 0 until viewerCount - 1) {
                    performClick(child)
                }
                return
            }
        }

        // 备用方案：查找包含"+"的可点击节点
        val plusNodes = rootNode.findAccessibilityNodeInfosByText("+")
        for (plusNode in plusNodes) {
            if (plusNode.isClickable) {
                for (k in 0 until viewerCount - 1) {
                    plusNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                return
            }
        }
    }

    /**
     * 点击"确定"按钮
     */
    private fun clickConfirm(rootNode: AccessibilityNodeInfo) {
        // 优先点击精确匹配"确定"的按钮
        val confirmNode = findNodeByTextExact(rootNode, "确定")
        if (confirmNode != null) {
            if (!performClick(confirmNode)) {
                // 节点不可点击，尝试点击其父容器
                val parent = confirmNode.parent
                if (parent != null) {
                    performClick(parent)
                }
            }
        }
    }

    /**
     * 勾选观演人复选框
     */
    private fun clickViewerCheckbox(viewerNode: AccessibilityNodeInfo): Boolean {
        val parent = viewerNode.parent ?: return false

        // 尝试查找复选框 — 通常在兄弟节点中
        for (i in 0 until parent.childCount) {
            val child = parent.getChild(i) ?: continue
            if (child.isCheckable) {
                if (!child.isChecked) {
                    return performClick(child)
                }
                return true // 已经勾选
            }
        }

        // 向上一层再找
        val grandParent = parent.parent ?: return false
        for (i in 0 until grandParent.childCount) {
            val child = grandParent.getChild(i) ?: continue
            if (child.isCheckable) {
                if (!child.isChecked) {
                    return performClick(child)
                }
                return true
            }
        }

        return false
    }

    /**
     * 检查重试次数是否超限
     */
    private fun checkRetryLimit(): Boolean {
        val cfg = config ?: return true
        if (retryCount >= cfg.maxRetries) {
            transitionTo(State.DONE, "已达最大重试次数(${cfg.maxRetries})，已停止")
            isRunning = false
            handler.postDelayed({
                ClickAccessibilityService.removeFloatingLog()
            }, 5000)
            return true
        }
        return false
    }

    private fun transitionTo(newState: State, msg: String) {
        state = newState
        statusMessage = msg
        Log.d("TicketGrab", "State: $newState - $msg")
        ClickAccessibilityService.appendTicketLog("[$newState] $msg")
        onStateChanged?.invoke(newState, msg)
    }

    private fun updateStatus(msg: String) {
        statusMessage = msg
        ClickAccessibilityService.appendTicketLog(msg)
        onStateChanged?.invoke(state, msg)
    }
}
