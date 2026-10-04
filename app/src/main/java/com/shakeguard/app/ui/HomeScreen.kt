package com.shakeguard.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shakeguard.app.core.BatchProgress
import com.shakeguard.app.core.BatchService
import com.shakeguard.app.core.Controller
import com.shakeguard.app.core.SensorState
import com.shakeguard.app.core.ShizukuStatus
import com.shakeguard.app.core.SkipAdAccessibilityService
import com.shakeguard.app.core.net.AdBlockVpnService

private enum class ConfirmKind { SHAKE, TRACK, RESTORE }

/** 没有 Shizuku 时改走"手动步骤引导" */
private enum class GuideKind { SHAKE, TRACK }

@Composable
fun HomeScreen(onRequestNotification: () -> Unit) {
    val status by Controller.status.collectAsState()
    val loading by Controller.loading.collectAsState()
    val apps by Controller.apps.collectAsState()
    val progress by BatchService.progress.collectAsState()
    val sensorSupported by Controller.sensorSupported.collectAsState()
    val a11yOn by Controller.accessibilityEnabled.collectAsState()
    val a11yAlive by Controller.accessibilityLive.collectAsState()
    var confirm by remember { mutableStateOf<ConfirmKind?>(null) }
    var guide by remember { mutableStateOf<GuideKind?>(null) }
    val context = LocalContext.current
    val shizukuReady = status == ShizukuStatus.READY
    // 只要开关是开着的就去试（真正掉线时，批处理会等 8 秒、报明原因并引导去重开）
    val channelReady = shizukuReady || a11yOn
    val canAuto = shizukuReady || a11yOn
    val channel = if (shizukuReady) "Shizuku" else "无障碍服务"

    fun startNav(kind: GuideKind) {
        onRequestNotification()
        if (kind == GuideKind.SHAKE) Controller.openShakePage() else Controller.openAdTrackPage()
    }

    fun startAuto(kind: GuideKind) {
        if (kind == GuideKind.SHAKE) confirm = ConfirmKind.SHAKE else confirm = ConfirmKind.TRACK
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ① 无障碍服务（开屏广告自动跳过）—— 放在第一位，它是唯一"开了就一直生效"的功能
        AccessibilityCard()

        if (!sensorSupported) {
            NoticeCard("本机没有 ColorOS 的「设备动作与方向」开关，摇一摇那部分用不了（本工程主要针对 OPPO / 一加 / ColorOS）。")
        }

        NoticeCard(
            "⚠️ 请先给本应用「加锁」，否则自动跳转会失效\n" +
                "1. 在「最近任务（多任务）」里把本应用的卡片向下拖动加锁 🔒\n" +
                "2. 设置 → 应用 → 摇一摇克星 → 耗电管理 → 允许后台活动（建议同时开启「允许自启动」）\n\n" +
                "原因：应用被系统清理后，Android 会把无障碍服务标记为「已崩溃」并停止自动重连，此时自动跳转会失败。\n" +
                "恢复方法：设置 → 无障碍 →「已下载的应用」→ 开屏广告自动跳过 → 关闭后重新打开一次。"
        )

        ActionCard(
            title = "② 关闭摇一摇广告",
            desc = "一键自动跳到系统的「设备动作与方向」页，最后一步由你确认：" +
                "右上角 ⋮ →「全部不允许」，所有第三方应用的摇一摇广告即失效。",
            footer = if (progress.running) "正在跳转…" else "跳到目标页后，你只需按最后一下：⋮ →「全部不允许」",
            footerColor = if (progress.running) WarnColor else OkColor,
            enabled = !progress.running,
            buttonText = "跳到设置页（最后一步我来点）",
            onClick = { startNav(GuideKind.SHAKE) },
            links = listOf(
                "一键全自动关掉" to { startAuto(GuideKind.SHAKE) },
                "查看手动步骤" to { guide = GuideKind.SHAKE }
            )
        )

        ActionCard(
            title = "③ 关闭广告追踪（OAID）",
            desc = "一键自动跳到系统的「广告跟踪」页，最后一步由你确认：" +
                "右上角 ⋮ →「全部关闭」，一次性关闭所有应用（含系统应用）读取 OAID 的能力，个性化广告会明显减少。",
            footer = if (progress.running) "正在跳转…" else "跳到目标页后，你只需按最后一下：⋮ →「全部关闭」",
            footerColor = if (progress.running) WarnColor else OkColor,
            enabled = !progress.running,
            buttonText = "跳到设置页（最后一步我来点）",
            onClick = { startNav(GuideKind.TRACK) },
            links = listOf(
                "一键全自动关掉" to { startAuto(GuideKind.TRACK) },
                "查看手动步骤" to { guide = GuideKind.TRACK }
            )
        )

        AdBlockCard()

        ActionCard(
            title = "⑤ 还原成系统默认",
            desc = "把已被改为「不允许」的应用还原为「仅开屏时不允许」（ColorOS 原始默认档，只拦截开屏的 6 秒）。" +
                if (shizukuReady) ""
                else "\n\n此功能需要 Shizuku。手动方式：权限管理 → 设备动作与方向 → 选择应用 → 「仅开屏时不允许」。",
            footer = if (shizukuReady) "已关闭：${apps.count { it.sensor == SensorState.DENIED }} 个应用" else "需要 Shizuku",
            footerColor = WarnColor,
            enabled = !progress.running && shizukuReady && apps.any { it.sensor == SensorState.DENIED },
            onClick = { confirm = ConfirmKind.RESTORE }
        )

        // 运行环境状态放最后：属于诊断信息，不影响主要功能
        ShizukuCard(status, loading)

        if (progress.running || progress.log.isNotEmpty()) {
            ProgressCard(progress)
        }

        NoticeCard(
            "执行期间会自己打开/点击系统设置界面，请保持屏幕点亮、不要手动操作手机。" +
                "「关闭摇一摇」每个应用约 5~8 秒；「关闭广告追踪」是系统级一键，十几秒。随时可停止。"
        )
    }

    confirm?.let { kind ->
        val title: String
        val body: String
        when (kind) {
            ConfirmKind.SHAKE -> {
                title = "关闭摇一摇广告"
                body = "将打开系统设置，自动点：隐私 → 权限管理 → 设备动作与方向 → ⋮ → 全部不允许。\n\n" +
                    "通道：$channel。执行期间请不要碰手机（自动化正在点系统界面）。\n\n现在开始吗？"
            }
            ConfirmKind.TRACK -> {
                title = "关闭广告追踪"
                body = "将打开系统设置，自动点：隐私 → 更多 → 设备标识与广告 → 广告跟踪 → ⋮ → 全部关闭。\n\n" +
                    "通道：$channel。这会关掉所有应用（含系统应用）的 OAID 广告跟踪。\n\n现在开始吗？"
            }
            else -> {
                title = "还原摇一摇设置"
                body = "将把 ${apps.count { it.sensor == SensorState.DENIED }} 个应用改回“仅开屏时不允许”。"
            }
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    onRequestNotification()
                    when (kind) {
                        ConfirmKind.SHAKE -> Controller.startDenyShake()
                        ConfirmKind.TRACK -> Controller.startDenyTracking()
                        ConfirmKind.RESTORE -> Controller.startRestoreShake()
                    }
                }) { Text("开始") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text("取消") }
            }
        )
    }

    // 没有 Shizuku（例如 ColorOS 16 限制了 adb 权限）时，走手动步骤引导
    guide?.let { kind ->
        val title = if (kind == GuideKind.SHAKE) "手动关闭摇一摇广告" else "手动关闭广告追踪"
        val body = if (kind == GuideKind.SHAKE) {
            "1. 设置 →「隐私」→「权限管理」\n" +
                "2. 点最上面的「权限」标签\n" +
                "3. 找到「设备动作与方向」点进去\n" +
                "4. 点右上角 ⋮ →「全部不允许」\n\n" +
                "导航、赛车游戏要保留摇一摇：在第 3 步的列表里点它 → 选「允许」。\n\n" +
                "（这台手机不让 App 自动点系统界面，所以只能手点；嫌麻烦可以插数据线，我用电脑帮你批量跑 100 多个应用。）"
        } else {
            "1. 设置 →「隐私」\n" +
                "2. 拉到最下面 →「更多」\n" +
                "3. 点「设备标识与广告」→「广告跟踪」\n" +
                "4. 点右上角 ⋮ →「全部关闭」\n\n" +
                "一次就能把所有应用（含系统应用）的 OAID 跟踪关掉。以后新装了 App 再点一次「全部关闭」即可。"
        }
        AlertDialog(
            onDismissRequest = { guide = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = {
                    openPrivacySettings(context)
                    guide = null
                }) { Text("打开系统设置") }
            },
            dismissButton = {
                TextButton(onClick = { guide = null }) { Text("知道了") }
            }
        )
    }
}

@Composable
private fun ActionCard(
    title: String,
    desc: String,
    footer: String,
    footerColor: Color,
    enabled: Boolean,
    buttonText: String = "开始",
    onClick: () -> Unit,
    links: List<Pair<String, () -> Unit>> = emptyList()
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(footer, style = MaterialTheme.typography.bodySmall, color = footerColor)
            Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(buttonText)
            }
            if (links.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    links.forEach { (label, action) ->
                        TextButton(onClick = action, enabled = enabled) {
                            Text(label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessibilityCard() {
    val context = LocalContext.current
    val enabled by Controller.accessibilityEnabled.collectAsState()
    val alive by Controller.accessibilityLive.collectAsState()
    val skipped by SkipAdAccessibilityService.skipCount.collectAsState()
    val lastLabel by SkipAdAccessibilityService.lastSkipLabel.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "① 开屏广告自动跳过",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "利用 Android 无障碍服务，在开屏广告出现时自动点击「跳过 / 关闭广告」按钮，" +
                    "原理与「李跳跳」相同。无需 root，不修改任何应用。" +
                    "开启后长期在后台生效，是本应用里唯一「设好就不用管」的功能。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                when {
                    !enabled -> "状态：未开启 —— 请到「设置 → 无障碍 → 已下载的应用」中开启"
                    !alive -> "状态：已开启但服务未连接（应用被系统清理过）—— 请到无障碍设置里关闭后重新开启一次"
                    else -> {
                        val extra = if (lastLabel.isNotBlank()) "　最近跳过：$lastLabel" else ""
                        "状态：已开启 ✅　累计自动跳过 $skipped 次$extra"
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled && alive) OkColor else WarnColor
            )
            Button(onClick = { openAccessibilitySettings(context) }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when {
                        !enabled -> "去开启（在「已下载的应用」里）"
                        !alive -> "去重新开启一次"
                        else -> "打开无障碍设置"
                    }
                )
            }
        }
    }
}

@Composable
private fun AdBlockCard() {
    val context = LocalContext.current
    val running by AdBlockVpnService.running.collectAsState()
    val blocked by AdBlockVpnService.blockedCount.collectAsState()
    val queries by AdBlockVpnService.queryCount.collectAsState()
    val recent by AdBlockVpnService.recentBlocked.collectAsState()
    val rules by Controller.ruleCount.collectAsState()
    val updating by Controller.ruleUpdating.collectAsState()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            Controller.startVpn()
        } else {
            Toast.makeText(context, "没有授权 VPN，拦截无法启用", Toast.LENGTH_SHORT).show()
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "④ 广告域名拦截（本地 VPN）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "像 AdGuard 一样把广告、追踪域名直接掐掉：开屏广告、广告 SDK、统计追踪大多靠这个拦下来。" +
                    "只劫持 DNS，其它流量根本不走 VPN —— 不耗电、不掉速、不用 root。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (running) "状态：运行中 ✅　已拦截 $blocked 次 / 共 $queries 次查询"
                else "状态：未开启",
                style = MaterialTheme.typography.bodySmall,
                color = if (running) OkColor else WarnColor
            )
            Text(
                "规则：$rules 条（内置 + 在线更新 + 自定义）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (recent.isNotEmpty()) {
                Text(
                    "最近拦截：" + recent.take(3).joinToString("、"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) {
                    OutlinedButton(onClick = { Controller.stopVpn() }, modifier = Modifier.weight(1f)) {
                        Text("关闭拦截")
                    }
                } else {
                    Button(
                        onClick = {
                            val prepare = VpnService.prepare(context)
                            if (prepare != null) launcher.launch(prepare) else Controller.startVpn()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("开启拦截") }
                }
                OutlinedButton(
                    onClick = { Controller.updateRulesOnline() },
                    enabled = !updating,
                    modifier = Modifier.weight(1f)
                ) { Text(if (updating) "更新中…" else "更新规则") }
            }
            Text(
                "提醒：抖音、小红书那种「信息流里的原生广告」是 App 自己接口下发的，拦域名拦不掉" +
                    "（AdGuard 不装 CA 证书做 HTTPS 解密也一样拦不到）。这一项主要干掉开屏广告、广告 SDK 和追踪域名。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProgressCard(p: BatchProgress) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.title.ifBlank { "批量处理" },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (p.running) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            LinearProgressIndicator(progress = { p.percent }, modifier = Modifier.fillMaxWidth())
            Text(
                "进度 ${p.index}/${p.total}　成功 ${p.ok}　失败 ${p.failed}",
                style = MaterialTheme.typography.bodySmall
            )
            if (p.current.isNotBlank()) {
                Text("正在处理：${p.current}", style = MaterialTheme.typography.bodySmall)
            }
            if (p.running) {
                OutlinedButton(onClick = { BatchService.requestStop() }) { Text("停止") }
            }
            if (p.log.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                p.log.takeLast(10).forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ShizukuCard(status: ShizukuStatus, loading: Boolean) {
    val context = LocalContext.current
    val ready = status == ShizukuStatus.READY
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (ready) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (ready) "Shizuku 已连接 ✅" else "Shizuku 未就绪",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(statusHint(status), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (status) {
                    ShizukuStatus.NOT_INSTALLED -> Button(onClick = { openShizukuStore(context) }) {
                        Text("获取 Shizuku")
                    }
                    ShizukuStatus.NOT_RUNNING -> Button(onClick = { openShizukuApp(context) }) {
                        Text("打开 Shizuku 启动服务")
                    }
                    ShizukuStatus.NO_PERMISSION -> Button(onClick = { Controller.requestShizukuPermission() }) {
                        Text("授权本应用")
                    }
                    ShizukuStatus.READY -> Button(onClick = { Controller.refresh() }, enabled = !loading) {
                        Text(if (loading) "读取中…" else "刷新权限状态")
                    }
                }
                OutlinedButton(onClick = { Controller.updateStatus() }) { Text("重新检测") }
            }
        }
    }
}

@Composable
private fun NoticeCard(text: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(
            text,
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun statusHint(status: ShizukuStatus): String = when (status) {
    ShizukuStatus.NOT_INSTALLED ->
        "未检测到 Shizuku。Shizuku 用于「读取各应用的权限状态」和「全自动批量修改」（可选）。" +
            "不安装也能使用：① 开屏广告跳过、② ③ 半自动跳转、④ 广告域名拦截。"
    ShizukuStatus.NOT_RUNNING ->
        "Shizuku 已安装但服务未启动。打开 Shizuku，按提示用「USB 调试」或「无线调试」启动即可。"
    ShizukuStatus.NO_PERMISSION ->
        "Shizuku 服务运行中，尚未授权本应用。若点击授权后弹出「adb 权限受限」，" +
            "说明该机型限制了 adb 权限（部分 ColorOS / 一加机型会如此），此时用无障碍通道同样可以完成操作。"
    ShizukuStatus.READY -> "已连接：可读取各应用权限状态、并可全自动批量修改。"
}

private fun openPrivacySettings(context: Context) {
    // 打开系统设置首页（ColorOS 的「隐私 / 权限管理」都在这一层下面）
    val intent = Intent("android.settings.SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "打不开系统设置，请手动进：设置 → 隐私", Toast.LENGTH_LONG).show() }
}

private fun openAccessibilitySettings(context: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "打不开无障碍设置，请手动进：设置 → 辅助功能 → 无障碍", Toast.LENGTH_LONG).show() }
}

private fun openShizukuApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
    if (intent != null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "打不开 Shizuku", Toast.LENGTH_SHORT).show() }
    } else {
        openShizukuStore(context)
    }
}

private fun openShizukuStore(context: Context) {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PACKAGE"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(market) }
        .recoverCatching { context.startActivity(web) }
        .onFailure { Toast.makeText(context, "请手动搜索安装 Shizuku", Toast.LENGTH_LONG).show() }
}

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
