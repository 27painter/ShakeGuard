package com.shakeguard.app.core

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.shakeguard.app.App
import com.shakeguard.app.R
import com.shakeguard.app.core.ui.A11yUiEngine
import com.shakeguard.app.core.ui.ShellUiEngine
import com.shakeguard.app.core.ui.UiEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class BatchProgress(
    val running: Boolean = false,
    val title: String = "",
    val total: Int = 0,
    val index: Int = 0,
    val ok: Int = 0,
    val failed: Int = 0,
    val current: String = "",
    val log: List<String> = emptyList()
) {
    val percent: Float get() = if (total <= 0) 0f else (index.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

/**
 * 批量执行器：跑在前台服务里，避免驱动系统界面时被 ColorOS 回收。
 *
 * 每处理一个应用大约 5~8 秒（要打开/点击/返回系统设置界面），
 * 所以 100 个应用大约需要 10 分钟，期间屏幕要保持点亮且不要手动操作手机。
 */
class BatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "批量处理"
        val packages = intent.getStringArrayListExtra(EXTRA_PACKAGES) ?: arrayListOf()
        val labels = intent.getStringArrayListExtra(EXTRA_LABELS) ?: arrayListOf()
        val sensorOption = intent.getStringExtra(EXTRA_SENSOR)
        val trackOption = intent.getStringExtra(EXTRA_TRACK)
        val globalAd = intent.getBooleanExtra(EXTRA_GLOBAL_AD, false)
        val singleTask = intent.getStringExtra(EXTRA_SINGLE_TASK)
        val whitelistLabels = intent.getStringArrayListExtra(EXTRA_WHITELIST_LABELS) ?: arrayListOf()

        startForeground(NOTIFICATION_ID, buildNotification(title, "准备中…", 0, packages.size))

        if (progress.value.running) return START_NOT_STICKY
        stopRequested = false
        progress.value = BatchProgress(running = true, title = title, total = if (singleTask != null) 1 else packages.size)
        scope.launch {
            when (singleTask) {
                TASK_AD_TRACK -> runSingleTask(title, TASK_AD_TRACK, emptyList())
                TASK_SHAKE_ALL -> runSingleTask(title, TASK_SHAKE_ALL, whitelistLabels)
                TASK_WHITELIST_SENSOR -> runSingleTask(title, TASK_WHITELIST_SENSOR, whitelistLabels)
                TASK_NAV_SHAKE -> runNavigateTask(title, TASK_NAV_SHAKE, whitelistLabels)
                TASK_NAV_TRACK -> runNavigateTask(title, TASK_NAV_TRACK, emptyList())
                else -> run(title, packages, labels, sensorOption, trackOption, globalAd)
            }
        }
        return START_NOT_STICKY
    }

    /**
     * 有 Shizuku 用 shell 通道；没有就用无障碍服务（ColorOS 16 只能走这条）。
     * 无障碍服务实例可能还没连上（进程刚被系统拉起来时），等一下再取。
     */
    private fun buildEngine(): UiEngine? {
        if (ShizukuShell.hasPermission()) return ShellUiEngine()
        SkipAdAccessibilityService.instance?.let { return A11yUiEngine(it) }

        appendLog("等待无障碍服务连接…（最多 8 秒）")
        repeat(16) {
            Thread.sleep(500L)
            SkipAdAccessibilityService.instance?.let {
                appendLog("无障碍服务已连上")
                return A11yUiEngine(it)
            }
        }
        return null
    }

    /**
     * 导航模式（默认用这条）：只把用户带到对应设置页，最后一步由用户自己点。
     * 比全自动可靠得多 —— 不受系统弹窗、界面差异影响。
     */
    private suspend fun runNavigateTask(title: String, task: String, whitelistLabels: List<String>) {
        try {
            val engine = buildEngine()
            if (engine == null) {
                appendLog("❌ 无障碍服务没连上，没法自动跳转。")
                appendLog("   已帮你打开无障碍设置：把「开屏广告自动跳过」关掉、再打开一次就好。")
                appendLog("   手动路径：设置 → 隐私 → 权限管理 → 权限 → 设备动作与方向 → 右上角 ⋮")
                runCatching {
                    startActivity(
                        Intent("android.settings.ACCESSIBILITY_SETTINGS")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                return
            }
            appendLog(if (ShizukuShell.hasPermission()) "通道：Shizuku" else "通道：无障碍服务")
            val automator = ColorOsAutomator(engine)
            val outcome = if (task == TASK_NAV_SHAKE) {
                automator.navigateToShakePage { appendLog(it) }
            } else {
                automator.navigateToAdTrackingPage { appendLog(it) }
            }

            val hint = if (task == TASK_NAV_SHAKE) {
                "现在请点右上角 ⋮ →「全部不允许」"
            } else {
                "现在请点右上角 ⋮ →「全部关闭」"
            }
            if (outcome.opened) {
                appendLog("✅ ${outcome.message}")
                appendLog("👉 $hint")
                if (task == TASK_NAV_SHAKE && whitelistLabels.isNotEmpty()) {
                    appendLog("提醒：白名单（${whitelistLabels.joinToString("、")}）点完「全部不允许」后要单独改回「允许」")
                }
                notifyInstruction(title, hint)
                toast(hint)
            } else {
                appendLog("⚠️ ${outcome.message}")
                notifyInstruction(title, outcome.message)
                toast("没能自动跳转，请按通知里的路径手动操作")
            }
        } catch (t: Throwable) {
            appendLog("❌ 出错：${t.javaClass.simpleName}: ${t.message}")
        } finally {
            progress.value = progress.value.copy(running = false, current = "", index = 1)
            delay(800L)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** 页面已就位：发一条普通通知提醒"最后一步"（用户照着点即可） */
    private fun notifyInstruction(title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            val n = NotificationCompat.Builder(this, App.CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            getSystemService(NotificationManager::class.java).notify(NOTIFY_INSTRUCTION_ID, n)
        }
    }

    private fun toast(text: String) {
        runCatching {
            Handler(Looper.getMainLooper()).post {
                runCatching { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
            }
        }
    }

    /** 系统级"一键"任务：①摇一摇「全部不允许」 / ②广告追踪「全部关闭」 */
    private suspend fun runSingleTask(title: String, task: String, whitelistLabels: List<String>) {
        val tuner = AnimationTuner()
        try {
            val engine = buildEngine()
            if (engine == null) {
                update { it.copy(failed = 1, index = 1) }
                appendLog("❌ 无障碍服务没连上，没办法自动点系统界面。")
                appendLog("   原因：App 被「清除全部」杀掉过之后，安卓会把无障碍服务标记成 crashed，不再自动重连。")
                appendLog("   已帮你打开无障碍设置：把「开屏广告自动跳过」关掉、再打开一次就好。")
                runCatching {
                    startActivity(
                        Intent("android.settings.ACCESSIBILITY_SETTINGS")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                return
            }
            val viaShell = ShizukuShell.hasPermission()
            appendLog(if (viaShell) "通道：Shizuku（shell）" else "通道：无障碍服务")
            tuner.disable()
            val automator = ColorOsAutomator(engine)

            val outcome = if (task == TASK_SHAKE_ALL) {
                appendLog("设置 → 隐私 → 权限管理 → 权限 → 设备动作与方向 → ⋮ → 全部不允许")
                automator.closeAllShake(whitelistLabels, viaShell) { appendLog(it) }
            } else if (task == TASK_WHITELIST_SENSOR) {
                appendLog("按白名单逐个设置「设备动作与方向」= 仅开屏时不允许（保留地图方向/体感）")
                automator.applyWhitelistOnSensorPage(whitelistLabels, SensorState.OPT_SPLASH) { appendLog(it) }
            } else {
                appendLog("设置 → 隐私 → 更多 → 设备标识与广告 → 广告跟踪 → ⋮ → 全部关闭")
                automator.closeAllAdTracking(viaShell) { appendLog(it) }
            }

            if (outcome.success) {
                update { it.copy(ok = 1, index = 1) }
                appendLog("✅ ${outcome.message}")
            } else {
                update { it.copy(failed = 1, index = 1) }
                appendLog("⚠️ ${outcome.message}")
            }

            if (task == TASK_AD_TRACK && viaShell) {
                val a = ShizukuShell.putSetting("secure", "ad_defender_switch", "1")
                appendLog(if (a.err.isBlank()) "✅ ColorOS 广告拦截已开启" else "⚠️ 广告拦截开关失败")
                val b = ShizukuShell.putSetting("system", "ad_interception_switch", "1")
                appendLog(if (b.err.isBlank()) "✅ 系统广告拦截开关已开启" else "⚠️ 系统广告拦截开关失败")
            }
        } catch (t: Throwable) {
            appendLog("❌ 出错：${t.javaClass.simpleName}: ${t.message}")
        } finally {
            tuner.restore()
            progress.value = progress.value.copy(running = false, current = "", index = 1)
            notify(title, "完成", 1, 1)
            delay(1200L)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun run(
        title: String,
        packages: List<String>,
        labels: List<String>,
        sensorOption: String?,
        trackOption: String?,
        globalAd: Boolean
    ) {
        val engine: UiEngine = ShellUiEngine()
        val automator = ColorOsAutomator(engine)
        val tuner = AnimationTuner()
        var ok = 0
        var failed = 0

        try {
            engine.wake()
            delay(500L)
            if (!engine.screenAwake()) {
                appendLog("⚠️ 屏幕没点亮/没解锁，自动化会失败，请先解锁屏幕")
            }

            tuner.disable()

            if (globalAd) {
                val a = ShizukuShell.putSetting("secure", "ad_defender_switch", "1")
                appendLog(
                    if (a.err.isBlank()) "✅ 已开启 ColorOS 广告拦截（ad_defender_switch=1）"
                    else "⚠️ 广告拦截开关失败：${a.err.trim().take(120)}"
                )
                val b = ShizukuShell.putSetting("global", "limit_ad_tracking", "1")
                appendLog(
                    if (b.err.isBlank()) "✅ 已写入 limit_ad_tracking=1（系统级限制广告追踪）"
                    else "⚠️ limit_ad_tracking 失败：${b.err.trim().take(120)}"
                )
            }

            for (i in packages.indices) {
                if (stopRequested) {
                    appendLog("⏹ 已手动停止")
                    break
                }
                val pkg = packages[i]
                val label = labels.getOrNull(i) ?: pkg
                update { it.copy(current = label, index = i) }

                val outcome = runCatching { automator.apply(pkg, sensorOption, trackOption) }
                    .getOrElse { ColorOsAutomator.Outcome(false, null, null, it.message ?: "异常") }

                if (outcome.success) ok++ else failed++
                update { it.copy(ok = ok, failed = failed, index = i + 1) }
                appendLog(
                    (if (outcome.success) "✅ " else "⚠️ ") + label +
                        (if (outcome.success) "" else "（${outcome.message}）")
                )
                notify(title, "$label  ${i + 1}/${packages.size}", i + 1, packages.size)
                delay(250L)
            }
        } catch (t: Throwable) {
            appendLog("❌ 出错：${t.javaClass.simpleName}: ${t.message}")
        } finally {
            tuner.restore()
            progress.value = progress.value.copy(running = false, current = "", index = progress.value.total)
            appendLog("🏁 结束：成功 $ok 个，失败 $failed 个")
            notify(title, "完成：成功 $ok，失败 $failed", 1, 1)
            delay(1200L)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun update(block: (BatchProgress) -> BatchProgress) {
        progress.value = block(progress.value)
    }

    private fun appendLog(line: String) {
        Log.i(TAG_LOG, line)
        update { it.copy(log = (it.log + line).takeLast(400)) }
    }

    private fun notify(title: String, text: String, index: Int, total: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification(title, text, index, total))
        }
    }

    private fun buildNotification(title: String, text: String, index: Int, total: Int): Notification {
        val stopIntent = Intent(this, StopReceiver::class.java).setAction(ACTION_STOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stopPi = PendingIntent.getBroadcast(this, 100, stopIntent, flags)
        return NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(1), index.coerceIn(0, total.coerceAtLeast(1)), false)
            .addAction(0, "停止", stopPi)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.shakeguard.app.action.STOP"

        private const val NOTIFICATION_ID = 1001
        private const val NOTIFY_INSTRUCTION_ID = 1002
        private const val TAG_LOG = "ShakeGuardBatch"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PACKAGES = "packages"
        private const val EXTRA_LABELS = "labels"
        private const val EXTRA_SENSOR = "sensor_option"
        private const val EXTRA_TRACK = "track_option"
        private const val EXTRA_GLOBAL_AD = "global_ad"
        private const val EXTRA_SINGLE_TASK = "single_task"
        private const val EXTRA_WHITELIST_LABELS = "whitelist_labels"
        const val TASK_AD_TRACK = "ad_track_all"
        const val TASK_SHAKE_ALL = "shake_all"
        const val TASK_NAV_SHAKE = "nav_shake"
        const val TASK_NAV_TRACK = "nav_track"
        const val TASK_WHITELIST_SENSOR = "whitelist_sensor"

        /** ② 的配套：把白名单里的应用（游戏 / 地图）逐个改成「仅开屏时不允许」 */
        fun startWhitelistSensor(context: Context, whitelistLabels: List<String>): Boolean {
            if (progress.value.running) return false
            val intent = Intent(context, BatchService::class.java).apply {
                putExtra(EXTRA_TITLE, "按白名单恢复")
                putExtra(EXTRA_SINGLE_TASK, TASK_WHITELIST_SENSOR)
                putStringArrayListExtra(EXTRA_WHITELIST_LABELS, ArrayList(whitelistLabels))
            }
            ContextCompat.startForegroundService(context, intent)
            return true
        }

        /** ① 把用户带到「设备动作与方向」页，最后一步交给用户 */
        fun startNavigateShake(context: Context, whitelistLabels: List<String>): Boolean {
            if (progress.value.running) return false
            val intent = Intent(context, BatchService::class.java).apply {
                putExtra(EXTRA_TITLE, "关闭摇一摇")
                putExtra(EXTRA_SINGLE_TASK, TASK_NAV_SHAKE)
                putStringArrayListExtra(EXTRA_WHITELIST_LABELS, ArrayList(whitelistLabels))
            }
            ContextCompat.startForegroundService(context, intent)
            return true
        }

        /** ② 把用户带到「广告跟踪」页，最后一步交给用户 */
        fun startNavigateAdTrack(context: Context): Boolean {
            if (progress.value.running) return false
            val intent = Intent(context, BatchService::class.java).apply {
                putExtra(EXTRA_TITLE, "关闭广告追踪")
                putExtra(EXTRA_SINGLE_TASK, TASK_NAV_TRACK)
            }
            ContextCompat.startForegroundService(context, intent)
            return true
        }

        val progress = MutableStateFlow(BatchProgress())

        @Volatile
        var stopRequested: Boolean = false
            private set

        fun requestStop() {
            stopRequested = true
        }

        /** 系统级广告跟踪一键全关（十几秒） */
        fun startAdTracking(context: Context): Boolean {
            if (progress.value.running) return false
            val intent = Intent(context, BatchService::class.java).apply {
                putExtra(EXTRA_TITLE, "关闭广告追踪")
                putExtra(EXTRA_SINGLE_TASK, TASK_AD_TRACK)
            }
            ContextCompat.startForegroundService(context, intent)
            return true
        }

        /** 摇一摇一键全关（系统自带的「全部不允许」+ 白名单恢复） */
        fun startShakeAll(context: Context, whitelistLabels: List<String>): Boolean {
            if (progress.value.running) return false
            val intent = Intent(context, BatchService::class.java).apply {
                putExtra(EXTRA_TITLE, "关闭摇一摇广告")
                putExtra(EXTRA_SINGLE_TASK, TASK_SHAKE_ALL)
                putStringArrayListExtra(EXTRA_WHITELIST_LABELS, ArrayList(whitelistLabels))
            }
            ContextCompat.startForegroundService(context, intent)
            return true
        }

        fun start(
            context: Context,
            title: String,
            packages: List<String>,
            labels: List<String>,
            sensorOption: String?,
            trackOption: String?,
            globalAd: Boolean = false
        ): Boolean {
            if (progress.value.running) return false
            if (packages.isEmpty() && !globalAd) return false
            val intent = Intent(context, BatchService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putStringArrayListExtra(EXTRA_PACKAGES, ArrayList(packages))
                putStringArrayListExtra(EXTRA_LABELS, ArrayList(labels))
                putExtra(EXTRA_SENSOR, sensorOption)
                putExtra(EXTRA_TRACK, trackOption)
                putExtra(EXTRA_GLOBAL_AD, globalAd)
            }
            ContextCompat.startForegroundService(context, intent)
            return true
        }
    }
}

/** 通知栏「停止」按钮 */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        BatchService.requestStop()
    }
}
