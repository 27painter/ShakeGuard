package com.shakeguard.app.core

/**
 * 读取每个应用的 app-op 状态。
 *
 * 实测（ColorOS 16 / Android 16）三档与 app-op 的对应关系：
 *   ColorOS「允许」          <-> DIRECTION_SENSORS: allow   (uid mode: allow)
 *   ColorOS「仅开屏时不允许」 <-> DIRECTION_SENSORS: default (或 "No operations.")
 *   ColorOS「不允许」        <-> DIRECTION_SENSORS: ignore  (uid mode: ignore)
 */
object OpsReader {

    const val OP_SENSOR = "DIRECTION_SENSORS"
    const val OP_APPLIST = "GET_INSTALLED_APPS"

    data class States(val sensor: SensorState, val track: TrackState)

    private val SENSOR_RE = Regex("DIRECTION_SENSORS:\\s*(\\w+)")
    private val TRACK_RE = Regex("GET_INSTALLED_APPS:\\s*(\\w+)")

    /** 一次性读取多个包的状态（分批拼成一条 shell 命令，比逐个调用快很多） */
    fun readAll(packages: List<String>): Map<String, States> {
        val result = LinkedHashMap<String, States>()
        packages.chunked(25).forEach { chunk ->
            val sb = StringBuilder()
            chunk.forEach { p ->
                sb.append("echo '@@").append(p).append("'; ")
                sb.append("echo 'S:'; cmd appops get ").append(p).append(' ').append(OP_SENSOR).append(" 2>&1; ")
                sb.append("echo 'T:'; cmd appops get ").append(p).append(' ').append(OP_APPLIST).append(" 2>&1; ")
                sb.append("echo '=='; ")
            }
            parse(ShizukuShell.exec(sb.toString(), 60_000L).text, result)
        }
        return result
    }

    fun readOne(pkg: String): States? = readAll(listOf(pkg))[pkg]

    private fun parse(text: String, into: MutableMap<String, States>) {
        var pkg: String? = null
        var section = 0
        val sensorText = StringBuilder()
        val trackText = StringBuilder()

        fun flush() {
            val p = pkg ?: return
            into[p] = States(parseSensor(sensorText.toString()), parseTrack(trackText.toString()))
        }

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("@@") -> {
                    flush()
                    pkg = line.removePrefix("@@").trim()
                    sensorText.setLength(0)
                    trackText.setLength(0)
                    section = 0
                }
                line == "S:" -> section = 1
                line == "T:" -> section = 2
                line == "==" -> {
                    flush()
                    pkg = null
                    section = 0
                }
                section == 1 -> sensorText.append(line).append('\n')
                section == 2 -> trackText.append(line).append('\n')
            }
        }
        flush()
    }

    private fun parseSensor(t: String): SensorState = when {
        t.isBlank() -> SensorState.UNKNOWN
        t.contains("Unknown operation") -> SensorState.UNSUPPORTED
        SENSOR_RE.findAll(t).any { it.groupValues[1] == "ignore" } -> SensorState.DENIED
        SENSOR_RE.findAll(t).any { it.groupValues[1] == "allow" } -> SensorState.ALLOWED
        else -> SensorState.SPLASH_ONLY
    }

    private fun parseTrack(t: String): TrackState = when {
        t.isBlank() -> TrackState.UNKNOWN
        t.contains("Unknown operation") -> TrackState.UNSUPPORTED
        TRACK_RE.findAll(t).any { it.groupValues[1] == "ignore" } -> TrackState.DENIED
        TRACK_RE.findAll(t).any { it.groupValues[1] == "allow" } -> TrackState.ALLOWED
        else -> TrackState.DEFAULT
    }
}
