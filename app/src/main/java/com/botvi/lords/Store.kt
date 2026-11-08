package com.botvi.lords

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Store {
    data class Stamped<T>(val at: Long, val v: T)

    private val gifts = LinkedHashMap<Int, Stamped<GiftEntry>>()
    private val might = LinkedHashMap<String, Stamped<MightEntry>>()
    private val received = ArrayList<Stamped<GiftReceived>>()
    private val logs = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile var status: String = "متوقف"
    @Volatile var frames: Long = 0

    @Synchronized
    fun add(p: ParsedPacket) {
        val now = System.currentTimeMillis() / 1000
        when (p) {
            is GiftEntry -> gifts.put(p.sortIndex, Stamped(now, p))
            is MightEntry -> might.put(p.name, Stamped(now, p))
            is GiftReceived -> received.add(Stamped(now, p))
        }
    }

    @Synchronized
    fun log(s: String) {
        logs.addLast(fmt.format(Date()) + "  " + s)
        while (logs.size > 300) logs.removeFirst()
    }

    @Synchronized
    fun tail(n: Int): String = logs.toList().takeLast(n).joinToString("\n")

    @Synchronized
    fun clear() {
        gifts.clear(); might.clear(); received.clear(); frames = 0
    }

    @Synchronized
    fun summary(): String =
        "حزم اللعبة المفكوكة: $frames\n" +
            "قائمة هدايا التحالف: ${gifts.size}\n" +
            "قائمة القوة: ${might.size}\n" +
            "هدايا صيد مستلمة: ${received.size}"

    private fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    @Synchronized
    fun csv(): String {
        val sb = StringBuilder("\uFEFF")
        sb.append("type,captured_at,name,rank,might,kills,last_seen,gift_type,gift_group,count,quality,monster_type,seconds_ago,sort_index\n")
        for (s in might.values) {
            val m = s.v
            sb.append("might,${s.at},${q(m.name)},${m.rank},${m.might},${m.kills},${m.lastSeen},,,,,,,\n")
        }
        for (s in gifts.values) {
            val g = s.v
            sb.append("gift,${s.at},${q(g.name)},,,,,${g.giftType},${g.giftGroup},${g.count},${g.quality},${g.monsterType},${g.secondsAgo},${g.sortIndex}\n")
        }
        for (s in received) {
            val r = s.v
            sb.append("hunt_gift,${s.at},${q(r.name)},,,,,,,,,${r.monsterType},,\n")
        }
        return sb.toString()
    }
}
