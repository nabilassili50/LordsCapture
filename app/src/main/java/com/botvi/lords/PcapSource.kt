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

/** Connects to the PCAPdroid "HTTP Server" dump mode and returns the live PCAP byte stream. */
object PcapSource {
    fun open(host: String, port: Int): PcapConnection {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), 5000)
        s.tcpNoDelay = true
        val out = s.getOutputStream()
        out.write("GET / HTTP/1.1\r\nHost: $host:$port\r\nAccept: */*\r\nConnection: close\r\n\r\n".toByteArray())
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
        if (!lower.startsWith("http/1.") || !statusLine.contains(" 200")) {
            s.close()
            throw IOException("HTTP: $statusLine")
        }
        val stream: InputStream =
            if (lower.contains("transfer-encoding: chunked")) BufferedInputStream(ChunkedInputStream(ins), 65536)
            else ins
        return PcapConnection(s, stream)
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
        val magic = ByteBuffer.wrap(gh).order(ByteOrder.LITTLE_ENDIAN).getInt(0)
        val order = when (magic) {
            MAGIC_LE, MAGIC_LE_NS -> ByteOrder.LITTLE_ENDIAN
            MAGIC_BE, MAGIC_BE_NS -> ByteOrder.BIG_ENDIAN
            else -> throw IOException("not a classic pcap stream")
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

    companion object {
        private val MAGIC_LE = 0xa1b2c3d4L.toInt()
        private val MAGIC_LE_NS = 0xa1b23c4dL.toInt()
        private val MAGIC_BE = 0xd4c3b2a1L.toInt()
        private val MAGIC_BE_NS = 0x4d3cb2a1L.toInt()
    }
}
