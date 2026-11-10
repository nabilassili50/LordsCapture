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

    /**
     * Next map spot for the scanning bot: the oldest block that is not fresher than [revisitMs].
     * Once castles are known only blocks around them are cycled (so movement is caught fast);
     * with nothing known yet the whole map is swept row by row.
     */
    @Synchronized
    fun nextTarget(revisitMs: Long = 60_000L, now: Long = System.currentTimeMillis()): Target? {
        val active = java.util.TreeSet<Int>()
        for (k in castleSeen.keys) {
            val bx = (k / 2048) / BLOCK
            val by = (k % 2048) / BLOCK
            for (dx in -1..1) for (dy in -1..1) {
                val xx = bx + dx; val yy = by + dy
                if (xx in 0 until BX && yy in 0 until BY) active.add(yy * BX + xx)
            }
        }
        var best = -1
        var bt = Long.MAX_VALUE
        if (active.isEmpty()) {
            for (i in blockSeen.indices) {
                val t = blockSeen[i]
                if (now - t >= revisitMs && t < bt) { bt = t; best = i }
            }
        } else {
            for (i in active) {
                val t = blockSeen[i]
                if (now - t >= revisitMs && t < bt) { bt = t; best = i }
            }
        }
        if (best < 0) return null
        val x = minOf((best % BX) * BLOCK + BLOCK / 2, GuidCodec.MAX_X)
        val y = minOf((best / BX) * BLOCK + BLOCK / 2, GuidCodec.MAX_Y)
        return Target(x, y, if (bt == 0L) "never" else "stale ${(now - bt) / 1000}s")
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
    }
}
