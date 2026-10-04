package com.shakeguard.app.core

/** 摇一摇 / 设备方向传感器状态（对应 ColorOS「设备动作与方向」的三档） */
enum class SensorState(val label: String, val short: String) {
    ALLOWED("允许（可以摇一摇）", "允许"),
    SPLASH_ONLY("仅开屏时不允许（系统默认）", "仅开屏"),
    DENIED("不允许（已彻底关闭）", "已关闭"),
    UNSUPPORTED("本机没有该开关", "不支持"),
    UNKNOWN("未知", "未知");

    companion object {
        /** ColorOS 界面里的三档文案 */
        const val OPT_ALLOW = "允许"
        const val OPT_SPLASH = "仅开屏时不允许"
        const val OPT_DENY = "不允许"
    }
}

/** 读取应用列表（广告定向里最常被滥用的一项） */
enum class TrackState(val label: String, val short: String) {
    ALLOWED("允许读取应用列表", "已允许"),
    DENIED("不允许读取应用列表", "已禁止"),
    DEFAULT("未授权（等同于禁止）", "未授权"),
    UNSUPPORTED("本机没有该开关", "不支持"),
    UNKNOWN("未知", "未知");

    companion object {
        const val OPT_ALLOW = "允许"
        const val OPT_DENY = "不允许"
    }
}

/** 要处理的目标 */
enum class Target { SENSOR, TRACK }

data class AppEntry(
    val packageName: String,
    val label: String,
    val uid: Int,
    val system: Boolean,
    val sensor: SensorState = SensorState.UNKNOWN,
    val track: TrackState = TrackState.UNKNOWN,
    val whitelisted: Boolean = false
)

enum class ShizukuStatus { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }
