package com.shakeguard.app.core

import android.content.Context

/** 极简本地存储：白名单 + 全局开关缓存 */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("shakeguard", Context.MODE_PRIVATE)

    var whitelist: Set<String>
        get() = sp.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_WHITELIST, HashSet(value)).apply()

    fun isWhitelisted(pkg: String): Boolean = whitelist.contains(pkg)

    fun setWhitelisted(pkg: String, on: Boolean) {
        val s = HashSet(whitelist)
        if (on) s.add(pkg) else s.remove(pkg)
        whitelist = s
    }

    var lastBatchAt: Long
        get() = sp.getLong(KEY_LAST, 0L)
        set(value) = sp.edit().putLong(KEY_LAST, value).apply()

    /** 用户是不是开着广告域名拦截（App 被"清除全部"杀掉后用它自动恢复） */
    var vpnDesired: Boolean
        get() = sp.getBoolean(KEY_VPN_DESIRED, false)
        set(value) = sp.edit().putBoolean(KEY_VPN_DESIRED, value).apply()

    companion object {
        private const val KEY_WHITELIST = "whitelist"
        private const val KEY_LAST = "last_batch_at"
        private const val KEY_VPN_DESIRED = "vpn_desired"
    }
}
