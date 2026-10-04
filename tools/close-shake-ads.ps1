# 摇一摇克星 · adb 批量脚本（不需要编译 App，插上数据线就能用）
#
# 作用：在 ColorOS / OPPO / 一加 上批量把「设备动作与方向」改成指定档位
#       （可选同时处理「读取应用列表」），用来根治摇一摇广告跳转。
#
# 原理：ColorOS 16 不允许 shell 直接改 app-op（会抛
#       SecurityException: uid 2000 does not have ...MANAGE_APP_OPS_MODES），
#       所以这里改为驱动系统自带的权限界面：打开应用详情 -> 权限管理 ->
#       点「设备动作与方向」-> 选档位；改完再用 `cmd appops get` 校验。
#
# 用法示例：
#   .\close-shake-ads.ps1 -Report                 # 先看现状，不改任何东西
#   .\close-shake-ads.ps1 -Only com.kugou.android.lite   # 只试一个应用
#   .\close-shake-ads.ps1 -Whitelist com.tencent.tmgp.pubgmhd -Yes   # 全关，留一个游戏
#   .\close-shake-ads.ps1 -AlsoAppList -Yes       # 连「读取应用列表」一起关
#   .\close-shake-ads.ps1 -Mode SplashOnly -Yes   # 全部还原成系统默认
#
# 运行期间手机会自动跳页面，请不要手动操作手机，保持屏幕点亮。

[CmdletBinding()]
param(
    [string]$Adb = '',
    [ValidateSet('Deny', 'Allow', 'SplashOnly')]
    [string]$Mode = 'Deny',
    [string[]]$Whitelist = @(),
    [string[]]$Only = @(),
    [switch]$Report,
    # 只做「广告追踪」全部关闭（设置-隐私-设备标识与广告-广告跟踪-全部关闭）
    [switch]$AdTracking,
    [switch]$AlsoAppList,
    [switch]$Yes,
    [int]$DelayMs = 600
)

$ErrorActionPreference = 'Continue'

try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false) } catch { }
try { $OutputEncoding = New-Object System.Text.UTF8Encoding($false) } catch { }

function Info($m) { Write-Host $m }
function Ok($m) { Write-Host $m -ForegroundColor Green }
function Warn($m) { Write-Host $m -ForegroundColor Yellow }
function Fail($m) { Write-Host $m -ForegroundColor Red }
function Head($m) { Write-Host $m -ForegroundColor Cyan }

# 界面文案用码点构造：这样即使脚本文件编码被误判，匹配逻辑依然正确
# 允许 -Only a,b,c 这种写法（用 -File 调用时整串会被当成一个字符串）
function Normalize-List([string[]]$values) {
    $out = @()
    foreach ($item in $values) {
        foreach ($piece in ($item -split '[,\s;]+')) {
            $v = $piece.Trim()
            if ($v) { $out += $v }
        }
    }
    return @($out | Sort-Object -Unique)
}

function U([int[]]$codes) { -join ($codes | ForEach-Object { [char]$_ }) }
$TXT_PERM_ENTRY = U @(0x6743, 0x9650, 0x7BA1, 0x7406)                               # 权限管理
$TXT_SENSOR_ROW = U @(0x8BBE, 0x5907, 0x52A8, 0x4F5C, 0x4E0E, 0x65B9, 0x5411)       # 设备动作与方向
$TXT_APPLIST_ROW = U @(0x8BFB, 0x53D6, 0x5E94, 0x7528, 0x5217, 0x8868)              # 读取应用列表
$TXT_DENY = U @(0x4E0D, 0x5141, 0x8BB8)                                             # 不允许
$TXT_ALLOW = U @(0x5141, 0x8BB8)                                                    # 允许
$TXT_SPLASH = U @(0x4EC5, 0x5F00, 0x5C4F, 0x65F6, 0x4E0D, 0x5141, 0x8BB8)           # 仅开屏时不允许
# 广告跟踪相关文案（设置 - 隐私 - 更多 - 设备标识与广告 - 广告跟踪 - 更多选项 - 全部关闭）
$TXT_PRIVACY = U @(0x9690, 0x79C1)                                                       # 隐私
$TXT_DEVICE_ID_AD = U @(0x8BBE, 0x5907, 0x6807, 0x8BC6, 0x4E0E, 0x5E7F, 0x544A)          # 设备标识与广告
$TXT_AD_TRACK = U @(0x5E7F, 0x544A, 0x8DDF, 0x8E2A)                                      # 广告跟踪
$TXT_MORE_OPTIONS = U @(0x66F4, 0x591A, 0x9009, 0x9879)                                  # 更多选项
$TXT_CLOSE_ALL = U @(0x5168, 0x90E8, 0x5173, 0x95ED)                                     # 全部关闭

$sensorOption = switch ($Mode) {
    'Deny' { $TXT_DENY }
    'Allow' { $TXT_ALLOW }
    'SplashOnly' { $TXT_SPLASH }
}

$Whitelist = Normalize-List $Whitelist
$Only = Normalize-List $Only

function Resolve-AdbPath([string]$given) {
    if ($given -and (Test-Path $given)) { return $given }
    $cands = @()
    if ($env:ANDROID_HOME) { $cands += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') }
    if ($env:ANDROID_SDK_ROOT) { $cands += (Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe') }
    $cands += (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
    $cands += 'C:\Program Files\ASUS\GlideX\adb.exe'
    foreach ($c in $cands) { if ($c -and (Test-Path $c)) { return $c } }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

$script:AdbPath = Resolve-AdbPath $Adb
if (-not $script:AdbPath) {
    Fail '找不到 adb.exe。请安装 Android SDK platform-tools，或用 -Adb "C:\path\to\adb.exe" 指定。'
    exit 1
}

function AdbShell([string]$cmd) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $script:AdbPath shell $cmd 2>&1 | Out-String
    }
    finally {
        $ErrorActionPreference = $prev
    }
    return $out
}

# ---------- 设备检查 ----------
$deviceLines = @(& $script:AdbPath devices 2>&1 | Where-Object { $_ -match '\sdevice' })
if ($deviceLines.Count -eq 0) {
    Fail '没有检测到已授权设备。请插好数据线、打开「USB 调试」，并在手机上允许这台电脑调试。'
    exit 1
}
$model = (AdbShell 'getprop ro.product.model').Trim()
$ver = (AdbShell 'getprop ro.build.version.release').Trim()
Info "设备：$model  Android $ver"
Info "adb ：$script:AdbPath"
Info ''

# ---------- 能力自检 ----------
$probe = AdbShell 'cmd appops get android DIRECTION_SENSORS'
if ($probe -match 'Unknown operation') {
    Warn '本机没有 DIRECTION_SENSORS 这个 app-op：不是 ColorOS/OPlus 的「设备动作与方向」实现，传感器部分无法处理。'
    if (-not $AlsoAppList -and -not $AdTracking) { exit 2 }
}

# ---------- 应用列表 ----------
if ($Only.Count -gt 0) {
    $pkgs = @($Only)
}
else {
    $pkgs = @()
    foreach ($line in ((AdbShell 'pm list packages -3') -split "`n")) {
        $p = ($line -replace '^package:', '').Trim()
        if ($p) { $pkgs += $p }
    }
}
if ($Whitelist.Count -gt 0) {
    $pkgs = @($pkgs | Where-Object { $Whitelist -notcontains $_ })
}
$pkgs = @($pkgs | Sort-Object -Unique)

if ($pkgs.Count -eq 0) {
    Warn '没有要处理的应用。'
    exit 0
}

# ---------- 现状读取（纯 ASCII 输出，最可靠） ----------
function Get-SensorState([string]$pkg) {
    $t = AdbShell "cmd appops get $pkg DIRECTION_SENSORS"
    if ($t -match 'Unknown operation') { return 'unsupported' }
    if ($t -match 'DIRECTION_SENSORS:\s*ignore') { return 'denied' }
    if ($t -match 'DIRECTION_SENSORS:\s*allow') { return 'allowed' }
    return 'splash'
}

function StateText([string]$state) {
    switch ($state) {
        'denied' { return (U @(0x4E0D, 0x5141, 0x8BB8)) }                                    # 不允许
        'allowed' { return (U @(0x5141, 0x8BB8)) }                                           # 允许
        'splash' { return (U @(0x4EC5, 0x5F00, 0x5C4F, 0x65F6, 0x4E0D, 0x5141, 0x8BB8)) }    # 仅开屏时不允许
        default { return 'unsupported' }
    }
}

if ($Report) {
    Head "包名  ->  设备动作与方向"
    Head ('-' * 60)
    foreach ($p in $pkgs) {
        Info ("{0,-48} {1}" -f $p, (StateText (Get-SensorState $p)))
    }
    exit 0
}

# ---------- UI 自动化 ----------
$script:TmpFile = '/sdcard/.shakeguard_ps.xml'

function Get-Doc {
    & $script:AdbPath shell "rm -f $script:TmpFile; uiautomator dump $script:TmpFile >/dev/null 2>&1" | Out-Null
    $xml = AdbShell "cat $script:TmpFile"
    if ($xml -notmatch '<hierarchy') { return $null }
    try { return [xml]$xml } catch { return $null }
}

function Find-Node($doc, [string]$text) {
    if (-not $doc) { return $null }
    $nodes = $doc.SelectNodes("//node[@text='$text']")
    if ($nodes -and $nodes.Count -gt 0) { return $nodes.Item(0) }
    return $null
}

function Get-Center($node) {
    if ($node -and $node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
        $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
        return @{ x = $x; y = $y }
    }
    return $null
}

function Tap-Text([string]$text, [int]$timeoutMs = 8000) {
    $deadline = (Get-Date).AddMilliseconds($timeoutMs)
    while ((Get-Date) -lt $deadline) {
        $c = Get-Center (Find-Node (Get-Doc) $text)
        if ($c) {
            & $script:AdbPath shell "input tap $($c.x) $($c.y)" | Out-Null
            return $true
        }
        Start-Sleep -Milliseconds 400
    }
    return $false
}

function Invoke-OneApp([string]$pkg) {
    & $script:AdbPath shell "am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$pkg" | Out-Null

    if (-not (Tap-Text $TXT_PERM_ENTRY 9000)) {
        & $script:AdbPath shell 'input keyevent 3' | Out-Null
        return 'open-permission-failed'
    }
    Start-Sleep -Milliseconds 800

    $note = ''
    if (Tap-Text $TXT_SENSOR_ROW 7000) {
        Start-Sleep -Milliseconds 900
        if (-not (Tap-Text $sensorOption 7000)) { $note += 'option-not-found ' }
        Start-Sleep -Milliseconds 400
        & $script:AdbPath shell 'input keyevent 4' | Out-Null
        Start-Sleep -Milliseconds 500
    }
    else {
        $note += 'no-sensor-switch '
    }

    if ($AlsoAppList) {
        if (Tap-Text $TXT_APPLIST_ROW 7000) {
            Start-Sleep -Milliseconds 900
            if (-not (Tap-Text $TXT_DENY 7000)) { $note += 'applist-option-not-found ' }
            Start-Sleep -Milliseconds 400
            & $script:AdbPath shell 'input keyevent 4' | Out-Null
            Start-Sleep -Milliseconds 500
        }
        else {
            $note += 'no-applist-switch '
        }
    }

    & $script:AdbPath shell 'input keyevent 3' | Out-Null
    Start-Sleep -Milliseconds $DelayMs
    return $note
}

# ---------- 广告追踪自动化辅助 ----------
function Scroll-Down {
    $size = AdbShell 'wm size'
    $w = 1080; $h = 2400
    if ($size -match '(\d+)x(\d+)') { $w = [int]$Matches[1]; $h = [int]$Matches[2] }
    $x = [int]($w / 2)
    $from = [int]($h * 0.82); $to = [int]($h * 0.32)
    & $script:AdbPath shell "input swipe $x $from $x $to 400" | Out-Null
}

function Scroll-ToTop {
    $size = AdbShell 'wm size'
    $w = 1080; $h = 2400
    if ($size -match '(\d+)x(\d+)') { $w = [int]$Matches[1]; $h = [int]$Matches[2] }
    $x = [int]($w / 2)
    $from = [int]($h * 0.32); $to = [int]($h * 0.82)
    for ($i = 0; $i -lt 6; $i++) {
        & $script:AdbPath shell "input swipe $x $from $x $to 300" | Out-Null
        Start-Sleep -Milliseconds 300
    }
}

function Tap-Text-Scrolling([string]$text, [int]$timeoutMs = 12000, [int]$maxScrolls = 5) {
    $deadline = (Get-Date).AddMilliseconds($timeoutMs)
    $scrolls = 0
    while ((Get-Date) -lt $deadline) {
        $c = Get-Center (Find-Node (Get-Doc) $text)
        if ($c) { & $script:AdbPath shell "input tap $($c.x) $($c.y)" | Out-Null; return $true }
        if ($scrolls -lt $maxScrolls) { Scroll-Down; $scrolls++; Start-Sleep -Milliseconds 600 }
        else { Start-Sleep -Milliseconds 400 }
    }
    return $false
}

function Tap-Desc([string]$desc, [int]$timeoutMs = 8000) {
    $deadline = (Get-Date).AddMilliseconds($timeoutMs)
    while ((Get-Date) -lt $deadline) {
        $doc = Get-Doc
        if ($doc) {
            $nodes = $doc.SelectNodes("//node[@content-desc='$desc']")
            if ($nodes -and $nodes.Count -gt 0) {
                $c = Get-Center $nodes.Item(0)
                if ($c) { & $script:AdbPath shell "input tap $($c.x) $($c.y)" | Out-Null; return $true }
            }
        }
        Start-Sleep -Milliseconds 400
    }
    return $false
}

function Close-AllAdTracking {
    # 设置应用可能停在子页面/上次滚动位置：先回桌面并强制重启设置，保证从首页开始
    & $script:AdbPath shell 'input keyevent 3' | Out-Null
    Start-Sleep -Milliseconds 500
    & $script:AdbPath shell 'am force-stop com.android.settings' | Out-Null
    Start-Sleep -Milliseconds 800
    & $script:AdbPath shell 'am start -a android.settings.SETTINGS' | Out-Null
    Start-Sleep -Milliseconds 2000
    Scroll-ToTop
    Start-Sleep -Milliseconds 500
    if (-not (Tap-Text-Scrolling $TXT_PRIVACY 15000 5)) { return 'no-privacy-entry' }
    Start-Sleep -Milliseconds 900
    if (-not (Tap-Text-Scrolling $TXT_DEVICE_ID_AD 12000 6)) { return 'no-device-id-page' }
    Start-Sleep -Milliseconds 900
    if (-not (Tap-Text $TXT_AD_TRACK 8000)) { return 'no-ad-track-row' }
    Start-Sleep -Milliseconds 1200
    if (-not (Tap-Desc $TXT_MORE_OPTIONS 8000)) { return 'no-overflow-menu' }
    Start-Sleep -Milliseconds 900
    if (-not (Tap-Text $TXT_CLOSE_ALL 6000)) { return 'no-close-all-item' }
    Start-Sleep -Milliseconds 1500
    $on = 0
    $d = Get-Doc
    if ($d) { $d.SelectNodes('//node[@resource-id="com.android.settings:id/item_switch"]') | ForEach-Object { if ($_.checked -eq 'true') { $on++ } } }
    & $script:AdbPath shell 'input keyevent 3' | Out-Null
    if ($on -eq 0) { return 'ok' } else { return "still-on:$on" }
}

# ---------- 广告追踪：系统级一键全关 ----------
if ($AdTracking) {
    Head '打开 设置 → 隐私 → 更多 → 设备标识与广告 → 广告跟踪 …'
    $r = Close-AllAdTracking
    if ($r -eq 'ok') { Ok '✅ 所有应用（含系统应用）的广告跟踪已全部关闭' }
    else { Warn "⚠️ 没能完成：$r" }
    exit 0
}

# ---------- 确认 ----------
Info ''
Head "即将处理 $($pkgs.Count) 个应用，目标档位：$sensorOption"
if ($AlsoAppList) { Info '同时把「读取应用列表」设为不允许。' }
if ($Whitelist.Count -gt 0) { Info ("白名单（跳过）：" + ($Whitelist -join ', ')) }
Warn '处理期间手机会自己跳动设置界面，请不要手动操作手机，保持屏幕点亮。'
if (-not $Yes) {
    $ans = Read-Host '继续？(y/N)'
    if ($ans -ne 'y' -and $ans -ne 'Y') { Info '已取消。'; exit 0 }
}

# ---------- 主循环 ----------
& $script:AdbPath shell 'input keyevent KEYCODE_WAKEUP' | Out-Null
Start-Sleep -Milliseconds 500

$i = 0
$okCount = 0
$failCount = 0
$failed = @()

foreach ($p in $pkgs) {
    $i++
    $note = Invoke-OneApp $p
    $state = Get-SensorState $p

    $good = $false
    if ($Mode -eq 'Deny' -and $state -eq 'denied') { $good = $true }
    elseif ($Mode -eq 'Allow' -and $state -eq 'allowed') { $good = $true }
    elseif ($Mode -eq 'SplashOnly' -and $state -eq 'splash') { $good = $true }

    if ($good) {
        $okCount++
        Ok ("[{0}/{1}] {2}   OK -> {3}" -f $i, $pkgs.Count, $p, (StateText $state))
    }
    else {
        $failCount++
        $failed += $p
        Warn ("[{0}/{1}] {2}   FAIL -> {3}  {4}" -f $i, $pkgs.Count, $p, (StateText $state), $note)
    }
}

Info ''
Info ('-' * 60)
Ok "完成：成功 $okCount 个，失败 $failCount 个"
if ($failCount -gt 0) {
    Warn '以下应用没改成功（多为系统应用，ColorOS 不给它们这个开关）：'
    foreach ($f in $failed) { Info "  - $f" }
}
Info ''
Info '还原：.\close-shake-ads.ps1 -Mode SplashOnly -Yes'
