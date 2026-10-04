@echo off
rem 摇一摇克星 · adb 脚本启动器
rem 作用：绕过 PowerShell 执行策略限制（未签名的 .ps1 默认不允许运行）
rem
rem 用法：
rem   双击本文件           -> 全部第三方应用关闭摇一摇（会先让你确认）
rem   命令行带参数，例如：
rem     run-close-shake-ads.cmd -Report
rem     run-close-shake-ads.cmd -Only com.kugou.android.lite
rem     run-close-shake-ads.cmd -Whitelist com.tencent.tmgp.pubgmhd -Yes
rem     run-close-shake-ads.cmd -AlsoAppList -Yes
rem     run-close-shake-ads.cmd -Mode SplashOnly -Yes

setlocal
set "PS1=%~dp0close-shake-ads.ps1"
if not exist "%PS1%" (
    echo [错误] 找不到 close-shake-ads.ps1，请确认它和本文件在同一个目录。
    pause
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%PS1%" %*
set "CODE=%ERRORLEVEL%"
echo.
echo （退出码 %CODE%）
pause
