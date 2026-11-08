package com.botvi.lords

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * LordsParser_v2.kt - Kotlin port of the active parsing in ParsePackets.cpp
 * (hunting gifts + alliance gift list + alliance might list), GUID codec and stream framing.
 * Integers are little-endian.
 */

object GuidCodec {
    const val MAX_X = 512
    const val MAX_Y = 1024

    fun decode(guid: Int): Pair<Int, Int>? {
        val b1 = (guid ushr 8) and 0xFF
        val b2 = (guid ushr 16) and 0xFF
        val b3 = (guid ushr 24) and 0xFF
        val y = (b3 ushr 4) or (b1 and 0xF0) or ((b2 and 0x0F) shl 8)
        val x = (y and 1) + 2 * ((b3 and 0x0F) or ((b1 and 0x0F) shl 4))
        if (x > MAX_X || y > MAX_Y) return null
        return Pair(x, y)
    }

    fun encode(x: Int, y: Int): Int {
        val xh = x shr 1
        val b3 = ((y and 0xF) shl 4) or (xh and 0xF)
        val b1 = (((y shr 4) and 0xF) shl 4) or ((xh shr 4) and 0xF)
        val b2 = (y shr 8) and 0xF
        return (b3 shl 24) or (b2 shl 16) or (b1 shl 8)
    }
}

sealed interface ParsedPacket

data class GiftReceived(val name: String, val monsterType: Int) : ParsedPacket

data class GiftEntry(
    val sortIndex: Int,
    val giftType: Int,
    val giftGroup: Int,
    val count: Int,
    val quality: Int,
    val monsterType: Int,
    val secondsAgo: Long,
    val name: String
) : ParsedPacket

data class MightEntry(
    val name: String,
    val rank: Int,
    val might: Long,
    val kills: Long,
    val lastSeen: Long
) : ParsedPacket

/**
 * Game stream = [u16 size (includes these 2 bytes)] [payload] ...
 * feed() raw TCP bytes; onFrame gets each payload without the size prefix.
 * A bad size triggers onDesync and drops the buffer.
 */
class StreamFramer(
    private val maxFrame: Int = DEFAULT_MAX,
    private val onDesync: () -> Unit = {},
    private val onFrame: (ByteArray) -> Unit
) {
    private var buf = ByteArray(8192)
    private var len = 0

    fun feed(data: ByteArray, n: Int = data.size) {
        if (len + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + n))
        System.arraycopy(data, 0, buf, len, n)
        len += n

        var pos = 0
        while (len - pos >= 2) {
            val size = (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8)
            if (size < 2 || size > maxFrame) {
                len = 0
                onDesync()
                return
            }
            if (len - pos < size) break
            onFrame(buf.copyOfRange(pos + 2, pos + size))
            pos += size
        }
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, len - pos)
            len -= pos
        }
    }

    fun reset() { len = 0 }

    companion object {
        const val DEFAULT_MAX = 0x3FFF

        /** True when [d] is made of whole game packets that end exactly at the end of [d]. */
        fun looksAligned(d: ByteArray, maxFrame: Int = DEFAULT_MAX): Boolean {
            if (d.size < 2) return false
            var pos = 0
            while (pos < d.size) {
                if (d.size - pos < 2) return false
                val size = (d[pos].toInt() and 0xFF) or ((d[pos + 1].toInt() and 0xFF) shl 8)
                if (size < 2 || size > maxFrame) return false
                pos += size
            }
            return pos == d.size
        }
    }
}

object PacketParser {
    private const val GIFT_LIST_HEADER = 13
    private const val GIFT_ENTRY_SIZE = 33
    private const val MIGHT_HEADER = 5
    private const val MIGHT_ENTRY_SIZE = 48

    fun parse(p: ByteArray, nowSec: Long = System.currentTimeMillis() / 1000): List<ParsedPacket> {
        if (p.size < 3) return emptyList()
        val op0 = p[0].toInt() and 0xFF
        val op1 = p[1].toInt() and 0xFF
        val op2 = p[2].toInt() and 0xFF
        val bb = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<ParsedPacket>()

        if (op0 == 0x2B && op1 == 0x0B && op2 == 0x12 && p.size >= 20) {
            val name = cstr(p, 7, 12)
            val monster = bb.getShort(5).toInt() and 0xFFFF
            if (name.isNotEmpty()) out.add(GiftReceived(name, monster))
        }

        if (op0 == 0x37 && op1 == 0x0B && op2 == 0x00 && p.size >= GIFT_LIST_HEADER) {
            val count = p[12].toInt() and 0xFF
            for (i in 0 until count) {
                val o = GIFT_LIST_HEADER + i * GIFT_ENTRY_SIZE
                if (o + GIFT_ENTRY_SIZE > p.size) break
                val time0 = bb.getInt(o + 5).toLong() and 0xFFFFFFFFL
                out.add(
                    GiftEntry(
                        sortIndex = bb.getInt(o),
                        monsterType = bb.getShort(o + 13).toInt() and 0xFFFF,
                        giftType = p[o + 15].toInt() and 0xFF,
                        giftGroup = p[o + 16].toInt() and 0xFF,
                        count = p[o + 17].toInt() and 0xFF,
                        quality = p[o + 19].toInt() and 0xFF,
                        secondsAgo = nowSec - time0,
                        name = cstr(p, o + 20, 12)
                    )
                )
            }
        }

        if (op0 == 0x06 && op1 == 0x0B && op2 == 0x00 && p.size >= MIGHT_HEADER) {
            val count = p[4].toInt() and 0xFF
            for (i in 0 until count) {
                val o = MIGHT_HEADER + i * MIGHT_ENTRY_SIZE
                if (o + MIGHT_ENTRY_SIZE > p.size) break
                out.add(
                    MightEntry(
                        name = cstr(p, o + 10, 12),
                        rank = p[o + 23].toInt() and 0xFF,
                        might = bb.getLong(o + 24),
                        kills = bb.getLong(o + 32),
                        lastSeen = bb.getLong(o + 40)
                    )
                )
            }
        }
        return out
    }

    private fun cstr(p: ByteArray, off: Int, maxLen: Int): String {
        var n = 0
        while (n < maxLen && off + n < p.size && p[off + n].toInt() != 0) n++
        return String(p, off, n, Charsets.UTF_8)
    }
}
