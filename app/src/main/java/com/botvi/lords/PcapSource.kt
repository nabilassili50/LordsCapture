package com.botvi.lords

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes an HTTP "Transfer-Encoding: chunked" body. */
class ChunkedInputStream(private val ins: InputStream) : InputStream() {
    private var remaining = 0
    private var eof = false

    private fun readLine(): String {
        val sb = StringBuilder()
        while (true) {
            val c = ins.read()
            if (c < 0) throw EOFException()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (eof) return -1
        while (remaining == 0) {
            val line = readLine().substringBefore(';').trim()
            if (line.isEmpty()) continue
            remaining = line.toInt(16)
            if (remaining == 0) { eof = true; return -1 }
        }
        val n = ins.read(b, off, minOf(len, remaining))
        if (n < 0) { eof = true; return -1 }
        remaining -= n
        return n
    }
}

class PcapConnection(val socket: Socket, val stream: InputStream) : Closeable {
    override fun close() {
        try { socket.close() } catch (_: Exception) {}
    }
}

/** Connects to the PCAPdroid "HTTP Server" dump mode and returns the live PCAP byte stream.
 *  PCAPdroid answers "GET /" with a 302 redirect to the real pcap path, so redirects are followed. */
object PcapSource {
    private const val MAX_REDIRECTS = 5

    /** Turns a Location header (absolute URL or relative path) into a request path. */
    private fun toPath(location: String): String {
        val loc = location.trim()
        if (loc.startsWith("http://", true) || loc.startsWith("https://", true)) {
            val rest = loc.substringAfter("://")
            val i = rest.indexOf('/')
            return if (i < 0) "/" else rest.substring(i)
        }
        return if (loc.startsWith("/")) loc else "/$loc"
    }

    fun open(host: String, port: Int): PcapConnection {
        var path = "/"
        var redirects = 0
        while (true) {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(host, port), 5000)
                s.tcpNoDelay = true
                val out = s.getOutputStream()
                out.write("GET $path HTTP/1.1\r\nHost: $host:$port\r\nAccept: */*\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()

                val ins = BufferedInputStream(s.getInputStream(), 65536)
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) {
                    val c = ins.read()
                    if (c < 0) throw EOFException("no HTTP response")
                    head.append(c.toChar())
                    if (head.length > 16384) throw IOException("bad HTTP header")
                }
                val text = head.toString()
                val lower = text.lowercase()
                val statusLine = text.lineSequence().first()
                val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0

                if (code in listOf(301, 302, 303, 307, 308)) {
                    val loc = text.lineSequence()
                        .firstOrNull { it.startsWith("location:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim()
                    s.close()
                    if (loc.isNullOrEmpty() || ++redirects > MAX_REDIRECTS)
                        throw IOException("HTTP: $statusLine (redirect)")
                    path = toPath(loc)
                    continue
                }
                if (!lower.startsWith("http/1.") || code != 200) {
                    s.close()
                    throw IOException("HTTP: $statusLine")
                }
                val stream: InputStream =
                    if (lower.contains("transfer-encoding: chunked")) BufferedInputStream(ChunkedInputStream(ins), 65536)
                    else ins
                return PcapConnection(s, stream)
            } catch (e: Exception) {
                try { s.close() } catch (_: Exception) {}
                throw e
            }
        }
    }
}

/** Reads a classic PCAP stream and hands every IPv4 packet (without link header) to [onIp]. */
class PcapStreamReader(
    private val ins: InputStream,
    private val onIp: (buf: ByteArray, off: Int, len: Int) -> Unit
) {
    private fun readFully(b: ByteArray, n: Int = b.size) {
        var o = 0
        while (o < n) {
            val r = ins.read(b, o, n - o)
            if (r < 0) throw EOFException()
            o += r
        }
    }

    fun pump(isRunning: () -> Boolean) {
        val gh = ByteArray(24)
        readFully(gh)
        // pcapng (PCAPdroid option "pcapng format"): starts with the section header block 0A 0D 0D 0A
        if (gh[0] == 0x0A.toByte() && gh[1] == 0x0D.toByte() && gh[2] == 0x0D.toByte() && gh[3] == 0x0A.toByte()) {
            pumpNg(gh, isRunning)
            return
        }
        val magic = ByteBuffer.wrap(gh).order(ByteOrder.LITTLE_ENDIAN).getInt(0)
        val order = when (magic) {
            MAGIC_LE, MAGIC_LE_NS -> ByteOrder.LITTLE_ENDIAN
            MAGIC_BE, MAGIC_BE_NS -> ByteOrder.BIG_ENDIAN
            else -> throw IOException(describeBadStart(gh))
        }
        val link = ByteBuffer.wrap(gh).order(order).getInt(20)
        val skip = when (link) {
            101, 228, 12 -> 0   // raw IP
            1 -> 14             // Ethernet
            113 -> 16           // Linux cooked
            else -> throw IOException("unsupported link type $link")
        }

        val rh = ByteArray(16)
        var buf = ByteArray(65536 + 64)
        while (isRunning()) {
            readFully(rh)
            val incl = ByteBuffer.wrap(rh).order(order).getInt(8)
            if (incl < 0 || incl > 262144) throw IOException("bad pcap record size $incl")
            if (buf.size < incl) buf = ByteArray(incl)
            readFully(buf, incl)
            if (incl <= skip) continue
            if (link == 1) {
                val et = ((buf[12].toInt() and 0xFF) shl 8) or (buf[13].toInt() and 0xFF)
                if (et != 0x0800) continue
            }
            onIp(buf, skip, incl - skip)
        }
    }

    /** explains what the port answered with instead of a pcap stream (a web page = another server owns the port) */
    private fun describeBadStart(gh: ByteArray): String {
        val ascii = String(CharArray(8) { i -> val c = gh[i].toInt() and 0xFF; if (c in 32..126) c.toChar() else '.' })
        val hex = (0 until 4).joinToString(" ") { String.format("%02X", gh[it].toInt() and 0xFF) }
        val web = ascii.startsWith("<") || ascii.startsWith("HTTP") || ascii.startsWith("{") || ascii.startsWith("[")
        return if (web) "المنفذ يرد بصفحة ويب ($ascii) وليس PCAPdroid — خادم آخر (مثل KSWEB) يستعمل نفس المنفذ؛ غيّر منفذ PCAPdroid أو أوقف الخادم"
        else "ليس تدفق pcap كلاسيكي (يبدأ بـ $hex '$ascii')"
    }

    private fun deliver(buf: ByteArray, off: Int, cap: Int, link: Int) {
        val skip = when (link) {
            101, 228, 12 -> 0
            1 -> 14
            113 -> 16
            else -> return
        }
        if (cap <= skip) return
        if (link == 1) {
            val et = ((buf[off + 12].toInt() and 0xFF) shl 8) or (buf[off + 13].toInt() and 0xFF)
            if (et != 0x0800) return
        }
        onIp(buf, off + skip, cap - skip)
    }

    /** pcapng stream: section header, interface descriptions (link type) and enhanced/simple packet blocks */
    private fun pumpNg(gh: ByteArray, isRunning: () -> Boolean) {
        val bom = ByteBuffer.wrap(gh).order(ByteOrder.LITTLE_ENDIAN).getInt(8)
        val order = if (bom == 0x1A2B3C4D) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val shbLen = ByteBuffer.wrap(gh).order(order).getInt(4)
        if (shbLen < 28 || shbLen > 65536) throw IOException("bad pcapng header")
        if (shbLen > 24) readFully(ByteArray(shbLen - 24))     // rest of the section header block
        val links = ArrayList<Int>()
        val head = ByteArray(8)
        var buf = ByteArray(65536 + 64)
        while (isRunning()) {
            readFully(head)
            val hb = ByteBuffer.wrap(head).order(order)
            val type = hb.getInt(0)
            val total = hb.getInt(4)
            if (total < 12 || total > 4_000_000) throw IOException("bad pcapng block size $total")
            val bodyLen = total - 8
            if (buf.size < bodyLen) buf = ByteArray(bodyLen)
            readFully(buf, bodyLen)
            val bb = ByteBuffer.wrap(buf).order(order)
            when (type) {
                0x0A0D0D0A -> links.clear()                                   // a new section starts: interfaces restart
                0x00000001 -> if (bodyLen >= 2) links.add(bb.getShort(0).toInt() and 0xFFFF)
                0x00000006 -> if (bodyLen >= 20) {                            // enhanced packet block
                    val ifId = bb.getInt(0)
                    val cap = bb.getInt(12)
                    if (cap >= 0 && 20 + cap <= bodyLen) deliver(buf, 20, cap, links.getOrElse(ifId) { 1 })
                }
                0x00000003 -> if (bodyLen >= 8) {                             // simple packet block
                    val cap = minOf(bb.getInt(0), bodyLen - 8)
                    if (cap > 0) deliver(buf, 4, cap, links.getOrElse(0) { 1 })
                }
                else -> {}
            }
        }
    }

    companion object {
        private val MAGIC_LE = 0xa1b2c3d4L.toInt()
        private val MAGIC_LE_NS = 0xa1b23c4dL.toInt()
        private val MAGIC_BE = 0xd4c3b2a1L.toInt()
        private val MAGIC_BE_NS = 0x4d3cb2a1L.toInt()
    }
}