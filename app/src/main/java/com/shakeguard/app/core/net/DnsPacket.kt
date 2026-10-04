package com.shakeguard.app.core.net

/**
 * 最小可用的 DNS / IPv4 / UDP 报文处理。
 *
 * 只做三件事：
 *  1) 从一个 UDP 报文里解析出被查询的域名和类型；
 *  2) 为被拦截的域名伪造一个 DNS 应答（A 记录返回 0.0.0.0，等于把这个域名掐死）；
 *  3) 把 DNS 应答内容重新包装成「源/目的互换」的 IPv4+UDP 报文，写回 TUN。
 *
 * 校验和（IP 头 + UDP 伪首部）都按标准算法算，写错了会导致手机断网，这里不能省。
 */
object DnsPacket {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28

    data class Question(val domain: String, val qType: Int)

    fun ipHeaderLength(packet: ByteArray): Int = (packet[0].toInt() and 0x0F) * 4

    fun isIpv4Udp(packet: ByteArray, length: Int): Boolean {
        if (length < 28) return false
        if ((packet[0].toInt() and 0xF0) != 0x40) return false
        val ihl = ipHeaderLength(packet)
        if (ihl < 20 || length < ihl + 8) return false
        return (packet[9].toInt() and 0xFF) == 17
    }

    fun destinationPort(packet: ByteArray): Int {
        val ihl = ipHeaderLength(packet)
        return ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
    }

    /** 解析 DNS 查询段：域名 + 查询类型 */
    fun parseQuestion(packet: ByteArray, length: Int): Question? {
        val dns = ipHeaderLength(packet) + 8
        if (length < dns + 12) return null
        val qdCount = ((packet[dns + 4].toInt() and 0xFF) shl 8) or (packet[dns + 5].toInt() and 0xFF)
        if (qdCount < 1) return null

        var p = dns + 12
        var guard = 0
        val sb = StringBuilder()
        while (p < length && guard++ < 128) {
            val len = packet[p].toInt() and 0xFF
            if (len == 0) {
                p++
                break
            }
            if (len and 0xC0 == 0xC0) {
                p += 2
                break
            }
            if (p + 1 + len > length) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(packet, p + 1, len, Charsets.US_ASCII))
            p += 1 + len
        }
        if (sb.isEmpty()) return null
        if (p + 4 > length) return null
        val qType = ((packet[p].toInt() and 0xFF) shl 8) or (packet[p + 1].toInt() and 0xFF)
        return Question(sb.toString().lowercase(), qType)
    }

    /** 被拦截域名的应答内容：A 查询返回 0.0.0.0，其它类型返回空应答（客户端会退回用 A，同样被拦） */
    fun buildBlockedPayload(packet: ByteArray, length: Int, question: Question): ByteArray? {
        val dns = ipHeaderLength(packet) + 8
        if (length < dns + 16) return null

        var p = dns + 12
        var guard = 0
        while (p < length && guard++ < 128) {
            val len = packet[p].toInt() and 0xFF
            if (len == 0) {
                p++
                break
            }
            if (len and 0xC0 == 0xC0) {
                p += 2
                break
            }
            p += 1 + len
        }
        if (p + 4 > length) return null
        val questionLen = (p + 4) - (dns + 12)
        if (questionLen <= 0) return null

        val answer: ByteArray = if (question.qType == TYPE_A) {
            byteArrayOf(
                0xC0.toByte(), 0x0C,               // 指向问题段的域名
                0x00, TYPE_A.toByte(),             // TYPE A
                0x00, 0x01,                        // CLASS IN
                0x00, 0x00, 0x00, 0x3C,            // TTL 60
                0x00, 0x04,                        // RDLENGTH
                0x00, 0x00, 0x00, 0x00             // 0.0.0.0
            )
        } else {
            ByteArray(0)
        }

        val payload = ByteArray(12 + questionLen + answer.size)
        System.arraycopy(packet, dns, payload, 0, 12)
        payload[2] = ((payload[2].toInt() and 0x01) or 0x80).toByte()   // QR=1，保留 RD
        payload[3] = 0x80.toByte()                                       // RA=1，RCODE=0
        payload[6] = 0
        payload[7] = if (answer.isEmpty()) 0 else 1
        payload[8] = 0
        payload[9] = 0
        payload[10] = 0
        payload[11] = 0
        System.arraycopy(packet, dns + 12, payload, 12, questionLen)
        if (answer.isNotEmpty()) System.arraycopy(answer, 0, payload, 12 + questionLen, answer.size)
        return payload
    }

    /** 上游查询失败时的 SERVFAIL 应答 */
    fun buildFailurePayload(packet: ByteArray, length: Int): ByteArray? {
        val dns = ipHeaderLength(packet) + 8
        if (length < dns + 12) return null
        val payload = ByteArray(12)
        System.arraycopy(packet, dns, payload, 0, 12)
        payload[2] = ((payload[2].toInt() and 0x01) or 0x80).toByte()
        payload[3] = (0x80 or 0x02).toByte()
        for (i in 6..11) payload[i] = 0
        return payload
    }

    /** 把应答内容包装成回给客户端的 IPv4+UDP 报文（源/目的互换，重算长度与校验和） */
    fun buildUdpReply(query: ByteArray, queryLen: Int, payload: ByteArray, payloadLen: Int): ByteArray? {
        if (queryLen < 28) return null
        val ihl = ipHeaderLength(query)
        if (ihl < 20 || queryLen < ihl + 8) return null
        val total = ihl + 8 + payloadLen
        val out = ByteArray(total)

        System.arraycopy(query, 0, out, 0, ihl)
        System.arraycopy(query, 16, out, 12, 4)     // 原目的地址 -> 新源地址
        System.arraycopy(query, 12, out, 16, 4)     // 原源地址   -> 新目的地址
        out[8] = 64                                  // TTL
        out[2] = ((total ushr 8) and 0xFF).toByte()
        out[3] = (total and 0xFF).toByte()
        out[10] = 0
        out[11] = 0
        val ipSum = onesComplement(out, 0, ihl)
        out[10] = ((ipSum ushr 8) and 0xFF).toByte()
        out[11] = (ipSum and 0xFF).toByte()

        val u = ihl
        System.arraycopy(query, u + 2, out, u, 2)   // 原目的端口(53) -> 新源端口
        System.arraycopy(query, u, out, u + 2, 2)   // 原源端口      -> 新目的端口
        val udpLen = 8 + payloadLen
        out[u + 4] = ((udpLen ushr 8) and 0xFF).toByte()
        out[u + 5] = (udpLen and 0xFF).toByte()
        out[u + 6] = 0
        out[u + 7] = 0
        System.arraycopy(payload, 0, out, u + 8, payloadLen)

        var sum = 0
        sum += word(out[12], out[13])
        sum += word(out[14], out[15])
        sum += word(out[16], out[17])
        sum += word(out[18], out[19])
        sum += 17
        sum += udpLen
        sum += wordSum(out, u, udpLen)
        while ((sum ushr 16) != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        var udpSum = sum.inv() and 0xFFFF
        if (udpSum == 0) udpSum = 0xFFFF
        out[u + 6] = ((udpSum ushr 8) and 0xFF).toByte()
        out[u + 7] = (udpSum and 0xFF).toByte()
        return out
    }

    private fun word(hi: Byte, lo: Byte): Int = ((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)

    private fun wordSum(data: ByteArray, offset: Int, len: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + len
        while (i + 1 < end) {
            sum += word(data[i], data[i + 1])
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        return sum
    }

    private fun onesComplement(data: ByteArray, offset: Int, len: Int): Int {
        var sum = wordSum(data, offset, len)
        while ((sum ushr 16) != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }
}
