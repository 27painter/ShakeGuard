package com.shakeguard.app.core.ui

import android.graphics.Rect
import com.shakeguard.app.core.ShizukuShell
import com.shakeguard.app.core.UiAutomator

/** 有 Shizuku 时：用 shell 通道（uiautomator dump + input tap） */
class ShellUiEngine(private val ui: UiAutomator = UiAutomator()) : UiEngine() {

    override fun dump(): List<UiNode> = ui.dump().map {
        UiNode(
            text = it.text,
            resId = it.resId,
            cls = it.cls,
            desc = it.desc,
            bounds = Rect(it.left, it.top, it.right, it.bottom),
            clickable = it.clickable,
            checked = it.checked
        )
    }

    override fun click(node: UiNode): Boolean {
        // 同一个安全警告（「保持开启」）在 shell 通道下也会吃掉 input tap，先点掉它
        if (!node.text.contains("保持开启")) {
            runCatching { dump().firstOrNull { it.text.contains("保持开启") } }
                .getOrNull()
                ?.let {
                    clickRaw(it)
                    Thread.sleep(500L)
                }
        }
        return clickRaw(node)
    }

    private fun clickRaw(node: UiNode): Boolean = ui.click(
        UiAutomator.Node(
            text = node.text,
            resId = node.resId,
            cls = node.cls,
            desc = node.desc,
            left = node.bounds.left,
            top = node.bounds.top,
            right = node.bounds.right,
            bottom = node.bounds.bottom,
            clickable = node.clickable,
            checked = node.checked
        )
    )

    override fun scrollDown() = ui.scrollDown()

    override fun scrollUp() = ui.scrollUp()

    override fun back() = ui.back()

    override fun home() = ui.home()

    override fun wake() = ui.wake()

    override fun screenAwake(): Boolean = ui.screenAwake()

    override fun startSettings() {
        ShizukuShell.exec("input keyevent 3", 6_000L)
        Thread.sleep(400L)
        // 两个都要清：设置的首页在 com.android.settings，权限页面在 com.oplus.securitypermission
        ShizukuShell.exec("am force-stop com.oplus.securitypermission", 8_000L)
        ShizukuShell.exec("am force-stop com.android.settings", 8_000L)
        Thread.sleep(800L)
        ShizukuShell.exec("am start -a android.settings.SETTINGS", 12_000L)
        Thread.sleep(2_000L)
    }
}
