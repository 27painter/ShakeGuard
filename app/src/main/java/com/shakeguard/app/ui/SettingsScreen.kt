package com.shakeguard.app.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shakeguard.app.core.Controller
import com.shakeguard.app.core.ShizukuStatus
import com.shakeguard.app.core.SkipAdAccessibilityService
import com.shakeguard.app.core.net.AdBlockVpnService

/**
 * 设置页。
 *
 * 原先是「说明」——一整屏长文，谁也不会读。现在拆成两块：
 *  - 上半部分是**能动的设置**：状态摘要 + 分组开关 + 规则维护；
 *  - 下半部分是**原理与兼容性**：默认折叠，想看再展开。
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val status by Controller.status.collectAsState()
    val rules by Controller.ruleCount.collectAsState()
    val updating by Controller.ruleUpdating.collectAsState()
    val vpnRunning by AdBlockVpnService.running.collectAsState()
    val blocked by AdBlockVpnService.blockedCount.collectAsState()
    val a11yEnabled by Controller.accessibilityEnabled.collectAsState()
    val a11yAlive by Controller.accessibilityLive.collectAsState()
    val skipped by SkipAdAccessibilityService.skipCount.collectAsState()
    val loginProtect by Controller.loginProtect.collectAsState()
    val grey by Controller.greyEntries.collectAsState()
    val adDefender by Controller.globalAdDefender.collectAsState()
    val limitAd by Controller.globalLimitAd.collectAsState()

    var blockInput by remember { mutableStateOf("") }
    var allowInput by remember { mutableStateOf("") }

    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "-"
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---------- 状态摘要 ----------
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("当前状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                StatusLine("开屏广告跳过", when {
                    !a11yEnabled -> "未开启"
                    !a11yAlive -> "已开启，服务未连接"
                    else -> "运行中 · 已跳过 $skipped 次"
                }, a11yEnabled && a11yAlive)
                StatusLine("广告域名拦截", if (vpnRunning) "运行中 · 已拦 $blocked 次" else "未开启", vpnRunning)
                StatusLine("规则库", "$rules 条", rules > 0)
                StatusLine("灰名单", if (grey.isEmpty()) "空" else "${grey.size} 个应用中", grey.isEmpty())
                StatusLine("Shizuku", shizukuShort(status), status == ShizukuStatus.READY)
            }
        }

        // ---------- 开屏广告跳过 ----------
        Group("开屏广告自动跳过") {
            Text(
                "无障碍服务监听界面变化，看到「跳过 / 关闭广告」就替你点掉。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { openAccessibilitySettings(context) },
                    modifier = Modifier.weight(1f)
                ) { Text(if (a11yEnabled) "无障碍设置" else "去开启") }
                OutlinedButton(
                    onClick = { Controller.refresh() },
                    modifier = Modifier.weight(1f)
                ) { Text("重新检测") }
            }
            HorizontalDivider()
            SettingRow(
                title = "灰名单",
                subtitle = if (grey.isEmpty()) {
                    "空 —— 撞上登录页的 App 会自动进这里，60 分钟内不自动跳过"
                } else {
                    grey.joinToString("、") { "${it.label}(${it.remainingMinutes}分)" }
                }
            ) {
                TextButton(onClick = { Controller.clearGrey() }, enabled = grey.isNotEmpty()) {
                    Text("清空")
                }
            }
        }

        // ---------- 广告域名拦截 ----------
        Group("广告域名拦截（本地 VPN）") {
            SettingRow(
                title = "登录跳转保护",
                subtitle = "带 login / passport / pay / oauth 字样的域名永不拦截，内置淘宝、支付宝、微信、QQ 等登录白名单"
            ) {
                Switch(checked = loginProtect, onCheckedChange = { Controller.setLoginProtect(it) })
            }
            HorizontalDivider()
            SettingRow(
                title = "规则库",
                subtitle = "$rules 条（内置种子 + 在线更新 + 手动添加）"
            ) {
                TextButton(onClick = { Controller.updateRulesOnline() }, enabled = !updating) {
                    Text(if (updating) "更新中…" else "更新")
                }
            }
            OutlinedTextField(
                value = blockInput,
                onValueChange = { blockInput = it },
                label = { Text("加一个要拦截的域名") },
                placeholder = { Text("ad.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = {
                    if (blockInput.isNotBlank()) {
                        Controller.addUserRule(blockInput)
                        blockInput = ""
                    }
                },
                enabled = blockInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("加入拦截") }
            HorizontalDivider()
            OutlinedTextField(
                value = allowInput,
                onValueChange = { allowInput = it },
                label = { Text("加一个要放行的域名") },
                placeholder = { Text("login.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = {
                    if (allowInput.isNotBlank()) {
                        Controller.allowDomain(allowInput)
                        allowInput = ""
                    }
                },
                enabled = allowInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("加入放行（登录被误拦时用）") }
        }

        // ---------- 系统级开关 ----------
        Group("系统级广告开关（需 Shizuku）") {
            SwitchRow(
                title = "ColorOS 广告拦截",
                subtitle = "ad_defender_switch",
                value = adDefender,
                onChange = { Controller.setGlobalFlag("ad_defender", it) }
            )
            HorizontalDivider()
            SwitchRow(
                title = "限制广告追踪",
                subtitle = "limit_ad_tracking（GMS 设备生效）",
                value = limitAd,
                onChange = { Controller.setGlobalFlag("limit_ad", it) }
            )
            HorizontalDivider()
            OutlinedButton(
                onClick = { Controller.startDenyAppList() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("进阶：逐个禁止「读取应用列表」") }
            Text(
                "「读取应用列表」是广告定向的重要依据。这项要逐个应用处理（每个 5~8 秒），" +
                    "一般用首页 ③ 的「全部关闭」就够了，这里属于加强版。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ---------- 原理与兼容性（折叠） ----------
        Group("原理 · 兼容性 · 限制") {
            Text(
                "默认收起，想看再点开。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Expandable("它是怎么做到的（不用 root）") {
                "本应用完全不用 root，靠三条通道干活：\n\n" +
                    "1）读状态：通过 Shizuku 以 shell 身份执行 cmd appops get <包名> DIRECTION_SENSORS，" +
                    "直接拿系统里的真实权限值。\n\n" +
                    "2）改状态：ColorOS 把 shell 改 app-op 的能力收掉了，" +
                    "所以写操作是通过自动化系统自带的「权限管理 → 设备动作与方向 / 读取应用列表」页面完成的。\n\n" +
                    "3）系统级开关：settings put 仍然可用，用来打开广告拦截、限制广告追踪。"
            }
            Expandable("实测证据（OPPO PMM110 / ColorOS 16 / Android 16）") {
                "直接改系统权限会被拒绝：\n\n" +
                    "cmd appops set <包> DIRECTION_SENSORS ignore\n" +
                    "→ SecurityException: uid 2000 does not have MANAGE_APP_OPS_MODES\n\n" +
                    "pm revoke <包> <权限>\n" +
                    "→ SecurityException: ... does not have REVOKE_RUNTIME_PERMISSIONS\n\n" +
                    "而 ColorOS 界面里的三档与系统 app-op 严格对应：\n\n" +
                    "允许 ↔ allow　｜　仅开屏时不允许 ↔ default（系统默认）　｜　不允许 ↔ ignore\n\n" +
                    "所以「一键关闭」= 批量驱动系统界面把档位改成“不允许”，" +
                    "状态显示则直接读 app-op，二者永远一致。"
            }
            Expandable("基于 ColorOS 开发") {
                "本应用基于 ColorOS 开发（实测：OPPO Reno 16 / ColorOS 16 / Android 16），" +
                    "功能路径与界面文案都按 ColorOS 设计。\n\n" +
                    "✅ ColorOS / OPPO / 一加 / realme：完整可用。\n" +
                    "⚠️ 系统应用：ColorOS 不提供「设备动作与方向」开关，批量处理时会跳过并标注。\n" +
                    "⚠️ 其他 ROM：若没有 DIRECTION_SENSORS 这个 op，摇一摇相关功能不适用；" +
                    "开屏广告跳过（①）、广告域名拦截（④）仍然可用。\n\n" +
                    "如果某些功能在你机型上不能用，请按应用内提示手动打开对应设置，效果相同。"
            }
            Expandable("Shizuku 怎么准备") {
                "1）安装 Shizuku（酷安 / Play 搜索 Shizuku）。\n\n" +
                    "2）打开开发者选项里的「USB 调试」，用数据线连电脑，" +
                    "在 Shizuku 里选「通过 USB 调试启动」；或用「无线调试」按提示配对。\n\n" +
                    "3）回到本应用，点「授权本应用」，在 Shizuku 弹窗里允许。\n\n" +
                    "注意：手机重启后 Shizuku 服务会停，需要重新启动一次。"
            }
            Expandable("没有 Shizuku 怎么办（本机实测）") {
                "在这台 OPPO / ColorOS 16 上，Shizuku 的**服务能启动**，但**授权一定失败**，所以实际用不了。\n\n" +
                    "卡在哪一步（实测）：\n" +
                    "1）Shizuku 服务以 shell(uid 2000) 身份正常跑起来了；\n" +
                    "2）点「授权本应用」时，Shizuku 要让 shell 执行 pm grant 把 " +
                    "moe.shizuku.manager.permission.API_V23 授给本应用；\n" +
                    "3）这个权限的 protectionLevel 是 dangerous，需要 GRANT_RUNTIME_PERMISSIONS；\n" +
                    "4）ColorOS 把 shell 的这个权限收掉了 → SecurityException → 弹「adb 权限受限」。\n\n" +
                    "手动绕过也不行：该权限不在应用的运行时权限列表里，系统设置里没有对应开关。\n\n" +
                    "所以需要 Shizuku 的两项（批量关摇一摇、批量关广告追踪）会自动切换成" +
                    "「看手动步骤」——点开就是系统设置里的准确路径，照着点几下即可。\n\n" +
                    "批量关摇一摇还有一条路：用电脑跑仓库里的 tools/close-shake-ads.ps1，" +
                    "同样不需要 Shizuku，100 多个应用约 2 分钟。"
            }
            Expandable("已知限制") {
                "• 自动化依赖 ColorOS 权限界面的文案（权限管理 / 设备动作与方向 / 不允许 等），" +
                    "系统大版本更新改了字就会失败，此时需要更新 core/ColorOsAutomator.kt 里的 TXT_ 常量。\n\n" +
                    "• 批量执行要占用屏幕，期间别手动操作、别锁屏。\n\n" +
                    "• 读状态需要 Shizuku 运行且已授权；没授权时应用列表仍可看，但状态显示“未知”。\n\n" +
                    "• DNS 拦截拦不到 App 自己接口下发的内容流广告，以及自带 HTTPDNS / DoH 的应用。\n\n" +
                    "• 当前 Shizuku 状态：" + shizukuShort(status)
            }
            Expandable("免责声明") {
                "本工具只操作「设备动作与方向」「读取应用列表」两个开关和两个系统广告设置，" +
                    "不修改应用数据、不卸载应用、不注入进程。所有改动都可以在系统设置里手动还原。" +
                    "请在理解上述机制后使用。"
            }
        }

        // ---------- 关于 ----------
        Group("关于") {
            InfoRow("版本", versionName)
            HorizontalDivider()
            InfoRow("许可", "MIT")
            HorizontalDivider()
            InfoRow("源码", "github.com/27painter/ShakeGuard") {
                TextButton(onClick = { openUrl(context, GITHUB_URL) }) { Text("打开") }
            }
            HorizontalDivider()
            OutlinedButton(
                onClick = { Controller.refresh() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("重新读取全部状态") }
        }

        Spacer(Modifier.height(8.dp))
    }
}

private const val GITHUB_URL = "https://github.com/27painter/ShakeGuard"

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) OkColor else WarnColor,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 左边标题+说明，右边放开关或按钮 */
@Composable
private fun SettingRow(title: String, subtitle: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
private fun InfoRow(label: String, value: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(70.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, value: Boolean?, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (value == null) "$subtitle · 读取不到（需要 Shizuku）"
                else "$subtitle · " + if (value) "已开启" else "已关闭",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = value == true, enabled = value != null, onCheckedChange = onChange)
    }
}

/** 可折叠条目：点标题展开长文，默认收起 */
@Composable
private fun Expandable(title: String, body: () -> String) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (expanded) {
            Text(
                body(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        HorizontalDivider()
    }
}

private fun shizukuShort(status: ShizukuStatus): String = when (status) {
    ShizukuStatus.NOT_INSTALLED -> "未安装"
    ShizukuStatus.NOT_RUNNING -> "服务未运行"
    ShizukuStatus.NO_PERMISSION -> "未授权"
    ShizukuStatus.READY -> "已连接"
}

private fun openAccessibilitySettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        Toast.makeText(context, "打不开无障碍设置，请手动进：设置 → 辅助功能 → 无障碍", Toast.LENGTH_LONG).show()
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        Toast.makeText(context, url, Toast.LENGTH_LONG).show()
    }
}
