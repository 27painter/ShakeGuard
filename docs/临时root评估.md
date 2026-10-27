# 「临时 root」可行性评估

> 针对「需要 Shizuku 的那部分功能」——批量改「设备动作与方向」、批量禁「读取应用列表」。
> 评估机型：OPPO Reno16（PMM110）/ ColorOS 16.0.10.500 / Android 16 (SDK 36)。
> 所有结论都基于本机实测，不是推测。

---

## 一、结论

**不建议做「临时 root」。** 三个理由，按重要性排序：

1. **这台机器上做不到** —— 生产版系统 + BL 锁死 + 无 su，`adb root` 直接被拒；唯一的路是解 BL，那已经不是「临时」了。
2. **代价远大于收益** —— 解 BL 清数据、失保修、支付类 App 可能拒跑，而本项目要解决的恰恰是淘宝/支付宝/微信这些 App 的广告。
3. **「临时」在 Android 上是个伪概念** —— root 来自改过的 boot 镜像，重启依然在；想「临时」只能每次手点开关，比现在的半自动更麻烦。

**真正该做的是**：不制造 root，而是**「有 root 就用」**——加一个可选通道，检测到 `su` 就直接写 appops，没检测到就走现在的 UI 自动化。约 60 行代码，对无 root 用户零影响。

---

## 二、这些功能到底卡在什么权限上（实测）

| 操作 | 命令 | shell (uid 2000) | root (uid 0) |
|---|---|---|---|
| 读权限状态 | `cmd appops get <pkg> DIRECTION_SENSORS` | ✅ 可以 | ✅ |
| 写权限状态 | `cmd appops set <pkg> DIRECTION_SENSORS ignore` | ❌ `SecurityException: uid 2000 does not have MANAGE_APP_OPS_MODES` | ✅ |
| 授予运行时权限 | `pm grant <pkg> <perm>` | ❌ `does not have GRANT_RUNTIME_PERMISSIONS` | ✅ |
| 写系统设置 | `settings put global xxx 1` | ✅ 可以 | ✅ |
| 驱动界面 | `uiautomator dump` / `input tap` | ✅ 可以 | ✅ |

**关键点：Shizuku 就是以 uid 2000 运行的。** 它不是 root，是「借 adb 的壳」。所以：

> Shizuku 能让 App 干的事，和电脑上插数据线跑 adb 完全一样 —— 读得到、点得到，但**改不了 appops**。

这也是为什么仓库里的 PC 脚本 `tools/close-shake-ads.ps1` 明明有 adb，也只能用 `uiautomator dump` + `input tap` 一个个点（见脚本第 184、211 行）。不是不想直接改，是 shell 没有这个权限。

---

## 三、「临时 root」在本机可行吗（实测）

| 检查项 | 实测值 | 含义 |
|---|---|---|
| `ro.build.type` | `user` | 生产版，不是 userdebug |
| `ro.debuggable` | `0` | 不可调试 |
| `adb root` | `adbd cannot run as root in production builds` | **直接拒绝** |
| `ro.boot.flash.locked` | `1` | Bootloader 锁着 |
| `ro.boot.vbmeta.device_state` | `locked` | AVB 校验开启 |
| `ro.boot.verifiedbootstate` | `green` | 系统未被改动 |
| `which su` | 无输出 | 没有 su |
| Shizuku | 已安装，但从未启动（`start.sh` 不存在） | 服务没跑过 |

**能走的路只剩一条**：OPPO「深度测试」申请解锁 BL → 刷 Magisk / KernelSU 补丁 boot。
那不是「临时 root」，是**永久改机**，而且 ColorOS 16 已经把解锁方式改成必须走深度测试（有账号时长/名额限制，且明确影响保修）。

顺带说明：网上那些「一键临时 root」（mtk-su、SunShine、KingRoot 之类）对 2022 年之后的机型基本全部失效 —— 依赖的都是早已修补的内核提权漏洞。现代 Android 上不存在「重启就消失、还能用」的 root。

---

## 四、临时 root 的优点

假设**已经有** root 环境，收益是实打实的：

| 收益 | 现在（无障碍 UI 自动化） | 有 root |
|---|---|---|
| 处理 100 个应用的耗时 | 约 10 分钟（每个 5~8 秒） | **1~2 秒** |
| 是否占用屏幕 | 要，期间不能碰手机 | 不需要，后台静默完成 |
| 是否依赖系统界面文案 | **强依赖**，ColorOS 改字就失效 | 不依赖 |
| 是否需要无障碍服务 | 需要（还得防系统清理） | 不需要 |
| 新装 App 自动处理 | 做不了 | 可以监听安装事件秒级处理 |
| 一键全部还原 | 逐个点，很慢 | 瞬时 |

其中**「不依赖界面文案」是最有价值的**——现在 ColorOS 大版本一升级，「权限管理」「设备动作与方向」这些字一改，自动化就全废，只能等作者更新常量。

---

## 五、临时 root 的缺点

### 技术层面

- **「临时」不成立**：root 权限来自被替换的 boot 镜像，重启后仍在。想「临时」只能手动关，反而多一步。
- **每个系统大版本都要重来**：OTA 会覆盖 boot，需要重新 patch；ColorOS 还可能在 OTA 时检测到改动而失败。
- **和「去广告」的目的冲突**：解 BL / root 会让 Play Integrity 掉级，**部分银行、支付、政务、企业 App 会直接拒绝运行或限制功能**。为了关淘宝广告，把支付宝弄成「环境异常」，这笔账不划算。

### 代价层面

| 风险 | 说明 |
|---|---|
| 数据清空 | 解锁 BL 必然格式化整机 |
| 保修失效 | OPPO 深度测试明确说明「加入深度测试的手机不保修」 |
| 变砖 | 刷错 boot 分区可能无法开机，需要售后救砖 |
| 系统更新 | 部分机型解锁后无法接收 OTA |
| 安全 | root 后任意 App 都能提权，恶意软件风险显著上升 |
| 门槛 | 普通用户根本做不了；这个 App 的用户大多是「不想折腾但想清静」的人 |

---

## 六、其他被问到的替代方案

| 方案 | 能读到 appops | 能改 appops | 评价 |
|---|---|---|---|
| 无障碍服务（现状） | ✅ | ❌（只能点界面） | 零门槛，慢，依赖文案 |
| Shizuku | ✅ | ❌（uid 2000 同样被拒） | 比无障碍稳一点，但天花板一样 |
| **Dhizuku（Device Owner）** | ✅ | ❌ | 能批量改**运行时权限**，但改不了 `DIRECTION_SENSORS`，对本项目核心功能无用 |
| PC + adb 脚本 | ✅ | ❌ | 同样只能 `input tap` |
| **Root** | ✅ | ✅ | 唯一能直接写的，但代价见上 |

---

## 七、建议

1. **不做「临时 root」**。技术上不可行，安全与保修代价不成比例，而且会劝退大部分目标用户。
2. **加一个可选的 Root 通道**（如果你想）：
   - 启动时探测 `su -c id` 是否返回 `uid=0`；
   - 有 root → `cmd appops set` 直接批量写，秒级完成；
   - 没有 → 完全回落现在的 UI 自动化，行为不变；
   - 界面上明确标「检测到 root，走快速通道」，让用户知道差别。
3. **如果哪天真解锁了**：也建议只在这个 App 里用 root 做 appops 写入，不要装常驻 root 服务，尽量降低被支付类 App 检测到的概率。

> 一句话：**不要为了这个 App 去 root 手机；但如果手机已经 root 了，这个 App 应该会用。**
