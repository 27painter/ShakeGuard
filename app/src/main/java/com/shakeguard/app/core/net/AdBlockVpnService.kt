package com.shakeguard.app.core.net

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.shakeguard.app.App
import com.shakeguard.app.MainActivity
import com.shakeguard.app.R
import com.shakeguard.app.core.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 广告域名拦截：一个「只劫持 DNS」的本地 VPN（AdGuard 的 DNS 模式就是这个原理）。
 *
 * 关键设计：只把虚拟 DNS 服务器这一个地址加进路由（addRoute("10.111.222.1", 32)），
 * 其它所有流量根本不进 VPN —— 所以不用转发数据包、不额外耗电、也不掉网速。
 *
 * 工作流程：
 *   应用的 DNS 查询被送到 10.111.222.1:53 -> 我们解析出域名
 *     -> 放行名单命中：直接转发，绝不拦（登录 / 支付域名走这条）
 *     -> 命中广告规则：直接回 0.0.0.0（这个域名等于被拔了网线）
 *     -> 没命中：先查本地缓存；缓存没有再用受保护的 socket 转发给上游 DNS
 *
 * 稳定性要点（v1.1 重点修的就是这里）：
 *   - 本地 LRU 缓存：冷启动那种几十个查询的突发场景不再打满线程池；
 *   - 线程池 8 -> 32：突发时不会排队排到客户端超时（超时的表现就是「偶尔断网」）；
 *   - 上游超时 3s -> 1.2s，并且记住上次成功的上游先问：最坏情况从 9s 降到 ~3.6s；
 *   - 不再对每个查询打 Log.i：那是同步 I/O，查询一多会明显拖慢解析。
 */
class AdBlockVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null

    @Volatile
    private var active = false

    private val cache = DnsCache()

    /** 记住上一次成功的上游下标，下次优先用它 */
    private val preferredUpstream = AtomicInteger(0)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (active) return
        RuleStore.reload(this)
        // 先挂通知：用 startForegroundService 启动的服务必须在 5 秒内进入前台
        startForeground(NOTIFICATION_ID, buildNotification())

        val pfd = try {
            Builder()
                .setSession("摇一摇克星·广告拦截")
                .setMtu(1500)
                .addAddress(VIRTUAL_ADDRESS, 32)
                .addDnsServer(VIRTUAL_DNS)
                .addRoute(VIRTUAL_DNS, 32)
                .setBlocking(true)
                .establish()
        } catch (t: Throwable) {
            null
        }

        if (pfd == null) {
            active = false
            running.value = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        tun = pfd
        active = true
        running.value = true
        cache.clear()
        worker = Thread({ loop(pfd) }, "shakeguard-dns").also { it.start() }
    }

    private fun loop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buffer = ByteArray(32767)
        val pool = Executors.newFixedThreadPool(POOL_SIZE)
        try {
            while (active) {
                val n = try {
                    input.read(buffer)
                } catch (t: Throwable) {
                    break
                }
                if (n <= 0) continue
                val packet = buffer.copyOf(n)
                runCatching { pool.execute { handle(packet, n, output) } }
            }
        } catch (t: Throwable) {
            // 服务被停掉时读会抛异常，正常
        } finally {
            pool.shutdownNow()
            runCatching { input.close() }
            runCatching { output.close() }
        }
    }

    private fun handle(packet: ByteArray, length: Int, output: FileOutputStream) {
        if (!DnsPacket.isIpv4Udp(packet, length)) return
        if (DnsPacket.destinationPort(packet) != 53) return

        val question = DnsPacket.parseQuestion(packet, length) ?: return
        queryCount.value = queryCount.value + 1

        // 1) 命中广告规则：回 0.0.0.0
        if (RuleStore.isBlocked(question.domain)) {
            blockedCount.value = blockedCount.value + 1
            recordBlocked(question.domain)
            val payload = DnsPacket.buildBlockedPayload(packet, length, question) ?: return
            writePacket(output, DnsPacket.buildUdpReply(packet, length, payload, payload.size))
            maybeUpdateNotification()
            return
        }

        // 2) 本地缓存
        val key = question.qType.toString() + "|" + question.domain
        val cached = cache.get(key)
        if (cached != null) {
            val payload = DnsPacket.rewriteForCached(cached, packet)
            writePacket(output, DnsPacket.buildUdpReply(packet, length, payload, payload.size))
            return
        }

        // 3) 问上游
        val upstream = queryUpstream(packet, length)
        if (upstream == null) {
            Log.w(TAG, "上游全部失败：${question.domain}")
            val payload = DnsPacket.buildFailurePayload(packet, length) ?: return
            writePacket(output, DnsPacket.buildUdpReply(packet, length, payload, payload.size))
            return
        }
        runCatching { cache.put(key, upstream, DnsPacket.minTtl(upstream)) }
        writePacket(output, DnsPacket.buildUdpReply(packet, length, upstream, upstream.size))
    }

    private fun writePacket(output: FileOutputStream, packet: ByteArray?) {
        if (packet == null) return
        synchronized(output) {
            runCatching { output.write(packet) }
        }
    }

    /** 用受保护的 socket 去问上游 DNS（不 protect 的话会被自己的 VPN 再抓回来，形成死循环） */
    private fun queryUpstream(packet: ByteArray, length: Int): ByteArray? {
        val dnsOffset = DnsPacket.dnsOffset(packet)
        val dnsLength = length - dnsOffset
        if (dnsLength <= 0) return null

        val start = preferredUpstream.get() % UPSTREAM_SERVERS.size
        for (i in UPSTREAM_SERVERS.indices) {
            val index = (start + i) % UPSTREAM_SERVERS.size
            val server = UPSTREAM_SERVERS[index]
            try {
                DatagramSocket().use { socket ->
                    socket.soTimeout = UPSTREAM_TIMEOUT_MS
                    protect(socket)
                    val address = InetAddress.getByName(server)
                    socket.send(DatagramPacket(packet, dnsOffset, dnsLength, address, 53))
                    val responseBuffer = ByteArray(4096)
                    val response = DatagramPacket(responseBuffer, responseBuffer.size)
                    socket.receive(response)
                    if (response.length > 0) {
                        preferredUpstream.set(index)
                        return responseBuffer.copyOf(response.length)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "上游 $server 查询失败：${t.javaClass.simpleName}")
            }
        }
        return null
    }

    private fun recordBlocked(domain: String) {
        val list = recentBlocked.value
        if (list.firstOrNull() == domain) return
        val next = ArrayList<String>(list.size + 1)
        next.add(domain)
        for (d in list) {
            if (next.size >= 60) break
            if (d != domain) next.add(d)
        }
        recentBlocked.value = next
    }

    private var lastNotifyAt = 0L

    private fun maybeUpdateNotification() {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < 5_000L) return
        lastNotifyAt = now
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    private fun stopVpn() {
        active = false
        running.value = false
        runCatching { worker?.interrupt() }
        runCatching { tun?.close() }
        tun = null
        worker = null
        cache.clear()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentPi = PendingIntent.getActivity(this, 10, openIntent, flags)
        val stopPi = PendingIntent.getService(
            this, 11,
            Intent(this, AdBlockVpnService::class.java).setAction(ACTION_STOP),
            flags
        )
        return NotificationCompat.Builder(this, App.CHANNEL_ADBLOCK)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle("广告域名拦截运行中")
            .setContentText("规则 ${RuleStore.size} 条，已拦截 ${blockedCount.value} 次")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPi)
            .addAction(0, "停止", stopPi)
            .build()
    }

    companion object {
        const val ACTION_START = "com.shakeguard.app.action.VPN_START"
        const val ACTION_STOP = "com.shakeguard.app.action.VPN_STOP"
        private const val TAG = "ShakeGuardVpn"

        private const val NOTIFICATION_ID = 1002
        private const val VIRTUAL_DNS = "10.111.222.1"
        private const val VIRTUAL_ADDRESS = "10.111.222.2"
        private const val POOL_SIZE = 32
        private const val UPSTREAM_TIMEOUT_MS = 1200
        private val UPSTREAM_SERVERS = listOf("223.5.5.5", "119.29.29.29", "114.114.114.114")

        val running = MutableStateFlow(false)
        val blockedCount = MutableStateFlow(0L)
        val queryCount = MutableStateFlow(0L)
        val recentBlocked = MutableStateFlow<List<String>>(emptyList())

        fun start(context: Context) {
            // 记下"用户想要拦截"，被系统杀掉之后可以自动拉回来
            runCatching { Prefs(context).vpnDesired = true }
            val intent = Intent(context, AdBlockVpnService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            runCatching { Prefs(context).vpnDesired = false }
            val intent = Intent(context, AdBlockVpnService::class.java).setAction(ACTION_STOP)
            runCatching { ContextCompat.startForegroundService(context, intent) }
            // 服务还没起来/已被系统回收时，上面的 stop intent 可能没人处理，这里兜一层
            running.value = false
        }

        fun hasNotificationPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }
}
