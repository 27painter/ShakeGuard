# 摇一摇克星 ShakeGuard

> 无需 root、无需 Xposed，用 **Android 无障碍服务 + Shizuku + 本地 VPN**，解决 ColorOS 手机上最烦人的四类广告：**摇一摇跳转广告、开屏广告、个性化广告追踪、广告域名**。

![主界面](docs/screenshot-home.jpg)

- **实测机型**：OPPO Reno 16（PMM110）/ ColorOS 16.0.10.500 / Android 16（SDK 36）
- **适用范围**：Android 11+，主要针对 OPPO / 一加 / realme（ColorOS、氢 OS 等带「设备动作与方向」权限项的系统）
- **技术栈**：Kotlin + Jetpack Compose + 无障碍服务 + `VpnService`（纯本地 DNS 拦截）+ Shizuku（可选）

---

## 一、它解决什么

| # | 功能 | 效果 | 是否需要 root |
|---|---|---|---|
| ① | 开屏广告自动跳过 | 广告出现时自动点「跳过 / 关闭广告」 | 否 |
| ② | 关闭摇一摇广告 | 把第三方应用的「设备动作与方向」改成不允许，摇一摇彻底失效 | 否 |
| ③ | 关闭广告追踪（OAID） | 调用系统总开关，一次关掉所有应用的 OAID 读取能力 | 否 |
| ④ | 广告域名拦截（本地 VPN） | 像 AdGuard 的 DNS 模式那样，直接掐掉广告 / 追踪域名 | 否 |

设计上的取舍：**能做全自动的做全自动（①④），系统不允许第三方应用越权的地方做「半自动」**（②③：自动跳到目标设置页，最后一下由你按），并在界面上如实说明，而不是假装能一键搞定。

---

## 二、功能说明

### ① 开屏广告自动跳过（设置一次，长期生效）

原理与知名的「李跳跳」相同：注册一个无障碍服务，监听窗口变化，发现「跳过 / 关闭广告 / Skip」这类按钮就自动点击。

- 只匹配长度 ≤ 12 的短文本，并排除系统设置界面与应用自身标题，避免误点；
- 界面卡片会实时显示「已开启 / 累计跳过 N 次」，方便确认它有没有在工作。

开启方式（ColorOS 隐藏得比较深）：

```
设置 → 无障碍 → 已下载的应用 → 开屏广告自动跳过 → 开启
```

### ② 关闭摇一摇广告

ColorOS 的「摇一摇跳转」受权限 **设备动作与方向** 控制，系统原生的三档开关与 app-op 一一对应（本机逐条实测）：

| ColorOS 界面 | 系统 app-op | 效果 |
|---|---|---|
| 允许 | `DIRECTION_SENSORS = allow` | 摇一摇可用 |
| 仅开屏时不允许 | `DIRECTION_SENSORS = default` | 只拦截开屏那 6 秒（系统默认档） |
| 不允许 | `DIRECTION_SENSORS = ignore` | 摇一摇彻底失效 |

点一下按钮，应用会自动跳到系统的「设备动作与方向」页，你只需按最后一下：

```
右上角 ⋮ →「全部不允许」
```

导航、赛车游戏需要保留摇一摇时，在该页列表里点应用 → 选「允许」。

### ③ 关闭广告追踪（OAID）

系统自带的总开关，一次关闭所有应用（含系统应用）读取 OAID 的能力：

```
设置 → 隐私 → 更多 → 设备标识与广告 → 广告跟踪 → 右上角 ⋮ →「全部关闭」
```

应用会自动跳到「广告跟踪」页，同样只留最后一下给你。

### ④ 广告域名拦截（本地 VPN）

用 `VpnService` 建立一块只承载 DNS 的虚拟网卡：**只劫持 DNS（UDP 53），其它流量根本不走 VPN**，所以不耗电、不掉速、不需要 root。

- 命中规则集的域名直接返回 `0.0.0.0`（A）/ 空（AAAA），广告 SDK、统计追踪、开屏广告大多在这里被拦掉；
- 上游 DNS 依次使用 `223.5.5.5` / `119.29.29.29` / `114.114.114.114`，查询走 `protect()` 直连，不经过 VPN；
- 内置规则约 11 万条，支持在线更新（anti-ad、AdRules、AdAway 源）与自定义域名；
- 手动构建 IPv4 / UDP 报文并计算 IP 与 UDP（含伪首部）校验和，不依赖任何三方库。

---

## 三、快速开始

### 方式 A：adb 脚本（最快，不用编译）

仓库 `tools/` 里有一份 PowerShell 脚本，直接用电脑的 adb 完成「批量关闭摇一摇 / 关闭广告追踪」：

```powershell
# 先看现状（只读，不做任何修改）
.\tools\close-shake-ads.ps1 -Report

# 全部第三方应用改为「不允许」，导航/赛车游戏进白名单
.\tools\close-shake-ads.ps1 -Whitelist com.baidu.BaiduMap,com.autonavi.minimap -Yes

# 广告追踪一键全关
.\tools\close-shake-ads.ps1 -AdTracking -Yes

# 全部还原成系统默认
.\tools\close-shake-ads.ps1 -Mode SplashOnly -Yes
```

也可以直接双击 `tools\run-close-shake-ads.cmd`。

### 方式 B：自己编译（Android Studio）

环境要求（本机实测通过）：

| 组件 | 版本 |
|---|---|
| JDK | 17+（Android Studio 自带 JBR 亦可） |
| Gradle | 9.8.0（wrapper 已带） |
| AGP | 9.4.1（AGP 9 自带 Kotlin 支持，**不要**再 apply `org.jetbrains.kotlin.android`） |
| Kotlin | 2.4.20 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 30 |

```bash
# 命令行构建
export JAVA_HOME="<Android Studio>/jbr"
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` 里写上你的 SDK 路径（该文件已被 .gitignore 忽略）：

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

### 方式 C：Shizuku（可选，用于全自动批量）

装了 [Shizuku](https://shizuku.rikka.app/) 并启动其服务、授权本应用后：

- 「应用与权限状态」页可读取每个应用的真实权限状态；
- ②③ 支持真正的「一键全自动」（逐个应用驱动系统设置界面完成修改）。

> 部分 ColorOS / 一加机型会限制 adb 权限（Shizuku 会提示「adb 权限受限」），此时 Shizuku 无法授权 —— 这**不影响**使用：用无障碍通道的半自动方式即可，功能完全一致。

---

## 四、原理（技术向）

### 4.1 ColorOS 不让第三方应用改权限

本机实测（shell，uid 2000）：

```
cmd appops set <pkg> DIRECTION_SENSORS ignore
→ SecurityException: uid 2000 does not have android.permission.MANAGE_APP_OPS_MODES

pm revoke <pkg> <permission>
→ SecurityException: ... REVOKE_RUNTIME_PERMISSIONS
```

结论：**写操作只能通过驱动系统设置界面完成**，因此本项目实现了两套 UI 自动化通道。

### 4.2 两条 UI 自动化通道

| 通道 | 实现 | 何时使用 |
|---|---|---|
| Shizuku | `ShellUiEngine`：`uiautomator dump` 读界面 + `input tap` 点击 | Shizuku 可用时 |
| 无障碍服务 | `A11yUiEngine`：读 `rootInActiveWindow` + `performAction(CLICK)` + `dispatchGesture` 滑动 | 默认（无需 Shizuku） |

两条通道共用同一套业务流程（`ColorOsAutomator`），实测可达页面：

```
② 摇一摇：设置 → 隐私 → 权限管理 →「权限」→ 设备动作与方向 → ⋮ → 全部不允许
③ 广告追踪：设置 → 隐私 → 更多 → 设备标识与广告 → 广告跟踪 → ⋮ → 全部关闭
```

### 4.3 踩过的坑（都已修复，供参考）

1. **ColorOS 的权限页属于另一个应用**：设置首页在 `com.android.settings`，而「权限管理 / 设备动作与方向」在 `com.oplus.securitypermission`，两个应用都会记住上次停留的页面 —— 必须同时清理，否则 `am start` 会把意图送回旧页面。
2. **无障碍的滚动要用真手势**：ColorOS 权限列表不响应 `ACTION_SCROLL_FORWARD`，必须用 `dispatchGesture` 模拟手指滑动（对应 `canPerformGestures="true"`）。
3. **无障碍的安全警告会吃掉点击**：启用 / 重连无障碍后系统会弹「检测到 XX 获取无障碍权限 → 关闭无障碍 / 保持开启」，该弹窗会挡住界面并吞掉所有点击 —— 自动化在每次点击前会先把它点掉（选「保持开启」）。
4. **应用被系统清理后，无障碍服务会被标记为 crashed**：此时 `dumpsys accessibility` 显示 `Enabled services` 有、`Bound services` 空、`Crashed services` 有，Android 不再自动重连，**只能由用户手动关闭再开启一次**（这是系统安全设计，App 无法自行恢复）。因此应用首页会提示用户「加锁 + 允许后台活动」，并在跳转失败时给出恢复路径。
5. **设置页的深链基本不可用**：ColorOS 把相关 Activity 设为不导出，标准 action 也会被重定向到搜索页，所以「跳到目标页」只能靠无障碍自动化完成。

---

## 五、常见问题

**Q：点了按钮没反应 / 跳转失败？**
A：先看首页 ① 卡片的状态行。如果是「已开启但服务未连接」，说明应用被系统清理过，无障碍服务已被标记 crashed —— 到 `设置 → 无障碍 → 已下载的应用 → 开屏广告自动跳过` 关闭再打开一次即可。建议平时在最近任务里给它**加锁 🔒**，并在「耗电管理」里允许后台活动 / 自启动。

**Q：Shizuku 提示「adb 权限受限」？**
A：部分 ColorOS / 一加机型限制了 adb 权限，Shizuku 无法授权，这是系统限制。不使用 Shizuku 也能用全部功能（走无障碍通道）。

**Q：为什么 ②③ 要点最后一下，④ 却能全自动？**
A：系统没有向第三方应用开放「批量修改权限页」的接口，只能自动化操作系统界面；而 ①④ 不需要任何系统权限，所以可以完全自动。

**Q：信息流里的原生广告能拦掉吗？**
A：不能。抖音、小红书那种「信息流广告」是应用自己接口下发的，域名拦截拦不到；④ 主要解决开屏广告、广告 SDK 与追踪域名。

---

## 六、已知限制

- 信息流原生广告无法通过域名拦截去除；
- OPPO 自带应用（浏览器 / 软件商店 / 视频 / 会员等）没有「设备动作与方向」开关，无法批量关闭摇一摇；
- 应用被系统清理后，无障碍服务需要用户手动重新开启一次（Android 机制）；
- ②③ 的「全自动」模式依赖 Shizuku；没有 Shizuku 时为半自动（自动跳转 + 最后一步手动）。

---

## 七、工程结构

```
app/src/main/java/com/shakeguard/app/
├── App.kt / MainActivity.kt
├── core/
│   ├── ShizukuShell.kt          # Shizuku 通道（反射调用 newProcess）
│   ├── UiAutomator.kt           # shell 版界面读取与点击
│   ├── ui/
│   │   ├── UiEngine.kt          # 自动化抽象（等待/点击/滚动等通用逻辑）
│   │   ├── ShellUiEngine.kt     # Shizuku 实现
│   │   └── A11yUiEngine.kt      # 无障碍实现（含手势滑动、安全弹窗自动处理）
│   ├── ColorOsAutomator.kt      # 各条业务流程（实测路径）
│   ├── OpsReader.kt             # 批量读取 app-op 权限状态
│   ├── BatchService.kt          # 前台服务执行批处理
│   ├── SkipAdAccessibilityService.kt  # ① 开屏广告自动跳过
│   ├── net/                     # ④ 本地 DNS 拦截（VpnService + 报文构造 + 规则集）
│   └── Prefs.kt / Models.kt / Controller.kt
└── ui/                          # Compose 界面
tools/                           # adb 批量脚本（PowerShell）
docs/                            # 截图
推荐广告怎么关.md                  # 信息流广告的可行做法（诚实版）
```

---

## 八、声明

- 本项目仅用于**个人学习与自用**，用于屏蔽自己设备上的广告；
- 请勿用于商业用途或任何侵犯他人权益的场景；
- 所有权限修改都在本机完成，不上传任何数据；④ 的 DNS 查询也仅在本机转发，不记录、不上报；
- 使用前请确认符合你所在地区的法律法规与相关服务条款。

## License

[MIT](LICENSE)
