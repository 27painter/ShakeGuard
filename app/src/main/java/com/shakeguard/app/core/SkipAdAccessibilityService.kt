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
 * v1.1 重点：不再乱点。以前只要是「跳过」就点，结果把登录页 / 授权页上的「跳过」
 * 也点掉了 —— 表现就是「点微信登录，跳过去又弹回来」。现在有三道闸：
 *
 *   闸 1（时间窗）：只在该应用「刚刚被切到前台」的 7 秒内点，过了这段时间一律不点。
 *                   开屏广告就在这几秒里，登录页/授权页都在之后。
 *   闸 2（登录页识别）：当前界面出现「登录 / 授权 / 验证码 / 手机号」这类字样时，绝不点。
 *   闸 3（授权宿主保护）：微信 / QQ / 支付宝 / 淘宝 这几个「登录中转站」完全不去点，
 *                   宁可漏跳它们自己的开屏广告，也不冒险打断登录跳转。
 */
class SkipAdAccessibilityService : AccessibilityService() {

    private var lastClickAt = 0L

    /** 当前前台包名，以及它「刚被切到前台」的时间 */
    private var foregroundPkg: String? = null
    private val firstSeenAt = HashMap<String, Long>()

    /** 识别到登录页后，这段时间内不再对该应用做任何点击 */
    private var loginScreenPkg: String? = null
    private var loginScreenUntil = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString()
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                noteForeground(pkg)
                tryClick(pkg)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> tryClick(pkg)
        }
    }

    private fun noteForeground(pkg: String?) {
        if (pkg == null) return
        if (pkg == foregroundPkg) return
        foregroundPkg = pkg
        val now = System.currentTimeMillis()
        firstSeenAt[pkg] = now
        if (firstSeenAt.size > 200) {
            // 只保留最近 100 个应用的记录，避免长期运行把内存堆起来
            val keep = firstSeenAt.entries.sortedByDescending { it.value }.take(100)
            firstSeenAt.clear()
            keep.forEach { firstSeenAt[it.key] = it.value }
        }
    }

    private fun tryClick(pkg: String?) {
        if (pkg == null || pkg == applicationContext.packageName) return
        // 系统设置类界面里出现的"跳过"不是广告，直接跳过，避免误点
        if (SKIP_PACKAGES.contains(pkg)) return
        if (pkg.startsWith("com.android.settings") || pkg.startsWith("com.oplus.securitypermission")) return
        // 闸 3：登录中转站，一律不点
        if (LOGIN_HOST_PACKAGES.contains(pkg)) return

        val now = System.currentTimeMillis()
        if (now - lastClickAt < MIN_INTERVAL_MS) return

        // 闸 1：只在该应用刚进前台的开屏窗口内动手
        val seen = firstSeenAt[pkg] ?: return
        if (now - seen > SPLASH_WINDOW_MS) return

        // 刚识别出这是登录页：安静一会儿
        if (loginScreenPkg == pkg && now < loginScreenUntil) return

        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        val match = runCatching { findSkipNode(root) }.getOrNull() ?: return

        // 闸 2：界面上有登录/授权字样就不点
        if (runCatching { looksLikeLoginScreen(root) }.getOrDefault(false)) {
            loginScreenPkg = pkg
            loginScreenUntil = now + LOGIN_SCREEN_QUIET_MS
            guardCount.value = guardCount.value + 1
            lastGuardLabel.value = match.label
            Log.i(TAG, "识别为登录/授权页，放弃点击「${match.label}」（$pkg）")
            return
        }

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
        // 「跳过登录」「跳过绑定」这类不是广告按钮
        if (NOT_AD_LABELS.any { label.contains(it) }) return false
        return KEYWORDS.any { label.equals(it, ignoreCase = true) || label.contains(it) }
    }

    /**
     * 界面上出现这些字样，就认为当前是登录 / 授权 / 支付页。
     * 这时候的「跳过」多半是「跳过登录」「暂不绑定」，点了就把用户踢出登录流程。
     */
    private fun looksLikeLoginScreen(root: AccessibilityNodeInfo): Boolean {
        for (hint in LOGIN_HINTS) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByText(hint) }.getOrNull() ?: continue
            if (nodes.isNotEmpty()) return true
        }
        return false
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

        /** 开屏窗口：应用被切到前台后只在这段时间内找「跳过」 */
        private const val SPLASH_WINDOW_MS = 7_000L

        /** 一旦认出登录页，这个应用安静 20 秒 */
        private const val LOGIN_SCREEN_QUIET_MS = 20_000L

        /** 无障碍服务实例：没有 Shizuku 时就是靠它来点系统界面 */
        @Volatile
        var instance: SkipAdAccessibilityService? = null
            private set

        /** 累计自动跳过次数（App 首页会显示） */
        val skipCount = MutableStateFlow(0)
        val lastSkipLabel = MutableStateFlow("")

        /** 因为「看起来像登录页」而主动放弃点击的次数（首页会显示，让你知道保护在生效） */
        val guardCount = MutableStateFlow(0)
        val lastGuardLabel = MutableStateFlow("")

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

        /** 带这些字样的「跳过」不是广告按钮，是登录流程里的「跳过登录 / 跳过绑定」 */
        private val NOT_AD_LABELS = listOf("登录", "登陆", "注册", "绑定", "授权", "跳过验证")

        /** 界面上出现这些字样 → 判定为登录 / 授权 / 支付页 */
        private val LOGIN_HINTS = listOf(
            "登录", "登陆", "一键登录", "授权", "手机号", "验证码",
            "扫码登录", "其他登录方式", "本机号码", "同意并"
        )

        /**
         * 登录中转站：微信 / QQ / 支付宝 / 淘宝。
         * 跳转登录时目标就是这几个 App，它们自己也有开屏广告 —— 但为了不打断登录，全部不点。
         */
        private val LOGIN_HOST_PACKAGES = setOf(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.eg.android.AlipayGphone",
            "com.taobao.taobao"
        )

        /** 这些系统界面的"跳过"不是广告 */
        private val SKIP_PACKAGES = setOf(
            "com.android.settings",
            "com.android.systemui",
            "com.oplus.securitypermission",
            "moe.shizuku.privileged.api"
        )
    }
}
