package com.botvi.lords

/** PacketDump.kt - hex + ASCII dump of a game payload (Kotlin port of ShowPacketInfo's content print). */
object PacketDump {
    private const val PER_LINE = 20

    fun hex(p: ByteArray, max: Int = 256): String {
        val n = minOf(p.size, max)
        val sb = StringBuilder()
        var i = 0
        while (i < n) {
            val end = minOf(i + PER_LINE, n)
            val hexPart = StringBuilder()
            val asc = StringBuilder()
            for (j in i until end) {
                val v = p[j].toInt() and 0xFF
                hexPart.append(String.format("%02X ", v))
                asc.append(if (v in 32..126) v.toChar() else '.')
            }
            sb.append(String.format("%04X  ", i))
              .append(hexPart.toString().padEnd(PER_LINE * 3))
              .append(" ").append(asc).append('\n')
            i = end
        }
        if (p.size > n) sb.append("... (+${p.size - n} bytes)\n")
        return sb.toString()
    }
}
