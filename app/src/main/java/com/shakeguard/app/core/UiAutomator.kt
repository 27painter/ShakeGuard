package com.shakeguard.app.core

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 极简 UI 自动化：uiautomator dump 拿控件树 -> input tap 点击。
 * 全部通过 Shizuku 的 shell 通道执行，所以不需要「无障碍服务」权限。
 */
class UiAutomator {

    data class Node(
        val text: String,
        val resId: String,
        val cls: String,
        val desc: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val clickable: Boolean,
        val checked: Boolean
    ) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
    }

    var lastError: String? = null
        private set

    private var screenW = 1080
    private var screenH = 2400
    private var screenSizeReady = false

    fun dump(): List<Node> {
        val r = ShizukuShell.exec(
            "rm -f $TMP; uiautomator dump $TMP >/dev/null 2>&1; cat $TMP 2>/dev/null",
            15_000L
        )
        val xml = r.out.trim()
        if (!xml.contains("<hierarchy")) {
            lastError = r.text.ifBlank { "拿不到界面结构（uiautomator dump 失败）" }
            return emptyList()
        }
        lastError = null
        return runCatching { parse(xml) }.getOrElse {
            lastError = "解析界面结构失败：${it.message}"
            emptyList()
        }
    }

    private fun parse(xml: String): List<Node> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val nodes = doc.getElementsByTagName("node")
        val list = ArrayList<Node>(nodes.length)
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            val m = BOUNDS.find(e.getAttribute("bounds")) ?: continue
            list.add(
                Node(
                    text = e.getAttribute("text").orEmpty(),
                    resId = e.getAttribute("resource-id").orEmpty(),
                    cls = e.getAttribute("class").orEmpty(),
                    desc = e.getAttribute("content-desc").orEmpty(),
                    left = m.groupValues[1].toInt(),
                    top = m.groupValues[2].toInt(),
                    right = m.groupValues[3].toInt(),
                    bottom = m.groupValues[4].toInt(),
                    clickable = e.getAttribute("clickable") == "true",
                    checked = e.getAttribute("checked") == "true"
                )
            )
        }
        return list
    }

    private fun ensureScreenSize() {
        if (screenSizeReady) return
        screenSizeReady = true
        val out = ShizukuShell.exec("wm size", 6_000L).out
        val m = SIZE.find(out)
        if (m != null) {
            screenW = m.groupValues[1].toInt()
            screenH = m.groupValues[2].toInt()
        }
    }

    /** 向下滚动一屏（位置按实际屏幕尺寸算，不写死坐标） */
    fun scrollDown() {
        ensureScreenSize()
        val x = screenW / 2
        val from = (screenH * 0.82).toInt()
        val to = (screenH * 0.32).toInt()
        ShizukuShell.exec("input swipe $x $from $x $to 400", 8_000L)
    }

    /** 向上滚一屏（滚回上面） */
    fun scrollUp() {
        ensureScreenSize()
        val x = screenW / 2
        val from = (screenH * 0.32).toInt()
        val to = (screenH * 0.82).toInt()
        ShizukuShell.exec("input swipe $x $from $x $to 400", 8_000L)
    }

    /** 向上滚回顶部（系统设置页会保留上次的滚动位置） */
    fun scrollToTop(times: Int = 6) {
        ensureScreenSize()
        val x = screenW / 2
        val from = (screenH * 0.32).toInt()
        val to = (screenH * 0.82).toInt()
        repeat(times) {
            ShizukuShell.exec("input swipe $x $from $x $to 300", 8_000L)
            Thread.sleep(250L)
        }
    }

    fun waitExact(text: String, timeoutMs: Long = 8_000L): Node? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            dump().firstOrNull { it.text == text }?.let { return it }
            Thread.sleep(400L)
        }
        return null
    }

    /** 找不到就往下滚着找（系统设置页常常要滚动） */
    fun waitExactScrolling(text: String, timeoutMs: Long = 10_000L, maxScrolls: Int = 5): Node? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var scrolls = 0
        while (System.currentTimeMillis() < deadline) {
            dump().firstOrNull { it.text == text }?.let { return it }
            if (scrolls < maxScrolls) {
                scrollDown()
                scrolls++
                Thread.sleep(600L)
            } else {
                Thread.sleep(400L)
            }
        }
        return null
    }

    /** 按 content-desc 找（例如右上角「更多选项」按钮） */
    fun waitByDesc(desc: String, timeoutMs: Long = 8_000L): Node? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            dump().firstOrNull { it.desc == desc }?.let { return it }
            Thread.sleep(400L)
        }
        return null
    }

    fun click(node: Node): Boolean {
        ShizukuShell.exec("input tap ${node.centerX} ${node.centerY}", 8_000L)
        return true
    }

    fun clickExact(text: String, timeoutMs: Long = 8_000L): Boolean {
        val node = waitExact(text, timeoutMs) ?: return false
        return click(node)
    }

    fun back() {
        ShizukuShell.exec("input keyevent 4", 6_000L)
    }

    fun home() {
        ShizukuShell.exec("input keyevent 3", 6_000L)
    }

    fun wake() {
        ShizukuShell.exec("input keyevent KEYCODE_WAKEUP", 6_000L)
    }

    fun screenAwake(): Boolean =
        ShizukuShell.exec("dumpsys power | grep mWakefulness=", 8_000L).out.contains("Awake")

    companion object {
        private const val TMP = "/sdcard/.shakeguard_ui.xml"
        private val BOUNDS = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]")
        private val SIZE = Regex("(\\d+)x(\\d+)")
    }
}
