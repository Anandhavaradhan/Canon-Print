package app.easyprint.core

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Tiny SNMP v1 client (GetRequest / GetNextRequest), enough to:
 *  - find Canon printers the way Canon's own software does (private OIDs, community "canon_admin"),
 *  - read paper/ink status from the standard Printer and Host Resources MIBs (community "public").
 */
object Snmp {
    const val PORT = 161

    sealed class Value {
        data class Int32(val v: Long) : Value()
        data class Bytes(val v: ByteArray) : Value() {
            fun text() = String(v, Charsets.UTF_8).trimEnd('\u0000').trim()
            override fun equals(other: Any?) = other is Bytes && v.contentEquals(other.v)
            override fun hashCode() = v.contentHashCode()
        }
        data class Oid(val v: String) : Value()
        data object Null : Value()
        data class Other(val tag: Int) : Value()
    }

    data class Response(val requestId: Int, val error: Int, val bindings: List<Pair<String, Value>>) {
        fun get(oid: String) = bindings.firstOrNull { it.first == oid.trimStart('.') }?.second
    }

    // ---------------------------------------------------------------- BER encoding
    private fun len(out: ByteArrayOutputStream, n: Int) {
        when {
            n < 0x80 -> out.write(n)
            n <= 0xff -> { out.write(0x81); out.write(n) }
            else -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xff) }
        }
    }

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(tag); len(o, content.size); o.write(content)
        return o.toByteArray()
    }

    private fun intBytes(v: Int): ByteArray {
        var x = v
        val b = ArrayList<Byte>()
        do { b.add(0, (x and 0xff).toByte()); x = x shr 8 } while (x != 0 && x != -1)
        if (v >= 0 && (b[0].toInt() and 0x80) != 0) b.add(0, 0)
        return b.toByteArray()
    }

    fun encodeOid(dots: String): ByteArray {
        val parts = dots.trim('.').split('.').map { it.toLong() }
        val o = ByteArrayOutputStream()
        o.write((parts[0] * 40 + parts[1]).toInt())
        for (p in parts.drop(2)) {
            val tmp = ArrayList<Int>()
            var x = p
            tmp.add((x and 0x7f).toInt()); x = x shr 7
            while (x > 0) { tmp.add(((x and 0x7f) or 0x80).toInt()); x = x shr 7 }
            tmp.reversed().forEach { o.write(it) }
        }
        return o.toByteArray()
    }

    /** pdu: 0xA0 GetRequest, 0xA1 GetNextRequest */
    fun request(community: String, requestId: Int, oids: List<String>, pdu: Int = 0xA0): ByteArray {
        val vbs = ByteArrayOutputStream()
        for (oid in oids) vbs.write(tlv(0x30, tlv(0x06, encodeOid(oid)) + byteArrayOf(0x05, 0x00)))
        val pduBody = tlv(0x02, intBytes(requestId)) + tlv(0x02, byteArrayOf(0)) + tlv(0x02, byteArrayOf(0)) + tlv(0x30, vbs.toByteArray())
        val msg = tlv(0x02, byteArrayOf(0)) + tlv(0x04, community.toByteArray()) + tlv(pdu, pduBody)
        return tlv(0x30, msg)
    }

    // ---------------------------------------------------------------- BER decoding
    private class Reader(val b: ByteArray, var p: Int = 0, val end: Int = b.size) {
        fun tag() = b[p++].toInt() and 0xff
        fun length(): Int {
            val first = b[p++].toInt() and 0xff
            if (first < 0x80) return first
            var n = 0
            repeat(first and 0x7f) { n = (n shl 8) or (b[p++].toInt() and 0xff) }
            return n
        }
        fun bytes(n: Int) = b.copyOfRange(p, p + n).also { p += n }
    }

    private fun decodeOid(c: ByteArray): String {
        if (c.isEmpty()) return ""
        val parts = mutableListOf((c[0].toInt() and 0xff) / 40L, (c[0].toInt() and 0xff) % 40L)
        var v = 0L
        for (i in 1 until c.size) {
            val x = c[i].toInt() and 0xff
            v = (v shl 7) or (x and 0x7f).toLong()
            if (x and 0x80 == 0) { parts.add(v); v = 0 }
        }
        return parts.joinToString(".")
    }

    private fun decodeInt(c: ByteArray): Long {
        var v = if (c.isNotEmpty() && c[0] < 0) -1L else 0L
        for (x in c) v = (v shl 8) or (x.toLong() and 0xff)
        return v
    }

    fun parse(data: ByteArray, length: Int = data.size): Response? = runCatching {
        val r = Reader(data, 0, length)
        if (r.tag() != 0x30) return null
        r.length()
        r.tag(); r.bytes(r.length()) // version
        r.tag(); r.bytes(r.length()) // community
        if (r.tag() != 0xA2) return null // GetResponse
        r.length()
        r.tag(); val reqId = decodeInt(r.bytes(r.length())).toInt()
        r.tag(); val err = decodeInt(r.bytes(r.length())).toInt()
        r.tag(); r.bytes(r.length()) // error index
        r.tag(); val vblLen = r.length()
        val vblEnd = r.p + vblLen
        val out = mutableListOf<Pair<String, Value>>()
        while (r.p < vblEnd) {
            r.tag(); r.length()
            r.tag(); val oid = decodeOid(r.bytes(r.length()))
            val t = r.tag(); val c = r.bytes(r.length())
            val v = when (t) {
                0x02, 0x41, 0x42, 0x43 -> Value.Int32(decodeInt(c))
                0x04 -> Value.Bytes(c)
                0x06 -> Value.Oid(decodeOid(c))
                0x05 -> Value.Null
                else -> Value.Other(t)
            }
            out += oid to v
        }
        Response(reqId, err, out)
    }.getOrNull()

    // ---------------------------------------------------------------- transport
    fun get(host: String, community: String, oids: List<String>, timeoutMs: Int = 2000, port: Int = PORT, retries: Int = 1): Response? =
        exchange(host, request(community, (System.nanoTime() and 0x7fffffff).toInt(), oids), timeoutMs, port, retries)

    fun getNext(host: String, community: String, oids: List<String>, timeoutMs: Int = 2000, port: Int = PORT): Response? =
        exchange(host, request(community, (System.nanoTime() and 0x7fffffff).toInt(), oids, 0xA1), timeoutMs, port, 1)

    private fun exchange(host: String, packet: ByteArray, timeoutMs: Int, port: Int, retries: Int): Response? {
        DatagramSocket().use { s ->
            s.soTimeout = timeoutMs
            val addr = InetAddress.getByName(host)
            repeat(retries + 1) {
                s.send(DatagramPacket(packet, packet.size, addr, port))
                val buf = ByteArray(4096)
                val dp = DatagramPacket(buf, buf.size)
                try {
                    s.receive(dp)
                    return parse(buf, dp.length)
                } catch (_: SocketTimeoutException) {
                }
            }
        }
        return null
    }

    /** Walk a table column (e.g. supplies), at most [max] rows. */
    fun walk(host: String, community: String, base: String, max: Int = 16, timeoutMs: Int = 1500, port: Int = PORT): List<Pair<String, Value>> {
        val out = mutableListOf<Pair<String, Value>>()
        var cur = base.trim('.')
        repeat(max) {
            val r = getNext(host, community, listOf(cur), timeoutMs, port) ?: return out
            val (oid, v) = r.bindings.firstOrNull() ?: return out
            if (!oid.startsWith(base.trim('.') + ".") || r.error != 0) return out
            out += oid to v
            cur = oid
        }
        return out
    }
}
