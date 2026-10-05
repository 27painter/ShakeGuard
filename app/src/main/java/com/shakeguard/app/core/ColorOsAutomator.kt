package com.shakeguard.app.core

import com.shakeguard.app.core.ui.UiEngine

/**
 * ColorOS 界面自动化。两条通道共用同一套流程：
 *  - 有 Shizuku：ShellUiEngine（uiautomator dump + input tap）
 *  - 没 Shizuku：A11yUiEngine（无障碍服务直接读写界面 + performAction）
 *
 * 实测出来的两条"一键"路径（ColorOS 16 / OPPO PMM110）：
 *
 * ① 摇一摇：
 *    设置 → 隐私 → 权限管理 →「权限」标签 → 设备动作与方向 → 右上角 ⋮ →「全部不允许」→ 确认
 *    然后按白名单把要保留的应用单独改回「允许」
 *
 * ② 广告追踪：
 *    设置 → 隐私 →（拉到最下）更多 → 设备标识与广告 → 广告跟踪 → 右上角 ⋮ →「全部关闭」
 */
class ColorOsAutomator(private val ui: UiEngine) {

    data class Outcome(
        val opened: Boolean,
        val sensorDone: Boolean?,
        val trackDone: Boolean?,
        val message: String,
        /** true = 结束时留在当前界面（导航模式：最后一步交给用户点） */
        val stay: Boolean = false
    ) {
        val success: Boolean
            get() = opened && sensorDone != false && trackDone != false
    }

    /**
     * 单个应用（只在有 Shizuku 时用）：走"应用详情 → 权限管理"那一套。
     * @param sensorOption null 表示不动传感器开关；否则 "允许" / "仅开屏时不允许" / "不允许"
     * @param trackOption  null 表示不动读取应用列表；否则 "允许" / "不允许"
     */
    fun apply(pkg: String, sensorOption: String?, trackOption: String?): Outcome {
        if (!ShizukuShell.hasPermission()) return fail("单个应用处理需要 Shizuku")
        ShizukuShell.exec("am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$pkg", 12_000L)

        if (!ui.clickExact(TXT_PERM_ENTRY, 9_000L)) {
            ui.home()
            return Outcome(false, null, null, "没找到「权限管理」入口")
        }
        Thread.sleep(700L)

        var sensor: Boolean? = null
        var track: Boolean? = null
        var message = "ok"

        if (sensorOption != null) {
            val row = ui.waitExact(TXT_SENSOR_ROW, 7_000L)
            if (row == null) {
                sensor = false
                message = "该应用没有「设备动作与方向」开关"
            } else {
                ui.click(row)
                Thread.sleep(800L)
                sensor = ui.clickExact(sensorOption, 7_000L)
                if (sensor == false) message = "没找到「$sensorOption」选项"
                Thread.sleep(400L)
                ui.back()
                Thread.sleep(500L)
            }
        }

        if (trackOption != null) {
            val row = ui.waitExact(TXT_APPLIST_ROW, 7_000L)
            if (row == null) {
                track = false
                if (message == "ok") message = "该应用没有「读取应用列表」开关"
            } else {
                ui.click(row)
                Thread.sleep(800L)
                track = ui.clickExact(trackOption, 7_000L)
                if (track == false && message == "ok") message = "没找到「$trackOption」选项"
                Thread.sleep(400L)
                ui.back()
                Thread.sleep(500L)
            }
        }

        ui.home()
        Thread.sleep(300L)
        return Outcome(true, sensor, track, message)
    }

    /**
     * ① 一键关闭摇一摇：用系统自带的「全部不允许」批量关，再把白名单改回「允许」。
     * @param whitelistLabels 白名单应用的显示名（要和系统列表里的名字一致）
     * @param viaShell true=自己用 shell 打开设置；false=调用方（前台 Activity）已经打开了设置
     */
    fun closeAllShake(
        whitelistLabels: List<String>,
        viaShell: Boolean,
        onLog: (String) -> Unit = {}
    ): Outcome {
        onLog("打开系统设置…")
        val privacy = ensurePrivacyEntry() ?: return fail("没找到「隐私」入口")
        onLog("① 找到「隐私」")
        ui.click(privacy)
        Thread.sleep(900L)

        if (!ui.clickExactScrolling(TXT_PERM_MANAGER, 12_000L, 5)) return fail("没找到「权限管理」")
        onLog("② 进入「权限管理」")
        Thread.sleep(1_200L)

        // 「权限」标签（已经在权限列表里时点它没副作用）
        ui.clickExact(TXT_TAB_PERMISSION, 3_500L)
        Thread.sleep(700L)
        onLog("③ 切到「权限」标签")

        if (!ui.clickExactScrolling(TXT_SENSOR_ROW, 15_000L, 10)) return fail("没找到「设备动作与方向」")
        onLog("④ 打开「设备动作与方向」")
        Thread.sleep(1_200L)

        if (!ui.clickDesc(TXT_MORE_OPTIONS, 8_000L)) return fail("没找到右上角菜单")
        Thread.sleep(900L)
        onLog("⑤ 点开右上角菜单")
        if (!ui.clickExact(TXT_DENY_ALL, 6_000L)) return fail("没找到「全部不允许」")
        onLog("⑥ 点「全部不允许」")
        Thread.sleep(1_200L)

        for (label in CONFIRM_LABELS) {
            if (ui.clickExact(label, 2_000L)) {
                Thread.sleep(1_200L)
                break
            }
        }
        onLog("已点「全部不允许」")

        // 白名单恢复成「允许」
        var restored = 0
        for (label in whitelistLabels) {
            val row = ui.waitExactScrolling(label, 8_000L, 8) ?: continue
            if (!ui.click(row)) continue
            Thread.sleep(900L)
            if (ui.clickExact(SensorState.OPT_ALLOW, 5_000L)) restored++
            Thread.sleep(600L)
        }
        if (whitelistLabels.isNotEmpty()) onLog("白名单已恢复 $restored/${whitelistLabels.size} 个")

        ui.home()
        return Outcome(true, true, null, "全部不允许完成，白名单恢复 $restored 个")
    }

    /** ② 一键关闭广告追踪：系统自带的「全部关闭」 */
    fun closeAllAdTracking(viaShell: Boolean, onLog: (String) -> Unit = {}): Outcome {
        onLog("打开系统设置…")
        val privacyEntry = ensurePrivacyEntry() ?: return fail("没找到「隐私」入口")
        onLog("① 找到「隐私」")
        ui.click(privacyEntry)
        Thread.sleep(900L)

        val entry = ui.waitExactScrolling(TXT_DEVICE_ID_AD, 12_000L, 8)
            ?: return fail("没找到「设备标识与广告」")
        onLog("② 打开「设备标识与广告」")
        ui.click(entry)
        Thread.sleep(900L)

        val track = ui.waitExact(TXT_AD_TRACK, 8_000L)
            ?: return fail("没找到「广告跟踪」")
        ui.click(track)
        Thread.sleep(1_200L)
        onLog("③ 打开「广告跟踪」")

        val more = ui.waitByDesc(TXT_MORE_OPTIONS, 8_000L)
            ?: return fail("没找到右上角菜单")
        ui.click(more)
        Thread.sleep(900L)
        onLog("④ 点开右上角菜单")

        val all = ui.waitExact(TXT_CLOSE_ALL, 6_000L)
            ?: return fail("没找到「全部关闭」")
        ui.click(all)
        Thread.sleep(1_500L)
        onLog("⑤ 点「全部关闭」")

        val stillOn = ui.countCheckedSwitches()
        ui.home()
        return Outcome(true, null, stillOn == 0, if (stillOn == 0) "已全部关闭" else "还有 $stillOn 个没关掉")
    }

    /**
     * 打开系统设置并找到「隐私」入口。
     * ColorOS 上「设置」和「权限」是两个不同的应用，而且都会记住上次停留的页面
     * （常见情况：上次自动化停在"设备动作与方向"页，这次就找不到"隐私"了）。
     *
     * 处理办法：先看当前页面；找不到就一路返回，每退一步都重新找一次
     * （找到就停，所以不会误退出设置）；退得太远时让无障碍服务重新打开设置。
     */
    private fun ensurePrivacyEntry(): com.shakeguard.app.core.ui.UiNode? {
        ui.scrollToTop(3)
        findPrivacy()?.let { return it }

        repeat(7) { round ->
            ui.back()
            Thread.sleep(500L)
            ui.scrollToTop(2)
            findPrivacy()?.let { return it }

            if (round == 3) {
                // 已经退到设置外面了：请无障碍服务重新打开一次
                ui.startSettings()
                Thread.sleep(2_000L)
                ui.scrollToTop(3)
                findPrivacy()?.let { return it }
            }
        }
        return null
    }

    private fun findPrivacy(): com.shakeguard.app.core.ui.UiNode? =
        ui.dump().firstOrNull { it.text == TXT_PRIVACY }
            ?: ui.waitExactScrolling(TXT_PRIVACY, 3_000L, 3)

    /**
     * 只导航、不点最后的批量动作 —— 把最后一下交给用户（更可靠也更透明）。
     * ① 摇一摇：跳到「设备动作与方向」页，用户只需点右上角 ⋮ →「全部不允许」
     */
    fun navigateToShakePage(onLog: (String) -> Unit = {}): Outcome {
        onLog("正在打开：设置 → 隐私 → 权限管理 → 权限 → 设备动作与方向 …")
        val privacy = ensurePrivacyEntry() ?: return stopAt("没能自动定位到「隐私」页")
        ui.click(privacy)
        Thread.sleep(900L)
        if (!ui.clickExactScrolling(TXT_PERM_MANAGER, 12_000L, 5)) return stopAt("没能自动点开「权限管理」")
        Thread.sleep(1_200L)
        ui.clickExact(TXT_TAB_PERMISSION, 3_500L)
        Thread.sleep(700L)
        if (!ui.clickExactScrolling(TXT_SENSOR_ROW, 15_000L, 10)) return stopAt("没能自动找到「设备动作与方向」")
        Thread.sleep(800L)
        onLog("✅ 已到「设备动作与方向」页，最后一步交给你")
        return Outcome(true, null, null, "已到页面：请点右上角 ⋮ →「全部不允许」", stay = true)
    }

    /** ② 广告追踪：跳到「广告跟踪」页，用户只需点右上角 ⋮ →「全部关闭」 */
    fun navigateToAdTrackingPage(onLog: (String) -> Unit = {}): Outcome {
        onLog("正在打开：设置 → 隐私 → 更多 → 设备标识与广告 → 广告跟踪 …")
        val privacy = ensurePrivacyEntry() ?: return stopAt("没能自动定位到「隐私」页")
        ui.click(privacy)
        Thread.sleep(900L)

        val entry = ui.waitExactScrolling(TXT_DEVICE_ID_AD, 12_000L, 8)
            ?: return stopAt("没能自动找到「设备标识与广告」")
        ui.click(entry)
        Thread.sleep(900L)

        val track = ui.waitExact(TXT_AD_TRACK, 8_000L)
            ?: return stopAt("没能自动找到「广告跟踪」")
        ui.click(track)
        Thread.sleep(1_000L)
        onLog("✅ 已到「广告跟踪」页，最后一步交给你")
        return Outcome(true, null, null, "已到页面：请点右上角 ⋮ →「全部关闭」", stay = true)
    }

    /** 导航模式下的失败：不退出设置界面，让用户接着手动点 */
    private fun stopAt(message: String): Outcome =
        Outcome(false, null, null, message, stay = true)

    /**
     * ② 的配套功能：跳到「设备动作与方向」页，把白名单里的应用逐个改成指定档位。
     * 默认用「仅开屏时不允许」—— 开屏那 6 秒的摇一摇广告照样拦，
     * 但 App 内的地图方向指示 / 体感传感器正常可用（导航、赛车、体感游戏专用档）。
     */
    fun applyWhitelistOnSensorPage(
        whitelistLabels: List<String>,
        option: String = SensorState.OPT_SPLASH,
        onLog: (String) -> Unit = {}
    ): Outcome {
        onLog("正在打开：设置 → 隐私 → 权限管理 → 权限 → 设备动作与方向 …")
        val privacy = ensurePrivacyEntry() ?: return stopAt("没能自动定位到「隐私」页")
        ui.click(privacy)
        Thread.sleep(900L)
        if (!ui.clickExactScrolling(TXT_PERM_MANAGER, 12_000L, 5)) return stopAt("没能自动点开「权限管理」")
        Thread.sleep(1_200L)
        ui.clickExact(TXT_TAB_PERMISSION, 3_500L)
        Thread.sleep(700L)
        if (!ui.clickExactScrolling(TXT_SENSOR_ROW, 15_000L, 10)) return stopAt("没能自动找到「设备动作与方向」")
        Thread.sleep(1_000L)
        onLog("✅ 已到「设备动作与方向」页，开始按白名单逐个设置")

        var done = 0
        val failed = ArrayList<String>()
        for (label in whitelistLabels) {
            // 系统列表按拼音排序、有上百个应用，白名单里的名字可能排在很后面，所以要多滚一段
            val row = ui.waitExactScrolling(label, 12_000L, 30)
            if (row == null) {
                failed += label
                onLog("⚠️ 列表里没找到「$label」（可能系统里显示的名字不同，请手动改）")
                continue
            }
            if (!ui.click(row)) {
                failed += label
                continue
            }
            Thread.sleep(900L)
            if (ui.clickExact(option, 5_000L)) {
                done++
                onLog("✅ $label → $option")
            } else {
                failed += label
                onLog("⚠️ $label 没能选中「$option」")
            }
            Thread.sleep(600L)
        }

        if (failed.isNotEmpty()) onLog("需要手动处理：${failed.joinToString("、")}")
        ui.home()
        return Outcome(
            true, null, null,
            "白名单设置完成：成功 $done / ${whitelistLabels.size} 个" +
                if (failed.isEmpty()) "" else "（${failed.size} 个需手动）"
        )
    }

    private fun fail(message: String): Outcome {
        ui.home()
        return Outcome(false, null, null, message)
    }

    companion object {
        const val TXT_PERM_ENTRY = "权限管理"
        const val TXT_SENSOR_ROW = "设备动作与方向"
        const val TXT_APPLIST_ROW = "读取应用列表"

        const val TXT_PRIVACY = "隐私"
        const val TXT_PERM_MANAGER = "权限管理"
        const val TXT_TAB_PERMISSION = "权限"
        const val TXT_DENY_ALL = "全部不允许"

        const val TXT_DEVICE_ID_AD = "设备标识与广告"
        const val TXT_AD_TRACK = "广告跟踪"
        const val TXT_CLOSE_ALL = "全部关闭"
        const val TXT_MORE_OPTIONS = "更多选项"

        private val CONFIRM_LABELS = listOf("确定", "允许", "确认", "好")
    }
}

/** 批量自动化期间临时关掉动画，结束后恢复原值（能显著提高点击/取界面的成功率） */
class AnimationTuner {

    private val keys = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
    private val backup = HashMap<String, String>()

    fun disable() {
        if (!ShizukuShell.hasPermission()) return
        keys.forEach { k ->
            backup[k] = ShizukuShell.getSetting("global", k)
            ShizukuShell.putSetting("global", k, "0")
        }
    }

    fun restore() {
        if (!ShizukuShell.hasPermission()) return
        keys.forEach { k ->
            val old = backup[k]
            if (old == null || old == "null") {
                ShizukuShell.exec("settings delete global $k", 6_000L)
            } else {
                ShizukuShell.putSetting("global", k, old)
            }
        }
    }
}
