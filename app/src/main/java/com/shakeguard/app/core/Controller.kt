package com.shakeguard.app.core

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.shakeguard.app.core.net.AdBlockVpnService
import com.shakeguard.app.core.net.RuleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/** 全局状态 + 动作入口（应用进程内单例） */
object Controller {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefs: Prefs? = null
    private var appContext: Context? = null
    private var listenersReady = false

    val apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val icons = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val loading = MutableStateFlow(false)
    val status = MutableStateFlow(ShizukuStatus.NOT_RUNNING)
    val message = MutableStateFlow<String?>(null)
    val sensorSupported = MutableStateFlow(true)
    val trackSupported = MutableStateFlow(true)
    val globalAdDefender = MutableStateFlow<Boolean?>(null)
    val globalLimitAd = MutableStateFlow<Boolean?>(null)
    val showSystemApps = MutableStateFlow(false)
    val accessibilityEnabled = MutableStateFlow(false)

    /** 无障碍服务是否真的连上了（设置里开着不代表连上） */
    val accessibilityLive = MutableStateFlow(false)
    val ruleCount = MutableStateFlow(0)
    val ruleUpdating = MutableStateFlow(false)

    /** 登录跳转保护开关（默认开） */
    val loginProtect = MutableStateFlow(true)

    /** 「一键全部还原」的执行报告，界面会逐行显示 */
    val restoreLog = MutableStateFlow<List<String>>(emptyList())
    val restoring = MutableStateFlow(false)

    private const val ICON_PX = 144

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (prefs == null) prefs = Prefs(ctx)
        loginProtect.value = prefs?.loginProtect ?: true
        registerListeners()
        updateStatus()
        scope.launch {
            runCatching { RuleStore.reload(ctx) }
            ruleCount.value = RuleStore.size
        }
    }

    // ---------- 广告域名拦截（本地 VPN / DNS）----------

    fun startVpn() {
        val ctx = appContext ?: return
        AdBlockVpnService.start(ctx)
    }

    fun stopVpn() {
        val ctx = appContext ?: return
        AdBlockVpnService.stop(ctx)
    }

    fun addUserRule(domain: String) {
        val ctx = appContext ?: return
        if (domain.isBlank()) return
        scope.launch {
            RuleStore.addUserRule(ctx, domain)
            ruleCount.value = RuleStore.size
            message.value = "已加入拦截：${domain.trim()}"
        }
    }

    /** 在线更新规则：依次尝试几个源，第一个成功的就用 */
    fun updateRulesOnline() {
        val ctx = appContext ?: return
        if (ruleUpdating.value) return
        scope.launch {
            ruleUpdating.value = true
            message.value = "正在下载规则…"
            var added = -1
            for (url in RuleStore.DEFAULT_SOURCES) {
                val n = RuleStore.download(ctx, url)
                if (n >= 0) {
                    added = n
                    break
                }
            }
            ruleCount.value = RuleStore.size
            ruleUpdating.value = false
            message.value = if (added >= 0) {
                "规则更新成功：新增 $added 条，当前生效 ${RuleStore.size} 条"
            } else {
                "规则更新失败（网络不可用或源被墙），稍后再试"
            }
        }
    }

    // ---------- 登录跳转保护 ----------

    fun setLoginProtect(on: Boolean) {
        val ctx = appContext ?: return
        RuleStore.setLoginProtect(ctx, on)
        loginProtect.value = on
        message.value = if (on) {
            "已开启登录跳转保护：登录 / 支付域名不再拦截"
        } else {
            "已关闭登录跳转保护：拦截更彻底，但登录跳转可能被误伤"
        }
    }

    /** 把某个被拦域名加进放行名单（用于个别 App 的登录 / 支付被误拦） */
    fun allowDomain(domain: String) {
        val ctx = appContext ?: return
        if (domain.isBlank()) return
        scope.launch {
            RuleStore.addUserAllow(ctx, domain)
            ruleCount.value = RuleStore.size
            message.value = "已放行 $domain，以后不再拦截"
        }
    }

    // ---------- 一键还原 ----------

    /** 一键还原 VPN：只关掉广告域名拦截，DNS / 网络回到系统默认 */
    fun restoreVpnOnly() {
        val ctx = appContext ?: return
        AdBlockVpnService.stop(ctx)
        restoreLog.value = listOf("已关闭广告域名拦截，DNS 已恢复系统默认（不再自动重启）")
        message.value = "VPN 已还原"
    }

    /**
     * 一键全部还原：把这个应用做过的所有改动撤回，回到「没装它」的状态。
     * 依次做四件事：关 VPN → 还原系统广告开关 → 关无障碍服务 → 逐应用还原权限。
     * 前三步几秒钟完成；第四步要挨个打开系统设置页，耗时较长，会显示进度、可随时停止。
     */
    fun restoreAll() {
        val ctx = appContext ?: return
        if (restoring.value) return
        scope.launch {
            restoring.value = true
            val log = ArrayList<String>()
            fun add(line: String) {
                log.add(line)
                restoreLog.value = log.toList()
            }
            try {
                // ① VPN
                AdBlockVpnService.stop(ctx)
                add("① 广告域名拦截（VPN）已关闭，DNS 恢复系统默认")

                val hasShizuku = status.value == ShizukuStatus.READY

                // ② 系统级广告开关回到出厂状态
                if (hasShizuku) {
                    ShizukuShell.putSetting("secure", "ad_defender_switch", "1")
                    ShizukuShell.putSetting("system", "ad_interception_switch", "0")
                    ShizukuShell.putSetting("global", "limit_ad_tracking", "0")
                    add("② 系统广告开关已还原为默认（广告追踪重新允许，这是 ColorOS 出厂状态）")
                } else {
                    add("② 系统广告开关需要 Shizuku，已跳过（可手动在 设置 → 隐私 里改回）")
                }

                // ③ 关掉无障碍服务（开屏广告自动跳过）
                val a11yOff = if (hasShizuku) disableAccessibilityService(ctx) else false
                if (a11yOff) {
                    add("③ 开屏广告自动跳过已关闭")
                } else {
                    add("③ 无障碍服务需要手动关：设置 → 无障碍 → 已下载的应用 → 开屏广告自动跳过")
                }
                refreshAccessibilityState()

                // ④ 逐应用还原权限
                val targets = apps.value.filter {
                    it.sensor == SensorState.DENIED || it.track == TrackState.DENIED
                }
                if (!hasShizuku) {
                    add("④ 各应用的权限状态读不到（需要 Shizuku），这一步跳过；" +
                        "手动方式：权限管理 → 设备动作与方向 → 选应用 → 「仅开屏时不允许」")
                } else if (targets.isEmpty()) {
                    add("④ 没有需要还原的应用权限，全部完成 ✅")
                } else {
                    val started = BatchService.start(
                        context = ctx,
                        title = "还原全部权限",
                        packages = targets.map { it.packageName },
                        labels = targets.map { it.label },
                        sensorOption = SensorState.OPT_SPLASH,
                        trackOption = TrackState.OPT_ALLOW
                    )
                    add(
                        if (started) "④ 正在把 ${targets.size} 个应用的权限逐个还原（约 ${targets.size * 7 / 60 + 1} 分钟，可随时停止）"
                        else "④ 有别的任务正在跑，权限还原没启动；等它跑完再点一次「一键全部还原」"
                    )
                }
                message.value = "已还原：VPN 已关，其余见下方报告"
            } catch (t: Throwable) {
                add("❌ 出错：${t.javaClass.simpleName}: ${t.message}")
            } finally {
                restoring.value = false
                refresh()
            }
        }
    }

    /** 通过 Shizuku 把本应用的无障碍服务从系统设置里摘掉 */
    private fun disableAccessibilityService(ctx: Context): Boolean {
        val self = ctx.packageName
        val current = ShizukuShell.getSetting("secure", "enabled_accessibility_services")
        if (current.contains("Exception", ignoreCase = true) || current.contains("Error", ignoreCase = true)) {
            return false
        }
        // settings get 在键不存在时返回字符串 "null"，别把它当成一个服务名写回去
        val rest = current.split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "null" && !it.startsWith(self) }
        val result = if (rest.isEmpty()) {
            val a = ShizukuShell.exec("settings delete secure enabled_accessibility_services", 8_000L)
            ShizukuShell.putSetting("secure", "accessibility_enabled", "0")
            a
        } else {
            ShizukuShell.putSetting("secure", "enabled_accessibility_services", rest.joinToString(":"))
        }
        return result.err.isBlank()
    }

    fun onResume() {
        updateStatus()
        refreshAccessibilityState()
        if (apps.value.isEmpty()) refresh()
        autoRestoreVpn()
    }

    /**
     * App 被系统杀过（比如在最近任务里"清除全部"）之后，本地 VPN 会跟着断。
     * 上次是开着的话，这里自动拉回来，不用用户再点一次。
     */
    private fun autoRestoreVpn() {
        val ctx = appContext ?: return
        if (prefs?.vpnDesired == true && !AdBlockVpnService.running.value) {
            AdBlockVpnService.start(ctx)
            message.value = "已自动恢复「广告域名拦截」（上次是开着时被系统清理了）"
        }
    }

    /** 检查「开屏广告自动跳过」的无障碍服务有没有被用户打开，以及有没有真的连上 */
    fun refreshAccessibilityState() {
        val ctx = appContext ?: return
        accessibilityEnabled.value = runCatching {
            val flat = android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return@runCatching false
            flat.contains("${ctx.packageName}/${SkipAdAccessibilityService::class.java.name}") ||
                flat.contains("${ctx.packageName}/.core.SkipAdAccessibilityService")
        }.getOrDefault(false)
        // 设置里开着 ≠ 服务活着：App 被系统清理后系统会把服务标成 crashed 且不再自动重连
        accessibilityLive.value = SkipAdAccessibilityService.instance != null
    }

    private fun registerListeners() {
        if (listenersReady) return
        listenersReady = true
        runCatching {
            Shizuku.addBinderReceivedListenerSticky {
                updateStatus()
                scope.launch { readGlobalFlags() }
            }
            Shizuku.addBinderDeadListener {
                status.value = ShizukuStatus.NOT_RUNNING
            }
            Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
                if (requestCode == ShizukuShell.REQUEST_CODE) {
                    message.value =
                        if (grantResult == PackageManager.PERMISSION_GRANTED) "Shizuku 授权成功"
                        else "Shizuku 授权被拒绝"
                    updateStatus()
                }
            }
        }
    }

    fun updateStatus() {
        val ctx = appContext ?: return
        status.value = when {
            !isShizukuInstalled(ctx) -> ShizukuStatus.NOT_INSTALLED
            !ShizukuShell.binderAlive() -> ShizukuStatus.NOT_RUNNING
            !ShizukuShell.hasPermission() -> ShizukuStatus.NO_PERMISSION
            else -> ShizukuStatus.READY
        }
    }

    fun requestShizukuPermission() {
        ShizukuShell.requestPermission()
    }

    private fun isShizukuInstalled(ctx: Context): Boolean = try {
        // Shizuku 的包名是 moe.shizuku.privileged.api（权限名才是 moe.shizuku.manager.permission.*）
        ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    } catch (t: Throwable) {
        false
    }

    fun refresh() {
        val ctx = appContext ?: return
        if (loading.value) return
        scope.launch {
            loading.value = true
            try {
                val pm = ctx.packageManager
                val self = ctx.packageName
                val wl = prefs?.whitelist ?: emptySet()
                var entries = pm.getInstalledApplications(0).asSequence()
                    .filter { it.packageName != self }
                    .filter { showSystemApps.value || isThirdParty(it) }
                    .map {
                        AppEntry(
                            packageName = it.packageName,
                            label = runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault(it.packageName),
                            uid = it.uid,
                            system = !isThirdParty(it),
                            whitelisted = wl.contains(it.packageName)
                        )
                    }
                    .sortedBy { it.label }
                    .toList()
                apps.value = entries

                icons.value = entries.mapNotNull { e ->
                    runCatching {
                        e.packageName to pm.getApplicationIcon(e.packageName).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
                    }.getOrNull()
                }.toMap()

                if (!ShizukuShell.hasPermission()) {
                    message.value = "Shizuku 未授权，只能显示应用列表，读不到权限状态"
                    return@launch
                }

                sensorSupported.value = ShizukuShell.opSupported(OpsReader.OP_SENSOR)
                trackSupported.value = ShizukuShell.opSupported(OpsReader.OP_APPLIST)

                val states = OpsReader.readAll(entries.map { it.packageName })
                entries = entries.map { e ->
                    states[e.packageName]?.let { e.copy(sensor = it.sensor, track = it.track) } ?: e
                }
                apps.value = entries
                readGlobalFlags()
            } catch (t: Throwable) {
                message.value = "读取失败：${t.javaClass.simpleName}: ${t.message}"
            } finally {
                loading.value = false
            }
        }
    }

    private fun isThirdParty(info: ApplicationInfo): Boolean =
        (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
            (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    fun setShowSystemApps(on: Boolean) {
        showSystemApps.value = on
        refresh()
    }

    fun readGlobalFlags() {
        if (!ShizukuShell.hasPermission()) return
        globalAdDefender.value = ShizukuShell.getSetting("secure", "ad_defender_switch").toBoolOrNull()
        globalLimitAd.value = ShizukuShell.getSetting("global", "limit_ad_tracking").toBoolOrNull()
    }

    private fun String.toBoolOrNull(): Boolean? = when (trim()) {
        "1", "true" -> true
        "0", "false" -> false
        else -> null
    }

    fun setGlobalFlag(which: String, on: Boolean) {
        scope.launch {
            if (which == "ad_defender") {
                ShizukuShell.putSetting("secure", "ad_defender_switch", if (on) "1" else "0")
            } else {
                ShizukuShell.putSetting("global", "limit_ad_tracking", if (on) "1" else "0")
            }
            readGlobalFlags()
            message.value = "系统开关已更新"
        }
    }

    // ---------- 待处理统计 ----------

    /** 需要关闭摇一摇的应用（非白名单、还没关闭的） */
    fun shakePendingCount(): Int = apps.value.count {
        !it.whitelisted && it.sensor != SensorState.DENIED && it.sensor != SensorState.UNSUPPORTED
    }

    /** 需要关闭广告追踪的应用（读取应用列表还开着的） */
    fun trackPendingCount(): Int = apps.value.count {
        it.track != TrackState.DENIED && it.track != TrackState.UNSUPPORTED
    }

    /** 已被关掉摇一摇的应用 */
    fun shakeDeniedCount(): Int = apps.value.count { it.sensor == SensorState.DENIED }

    // ---------- 批量动作 ----------

    /** ① 带用户去「设备动作与方向」页，最后一步（⋮ → 全部不允许）由用户点 */
    fun openShakePage() {
        val ctx = appContext ?: return
        launchSettings()
        val labels = apps.value.filter { it.whitelisted }.map { it.label }
        if (!BatchService.startNavigateShake(ctx, labels)) message.value = "已有任务在执行"
    }

    /** ② 的配套：把白名单里的应用（导航 / 赛车 / 体感游戏）逐个改成「仅开屏时不允许」 */
    fun restoreWhitelistSensor() {
        val ctx = appContext ?: return
        val labels = apps.value.filter { it.whitelisted }.map { it.label }
        if (labels.isEmpty()) {
            message.value = "白名单是空的：先到「应用」页把导航 / 赛车 / 体感游戏加进来"
            return
        }
        launchSettings()
        if (!BatchService.startWhitelistSensor(ctx, labels)) message.value = "已有任务在执行"
    }

    /** ② 带用户去「广告跟踪」页，最后一步（⋮ → 全部关闭）由用户点 */
    fun openAdTrackPage() {
        val ctx = appContext ?: return
        launchSettings()
        if (!BatchService.startNavigateAdTrack(ctx)) message.value = "已有任务在执行"
    }

    /** 一键关闭摇一摇：有 Shizuku 走逐应用自动化；没有就用无障碍点系统的「全部不允许」 */
    fun startDenyShake() {
        val ctx = appContext ?: return
        if (status.value == ShizukuStatus.READY) {
            val target = apps.value.filter {
                !it.whitelisted && it.sensor != SensorState.DENIED && it.sensor != SensorState.UNSUPPORTED
            }
            val started = BatchService.start(
                context = ctx,
                title = "关闭摇一摇广告",
                packages = target.map { it.packageName },
                labels = target.map { it.label },
                sensorOption = SensorState.OPT_DENY,
                trackOption = null
            )
            if (!started) message.value = "已有任务在执行，或当前没有需要处理的应用"
        } else {
            // 无障碍通道：先由前台 App 自己打开系统设置，再让服务去点界面
            val labels = apps.value.filter { it.whitelisted }.map { it.label }
            launchSettings()
            val started = BatchService.startShakeAll(ctx, labels)
            if (!started) message.value = "已有任务在执行"
        }
    }

    private fun launchSettings() {
        val ctx = appContext ?: return
        runCatching {
            // CLEAR_TASK：设置应用会记住上次停留的页面，强制清掉，保证从首页开始
            ctx.startActivity(
                Intent("android.settings.SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }
    }

    /** 一键关闭广告追踪：有 Shizuku 直接用 shell 打点，没有就用无障碍点界面 */
    fun startDenyTracking() {
        val ctx = appContext ?: return
        if (status.value != ShizukuStatus.READY) launchSettings()
        val started = BatchService.startAdTracking(ctx)
        if (!started) message.value = "已有任务在执行，请稍候"
    }

    /** 进阶：逐个应用把「读取应用列表」设为不允许（需要 Shizuku） */
    fun startDenyAppList() {
        val ctx = appContext ?: return
        if (status.value != ShizukuStatus.READY) {
            message.value = "这项需要 Shizuku，本机用不了（可以手动在系统的权限管理里改）"
            return
        }
        val target = apps.value.filter {
            it.track != TrackState.DENIED && it.track != TrackState.UNSUPPORTED
        }
        val started = BatchService.start(
            context = ctx,
            title = "禁止读取应用列表",
            packages = target.map { it.packageName },
            labels = target.map { it.label },
            sensorOption = null,
            trackOption = TrackState.OPT_DENY,
            globalAd = true
        )
        if (!started) message.value = "已有任务在执行，请稍候"
    }

    /** 全部还原成系统默认（需要 Shizuku，走逐应用自动化） */
    fun startRestoreShake() {
        val ctx = appContext ?: return
        if (status.value != ShizukuStatus.READY) {
            message.value = "还原需要 Shizuku；手动方式：权限管理 → 设备动作与方向 → 点该应用 → 选「仅开屏时不允许」"
            return
        }
        val target = apps.value.filter { it.sensor == SensorState.DENIED }
        val started = BatchService.start(
            context = ctx,
            title = "还原摇一摇设置",
            packages = target.map { it.packageName },
            labels = target.map { it.label },
            sensorOption = SensorState.OPT_SPLASH,
            trackOption = null
        )
        if (!started) message.value = "已有任务在执行，或没有需要还原的应用"
    }

    /** 单个应用：设置为指定档位 */
    fun applyOne(pkg: String, label: String, sensorOption: String?, trackOption: String?) {
        val ctx = appContext ?: return
        val started = BatchService.start(
            context = ctx,
            title = "处理 $label",
            packages = listOf(pkg),
            labels = listOf(label),
            sensorOption = sensorOption,
            trackOption = trackOption
        )
        if (!started) message.value = "已有任务在执行，请等它跑完再操作这一个应用"
    }

    /** 白名单：有 Shizuku 时自动改权限；没有就只记下来并提示手动改 */
    fun setWhitelisted(pkg: String, label: String, on: Boolean) {
        prefs?.setWhitelisted(pkg, on)
        apps.value = apps.value.map { if (it.packageName == pkg) it.copy(whitelisted = on) else it }
        if (status.value != ShizukuStatus.READY) {
            message.value = if (on) "已加入白名单；本机不能自动改权限，请手动设为「允许」"
            else "已移出白名单；本机不能自动改权限，请手动设为「不允许」"
            return
        }
        applyOne(
            pkg = pkg,
            label = label,
            sensorOption = if (on) SensorState.OPT_ALLOW else SensorState.OPT_DENY,
            trackOption = null
        )
    }

    fun clearMessage() {
        message.value = null
    }
}
