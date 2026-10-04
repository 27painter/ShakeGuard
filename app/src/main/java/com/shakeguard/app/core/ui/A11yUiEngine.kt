package com.shakeguard.app.core.ui

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 没有 Shizuku 时：用无障碍服务直接读写界面并点击。
 *
 * 这条路不需要 shell 权限，ColorOS 16 也能用（只要无障碍服务处于开启状态）。
 * 只在我们自己的批处理里用，不监听其它事件。
 */
class A11yUiEngine(private val service: AccessibilityService) : UiEngine() {

    override fun dump(): List<UiNode> {
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return emptyList()
        val out = ArrayList<UiNode>(160)
        runCatching { collect(root, out, 0) }
        return out
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<UiNode>, depth: Int) {
        if (node == null || depth > 40) return
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                out.add(
                    UiNode(
                        text = text,
                        resId = runCatching { node.viewIdResourceName }.getOrNull().orEmpty(),
                        cls = node.className?.toString().orEmpty(),
                        desc = desc,
                        bounds = rect,
                        clickable = node.isClickable,
                        checked = node.isChecked,
                        a11y = node
                    )
                )
            }
        }
        for (i in 0 until node.childCount) {
            collect(runCatching { node.getChild(i) }.getOrNull(), out, depth + 1)
        }
    }

    override fun click(node: UiNode): Boolean {
        // ColorOS 在无障碍被启用/重连时会弹一个安全警告
        //（「检测到XX获取无障碍权限」→「关闭无障碍」/「保持开启」），
        // 这个弹窗会盖住界面、把所有点击都吃掉，所以每次点击前先把它点掉。
        if (!node.text.contains("保持开启")) {
            runCatching { dump().firstOrNull { it.text.contains("保持开启") } }
                .getOrNull()
                ?.let { keep ->
                    clickNode(keep)
                    Thread.sleep(600L)
                }
        }
        return clickNode(node)
    }

    private fun clickNode(node: UiNode): Boolean {
        val n = node.a11y
        if (n != null) {
            if (n.isClickable && runCatching { n.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) {
                return true
            }
            var parent = runCatching { n.parent }.getOrNull()
            var depth = 0
            while (parent != null && depth < 5) {
                if (parent.isClickable &&
                    runCatching { parent.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
                ) return true
                parent = runCatching { parent!!.parent }.getOrNull()
                depth++
            }
        }
        // 兜底：找屏幕上文字相同的可点击节点再点一次
        val again = dump().firstOrNull { it.text == node.text && it.desc == node.desc && it.clickable }
        val target = again?.a11y
        return target != null &&
            runCatching { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
    }

    override fun scrollDown() = swipe(fromRatio = 0.72f, toRatio = 0.28f)

    override fun scrollUp() = swipe(fromRatio = 0.28f, toRatio = 0.72f)

    /**
     * 用真实手势滑动（模拟手指），比 ACTION_SCROLL_FORWARD 可靠得多：
     * 很多系统界面（ColorOS 的权限管理就是）不支持 ACTION_SCROLL，只认手势。
     */
    private fun swipe(fromRatio: Float, toRatio: Float) {
        val dm = service.resources.displayMetrics
        val width = dm.widthPixels.toFloat()
        val height = dm.heightPixels.toFloat()
        val x = width / 2f
        val path = Path().apply {
            moveTo(x, height * fromRatio)
            lineTo(x, height * toRatio)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 320L))
            .build()
        val ok = runCatching { service.dispatchGesture(gesture, null, null) }.getOrDefault(false)
        if (!ok) {
            // 兜底：老办法
            val root = runCatching { service.rootInActiveWindow }.getOrNull()
            val target = findScrollable(root)
            val action = if (fromRatio > toRatio) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            runCatching { target?.performAction(action) }
        }
    }

    private fun findScrollable(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 25) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            findScrollable(runCatching { node.getChild(i) }.getOrNull(), depth + 1)?.let { return it }
        }
        return null
    }

    override fun back() {
        runCatching { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
    }

    override fun home() {
        runCatching { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }
    }

    override fun wake() = Unit

    override fun screenAwake(): Boolean = true

    /** 无障碍服务启动设置首页（服务是系统绑定的，可以启动 Activity） */
    override fun startSettings() {
        runCatching {
            service.startActivity(
                Intent("android.settings.SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }
    }
}
