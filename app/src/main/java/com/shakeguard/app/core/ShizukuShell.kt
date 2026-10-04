package com.shakeguard.app.core

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess

/**
 * 通过 Shizuku 以 shell(uid 2000) 身份执行命令。
 *
 * 为什么不用 `cmd appops set` 直接改？
 * 实测 ColorOS 16（Android 16 / PMM110）已经把 shell 的特权收掉了：
 *
 *   cmd appops set <pkg> DIRECTION_SENSORS ignore
 *     -> SecurityException: uid 2000 does not have android.permission.MANAGE_APP_OPS_MODES
 *   pm revoke <pkg> <perm>
 *     -> SecurityException: ... does not have android.permission.REVOKE_RUNTIME_PERMISSIONS
 *
 * 但下面这些 shell 能力仍然可用，本应用就是靠它们工作的：
 *   1) 读：`cmd appops get`（有 GET_APP_OPS_STATS）
 *   2) 驱动系统界面：`am start` / `uiautomator dump` / `input tap`
 *   3) 写全局设置：`settings put`（有 WRITE_SECURE_SETTINGS）
 */
object ShizukuShell {

    const val REQUEST_CODE = 4210

    data class Result(val out: String, val err: String) {
        val text: String get() = if (err.isBlank()) out else "$out\n$err"
    }

    fun binderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        binderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    fun requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (_: Throwable) {
        }
    }

    /**
     * Shizuku API 里的 newProcess 是 private 的（官方示例也用反射调用），
     * 所以这里必须走反射，否则编译期就会报 "Cannot access ... it is private"。
     */
    private val newProcessMethod: java.lang.reflect.Method? by lazy {
        try {
            Shizuku::class.java
                .getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                .apply { isAccessible = true }
        } catch (t: Throwable) {
            null
        }
    }

    /** 执行一条 shell 命令。命令卡住超时后会 destroy 进程。 */
    fun exec(command: String, timeoutMs: Long = 20_000L): Result {
        if (!hasPermission()) return Result("", "Shizuku 未授权")
        val method = newProcessMethod
            ?: return Result("", "当前 Shizuku 版本里找不到 newProcess，请用 Shizuku 13.x")
        var process: ShizukuRemoteProcess? = null
        return try {
            val p = method.invoke(null, arrayOf("sh", "-c", command), null, null) as? ShizukuRemoteProcess
                ?: return Result("", "Shizuku.newProcess 返回空")
            process = p
            val out = StringBuilder()
            val err = StringBuilder()
            val tOut = Thread { runCatching { p.inputStream.bufferedReader().use { out.append(it.readText()) } } }
            val tErr = Thread { runCatching { p.errorStream.bufferedReader().use { err.append(it.readText()) } } }
            tOut.start()
            tErr.start()
            tOut.join(timeoutMs)
            tErr.join(1500L)
            val stuck = tOut.isAlive || tErr.isAlive
            if (stuck) runCatching { p.destroy() }
            Result(out.toString(), if (stuck) err.toString() + "\n[命令超时]" else err.toString())
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            Result("", "命令执行失败：${cause.javaClass.simpleName}: ${cause.message}")
        } finally {
            runCatching { process?.destroy() }
        }
    }

    /** settings get，返回去掉换行的值 */
    fun getSetting(namespace: String, key: String): String =
        exec("settings get $namespace $key", 8_000L).out.trim()

    /** settings put */
    fun putSetting(namespace: String, key: String, value: String): Result =
        exec("settings put $namespace $key $value", 8_000L)

    /** 判断本机是否支持某个 app-op 名（ColorOS 才有 DIRECTION_SENSORS） */
    fun opSupported(op: String): Boolean =
        !exec("cmd appops get android $op", 8_000L).text.contains("Unknown operation")
}
