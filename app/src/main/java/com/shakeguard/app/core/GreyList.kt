package com.shakeguard.app.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 灰名单：**临时放行**名单。
 *
 * 为什么需要它：
 *   微信 / QQ / 支付宝 / 淘宝 这些 App 既是「开屏广告大户」，又是「登录中转站」。
 *   一刀切不点它们，开屏广告全留着；一刀切全点，又可能把「跳过登录」点掉。
 *
 * 所以这里用「撞了才退」的办法：
 *   - 平时照常跳过它们的开屏广告（用户最想要的效果）；
 *   - 一旦在它们身上识别到登录 / 授权页，说明这里有风险 ——
 *     立刻把这个 App 放进灰名单，接下来 [DEFAULT_MINUTES] 分钟内**完全不去点它**，
 *     让登录流程安安稳稳走完；
 *   - 到期后自动解除，重新开始跳开屏广告。
 *
 * 灰名单也可以在首页手动解除 / 清空。
 */
object GreyList {

    private const val PREFS = "shakeguard_grey"
    private const val KEY_ENTRIES = "entries"
    private const val DEFAULT_MINUTES = 60L

    data class Entry(
        val pkg: String,
        val label: String,
        val reason: String,
        val until: Long,
        val hits: Int
    ) {
        val active: Boolean get() = until > System.currentTimeMillis()
        val remainingMinutes: Int
            get() = (((until - System.currentTimeMillis()) + 59_999L) / 60_000L).toInt().coerceAtLeast(0)
    }

    /** 界面用：灰名单里的条目（只放还在有效期内的） */
    val entries = MutableStateFlow<List<Entry>>(emptyList())

    @Volatile
    private var map: HashMap<String, Entry> = HashMap()

    fun load(context: Context) {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null)
        val loaded = HashMap<String, Entry>()
        if (!raw.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val e = Entry(
                        pkg = o.optString("pkg"),
                        label = o.optString("label"),
                        reason = o.optString("reason"),
                        until = o.optLong("until"),
                        hits = o.optInt("hits", 1)
                    )
                    if (e.pkg.isNotEmpty() && e.active) loaded[e.pkg] = e
                }
            }
        }
        map = loaded
        entries.value = loaded.values.sortedByDescending { it.until }
    }

    private fun save(context: Context) {
        val arr = JSONArray()
        map.values.forEach { e ->
            arr.put(
                JSONObject()
                    .put("pkg", e.pkg)
                    .put("label", e.label)
                    .put("reason", e.reason)
                    .put("until", e.until)
                    .put("hits", e.hits)
            )
        }
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ENTRIES, arr.toString()).apply()
        }
        entries.value = map.values.filter { it.active }.sortedByDescending { it.until }
    }

    /** 这个包现在是不是被放行中（热路径，纯内存判断） */
    fun isGrey(pkg: String): Boolean {
        val e = map[pkg] ?: return false
        return e.active
    }

    /** 把应用放进灰名单；已经在里面就续期并累加次数 */
    fun add(context: Context, pkg: String, reason: String, minutes: Long = DEFAULT_MINUTES): Entry {
        val label = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        val old = map[pkg]
        val e = Entry(
            pkg = pkg,
            label = if (old != null && old.label.isNotBlank()) old.label else label,
            reason = reason,
            until = System.currentTimeMillis() + minutes * 60_000L,
            hits = (old?.hits ?: 0) + 1
        )
        map[pkg] = e
        save(context)
        return e
    }

    fun remove(context: Context, pkg: String) {
        map.remove(pkg)
        save(context)
    }

    fun clear(context: Context) {
        map.clear()
        save(context)
    }

    /** 清掉已过期的条目（服务里定期调用，避免列表越来越长） */
    fun prune(context: Context) {
        val expired = map.filterValues { !it.active }.keys
        if (expired.isEmpty()) return
        expired.forEach { map.remove(it) }
        save(context)
    }

    /** 界面上手动「解除」某个应用时用 */
    fun removeAll(context: Context, pkgs: Collection<String>) {
        var changed = false
        pkgs.forEach { if (map.remove(it) != null) changed = true }
        if (changed) save(context)
    }
}
