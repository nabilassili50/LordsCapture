package com.botvi.lords

/**
 * ScanPlanner.kt - passive coverage planner (Kotlin port of the scan-timeout idea).
 * Nothing is sent to the server: it only tracks what was captured and tells you
 * which castles to tap again and which map blocks to scroll to.
 *
 * Coordinates follow GuidCodec: x in 0..512, y in 0..1024.
 */
object ScanPlanner {
    const val BLOCK = 16
    const val BLOCK_TIMEOUT_MS = 10 * 60 * 1000L   // block counts as stale after 10 min
    const val CASTLE_TIMEOUT_MS = 2 * 60 * 1000L   // castle re-tap interval: 2 min

    private const val BX = GuidCodec.MAX_X / BLOCK + 1
    private const val BY = GuidCodec.MAX_Y / BLOCK + 1

    private val blockSeen = LongArray(BX * BY)               // 0 = never seen
    private val castleSeen = LinkedHashMap<Int, Long>()      // last time seen on the map
    private val castleTapped = HashMap<Int, Long>()          // last time its popup was captured

    data class Target(val x: Int, val y: Int, val note: String)

    private fun key(x: Int, y: Int) = x * 2048 + y
    private fun blockIdx(x: Int, y: Int) = (y / BLOCK) * BX + (x / BLOCK)
    private fun valid(x: Int, y: Int) = x in 0..GuidCodec.MAX_X && y in 0..GuidCodec.MAX_Y

    @Synchronized
    fun onMapPlayer(x: Int, y: Int, now: Long = System.currentTimeMillis()) {
        if (!valid(x, y)) return
        blockSeen[blockIdx(x, y)] = now
        castleSeen[key(x, y)] = now
    }

    @Synchronized
    fun onPopup(x: Int, y: Int, now: Long = System.currentTimeMillis()) {
        if (!valid(x, y)) return
        blockSeen[blockIdx(x, y)] = now
        castleSeen.putIfAbsent(key(x, y), now)
        castleTapped[key(x, y)] = now
    }

    /** The bot reports it has looked at this spot, even if no castle was there (empty blocks never get marked otherwise). */
    @Synchronized
    fun markVisited(x: Int, y: Int, now: Long = System.currentTimeMillis()) {
        if (!valid(x, y)) return
        blockSeen[blockIdx(x, y)] = now
    }

    /** true: sweep the WHOLE map first (snake order, land or sea alike); false: explore around known castles first. */
    @Volatile var fullMap = true

    // visitable block grid: block centres 24..504 (x) and 24..1016 (y), the game's dialog rejects the outer 24 rows/columns
    private const val C0 = 1
    private const val C1 = 31
    private const val R0 = 1
    private const val R1 = 63
    private const val NC = C1 - C0 + 1
    private const val SWEEP_TOTAL = NC * (R1 - R0 + 1)
    private var sweepPos = 0

    private fun sweepIdx(p: Int): Int {
        val rr = p / NC
        val cc = p % NC
        val c = if (rr % 2 == 0) C0 + cc else C1 - cc      // snake: left->right, then right->left
        return (R0 + rr) * BX + c
    }

    /** next block of the sweep that has not been seen yet, or -1 when the whole map has been covered */
    private fun sweepNextIdx(): Int {
        while (sweepPos < SWEEP_TOTAL) {
            val i = sweepIdx(sweepPos)
            if (blockSeen[i] == 0L) return i
            sweepPos++
        }
        return -1
    }

    /** first map row (west to east, north to south) that still has an unseen block: [centreY, centreX of its first unseen block], or null when the whole kingdom is covered */
    @Synchronized
    fun nextDragRow(): IntArray? {
        for (r in R0..R1) for (c in C0..C1) {
            if (blockSeen[r * BX + c] == 0L) return intArrayOf(r * BLOCK + BLOCK / 2, c * BLOCK + BLOCK / 2)
        }
        return null
    }

    /** marks every block on the row of [y] between x0 and x1 as visited (the drag scan moved the view across them) */
    @Synchronized
    fun markSpan(x0: Int, x1: Int, y: Int) {
        var x = maxOf(x0, 0)
        while (x < x1) { markVisited(x, y); x += BLOCK }
        markVisited(x1, y)
    }

    @Synchronized
    fun sweepText(): String {
        var done = 0
        for (p in 0 until SWEEP_TOTAL) if (blockSeen[sweepIdx(p)] != 0L) done++
        return if (done >= SWEEP_TOTAL) "اكتمل مسح الخريطة ($SWEEP_TOTAL/$SWEEP_TOTAL) — نعيد فحص ما حول القلاع"
        else "مسح كل الخريطة: $done/$SWEEP_TOTAL (${done * 100 / SWEEP_TOTAL}%)"
    }

    /** start a fresh full-map sweep (castles stay known; only the "visited" marks are cleared) */
    @Synchronized
    fun newSweep() {
        blockSeen.fill(0L)
        sweepPos = 0
    }

    @Synchronized
    fun exportSeen(): List<Pair<Int, Long>> {
        val out = ArrayList<Pair<Int, Long>>()
        for (i in blockSeen.indices) if (blockSeen[i] != 0L) out.add(i to blockSeen[i])
        return out
    }

    @Synchronized
    fun importSeen(idx: Int, t: Long) {
        if (idx in blockSeen.indices && blockSeen[idx] < t) blockSeen[idx] = t
    }

    private var lastX = GuidCodec.MAX_X / 2          // where the previous target was: exploration continues from there
    private var lastY = GuidCodec.MAX_Y / 2
    private const val FRONTIER = 4                    // blocks around a known castle that get explored first

    /**
     * Next map spot for the scanning bot, in this order:
     *  1) never-visited blocks within FRONTIER blocks of a known castle (the castle cluster grows outwards),
     *  2) the oldest block around a known castle (+-1) that is not fresher than [revisitMs] (catches relocations),
     *  3) any never-visited block, nearest to the previous target (slow sweep, so empty areas still get explored).
     * Before this, one known castle (usually your own) locked the scan onto 9 blocks around it forever.
     */
    @Synchronized
    fun nextTarget(revisitMs: Long = 60_000L, now: Long = System.currentTimeMillis()): Target? {
        val active = java.util.TreeSet<Int>()
        val cbx = ArrayList<Int>()
        val cby = ArrayList<Int>()
        val dup = HashSet<Int>()
        for (k in castleSeen.keys) {
            val bx = (k / 2048) / BLOCK
            val by = (k % 2048) / BLOCK
            if (bx !in 0 until BX || by !in 0 until BY) continue
            if (dup.add(by * BX + bx)) { cbx.add(bx); cby.add(by) }
            for (dx in -1..1) for (dy in -1..1) {
                val xx = bx + dx; val yy = by + dy
                if (xx in 0 until BX && yy in 0 until BY) active.add(yy * BX + xx)
            }
        }
        fun dist(i: Int) = Math.abs((i % BX) * BLOCK + BLOCK / 2 - lastX) + Math.abs((i / BX) * BLOCK + BLOCK / 2 - lastY)

        var best = -1
        var note = ""
        // 0) whole-map sweep: every visitable block exactly once, in snake order
        if (fullMap) {
            val si = sweepNextIdx()
            if (si >= 0) {
                val sx = minOf((si % BX) * BLOCK + BLOCK / 2, GuidCodec.MAX_X)
                val sy = minOf((si / BX) * BLOCK + BLOCK / 2, GuidCodec.MAX_Y)
                lastX = sx
                lastY = sy
                return Target(sx, sy, "sweep")
            }
        }
        // 1) frontier around known castles
        var bd = Int.MAX_VALUE
        var bl = Int.MAX_VALUE
        for (n in cbx.indices) {
            for (dx in -FRONTIER..FRONTIER) for (dy in -FRONTIER..FRONTIER) {
                val xx = cbx[n] + dx; val yy = cby[n] + dy
                if (xx !in 0 until BX || yy !in 0 until BY) continue
                val i = yy * BX + xx
                if (blockSeen[i] != 0L) continue
                val d = maxOf(Math.abs(dx), Math.abs(dy))
                val dl = dist(i)
                if (d < bd || (d == bd && dl < bl)) { bd = d; bl = dl; best = i }
            }
        }
        if (best >= 0) note = "frontier"
        // 2) stale blocks around castles
        if (best < 0) {
            var bt = Long.MAX_VALUE
            for (i in active) {
                val t = blockSeen[i]
                if (now - t >= revisitMs && t < bt) { bt = t; best = i }
            }
            if (best >= 0) note = if (bt == 0L) "never" else "stale ${(now - bt) / 1000}s"
        }
        // 3) global sweep of never-visited blocks, nearest first
        if (best < 0) {
            var bdl = Int.MAX_VALUE
            for (i in blockSeen.indices) {
                if (blockSeen[i] != 0L) continue
                // the outer rows/columns cannot be jumped to; the screen centred on their neighbours already shows them
                if (fullMap && ((i % BX) !in C0..C1 || (i / BX) !in R0..R1)) continue
                val dl = dist(i)
                if (dl < bdl) { bdl = dl; best = i }
            }
            if (best >= 0) note = "sweep"
        }
        if (best < 0) return null
        val x = minOf((best % BX) * BLOCK + BLOCK / 2, GuidCodec.MAX_X)
        val y = minOf((best / BX) * BLOCK + BLOCK / 2, GuidCodec.MAX_Y)
        lastX = x
        lastY = y
        return Target(x, y, note)
    }

    /** A castle left this spot (relocation): stop asking to re-tap it. */
    @Synchronized
    fun forget(x: Int, y: Int) {
        castleSeen.remove(key(x, y))
        castleTapped.remove(key(x, y))
    }

    /** Castles never tapped or whose popup is older than CASTLE_TIMEOUT_MS, oldest first. */
    @Synchronized
    fun dueCastles(limit: Int = 5, now: Long = System.currentTimeMillis()): List<Target> =
        castleSeen.keys
            .map { k -> k to (castleTapped[k] ?: 0L) }
            .filter { (_, t) -> now - t >= CASTLE_TIMEOUT_MS }
            .sortedBy { it.second }
            .take(limit)
            .map { (k, t) -> Target(k / 2048, k % 2048, if (t == 0L) "لم تُفحص" else "قديمة ${(now - t) / 1000}ث") }

    /** Blocks never seen or stale, never-seen first then oldest. Coordinates = block centre. */
    @Synchronized
    fun staleBlocks(limit: Int = 5, now: Long = System.currentTimeMillis()): List<Target> {
        val out = ArrayList<Pair<Int, Long>>()
        for (i in blockSeen.indices) {
            val t = blockSeen[i]
            if (now - t >= BLOCK_TIMEOUT_MS) out.add(i to t)
        }
        out.sortBy { it.second }
        return out.take(limit).map { (i, t) ->
            val x = minOf((i % BX) * BLOCK + BLOCK / 2, GuidCodec.MAX_X)
            val y = minOf((i / BX) * BLOCK + BLOCK / 2, GuidCodec.MAX_Y)
            Target(x, y, if (t == 0L) "منطقة لم تُمسح" else "منطقة قديمة")
        }
    }

    @Synchronized
    fun report(now: Long = System.currentTimeMillis()): String {
        val covered = blockSeen.count { it != 0L && now - it < BLOCK_TIMEOUT_MS }
        val due = castleSeen.keys.count { now - (castleTapped[it] ?: 0L) >= CASTLE_TIMEOUT_MS }
        val sb = StringBuilder()
        sb.append("مناطق مغطاة: $covered / ${blockSeen.size}\n")
        sb.append("قلاع تحتاج إعادة فحص: $due")
        val next = dueCastles(3, now)
        if (next.isNotEmpty()) {
            sb.append("\nالتالي: ").append(next.joinToString(" | ") { "${it.x},${it.y} (${it.note})" })
        }
        return sb.toString()
    }


    @Synchronized
    fun coveredBlocks(now: Long = System.currentTimeMillis()): Int = blockSeen.count { it != 0L && now - it < BLOCK_TIMEOUT_MS }

    fun totalBlocks(): Int = BX * BY

    @Synchronized
    fun dueCount(now: Long = System.currentTimeMillis()): Int =
        castleSeen.keys.count { now - (castleTapped[it] ?: 0L) >= CASTLE_TIMEOUT_MS }

    /** "التالي: x,y (note) | ..." or empty when nothing is due. */
    @Synchronized
    fun nextText(now: Long = System.currentTimeMillis()): String {
        val next = dueCastles(3, now)
        if (next.isEmpty()) return ""
        return "التالي: " + next.joinToString(" | ") { "${it.x},${it.y} (${it.note})" }
    }

    @Synchronized
    fun reset() {
        blockSeen.fill(0L)
        castleSeen.clear()
        castleTapped.clear()
        lastX = GuidCodec.MAX_X / 2
        lastY = GuidCodec.MAX_Y / 2
        sweepPos = 0
    }
}