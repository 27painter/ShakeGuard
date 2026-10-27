package com.shakeguard.app.core.net

import android.content.Context
import com.shakeguard.app.core.Prefs
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 广告域名规则库。
 *
 * 拦截来源三份，合并成一个集合：
 *  1) assets/ad_domains.txt —— 内置种子规则（保守，只放明确的广告/追踪域名）
 *  2) files/rules_downloaded.txt —— 在线更新的规则（hosts / Clash domain-set / AdGuard 三种格式都解析）
 *  3) files/rules_user.txt —— 用户自己加的域名
 *
 * 放行来源两份（优先级高于拦截，永不拦截）：
 *  1) assets/allow_domains.txt —— 登录 / 支付 / 授权关键域名（淘宝、支付宝、微信、QQ、京东…）
 *  2) files/rules_allow.txt —— 用户自己加的放行域名
 *
 * 匹配规则：
 *  - 拦截：完全相等，或该域名的任意上级域名命中（sub.example.com 命中 example.com）；
 *  - 放行：同上，但优先级更高；并且放行项会「向上保护」它的上级域名，
 *    避免规则库里有人拦了 qq.com 把 graph.qq.com（QQ 登录）一起带走。
 */
object RuleStore {

    private const val ASSET_SEED = "ad_domains.txt"
    private const val ASSET_ALLOW = "allow_domains.txt"
    private const val FILE_DOWNLOADED = "rules_downloaded.txt"
    private const val FILE_USER = "rules_user.txt"
    private const val FILE_ALLOW_USER = "rules_allow.txt"

    /** 在线规则源（2026-10 实测可用；raw.githubusercontent 在国内会被重置，不放进来） */
    val DEFAULT_SOURCES = listOf(
        "https://anti-ad.net/domains.txt",
        "https://adrules.top/adrules_domainset.txt",
        "https://adaway.org/hosts.txt"
    )

    /**
     * 登录跳转保护：域名里带这些词的，默认不拦。
     *
     * 这一条是「宁可少拦一点，也别把登录弄坏」的兜底 —— 实测被拦之后的表现是
     * 「点了微信登录，跳过去又弹回来」或者授权页一片空白，普通用户根本查不出原因。
     */
    private val LOGIN_KEYWORDS = listOf(
        "login", "passport", "oauth", "sso", "signin", "verify",
        "captcha", "openapi", "wallet", "pay", "auth", "account", "token"
    )

    @Volatile
    private var rules: HashSet<String> = HashSet()

    @Volatile
    private var allow: HashSet<String> = HashSet()

    /** 登录跳转保护开关（UI 可切，持久化在 Prefs） */
    @Volatile
    var loginProtect: Boolean = true

    val size: Int get() = rules.size
    val allowSize: Int get() = allow.size

    fun reload(context: Context) {
        loginProtect = Prefs(context).loginProtect

        val blockSet = HashSet<String>(32768)
        runCatching {
            context.assets.open(ASSET_SEED).bufferedReader().use { r -> r.forEachLine { addLine(blockSet, it) } }
        }
        runCatching { readFile(File(context.filesDir, FILE_DOWNLOADED), blockSet) }
        runCatching { readFile(File(context.filesDir, FILE_USER), blockSet) }
        rules = blockSet

        val allowSet = HashSet<String>(1024)
        runCatching {
            context.assets.open(ASSET_ALLOW).bufferedReader().use { r -> r.forEachLine { addAllowLine(allowSet, it) } }
        }
        runCatching { readFile(File(context.filesDir, FILE_ALLOW_USER), allowSet) }
        allow = expandParents(allowSet)
    }

    private fun readFile(file: File, set: HashSet<String>) {
        if (!file.exists()) return
        file.bufferedReader().use { r -> r.forEachLine { addLine(set, it) } }
    }

    /** 放行名单用同一套写法解析，额外支持 `*.example.com` 通配 */
    private fun addAllowLine(set: HashSet<String>, raw: String) {
        val cleaned = raw.trim().removePrefix("*.").removePrefix("+.")
        if (cleaned.isEmpty()) return
        addLine(set, cleaned)
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

    /**
     * 放行项向上保护：graph.qq.com 放行时，把 qq.com 也放进放行集合。
     * 这样即使规则库里出现「qq.com」这种大范围条目，登录也不会被打断。
     */
    private fun expandParents(source: HashSet<String>): HashSet<String> {
        val out = HashSet<String>(source.size * 3 + 16)
        for (domain in source) {
            out.add(domain)
            var d = domain
            var idx = d.indexOf('.')
            while (idx in 1 until d.length) {
                d = d.substring(idx + 1)
                if (d.count { it == '.' } < 1) break   // 只剩 com / cn 这类顶级域时停手
                out.add(d)
                idx = d.indexOf('.')
            }
        }
        return out
    }

    fun isBlocked(domain: String): Boolean {
        val set = rules
        if (set.isEmpty()) return false
        val d = domain.lowercase().trimEnd('.')
        if (d.isEmpty()) return false
        if (isAllowed(d)) return false
        if (loginProtect && looksLikeLoginDomain(d)) return false
        return matchSuffix(set, d)
    }

    /** 是否在放行名单里（含子域名） */
    fun isAllowed(domain: String): Boolean {
        val set = allow
        if (set.isEmpty()) return false
        val d = domain.lowercase().trimEnd('.')
        if (d.isEmpty()) return false
        return matchSuffix(set, d)
    }

    private fun matchSuffix(set: HashSet<String>, domain: String): Boolean {
        var d = domain
        if (set.contains(d)) return true
        var idx = d.indexOf('.')
        while (idx in 1 until d.length) {
            d = d.substring(idx + 1)
            if (set.contains(d)) return true
            idx = d.indexOf('.')
        }
        return false
    }

    /** 域名里出现 login / passport / pay 之类的词，就当登录链路保护起来 */
    private fun looksLikeLoginDomain(domain: String): Boolean {
        for (label in domain.split('.')) {
            if (label.length < 4) continue
            for (kw in LOGIN_KEYWORDS) {
                if (label.contains(kw)) return true
            }
        }
        return false
    }

    /** 切换「登录跳转保护」，立即生效并持久化 */
    fun setLoginProtect(context: Context, on: Boolean) {
        loginProtect = on
        Prefs(context).loginProtect = on
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
            setRequestProperty("User-Agent", "ShakeGuard/1.1")
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
        val d = normalizeDomain(domain) ?: return
        runCatching { File(context.filesDir, FILE_USER).appendText("$d\n") }
        reload(context)
    }

    /** 用户手动放行某个域名（例如某个 App 的登录被误拦） */
    fun addUserAllow(context: Context, domain: String) {
        val d = normalizeDomain(domain) ?: return
        runCatching { File(context.filesDir, FILE_ALLOW_USER).appendText("$d\n") }
        reload(context)
    }

    private fun normalizeDomain(domain: String): String? {
        val d = domain.trim().lowercase()
            .removePrefix("https://").removePrefix("http://")
            .substringBefore('/')
            .removePrefix("*.")
        if (d.isEmpty() || !d.contains('.')) return null
        if (d.any { it.isWhitespace() || it == ':' || it == '@' }) return null
        return d
    }

    fun downloadedFile(context: Context): File = File(context.filesDir, FILE_DOWNLOADED)
}
