package com.shakeguard.app.core.ui

/**
 * 界面自动化的抽象：下面有两个实现
 *  - ShellUiEngine：走 Shizuku 的 shell（uiautomator dump + input tap），有 Shizuku 时用
 *  - A11yUiEngine： 走无障碍服务（读写界面 + performAction 点击），没有 Shizuku 时用（ColorOS 16 的情况）
 *
 * 公共的"等待/点击/滚动查找"逻辑都写在基类里，两个实现只需要提供基本动作。
 */
abstract class UiEngine {

    abstract fun dump(): List<UiNode>

    abstract fun click(node: UiNode): Boolean

    abstract fun scrollDown()

    abstract fun scrollUp()

    abstract fun back()

    abstract fun home()

    abstract fun wake()

    abstract fun screenAwake(): Boolean

    /**
     * 打开系统设置首页。
     * 注意：ColorOS 的权限相关页面属于 com.oplus.securitypermission（另一个 App），
     * 它和 com.android.settings 都会"记住上次的页面"，所以两边都要清掉才能保证从首页开始。
     */
    abstract fun startSettings()

    fun scrollToTop(times: Int = 6) {
        repeat(times) {
            scrollUp()
            Thread.sleep(250L)
        }
    }

    /** 等一个文字完全相同的控件出现 */
    fun waitExact(text: String, timeoutMs: Long = 8_000L): UiNode? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            dump().firstOrNull { it.text == text }?.let { return it }
            Thread.sleep(350L)
        }
        return null
    }

    /** 找不到就往下滚着找 */
    fun waitExactScrolling(text: String, timeoutMs: Long = 10_000L, maxScrolls: Int = 6): UiNode? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var scrolls = 0
        while (System.currentTimeMillis() < deadline) {
            dump().firstOrNull { it.text == text }?.let { return it }
            if (scrolls < maxScrolls) {
                scrollDown()
                scrolls++
                Thread.sleep(550L)
            } else {
                Thread.sleep(350L)
            }
        }
        return null
    }

    fun waitByDesc(desc: String, timeoutMs: Long = 8_000L): UiNode? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            dump().firstOrNull { it.desc == desc }?.let { return it }
            Thread.sleep(350L)
        }
        return null
    }

    fun clickExact(text: String, timeoutMs: Long = 8_000L): Boolean {
        val node = waitExact(text, timeoutMs) ?: return false
        return click(node)
    }

    fun clickExactScrolling(text: String, timeoutMs: Long = 10_000L, maxScrolls: Int = 6): Boolean {
        val node = waitExactScrolling(text, timeoutMs, maxScrolls) ?: return false
        return click(node)
    }

    fun clickDesc(desc: String, timeoutMs: Long = 8_000L): Boolean {
        val node = waitByDesc(desc, timeoutMs) ?: return false
        return click(node)
    }

    /** 统计当前屏幕上"开着的开关"数量（用于验证批量操作是否生效） */
    fun countCheckedSwitches(): Int =
        dump().count { it.cls.contains("Switch") && it.checked }
}
