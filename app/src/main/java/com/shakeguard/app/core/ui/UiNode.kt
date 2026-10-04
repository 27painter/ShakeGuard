package com.shakeguard.app.core.ui

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 一个界面元素。
 * 两条通道共用它：无障碍通道（带 a11y 引用，可以直接 performAction 点击）
 * 和 shell 通道（只有坐标，用 input tap 点）。
 */
class UiNode(
    val text: String,
    val resId: String,
    val cls: String,
    val desc: String,
    val bounds: Rect,
    val clickable: Boolean,
    val checked: Boolean,
    val a11y: AccessibilityNodeInfo? = null
) {
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()
}
