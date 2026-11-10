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
    private val mapObjs = LinkedHashMap<Int, Stamped<MapObject>>()      // non-player map objects (fortresses...), by x/y
    private val logs = ArrayDeque<String>()
    // relocation tracking: player identity (name|realm) -> map key of the castle position last seen
    private val nameIdx = HashMap<String, Int>()
    class Move(val at: Long, val name: String, val guild: String, val realm: Int,
               val fx: Int, val fy: Int, val tx: Int, val ty: Int, val gap: Long)
    private val moves = ArrayList<Move>()
    private const val MAX_MOVES = 500
    private fun ident(name: String, realm: Int) = "$name|$realm"

    // ---- watch list: names the user wants alerts for (kept in watch.txt, survives clear()) ----
    private val watch = LinkedHashMap<String, String>()      // lowercase name -> display name
    private val watchSeen = HashMap<String, Long>()          // lowercase name -> last time seen on the map (sec)
    private val pendingAlerts = ArrayList<String>()
    private const val WATCH_GONE_SEC = 1800L                 // seen again after this long = "appeared" alert
    private const val MAX_ALERTS = 20
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile var status: String = "متوقف"
    @Volatile var frames: Long = 0
    @Volatile var dumpOn: Boolean = false
    /** Message ids to dump; empty = every packet. Set from the tools card (e.g. "1110,1114"). */
    @Volatile var dumpIds: Set<Int> = emptySet()

    /** "rare" mode: dump only packets whose 3-byte opcode has been seen at most RARE_MAX times (skips chat/map spam). */
    @Volatile var dumpRare: Boolean = false
    private const val RARE_MAX = 4

    fun parseDumpIds(text: String): Set<Int> {
        dumpRare = text.contains("rare", ignoreCase = true) || text.contains("نادر")
        return text.split(',', ' ', ';', '،', '\n').mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

    @Synchronized
    fun dumpWanted(payload: ByteArray): Boolean {
        if (dumpRare) {
            val n = minOf(payload.size, 3)
            if (n == 0) return false
            val key = (0 until n).joinToString(" ") { String.format("%02X", payload[it].toInt() and 0xFF) }
            return (ops[key]?.count ?: 0L) <= RARE_MAX
        }
        val ids = dumpIds
        return ids.isEmpty() || PacketNames.idOf(payload) in ids
    }
    @Volatile var reportTotal: Int = 0       // number of rows in the last report (before the display limit)
    @Volatile var reportKeys: Map<Int, String> = emptyMap()   // report line index -> player name (tappable rows)
    @Volatile var fortType: Int = -1         // object type id the user marked as "fortress" (-1 = not chosen)
    @Volatile var version: Long = 0          // bumped on every change, so the UI can skip redraws
    @Volatile var lastUpload: String = ""
    private var dirtyData = false
    private val sentHash = HashMap<String, Int>()
    private val pendingHash = HashMap<String, Int>()

    data class Stats(
        val frames: Long, val might: Int, val castles: Int, val popups: Int, val gifts: Int,
        val received: Int, val changed: Int, val covered: Int, val blocks: Int, val due: Int,
        val mapFrames: Long, val popupFrames: Long
    )
    class Snapshot(val json: String, val items: Int)

    fun onFrame() { frames++; version++ }

    @Synchronized
    fun setUpload(msg: String) { lastUpload = fmt.format(Date()) + "  " + msg; version++ }

    @Synchronized
    fun commitSent() { sentHash.putAll(pendingHash); pendingHash.clear() }

    @Synchronized
    fun stats(): Stats = Stats(
        frames, might.size, mapPlayers.size, popups.size, gifts.size, received.size,
        mightHist.count { it.value.size > 1 },
        ScanPlanner.coveredBlocks(), ScanPlanner.totalBlocks(), ScanPlanner.dueCount(),
        opCount { it in MAP_OPS_HEX }, opCount { it == "AC 08 0C" }
    )

    private val MAP_OPS_HEX = setOf("02", "03", "0F", "0D", "0E", "09", "18", "17", "16").map { "AC 08 $it" }.toSet()

    private fun opCount(match: (String) -> Boolean): Long = ops.entries.filter { match(it.key) }.sumOf { it.value.count }

    @Synchronized
    fun extras(): String {
        val mapF = opCount { it in MAP_OPS_HEX }
        val hint = when {
            frames > 20 && mapF == 0L -> "لم تصل حزم خريطة بعد — افتح الخريطة في اللعبة وحرّكها\n"
            mapF > 0 && mapPlayers.isEmpty() -> "وصلت حزم خريطة لكن لم تُفك — فعّل تفريغ الحزم وصدّره وابعثه\n"
            else -> ""
        }
        return (hint + ScanPlanner.nextText() + opsBrief()).trim()
    }
    private val dumps = ArrayDeque<String>()

    @Synchronized
    fun addDump(payload: ByteArray, parsed: Int) {
        dumps.addLast("[" + fmt.format(Date()) + "] len=" + payload.size + " id=" + PacketNames.label(PacketNames.idOf(payload)) + " parsed=" + parsed + "\n" + PacketDump.hex(payload, if (dumpIds.isEmpty() && !dumpRare) 256 else 1024))
        while (dumps.size > 200) dumps.removeFirst()
    }

    @Synchronized
    fun dumpText(): String = "== إحصاء الحزم ==\n" + opsReport(400) +
        "\n== التفريغ" + (if (dumpRare) " (نادر فقط)" else if (dumpIds.isEmpty()) "" else " (أرقام: " + dumpIds.sorted().joinToString(",") + ")") + " ==\n" +
        dumps.joinToString("\n")

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
        loadWatch()
    }

    private fun recordMight(m: MightEntry, now: Long) {
        val list = mightHist.getOrPut(m.name) { ArrayList() }
        val last = list.lastOrNull()
        if (last == null || last.might != m.might || last.kills != m.kills) {
            if (last != null && last.might > 0) {
                val d = m.might - last.might
                if (isWatched(m.name)) watchAlert("👁 " + m.name + ": القوة " + sgn(d) + " | قتلى " + sgn(m.kills - last.kills))
                else if (Math.abs(d) * 100.0 / last.might >= ALERT_PCT) log("⚠ " + m.name + ": القوة " + sgn(d))
            }
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
                .append(v.parsed.toString().padStart(7)).append(if (v.parsed == 0L) "  <- غير مفكوك" else "")
                .append("  ").append(PacketNames.labelForKey(k)).append('\n')
        }
        return sb.toString()
    }

    private fun opsBrief(): String {
        val un = ops.entries.filter { it.value.parsed == 0L && it.value.count >= 3 }
            .sortedByDescending { it.value.bytes }.take(5)
        if (un.isEmpty()) return ""
        return "\nحزم غير مفكوكة (الأكثر): " + un.joinToString(" | ") {
            "${it.key} ${PacketNames.labelForKey(it.key)} ×${it.value.count} (~${it.value.bytes / it.value.count}B)"
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
    fun save() { if (dirtyData) saveLocked() }

    private fun maybeSave() {
        if (dirtyData && System.currentTimeMillis() - lastSave > 60_000) saveLocked()
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
            for (s in mapObjs.values) {
                val o = s.v
                sb.append("X\t${s.at}\t${o.x}\t${o.y}\t${o.type}\t${esc(o.name)}\t${esc(o.guild)}\n")
            }
            for (m in moves) sb.append("V\t${m.at}\t${esc(m.name)}\t${esc(m.guild)}\t${m.realm}\t${m.fx}\t${m.fy}\t${m.tx}\t${m.ty}\t${m.gap}\n")
            for ((name, list) in mightHist) for (pt in list) sb.append("H\t${esc(name)}\t${pt.at}\t${pt.might}\t${pt.kills}\n")
            for ((k, v) in ops) sb.append("O\t$k\t${v.count}\t${v.bytes}\t${v.parsed}\n")
            val tmp = File(d, "store.tmp")
            tmp.writeText(sb.toString())
            val f = File(d, "store.dat")
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            lastSave = System.currentTimeMillis()
            dirtyData = false
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
                            nameIdx[ident(unesc(t[4]), t[7].toInt())] = key(x, y)
                        }
                        "V" -> moves.add(Move(t[1].toLong(), unesc(t[2]), unesc(t[3]), t[4].toInt(),
                            t[5].toInt(), t[6].toInt(), t[7].toInt(), t[8].toInt(), t[9].toLong()))
                        "C" -> {
                            val x = t[2].toInt(); val y = t[3].toInt()
                            popups.put(key(x, y), Stamped(t[1].toLong(),
                                CastlePopup(x, y, unesc(t[4]), t[5].toInt(), t[6].toInt(), t[7].toLong(), t[8].toLong())))
                            ScanPlanner.onPopup(x, y, t[1].toLong() * 1000)
                        }
                        "X" -> {
                            val x = t[2].toInt(); val y = t[3].toInt()
                            mapObjs.put(key(x, y), Stamped(t[1].toLong(), MapObject(x, y, t[4].toInt(), unesc(t[5]), unesc(t[6]))))
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


    // ---- reports: lists, alliances, inactive, changes ----
    const val MODE_POWER = 0
    const val MODE_CASTLES = 1
    const val MODE_GIFTS = 2
    const val MODE_ALLIANCE = 3
    const val MODE_INACTIVE = 4
    const val MODE_CHANGES = 5
    const val MODE_FORTS = 6
    const val MODE_MOVES = 7
    const val MODE_WATCH = 8
    const val FAST_MOVE_SEC = 9L   // seen at the old spot less than this long before the new one = moved "live"
    const val INACTIVE_DAYS = 3
    const val ALERT_PCT = 5.0

    private class Agg {
        var n = 0; var withMight = 0; var total = 0L; var kills = 0L; var lvl = 0L
        var sx = 0L; var sy = 0L
        var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE; var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
    }

    class Pt(val x: Int, val y: Int, val tag: String, val name: String = "", val realm: Int = 0)

    @Synchronized
    fun alliancePoints(): List<Pt> = mapPlayers.values.map { Pt(it.v.x, it.v.y, it.v.guild, it.v.name, it.v.realm) }

    @Synchronized
    fun fortPoints(): List<Pt> =
        if (fortType < 0) emptyList() else mapObjs.values.filter { it.v.type == fortType }.map { Pt(it.v.x, it.v.y, it.v.guild) }

    @Synchronized
    fun objCount(): Int = mapObjs.size

    private class Chg(val name: String, val dm: Long, val dk: Long, val prev: Long, val total: Long)

    private fun fmtNum(v: Long): String {
        val a = Math.abs(v.toDouble())
        return when {
            a >= 1e9 -> String.format(Locale.US, "%.2fB", v / 1e9)
            a >= 1e6 -> String.format(Locale.US, "%.1fM", v / 1e6)
            a >= 1e3 -> String.format(Locale.US, "%.1fK", v / 1e3)
            else -> v.toString()
        }
    }

    private fun sgn(v: Long) = (if (v > 0) "+" else "") + fmtNum(v)

    private fun mightMap(): Map<String, MightEntry> {
        val r = HashMap<String, MightEntry>()
        for (s in might.values) r[s.v.name.take(12)] = s.v
        return r
    }

    @Synchronized
    fun report(mode: Int, query: String, limit: Int = 100): String {
        val qy = query.trim()
        fun ok(name: String) = qy.isEmpty() || name.contains(qy, ignoreCase = true)
        val now = System.currentTimeMillis() / 1000
        val sb = StringBuilder()
        val keyed = HashMap<Int, String>()
        reportTotal = 0
        fun mark(n: String) { keyed[sb.count { it == '\n' }] = n }
        when (mode) {
            MODE_POWER -> {
                val rows = might.values.map { it.v }.filter { ok(it.name) }.sortedByDescending { it.might }
                reportTotal = rows.size
                sb.append("قائمة القوة: ").append(rows.size).append('\n')
                rows.take(limit).forEachIndexed { i, m ->
                    mark(m.name)
                    sb.append("${i + 1}. ${m.name} | ${fmtNum(m.might)} | قتلى ${fmtNum(m.kills)} | رتبة ${m.rank}\n")
                }
            }
            MODE_CASTLES -> {
                val byName = mightMap()
                val rows = mapPlayers.entries.filter { ok(it.value.v.name) || ok(it.value.v.guild) }
                    .sortedByDescending { popups[it.key]?.v?.might ?: byName[it.value.v.name.take(12)]?.might ?: 0L }
                reportTotal = rows.size
                sb.append("القلاع: ").append(rows.size).append('\n')
                for ((k, st) in rows.take(limit)) {
                    val c = st.v
                    val pop = popups[k]?.v
                    val mg = pop?.might ?: byName[c.name.take(12)]?.might
                    mark(c.name)
                    sb.append("${c.name} [${c.guild}] (${c.x},${c.y}) م${c.level}")
                    if (pop != null) sb.append(" VIP ${pop.vip} رتبة ${pop.guildRank}")
                    if (mg != null) sb.append(" | ${fmtNum(mg)}")
                    sb.append('\n')
                }
            }
            MODE_GIFTS -> {
                val rows = gifts.values.map { it.v }.filter { ok(it.name) }.sortedBy { it.sortIndex }
                reportTotal = rows.size
                sb.append("هدايا التحالف: ").append(rows.size).append(" | هدايا صيد مستلمة: ").append(received.size).append('\n')
                rows.take(limit).forEach { g ->
                    mark(g.name)
                    sb.append("${g.name} | نوع ${g.giftType}/${g.giftGroup} | ×${g.count} | جودة ${g.quality}\n")
                }
            }
            MODE_ALLIANCE -> {
                val byName = mightMap()
                val g = HashMap<String, Agg>()
                val full = HashMap<String, String>()
                for ((k, st) in mapPlayers) {
                    val c = st.v
                    val tag = if (c.guild.isBlank()) "(بدون تحالف)" else c.guild
                    val fnm = popups[k]?.v?.guildFullName
                    if (!fnm.isNullOrBlank()) full[tag] = fnm
                    val a = g.getOrPut(tag) { Agg() }
                    a.n++
                    a.lvl += c.level
                    a.sx += c.x; a.sy += c.y
                    a.minX = minOf(a.minX, c.x); a.maxX = maxOf(a.maxX, c.x)
                    a.minY = minOf(a.minY, c.y); a.maxY = maxOf(a.maxY, c.y)
                    val pop = popups[k]?.v
                    val mg = pop?.might ?: byName[c.name.take(12)]?.might
                    val kl = pop?.kills ?: byName[c.name.take(12)]?.kills
                    if (mg != null) { a.withMight++; a.total += mg; a.kills += (kl ?: 0L) }
                }
                val rows = g.entries.filter { ok(it.key) || ok(full[it.key] ?: "") }
                    .sortedWith(compareByDescending<Map.Entry<String, Agg>> { it.value.n }.thenByDescending { it.value.total })
                reportTotal = rows.size
                sb.append("التحالفات: ").append(rows.size).append(" | قلاع ظاهرة: ").append(mapPlayers.size).append('\n')
                for ((tag, a) in rows.take(limit)) {
                    val cx = Math.round(a.sx.toDouble() / a.n)
                    val cy = Math.round(a.sy.toDouble() / a.n)
                    val lv = String.format(Locale.US, "%.1f", a.lvl.toDouble() / maxOf(a.n, 1))
                    sb.append("\n[$tag]").append(full[tag]?.let { " $it" } ?: "").append('\n')
                    sb.append("  أعضاء ظاهرون ${a.n} | المركز ($cx,$cy) | x ${a.minX}–${a.maxX} y ${a.minY}–${a.maxY}\n")
                    if (fortType >= 0) {
                        val fs = mapObjs.values.map { it.v }.filter { it.type == fortType && it.guild == tag }
                        if (fs.isNotEmpty()) sb.append("  حصون ${fs.size}: " + fs.take(8).joinToString(" ") { "(${it.x},${it.y})" } + "\n")
                    }
                    if (a.withMight == 0) {
                        sb.append("  القوة: غير معروفة (اضغط على قلاعهم) | م$lv\n")
                    } else {
                        val avg = a.total / a.withMight
                        sb.append("  القوة ${fmtNum(a.total)} (${a.withMight}/${a.n} معروفة) | متوسط ${fmtNum(avg)} | قتلى ${fmtNum(a.kills)} | م$lv\n")
                    }
                }
            }
            MODE_FORTS -> {
                if (fortType < 0) {
                    val byType = mapObjs.values.groupBy { it.v.type }
                    sb.append("أنواع الكائنات الملتقطة (غير القلاع): ").append(mapObjs.size).append('\n')
                    sb.append("اكتب رقم نوع الحصن في خانة «نوع الحصن» تحت الخريطة. ابحث عن النوع الذي تتجمع إحداثياته على أماكن الحصون.\n")
                    for ((tp, l) in byType.entries.sortedByDescending { it.value.size }.take(30)) {
                        val ex = l.take(3).joinToString("  ") { "(${it.v.x},${it.v.y})" + (if (it.v.guild.isNotEmpty()) "[${it.v.guild}]" else "") }
                        sb.append("\nنوع $tp ×${l.size}: $ex")
                    }
                    sb.append('\n')
                } else {
                    val rows = mapObjs.values.map { it.v }.filter { it.type == fortType }
                        .filter { ok(it.guild) || ok(it.name) }
                        .sortedWith(compareBy({ it.guild.isEmpty() }, { it.guild }, { it.x }, { it.y }))
                    reportTotal = (rows.size + 2) / 3
                    sb.append("الحصون (نوع $fortType): ").append(rows.size).append('\n')
                    for (o in rows.take(limit * 3)) {
                        sb.append("(${o.x},${o.y})")
                        if (o.guild.isNotEmpty()) sb.append(" [${o.guild}]")
                        if (o.name.isNotEmpty()) sb.append(" ${o.name}")
                        sb.append('\n')
                    }
                }
            }
            MODE_MOVES -> {
                val rows = moves.filter { ok(it.name) || ok(it.guild) }.asReversed()
                reportTotal = rows.size
                sb.append("لاعبون غيّروا مكانهم: ").append(rows.map { it.name }.toSet().size)
                    .append(" | عدد الانتقالات: ").append(rows.size).append('\n')
                rows.take(limit).forEach { m ->
                    mark(m.name)
                    val ago = now - m.at
                    val agoTxt = if (ago < 90) "${ago}ث" else if (ago < 5400) "${ago / 60}د" else if (ago < 129600) "${ago / 3600}س" else "${ago / 86400}ي"
                    val live = if (m.gap in 0..FAST_MOVE_SEC) " ⚡" else ""
                    sb.append("${m.name}${if (m.guild.isNotEmpty()) " [${m.guild}]" else ""} | (${m.fx},${m.fy}) ← (${m.tx},${m.ty}) | قبل $agoTxt$live\n")
                }
            }
            MODE_WATCH -> {
                val names = watch.values.filter { ok(it) }
                reportTotal = names.size
                sb.append("المراقَبون: ").append(watch.size).append('\n')
                if (watch.isEmpty()) {
                    sb.append("أضف لاعباً: اكتب اسمه في خانة المراقبة، أو اضغط صفه في أي قائمة ثم «👁 راقب».\n")
                }
                names.take(limit).forEach { n ->
                    mark(n)
                    val c = mapPlayers.values.firstOrNull { it.v.name.equals(n, ignoreCase = true) }
                    val m = might.values.firstOrNull { it.v.name.equals(n, ignoreCase = true) }?.v
                    val sbl = StringBuilder(n)
                    if (c != null) {
                        if (c.v.guild.isNotEmpty()) sbl.append(" [").append(c.v.guild).append(']')
                        sbl.append(" | K${c.v.realm} (${c.v.x},${c.v.y})")
                    } else sbl.append(" | لم يظهر على الخريطة بعد")
                    if (m != null) sbl.append(" | ").append(fmtNum(m.might)).append(" | قتلى ").append(fmtNum(m.kills))
                    val seen = watchSeen[n.trim().lowercase()]
                    if (seen != null) {
                        val ago = now - seen
                        sbl.append(" | ظهر قبل ").append(if (ago < 90) "${ago}ث" else if (ago < 5400) "${ago / 60}د" else if (ago < 129600) "${ago / 3600}س" else "${ago / 86400}ي")
                    }
                    sb.append(sbl).append('\n')
                }
            }
            MODE_INACTIVE -> {
                val rows = might.values.map { it.v }.filter { ok(it.name) }
                val online = rows.count { it.lastSeen == 0L }
                val idle = rows.filter { it.lastSeen > 0L }
                    .map { it to (now - it.lastSeen) / 86400.0 }
                    .filter { it.second >= INACTIVE_DAYS }
                    .sortedByDescending { it.second }
                reportTotal = idle.size
                sb.append("خاملون ≥ $INACTIVE_DAYS أيام: ${idle.size} | متصل/غير معروف: $online\n")
                idle.take(limit).forEach { (m, d) ->
                    mark(m.name)
                    val ds = String.format(Locale.US, "%.1f", d)
                    sb.append("${m.name} | $ds يوم | ${fmtNum(m.might)}\n")
                }
            }
            MODE_CHANGES -> {
                val rows = ArrayList<Chg>()
                for ((n, l) in mightHist) {
                    if (l.size < 2 || !ok(n)) continue
                    val a = l[l.size - 2]; val b = l[l.size - 1]; val f = l[0]
                    rows.add(Chg(n, b.might - a.might, b.kills - a.kills, a.might, b.might - f.might))
                }
                rows.sortByDescending { Math.abs(it.dm) }
                reportTotal = rows.size
                sb.append("لاعبون تغيرت قوتهم: ").append(rows.size).append('\n')
                rows.take(limit).forEach { r ->
                    mark(r.name)
                    val pct = if (r.prev > 0) String.format(Locale.US, "%.1f", r.dm * 100.0 / r.prev) else "-"
                    sb.append("${r.name} | ${sgn(r.dm)} ($pct%) | قتلى ${sgn(r.dk)} | منذ البداية ${sgn(r.total)}\n")
                }
            }
        }
        reportKeys = keyed
        return sb.toString()
    }

    @Synchronized
    fun castleFor(name: String): Pt? {
        val n = name.take(12)
        val c = mapPlayers.values.firstOrNull { it.v.name.take(12) == n }?.v ?: return null
        return Pt(c.x, c.y, c.guild, c.name, c.realm)
    }

    // ---- JSON snapshot for the PHP server ----
    private fun js(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun nn(v: Any?) = v?.toString() ?: "null"

    @Synchronized
    fun json(token: String, delta: Boolean = false): Snapshot {
        pendingHash.clear()
        var items = 0
        // true when this item must be sent (always for a full upload; only if changed for a delta upload)
        fun want(key: String, h: Int): Boolean {
            if (delta && sentHash[key] == h) return false
            pendingHash[key] = h
            items++
            return true
        }
        val sb = StringBuilder()
        sb.append("{\"token\":").append(js(token)).append(",\"sent_at\":").append(System.currentTimeMillis() / 1000)
        sb.append(",\"might\":[")
        var first = true
        for (s in might.values) {
            val m = s.v
            if (!want("m:" + m.name, "${m.rank}|${m.might}|${m.kills}|${m.lastSeen}".hashCode())) continue
            if (!first) sb.append(','); first = false
            sb.append("{\"name\":").append(js(m.name))
                .append(",\"rank\":${m.rank},\"might\":${m.might},\"kills\":${m.kills},\"last_seen\":${m.lastSeen},\"at\":${s.at}}")
        }
        sb.append("],\"castles\":[")
        first = true
        for ((k, st) in mapPlayers) {
            val c = st.v
            val pop = popups[k]?.v
            val h = "${c.name}|${c.guild}|${c.level}|${pop?.vip}|${pop?.guildRank}|${pop?.might}|${pop?.kills}|${pop?.guildFullName}".hashCode()
            if (!want("c:$k", h)) continue
            if (!first) sb.append(','); first = false
            sb.append("{\"x\":${c.x},\"y\":${c.y},\"name\":").append(js(c.name))
                .append(",\"guild\":").append(js(c.guild))
                .append(",\"level\":${c.level},\"realm\":${c.realm},\"flags\":${c.statusFlags},\"title\":${c.title}")
                .append(",\"vip\":${nn(pop?.vip)},\"guild_rank\":${nn(pop?.guildRank)}")
                .append(",\"guild_full_name\":").append(if (pop != null) js(pop.guildFullName) else "null")
                .append(",\"might\":${nn(pop?.might)},\"kills\":${nn(pop?.kills)},\"at\":${st.at}}")
        }
        for ((k, st) in popups) {
            if (mapPlayers.containsKey(k)) continue
            val p = st.v
            if (!want("c:$k", "p|${p.vip}|${p.guildRank}|${p.might}|${p.kills}|${p.guildFullName}".hashCode())) continue
            if (!first) sb.append(','); first = false
            sb.append("{\"x\":${p.x},\"y\":${p.y},\"name\":\"\",\"guild\":\"\",\"level\":null,\"realm\":null,\"flags\":null,\"title\":null")
                .append(",\"vip\":${p.vip},\"guild_rank\":${p.guildRank},\"guild_full_name\":").append(js(p.guildFullName))
                .append(",\"might\":${p.might},\"kills\":${p.kills},\"at\":${st.at}}")
        }
        sb.append("],\"gifts\":[")
        first = true
        for (s in gifts.values) {
            val g = s.v
            if (!want("g:${g.sortIndex}", "${g.name}|${g.giftType}|${g.giftGroup}|${g.count}|${g.quality}|${g.monsterType}".hashCode())) continue
            if (!first) sb.append(','); first = false
            sb.append("{\"sort_index\":${g.sortIndex},\"name\":").append(js(g.name))
                .append(",\"gift_type\":${g.giftType},\"gift_group\":${g.giftGroup},\"count\":${g.count},\"quality\":${g.quality}")
                .append(",\"monster_type\":${g.monsterType},\"seconds_ago\":${g.secondsAgo},\"at\":${s.at}}")
        }
        sb.append("],\"moves\":[")
        first = true
        for (m in moves) {
            if (!want("v:${m.at}|${m.name}|${m.tx}|${m.ty}", 1)) continue
            if (!first) sb.append(','); first = false
            sb.append("{\"name\":").append(js(m.name)).append(",\"guild\":").append(js(m.guild))
                .append(",\"realm\":${m.realm},\"from_x\":${m.fx},\"from_y\":${m.fy},\"to_x\":${m.tx},\"to_y\":${m.ty}")
                .append(",\"gap_sec\":${m.gap},\"at\":${m.at}}")
        }
        sb.append("]}")
        return Snapshot(sb.toString(), items)
    }

    private fun key(x: Int, y: Int) = x * 2048 + y

    @Synchronized
    fun add(p: ParsedPacket) {
        val now = System.currentTimeMillis() / 1000
        when (p) {
            is GiftEntry -> gifts.put(p.sortIndex, Stamped(now, p))
            is MightEntry -> { might.put(p.name, Stamped(now, p)); recordMight(p, now) }
            is GiftReceived -> received.add(Stamped(now, p))
            is MapPlayer -> {
                val k = key(p.x, p.y)
                val id = ident(p.name, p.realm)
                val oldK = nameIdx[id]
                var relocatedFrom: MapPlayer? = null
                if (oldK != null && oldK != k) {
                    val old = mapPlayers[oldK]
                    // same player (name + kingdom) now at another spot = relocation: drop the old castle, keep the history
                    if (old != null && old.v.name == p.name && old.v.realm == p.realm) {
                        moves.add(Move(now, p.name, p.guild, p.realm, old.v.x, old.v.y, p.x, p.y, now - old.at))
                        if (moves.size > MAX_MOVES) moves.removeAt(0)
                        log("🚚 " + p.name + ": (" + old.v.x + "," + old.v.y + ") ← (" + p.x + "," + p.y + ")")
                        relocatedFrom = old.v
                        mapPlayers.remove(oldK)
                        val pop = popups.remove(oldK)
                        if (pop != null && !popups.containsKey(k)) popups[k] = pop   // keep rank/might until re-tapped
                        ScanPlanner.forget(old.v.x, old.v.y)
                    }
                }
                mapPlayers.put(k, Stamped(now, p))
                nameIdx[id] = k
                ScanPlanner.onMapPlayer(p.x, p.y)
                onWatchedSeen(p, relocatedFrom, now)
            }
            is MapObject -> {
                // a player castle at the same spot always wins over a generic object
                if (!mapPlayers.containsKey(key(p.x, p.y))) mapObjs.put(key(p.x, p.y), Stamped(now, p))
            }
            is CastlePopup -> { popups.put(key(p.x, p.y), Stamped(now, p)); ScanPlanner.onPopup(p.x, p.y) }
        }
        dirtyData = true
        version++
        maybeSave()
    }

    // ---- watch list API ----
    private fun wkey(name: String) = name.trim().lowercase()

    private fun isWatched(name: String) = watch.containsKey(wkey(name))

    @Synchronized
    fun isWatchedName(name: String): Boolean = isWatched(name)

    @Synchronized
    fun watchCount(): Int = watch.size

    @Synchronized
    fun watchAdd(name: String): Boolean {
        val n = name.trim().replace('\t', ' ').replace('\n', ' ')
        if (n.isEmpty() || watch.containsKey(wkey(n))) return false
        watch[wkey(n)] = n
        saveWatch()
        log("👁 بدأت مراقبة " + n)
        version++
        return true
    }

    @Synchronized
    fun watchRemove(name: String) {
        if (watch.remove(wkey(name)) != null) {
            watchSeen.remove(wkey(name))
            saveWatch()
            log("👁 أُلغيت مراقبة " + name.trim())
            version++
        }
    }

    /** Alerts produced since the last call (the capture service shows them as a notification). */
    @Synchronized
    fun takeAlerts(): List<String> {
        if (pendingAlerts.isEmpty()) return emptyList()
        val r = ArrayList(pendingAlerts)
        pendingAlerts.clear()
        return r
    }

    private fun watchAlert(msg: String) {
        log(msg)
        pendingAlerts.add(msg)
        while (pendingAlerts.size > MAX_ALERTS) pendingAlerts.removeAt(0)
    }

    private fun onWatchedSeen(p: MapPlayer, from: MapPlayer?, now: Long) {
        val wk = wkey(p.name)
        if (!watch.containsKey(wk)) return
        val last = watchSeen[wk]
        if (from != null) {
            watchAlert("👁🚚 " + p.name + " انتقل: (" + from.x + "," + from.y + ") ← (" + p.x + "," + p.y + ") K" + p.realm)
        } else if (last == null || now - last > WATCH_GONE_SEC) {
            watchAlert("👁 " + p.name + " ظهر عند (" + p.x + "," + p.y + ") K" + p.realm +
                (if (p.guild.isNotEmpty()) " [" + p.guild + "]" else ""))
        }
        watchSeen[wk] = now
    }

    private fun saveWatch() {
        val d = dir ?: return
        try { File(d, "watch.txt").writeText(watch.values.joinToString("\n")) } catch (_: Exception) {}
    }

    private fun loadWatch() {
        val d = dir ?: return
        try {
            val f = File(d, "watch.txt")
            if (!f.exists()) return
            f.forEachLine { l -> val n = l.trim(); if (n.isNotEmpty()) watch[wkey(n)] = n }
        } catch (_: Exception) {}
    }

    @Synchronized
    fun log(s: String) {
        logs.addLast(fmt.format(Date()) + "  " + s)
        version++
        while (logs.size > 300) logs.removeFirst()
    }

    @Synchronized
    fun tail(n: Int): String = logs.toList().takeLast(n).joinToString("\n")

    @Synchronized
    fun clear() {
        gifts.clear(); might.clear(); received.clear(); mapPlayers.clear(); popups.clear(); mapObjs.clear(); nameIdx.clear(); moves.clear(); frames = 0; dumps.clear(); mightHist.clear(); ops.clear(); ScanPlanner.reset(); sentHash.clear(); pendingHash.clear(); version++; saveLocked()
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
            "انتقالات لاعبين: ${moves.size}\n" +
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
