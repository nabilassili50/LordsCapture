package com.botvi.lords

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

    private fun key(x: Int, y: Int) = x * 2048 + y

    @Synchronized
    fun add(p: ParsedPacket) {
        val now = System.currentTimeMillis() / 1000
        when (p) {
            is GiftEntry -> gifts.put(p.sortIndex, Stamped(now, p))
            is MightEntry -> might.put(p.name, Stamped(now, p))
            is GiftReceived -> received.add(Stamped(now, p))
            is MapPlayer -> { mapPlayers.put(key(p.x, p.y), Stamped(now, p)); ScanPlanner.onMapPlayer(p.x, p.y) }
            is CastlePopup -> { popups.put(key(p.x, p.y), Stamped(now, p)); ScanPlanner.onPopup(p.x, p.y) }
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
        gifts.clear(); might.clear(); received.clear(); mapPlayers.clear(); popups.clear(); frames = 0; ScanPlanner.reset()
    }

    @Synchronized
    fun summary(): String =
        "حزم اللعبة المفكوكة: $frames\n" +
            "قائمة هدايا التحالف: ${gifts.size}\n" +
            "قائمة القوة: ${might.size}\n" +
            "هدايا صيد مستلمة: ${received.size}\n" +
            "قلاع على الخريطة: ${mapPlayers.size}\n" +
            "نوافذ قلاع (إحداثيات/رتبة): ${popups.size}\n" +
            ScanPlanner.report()

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
