package com.botvi.lords

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Store {
    data class Stamped<T>(val at: Long, val v: T)

    private val gifts = LinkedHashMap<Int, Stamped<GiftEntry>>()
    private val might = LinkedHashMap<String, Stamped<MightEntry>>()
    private val received = ArrayList<Stamped<GiftReceived>>()
    private val mapPlayers = LinkedHashMap<Int, Stamped<MapPlayer>>()   // castles seen on the map, by x/y
    private val popups = LinkedHashMap<Int, Stamped<CastlePopup>>()     // castle tap popups, by x/y
    private val logs = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile var status: String = "متوقف"
    @Volatile var frames: Long = 0
    @Volatile var dumpOn: Boolean = false
    private val dumps = ArrayDeque<String>()

    @Synchronized
    fun addDump(payload: ByteArray, parsed: Int) {
        dumps.addLast("[" + fmt.format(Date()) + "] len=" + payload.size + " parsed=" + parsed + "\n" + PacketDump.hex(payload))
        while (dumps.size > 200) dumps.removeFirst()
    }

    @Synchronized
    fun dumpText(): String = "== إحصاء الحزم ==\n" + opsReport(60) + "\n== التفريغ ==\n" + dumps.joinToString("\n")

    // ---- might history, opcode stats, persistence ----
    data class MightPoint(val at: Long, val might: Long, val kills: Long)
    class OpStat { var count = 0L; var bytes = 0L; var parsed = 0L }

    private val mightHist = HashMap<String, ArrayList<MightPoint>>()
    private val ops = HashMap<String, OpStat>()
    private var dir: File? = null
    private var lastSave = 0L

    @Synchronized
    fun init(ctx: android.content.Context) {
        if (dir != null) return
        dir = ctx.filesDir
        loadLocked()
    }

    private fun recordMight(m: MightEntry, now: Long) {
        val list = mightHist.getOrPut(m.name) { ArrayList() }
        val last = list.lastOrNull()
        if (last == null || last.might != m.might || last.kills != m.kills) {
            list.add(MightPoint(now, m.might, m.kills))
            if (list.size > 200) list.removeAt(0)
        }
    }

    /** Count every game frame by its first 3 bytes; parsed = frames that produced at least one item. */
    @Synchronized
    fun recordOp(payload: ByteArray, parsed: Int) {
        val n = minOf(payload.size, 3)
        if (n == 0) return
        val key = (0 until n).joinToString(" ") { String.format("%02X", payload[it].toInt() and 0xFF) }
        val st = ops.getOrPut(key) { OpStat() }
        st.count++
        st.bytes += payload.size
        if (parsed > 0) st.parsed++
    }

    @Synchronized
    fun opsReport(limit: Int): String {
        val sb = StringBuilder("opcode    count  avgBytes  parsed\n")
        for ((k, v) in ops.entries.sortedByDescending { it.value.count }.take(limit)) {
            sb.append(k.padEnd(9)).append(' ')
                .append(v.count.toString().padStart(6)).append(' ')
                .append((v.bytes / maxOf(v.count, 1L)).toString().padStart(8)).append(' ')
                .append(v.parsed.toString().padStart(7)).append(if (v.parsed == 0L) "  <- غير مفكوك" else "").append('\n')
        }
        return sb.toString()
    }

    private fun opsBrief(): String {
        val un = ops.entries.filter { it.value.parsed == 0L && it.value.count >= 3 }
            .sortedByDescending { it.value.bytes }.take(5)
        if (un.isEmpty()) return ""
        return "\nحزم غير مفكوكة (الأكثر): " + un.joinToString(" | ") {
            "${it.key} ×${it.value.count} (~${it.value.bytes / it.value.count}B)"
        }
    }

    @Synchronized
    fun historyCsv(): String {
        val sb = StringBuilder("\uFEFF")
        sb.append("name,captured_at,might,kills,delta_might,delta_kills\n")
        for ((name, list) in mightHist) {
            var prev: MightPoint? = null
            for (pt in list) {
                val dm = prev?.let { pt.might - it.might }?.toString() ?: ""
                val dk = prev?.let { pt.kills - it.kills }?.toString() ?: ""
                sb.append("${q(name)},${pt.at},${pt.might},${pt.kills},$dm,$dk\n")
                prev = pt
            }
        }
        return sb.toString()
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    private fun unesc(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                i++
                when (s[i]) {
                    't' -> sb.append('\t')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    else -> sb.append(s[i])
                }
            } else sb.append(c)
            i++
        }
        return sb.toString()
    }

    @Synchronized
    fun save() = saveLocked()

    private fun maybeSave() {
        if (System.currentTimeMillis() - lastSave > 60_000) saveLocked()
    }

    private fun saveLocked() {
        val d = dir ?: return
        try {
            val sb = StringBuilder("LC1\n")
            for (s in gifts.values) {
                val g = s.v
                sb.append("G\t${s.at}\t${g.sortIndex}\t${g.giftType}\t${g.giftGroup}\t${g.count}\t${g.quality}\t${g.monsterType}\t${g.secondsAgo}\t${esc(g.name)}\n")
            }
            for (s in might.values) {
                val m = s.v
                sb.append("M\t${s.at}\t${esc(m.name)}\t${m.rank}\t${m.might}\t${m.kills}\t${m.lastSeen}\n")
            }
            for (s in received) sb.append("R\t${s.at}\t${esc(s.v.name)}\t${s.v.monsterType}\n")
            for (s in mapPlayers.values) {
                val c = s.v
                sb.append("P\t${s.at}\t${c.x}\t${c.y}\t${esc(c.name)}\t${esc(c.guild)}\t${c.level}\t${c.realm}\t${c.statusFlags}\t${c.title}\n")
            }
            for (s in popups.values) {
                val c = s.v
                sb.append("C\t${s.at}\t${c.x}\t${c.y}\t${esc(c.guildFullName)}\t${c.vip}\t${c.guildRank}\t${c.might}\t${c.kills}\n")
            }
            for ((name, list) in mightHist) for (pt in list) sb.append("H\t${esc(name)}\t${pt.at}\t${pt.might}\t${pt.kills}\n")
            for ((k, v) in ops) sb.append("O\t$k\t${v.count}\t${v.bytes}\t${v.parsed}\n")
            val tmp = File(d, "store.tmp")
            tmp.writeText(sb.toString())
            val f = File(d, "store.dat")
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            lastSave = System.currentTimeMillis()
        } catch (_: Exception) {
        }
    }

    private fun loadLocked() {
        val d = dir ?: return
        val f = File(d, "store.dat")
        if (!f.exists()) return
        try {
            f.forEachLine { line ->
                val t = line.split('\t')
                try {
                    when (t[0]) {
                        "G" -> gifts.put(t[2].toInt(), Stamped(t[1].toLong(),
                            GiftEntry(t[2].toInt(), t[3].toInt(), t[4].toInt(), t[5].toInt(), t[6].toInt(), t[7].toInt(), t[8].toLong(), unesc(t[9]))))
                        "M" -> { val nm = unesc(t[2]); might.put(nm, Stamped(t[1].toLong(), MightEntry(nm, t[3].toInt(), t[4].toLong(), t[5].toLong(), t[6].toLong()))) }
                        "R" -> received.add(Stamped(t[1].toLong(), GiftReceived(unesc(t[2]), t[3].toInt())))
                        "P" -> {
                            val x = t[2].toInt(); val y = t[3].toInt()
                            mapPlayers.put(key(x, y), Stamped(t[1].toLong(),
                                MapPlayer(x, y, unesc(t[4]), unesc(t[5]), t[6].toInt(), t[7].toInt(), t[8].toInt(), t[9].toInt())))
                            ScanPlanner.onMapPlayer(x, y, t[1].toLong() * 1000)
                        }
                        "C" -> {
                            val x = t[2].toInt(); val y = t[3].toInt()
                            popups.put(key(x, y), Stamped(t[1].toLong(),
                                CastlePopup(x, y, unesc(t[4]), t[5].toInt(), t[6].toInt(), t[7].toLong(), t[8].toLong())))
                            ScanPlanner.onPopup(x, y, t[1].toLong() * 1000)
                        }
                        "H" -> mightHist.getOrPut(unesc(t[1])) { ArrayList() }.add(MightPoint(t[2].toLong(), t[3].toLong(), t[4].toLong()))
                        "O" -> { val st = OpStat(); st.count = t[2].toLong(); st.bytes = t[3].toLong(); st.parsed = t[4].toLong(); ops[t[1]] = st }
                        else -> {}
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun key(x: Int, y: Int) = x * 2048 + y

    @Synchronized
    fun add(p: ParsedPacket) {
        val now = System.currentTimeMillis() / 1000
        when (p) {
            is GiftEntry -> gifts.put(p.sortIndex, Stamped(now, p))
            is MightEntry -> { might.put(p.name, Stamped(now, p)); recordMight(p, now) }
            is GiftReceived -> received.add(Stamped(now, p))
            is MapPlayer -> { mapPlayers.put(key(p.x, p.y), Stamped(now, p)); ScanPlanner.onMapPlayer(p.x, p.y) }
            is CastlePopup -> { popups.put(key(p.x, p.y), Stamped(now, p)); ScanPlanner.onPopup(p.x, p.y) }
        }
        maybeSave()
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
        gifts.clear(); might.clear(); received.clear(); mapPlayers.clear(); popups.clear(); frames = 0; dumps.clear(); mightHist.clear(); ops.clear(); ScanPlanner.reset(); saveLocked()
    }

    @Synchronized
    fun summary(): String =
        "حزم اللعبة المفكوكة: $frames\n" +
            "قائمة هدايا التحالف: ${gifts.size}\n" +
            "قائمة القوة: ${might.size}\n" +
            "هدايا صيد مستلمة: ${received.size}\n" +
            "قلاع على الخريطة: ${mapPlayers.size}\n" +
            "نوافذ قلاع (إحداثيات/رتبة): ${popups.size}\n" +
            ScanPlanner.report() + "\n" +
            "لاعبون تغيرت قوتهم: ${mightHist.count { it.value.size > 1 }}" + opsBrief()

    private fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    /** Ten extra columns: x,y,guild,castle_level,vip,guild_rank,guild_full_name,status_flags,title,realm. */
    private fun ex(vararg f: Any?) = f.joinToString("") { "," + (it?.toString() ?: "") }

    @Synchronized
    fun csv(): String {
        val sb = StringBuilder("﻿")
        sb.append("type,captured_at,name,rank,might,kills,last_seen,gift_type,gift_group,count,quality,monster_type,seconds_ago,sort_index,")
        sb.append("x,y,guild,castle_level,vip,guild_rank,guild_full_name,status_flags,title,realm\n")

        // castle lookup by player name (might-list names hold 12 chars, map names up to 13)
        val byName = HashMap<String, MapPlayer>()
        for (s in mapPlayers.values) byName[s.v.name.take(12)] = s.v

        for (s in might.values) {
            val m = s.v
            val mp = byName[m.name.take(12)]
            val pop = mp?.let { popups[key(it.x, it.y)]?.v }
            sb.append("might,${s.at},${q(m.name)},${m.rank},${m.might},${m.kills},${m.lastSeen},,,,,,,")
            sb.append(ex(mp?.x, mp?.y, mp?.guild?.let { q(it) }, mp?.level, pop?.vip, pop?.guildRank,
                pop?.guildFullName?.let { q(it) }, mp?.statusFlags, mp?.title, mp?.realm))
            sb.append("\n")
        }
        for (s in gifts.values) {
            val g = s.v
            sb.append("gift,${s.at},${q(g.name)},,,,,${g.giftType},${g.giftGroup},${g.count},${g.quality},${g.monsterType},${g.secondsAgo},${g.sortIndex}")
            sb.append(ex(null, null, null, null, null, null, null, null, null, null))
            sb.append("\n")
        }
        for (s in received) {
            val r = s.v
            sb.append("hunt_gift,${s.at},${q(r.name)},,,,,,,,,${r.monsterType},,")
            sb.append(ex(null, null, null, null, null, null, null, null, null, null))
            sb.append("\n")
        }
        // castle rows: every castle seen on the map (+ popup data when tapped), then popups with no map record
        for ((k, s) in mapPlayers) {
            val c = s.v
            val pop = popups[k]?.v
            sb.append("castle,${s.at},${q(c.name)},${pop?.guildRank ?: ""},${pop?.might ?: ""},${pop?.kills ?: ""},,,,,,,,")
            sb.append(ex(c.x, c.y, q(c.guild), c.level, pop?.vip, pop?.guildRank,
                pop?.guildFullName?.let { q(it) }, c.statusFlags, c.title, c.realm))
            sb.append("\n")
        }
        for ((k, s) in popups) {
            if (mapPlayers.containsKey(k)) continue
            val p = s.v
            sb.append("castle,${s.at},\"\",${p.guildRank},${p.might},${p.kills},,,,,,,,")
            sb.append(ex(p.x, p.y, null, null, p.vip, p.guildRank, q(p.guildFullName), null, null, null))
            sb.append("\n")
        }
        return sb.toString()
    }
}
