package com.shakeguard.app.core.net

/**
 * 本地 DNS 缓存（LRU）。
 *
 * 为什么必须有它：手机 App 一次冷启动会连着发几十个 DNS 查询（尤其是登录跳转那种
 * 「拉起另一个 App 再拉回来」的场景）。没有缓存时每个查询都要占一个线程去问上游，
 * 线程一满，后面的查询就排队排到客户端超时 —— 表现就是「偶尔断网 / 跳转失败」。
 *
 * 命中缓存后是纯内存操作，不占线程、不问上游。
 */
class DnsCache(private val maxEntries: Int = 2048) {

    private class Entry(val payload: ByteArray, val expiresAt: Long)

    private val map = object : LinkedHashMap<String, Entry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(key: String): ByteArray? {
        val e = map[key] ?: return null
        if (e.expiresAt < System.currentTimeMillis()) {
            map.remove(key)
            return null
        }
        return e.payload
    }

    @Synchronized
    fun put(key: String, payload: ByteArray, ttlSeconds: Int) {
        val ttl = ttlSeconds.coerceIn(5, 3600)
        map[key] = Entry(payload.copyOf(), System.currentTimeMillis() + ttl * 1000L)
    }

    @Synchronized
    fun clear() = map.clear()

    @Synchronized
    fun size(): Int = map.size
}
