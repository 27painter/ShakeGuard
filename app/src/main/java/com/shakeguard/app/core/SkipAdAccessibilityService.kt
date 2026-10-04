package com.shakeguard.app.core

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 开屏广告自动跳过。
 *
 * 原理和「李跳跳」一样：无障碍服务监听界面变化，一旦出现「跳过 / 关闭广告」这类按钮就替用户点掉，
 * 不需要 root，也不修改任何应用。
 *
 * 为什么开屏广告只能这么做：开屏广告是应用自己画出来的界面，系统没有任何开关能禁止它，
 * 唯一不用 root 的办法就是「看到就点掉」。
 */
class SkipAdAccessibilityService : AccessibilityService() {

    private var lastClickAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> tryClick(e.packageName?.toString())
        }
    }

    private fun tryClick(pkg: String?) {
        if (pkg == null || pkg == applicationContext.packageName) return
        // 系统设置类界面里出现的"跳过"不是广告，直接跳过，避免误点
        if (SKIP_PACKAGES.contains(pkg)) return
        if (pkg.startsWith("com.android.settings") || pkg.startsWith("com.oplus.securitypermission")) return
        val now = System.currentTimeMillis()
        if (now - lastClickAt < MIN_INTERVAL_MS) return
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        val match = runCatching { findSkipNode(root) }.getOrNull() ?: return
        if (runCatching { click(match.node) }.getOrDefault(false)) {
            lastClickAt = System.currentTimeMillis()
            skipCount.value = skipCount.value + 1
            lastSkipLabel.value = match.label
            Log.i(TAG, "自动点击「${match.label}」来自 $pkg，累计 ${skipCount.value} 次")
        }
    }

    private data class Match(val node: AccessibilityNodeInfo, val label: String)

    private fun findSkipNode(root: AccessibilityNodeInfo): Match? {
        for (keyword in KEYWORDS) {
            val found = runCatching { root.findAccessibilityNodeInfosByText(keyword) }.getOrNull() ?: continue
            for (node in found) {
                val label = (node.text?.toString() ?: node.contentDescription?.toString())?.trim() ?: continue
                if (!isSkipLabel(label)) continue
                val target = walkUpToClickable(node) ?: continue
                return Match(target, label)
            }
        }
        return null
    }

    /** 只认这些词，避免误点正文里的「跳过」 */
    private fun isSkipLabel(label: String): Boolean {
        if (label.length > 12) return false
        // 我们自己那一行的标题（开屏广告自动跳过）也会命中"跳过"，必须排除
        if (IGNORE_LABELS.any { label.contains(it) }) return false
        return KEYWORDS.any { label.equals(it, ignoreCase = true) || label.contains(it) }
    }

    private fun walkUpToClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 4) {
            if (cur.isClickable) return cur
            cur = runCatching { cur!!.parent }.getOrNull()
            depth++
        }
        return null
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var parent = runCatching { node.parent }.getOrNull()
        var depth = 0
        while (parent != null && depth < 4) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            parent = runCatching { parent!!.parent }.getOrNull()
            depth++
        }
        return false
    }

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        alive.value = true
        Log.i(TAG, "无障碍服务已连接，可以用它来代替 Shizuku 驱动系统界面")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        alive.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        alive.value = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ShakeGuardSkip"
        private const val MIN_INTERVAL_MS = 600L

        /** 无障碍服务实例：没有 Shizuku 时就是靠它来点系统界面 */
        @Volatile
        var instance: SkipAdAccessibilityService? = null
            private set

        /** 累计自动跳过次数（App 首页会显示） */
        val skipCount = MutableStateFlow(0)
        val lastSkipLabel = MutableStateFlow("")

        /**
         * 服务是否真的处于"已连接"状态。
         * 注意：系统设置里显示"已开启"不代表服务活着 —— App 被"清除全部"杀掉之后，
         * ColorOS 会把 enabled_accessibility_services 清空，需要重新到无障碍里打开。
         */
        val alive = MutableStateFlow(false)

        private val KEYWORDS = listOf(
            "跳过", "跳过广告", "点击跳过", "关闭广告", "跳过>", "跳过 >",
            "Skip Ad", "Skip ad", "SKIP", "Skip"
        )

        /** 出现这些字样的控件不点（主要是本应用自己的名称） */
        private val IGNORE_LABELS = listOf("开屏广告自动跳过", "自动跳过", "摇一摇克星")

        /** 这些系统界面的"跳过"不是广告 */
        private val SKIP_PACKAGES = setOf(
            "com.android.settings",
            "com.android.systemui",
            "com.oplus.securitypermission",
            "moe.shizuku.privileged.api"
        )
    }
}
