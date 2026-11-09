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
    fun reset() {
        blockSeen.fill(0L)
        castleSeen.clear()
        castleTapped.clear()
    }
}
