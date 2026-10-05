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

    private const val ICON_PX = 144

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (prefs == null) prefs = Prefs(ctx)
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
