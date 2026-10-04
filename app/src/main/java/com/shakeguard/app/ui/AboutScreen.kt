package com.shakeguard.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shakeguard.app.core.Controller

@Composable
fun AboutScreen() {
    val adDefender by Controller.globalAdDefender.collectAsState()
    val limitAd by Controller.globalLimitAd.collectAsState()
    val status by Controller.status.collectAsState()
    val rules by Controller.ruleCount.collectAsState()
    val updating by Controller.ruleUpdating.collectAsState()
    var custom by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Section("它是怎么做到的") {
            Text(
                "本应用完全不用 root，靠三条通道干活：\n\n" +
                    "1）读状态：通过 Shizuku 以 shell 身份执行 cmd appops get <包名> DIRECTION_SENSORS，" +
                    "直接拿系统里的真实权限值。\n\n" +
                    "2）改状态：ColorOS 把 shell 改 app-op 的能力收掉了（见下面实测），" +
                    "所以写操作是通过自动化系统自带的「权限管理 → 设备动作与方向 / 读取应用列表」页面完成的：" +
                    "打开应用详情 → 点权限管理 → 点对应开关 → 选档位。\n\n" +
                    "3）系统级开关：settings put 仍然可用，用来打开广告拦截、限制广告追踪。"
            )
        }

        Section("实测证据（OPPO PMM110 / ColorOS 16 / Android 16）") {
            Text(
                "直接改系统权限会被拒绝：\n\n" +
                    "cmd appops set <包> DIRECTION_SENSORS ignore\n" +
                    "→ SecurityException: uid 2000 does not have android.permission.MANAGE_APP_OPS_MODES\n\n" +
                    "pm revoke <包> <权限>\n" +
                    "→ SecurityException: ... does not have android.permission.REVOKE_RUNTIME_PERMISSIONS\n\n" +
                    "而 ColorOS 界面里的三档与系统 app-op 是严格对应的（逐条验证过）：\n\n" +
                    "允许            ↔ DIRECTION_SENSORS = allow\n" +
                    "仅开屏时不允许   ↔ DIRECTION_SENSORS = default（系统默认，只拦开屏 6 秒）\n" +
                    "不允许          ↔ DIRECTION_SENSORS = ignore（摇一摇彻底失效）\n\n" +
                    "所以「一键关闭」= 批量驱动系统界面把档位改成“不允许”，" +
                    "状态显示则直接读 app-op，二者永远一致。"
            )
        }

        Section("基于 ColorOS 开发") {
            Text(
                "本应用**基于 ColorOS 开发**（实测机型：OPPO Reno 16 / ColorOS 16 / Android 16），" +
                    "功能路径与界面文案都按 ColorOS 设计。\n\n" +
                    "如果某些功能在你的机型上不能使用（例如没有「设备动作与方向」这一项、" +
                    "或系统的权限页面位置不同），**请按应用内的提示手动打开对应设置**，同样可以达到目的。\n\n" +
                    "✅ ColorOS / OPPO / 一加 / realme：完整可用。\n" +
                    "⚠️ 系统应用：ColorOS 不提供「设备动作与方向」开关，批量处理时会跳过并标注。\n" +
                    "⚠️ 其他 ROM（MIUI / HyperOS / 原生 Android）：若没有 DIRECTION_SENSORS 这个 op，" +
                    "首页会提示“本机没有该开关”，摇一摇相关功能不适用；" +
                    "开屏广告跳过（①）、广告域名拦截（④）以及系统级广告开关仍然可用。"
            )
        }

        Section("Shizuku 怎么准备") {
            Text(
                "1）安装 Shizuku（酷安 / Play 搜索 Shizuku）。\n\n" +
                    "2）打开开发者选项里的「USB 调试」，用数据线连电脑，" +
                    "在 Shizuku 里选「通过 USB 调试启动」；或者用「无线调试」按提示配对启动。\n\n" +
                    "3）回到本应用，点「授权本应用」，在 Shizuku 弹窗里允许。\n\n" +
                    "注意：手机重启后 Shizuku 服务会停，需要重新启动一次。"
            )
        }

        Section("系统级广告开关（settings put，写入立即生效）") {
            SwitchRow(
                title = "ColorOS 广告拦截（ad_defender_switch）",
                value = adDefender,
                onChange = { Controller.setGlobalFlag("ad_defender", it) }
            )
            HorizontalDivider()
            SwitchRow(
                title = "限制广告追踪（limit_ad_tracking，GMS 设备生效）",
                value = limitAd,
                onChange = { Controller.setGlobalFlag("limit_ad", it) }
            )
            Text(
                "这两个开关是全局的，写的是系统 settings 表；" +
                    "ColorOS 自己的「广告与隐私」页面里也能看到同一份值。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Section("进阶：禁止应用读取「应用列表」") {
            Text(
                "「读取应用列表」是国内广告定向的重要依据（判断你装了哪些 App 来推断兴趣）。" +
                    "关掉后个性化推荐会明显变差，代价是个别应用的“检测某应用已安装”功能可能失灵。\n\n" +
                    "这项要逐个应用处理（每个约 5~8 秒，100 多个应用约 10 分钟）。普通需求用上面的" +
                    "「广告跟踪 → 全部关闭」就够了，这里属于加强版。"
            )
            OutlinedButton(
                onClick = { Controller.startDenyAppList() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("开始逐个禁止（执行时别碰手机）") }
        }

        Section("广告域名拦截（本地 VPN / DNS，AdGuard 那种做法）") {
            Text(
                "原理：给手机设一个虚拟 DNS，并且只把 DNS 那一个地址放进 VPN 路由 —— 所以只有 DNS 查询会被拦，" +
                    "其它流量根本不进 VPN。域名命中规则就回 0.0.0.0（等于把这个域名拔网线），没命中就转发给上游 DNS。\n\n" +
                    "能拦：开屏广告、广告 SDK（穿山甲 / 广点通 / 百度联盟 / Unity / AppLovin…）、统计与追踪域名、" +
                    "全球广告交易平台。\n\n" +
                    "拦不住：App 自己接口下发的内容流广告（抖音、小红书那种原生推荐），以及自带 DNS（HTTPDNS / DoH）的应用" +
                    "—— 这部分 AdGuard 不装 CA 证书做 HTTPS 解密也一样拦不到。\n\n" +
                    "当前生效规则：$rules 条（内置种子 + 在线更新 + 手动添加）。"
            )
            OutlinedButton(
                onClick = { Controller.updateRulesOnline() },
                enabled = !updating,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (updating) "更新中…" else "更新在线规则（adrules / anti-AD）") }
            OutlinedTextField(
                value = custom,
                onValueChange = { custom = it },
                label = { Text("手动加一个要拦截的域名，例如 ad.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = {
                    if (custom.isNotBlank()) {
                        Controller.addUserRule(custom)
                        custom = ""
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("加入拦截") }
            Text(
                "规则文件在 App 私有目录：rules_downloaded.txt（在线更新）、rules_user.txt（手动添加）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Section("不想要了怎么还原") {
            Text(
                "• 单个应用：应用列表里点它 → 「还原为系统默认」。\n\n" +
                    "• 全部还原：主页「还原成系统默认」按钮，把所有被改成“不允许”的应用改回“仅开屏时不允许”。\n\n" +
                    "• 广告追踪：在应用详情里把「读取应用列表」改回“允许”，或直接在系统的" +
                    "权限管理里改。\n\n" +
                    "• 卸载本应用不会自动还原任何权限，权限是写在系统里的。"
            )
        }

        Section("没有 Shizuku 怎么办（本机实测）") {
            Text(
                "在这台 OPPO / ColorOS 16 上 Shizuku 用不了：它的界面自己会提示" +
                    "「您的设备制造商限制了 adb 的权限，使用 Shizuku 的应用无法正常工作」，" +
                    "而开发者选项里并没有能解开这个限制的开关（168 项全部翻过）。" +
                    "这跟系统拒绝 shell 改 app-op 是同一个原因。\n\n" +
                    "所以需要 Shizuku 的两项（批量关摇一摇、批量关广告追踪）在本机会自动切换成" +
                    "「看手动步骤」——点开就是系统设置里的准确路径，照着点几下即可。\n\n" +
                    "批量关摇一摇还有一条路：用电脑跑仓库里的 tools/close-shake-ads.ps1，" +
                    "同样不需要 Shizuku，100 多个应用约 2 分钟。"
            )
        }

        Section("已知限制") {
            Text(
                "• 自动化依赖 ColorOS 权限界面的文案（权限管理 / 设备动作与方向 / 读取应用列表 / 不允许），" +
                    "如果系统大版本更新改了这些字，自动化会失败并在日志里标 ⚠️，此时需要更新代码里的文案常量" +
                    "（core/ColorOsAutomator.kt 底部的 TXT_ 常量）。\n\n" +
                    "• 批量执行要占用屏幕，期间不要手动操作手机，也不要锁屏。\n\n" +
                    "• 读状态需要 Shizuku 处于运行且已授权状态；没授权时应用列表仍可看，但状态显示为“未知”。\n\n" +
                    "• 当前 Shizuku 状态：" + statusText(status)
            )
        }

        Section("免责声明") {
            Text(
                "本工具只操作「设备动作与方向」「读取应用列表」这两个开关和两个系统广告设置，" +
                    "不修改应用数据、不卸载应用、不注入进程。所有改动都可以在系统设置里手动还原。" +
                    "请在理解上述机制后使用。"
            )
        }

        OutlinedButton(
            onClick = { Controller.refresh() },
            modifier = Modifier.fillMaxWidth()
        ) { Text("重新读取全部状态") }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, value: Boolean?, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (value == null) "读取不到（可能需要 Shizuku 授权）" else if (value) "已开启" else "已关闭",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = value == true,
            enabled = value != null,
            onCheckedChange = onChange
        )
    }
}

private fun statusText(status: com.shakeguard.app.core.ShizukuStatus): String = when (status) {
    com.shakeguard.app.core.ShizukuStatus.NOT_INSTALLED -> "Shizuku 未安装"
    com.shakeguard.app.core.ShizukuStatus.NOT_RUNNING -> "Shizuku 服务未运行"
    com.shakeguard.app.core.ShizukuStatus.NO_PERMISSION -> "Shizuku 未授权本应用"
    com.shakeguard.app.core.ShizukuStatus.READY -> "已连接，可读写"
}
