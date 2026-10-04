package com.shakeguard.app.core.net

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 广告域名规则库。
 *
 * 来源三份，合并成一个集合：
 *  1) assets/ad_domains.txt —— 内置种子规则（保守，只放明确的广告/追踪域名）
 *  2) files/rules_downloaded.txt —— 在线更新的规则（hosts / Clash domain-set / AdGuard 三种格式都解析）
 *  3) files/rules_user.txt —— 用户自己加的域名
 *
 * 匹配规则：完全相等，或该域名的任意上级域名命中（sub.example.com 命中 example.com）。
 */
object RuleStore {

    private const val ASSET_SEED = "ad_domains.txt"
    private const val FILE_DOWNLOADED = "rules_downloaded.txt"
    private const val FILE_USER = "rules_user.txt"

    /** 在线规则源（2026-10 实测可用；raw.githubusercontent 在国内会被重置，不放进来） */
    val DEFAULT_SOURCES = listOf(
        "https://anti-ad.net/domains.txt",
        "https://adrules.top/adrules_domainset.txt",
        "https://adaway.org/hosts.txt"
    )

    @Volatile
    private var rules: HashSet<String> = HashSet()

    val size: Int get() = rules.size

    fun reload(context: Context) {
        val set = HashSet<String>(32768)
        runCatching {
            context.assets.open(ASSET_SEED).bufferedReader().use { r -> r.forEachLine { addLine(set, it) } }
        }
        runCatching { readFile(File(context.filesDir, FILE_DOWNLOADED), set) }
        runCatching { readFile(File(context.filesDir, FILE_USER), set) }
        rules = set
    }

    private fun readFile(file: File, set: HashSet<String>) {
        if (!file.exists()) return
        file.bufferedReader().use { r -> r.forEachLine { addLine(set, it) } }
    }

    /**
     * 支持四种写法：
     *  - hosts：       0.0.0.0 ads.example.com
     *  - 纯域名：      ads.example.com
     *  - Clash 域集：  +.ads.example.com / .ads.example.com / domain:ads.example.com
     *  - AdGuard：     ||ads.example.com^
     * 白名单、元素隐藏、带路径/通配的正则规则一律跳过。
     */
    private fun addLine(set: HashSet<String>, raw: String) {
        var line = raw.trim()
        if (line.isEmpty()) return
        if (line.startsWith("#") || line.startsWith("!") || line.startsWith("[")) return
        if (line.startsWith("@@")) return
        if (line.contains("##") || line.contains("#@#") || line.contains("#?#")) return

        if (line.startsWith("+.")) line = line.removePrefix("+.")
        else if (line.startsWith(".")) line = line.removePrefix(".")

        for (prefix in listOf("domain:", "full:", "host:", "address=")) {
            if (line.startsWith(prefix)) line = line.removePrefix(prefix)
        }
        if (line.startsWith("keyword:") || line.startsWith("regexp:") || line.startsWith("server=/")) return

        if (line.startsWith("||")) line = line.removePrefix("||")
        line = line.removeSuffix("^").trim()
        if (line.isEmpty()) return
        if (line.contains('/') || line.contains('*') || line.contains('$') || line.contains('|')) return

        val parts = line.split(Regex("\\s+"))
        var domain = if (parts.size >= 2 &&
            (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1" || parts[0] == "::1" || parts[0] == "::")
        ) parts[1] else parts[0]

        domain = domain.trim().lowercase().trimEnd('.')
        if (domain.isEmpty() || domain.length > 253) return
        if (!domain.contains('.')) return
        if (domain == "localhost" || domain.endsWith(".local") || domain.endsWith(".lan")) return
        if (domain.any { it.isWhitespace() || it == ':' || it == '@' }) return
        set.add(domain)
    }

    fun isBlocked(domain: String): Boolean {
        val set = rules
        if (set.isEmpty()) return false
        var d = domain.lowercase().trimEnd('.')
        if (d.isEmpty()) return false
        if (set.contains(d)) return true
        var idx = d.indexOf('.')
        while (idx in 1 until d.length) {
            d = d.substring(idx + 1)
            if (set.contains(d)) return true
            idx = d.indexOf('.')
        }
        return false
    }

    /**
     * 在线更新：返回解析出的规则条数，-1 表示失败。
     * 会校验内容（不是网页、条数够多），不合格就返回失败，让上层去试下一个源，也不会覆盖已有规则。
     */
    fun download(context: Context, url: String): Int {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ShakeGuard/1.0")
        }
        return try {
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            if (text.contains("<html", ignoreCase = true) || text.contains("<!DOCTYPE", ignoreCase = true)) {
                return -1
            }
            val temp = HashSet<String>(32768)
            text.lineSequence().forEach { addLine(temp, it) }
            if (temp.size < 100) return -1
            File(context.filesDir, FILE_DOWNLOADED).writeText(text)
            reload(context)
            temp.size
        } catch (t: Throwable) {
            -1
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    fun addUserRule(context: Context, domain: String) {
        val d = domain.trim().lowercase()
        if (d.isEmpty()) return
        runCatching { File(context.filesDir, FILE_USER).appendText("$d\n") }
        reload(context)
    }

    fun downloadedFile(context: Context): File = File(context.filesDir, FILE_DOWNLOADED)
}
