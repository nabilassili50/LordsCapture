package com.botvi.lords

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.TargetApi
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Screenshot with coordinates expressed in the 1280x800 landscape design space used by all the button positions. */
class Shot(val w: Int, val h: Int, private val px: IntArray) {
    private val sx = w / 1280f
    private val sy = h / 800f
    val landscape: Boolean get() = w > h

    fun at(x: Int, y: Int): Int {
        val xx = (x * sx).toInt().coerceIn(0, w - 1)
        val yy = (y * sy).toInt().coerceIn(0, h - 1)
        return px[yy * w + xx]
    }

    fun count(x0: Int, y0: Int, x1: Int, y1: Int, step: Int, f: (Int, Int, Int) -> Boolean): Int {
        var n = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val c = at(x, y)
                if (f((c shr 16) and 255, (c shr 8) and 255, c and 255)) n++
                x += step
            }
            y += step
        }
        return n
    }

    /** average colour (r, g, b) of a box */
    fun avg(x0: Int, y0: Int, x1: Int, y1: Int, step: Int = 8): IntArray {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val c = at(x, y)
                r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255; n++
                x += step
            }
            y += step
        }
        if (n == 0) return intArrayOf(0, 0, 0)
        return intArrayOf((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
}

class ScanAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var inst: ScanAccessibilityService? = null
        const val GAME = "com.igg.android.lordsmobile"
    }

    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var lastPkg = ""
    @Volatile private var scaleX = 1f
    @Volatile private var scaleY = 1f

    override fun onServiceConnected() {
        super.onServiceConnected()
        inst = this
        Store.log("خدمة المسح الآلي جاهزة")
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        val p = e.packageName?.toString() ?: return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) lastPkg = p
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        AutoScan.stop()
        inst = null
        return super.onUnbind(intent)
    }

    /** true when the game is the foreground app */
    fun inGame(): Boolean {
        val p = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null } ?: lastPkg
        return p == GAME
    }

    /** One gesture made of several taps. Each point is [x, y, startMs] in design coordinates (max 10 per call). */
    fun gesture(pts: List<IntArray>): Boolean {
        if (pts.isEmpty() || pts.size > 10) return false
        val b = GestureDescription.Builder()
        var last = 0
        for (p in pts) {
            val path = Path()
            path.moveTo(p[0] * scaleX, p[1] * scaleY)
            b.addStroke(GestureDescription.StrokeDescription(path, p[2].toLong(), 45L))
            if (p[2] > last) last = p[2]
        }
        val latch = CountDownLatch(1)
        val ok = BooleanArray(1)
        val cb = object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { ok[0] = true; latch.countDown() }
            override fun onCancelled(g: GestureDescription?) { latch.countDown() }
        }
        val gd = b.build()
        main.post { if (!dispatchGesture(gd, cb, null)) latch.countDown() }
        latch.await((last + 3000).toLong(), TimeUnit.MILLISECONDS)
        return ok[0]
    }

    fun tap(x: Int, y: Int): Boolean = gesture(listOf(intArrayOf(x, y, 0)))

    /** One straight drag from (x1,y1) to (x2,y2) in design coordinates, taking [durMs] (slow enough that the game does not fling the map). */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durMs: Int): Boolean {
        val path = Path()
        path.moveTo(x1 * scaleX, y1 * scaleY)
        path.lineTo(x2 * scaleX, y2 * scaleY)
        val gd = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durMs.toLong()))
            .build()
        val latch = CountDownLatch(1)
        val ok = BooleanArray(1)
        val cb = object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { ok[0] = true; latch.countDown() }
            override fun onCancelled(g: GestureDescription?) { latch.countDown() }
        }
        main.post { if (!dispatchGesture(gd, cb, null)) latch.countDown() }
        latch.await((durMs + 3000).toLong(), TimeUnit.MILLISECONDS)
        return ok[0]
    }

    /** Takes a screenshot (Android 11+); retries because the system limits how often it can be called. */
    @TargetApi(30)
    fun shot(): Shot? {
        if (Build.VERSION.SDK_INT < 30) return null
        repeat(4) {
            val latch = CountDownLatch(1)
            val holder = arrayOfNulls<Shot>(1)
            takeScreenshot(Display.DEFAULT_DISPLAY, exec, object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    try {
                        val hb = r.hardwareBuffer
                        val bm = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)
                        val soft = bm?.copy(Bitmap.Config.ARGB_8888, false)
                        hb.close()
                        if (soft != null) {
                            val px = IntArray(soft.width * soft.height)
                            soft.getPixels(px, 0, soft.width, 0, 0, soft.width, soft.height)
                            holder[0] = Shot(soft.width, soft.height, px)
                        }
                    } catch (_: Throwable) {
                    }
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) { latch.countDown() }
            })
            latch.await(3, TimeUnit.SECONDS)
            val s = holder[0]
            if (s != null) {
                scaleX = s.w / 1280f
                scaleY = s.h / 800f
                return s
            }
            try { Thread.sleep(400) } catch (_: InterruptedException) { return null }
        }
        return null
    }
}

// ---------- screen detectors (same colours/regions as the Termux scanner, design space 1280x800) ----------
private fun teal(r: Int, g: Int, b: Int) = r < 75 && g in 111..174 && b > 140 && b > r + 80
private fun keyTeal(r: Int, g: Int, b: Int) = r < 85 && g in 101..149 && b in 116..174 && b > r + 55
private fun dialogOpen(s: Shot) = s.count(440, 415, 840, 460, 3, ::teal) > 1200       // "انتقال" button of the search dialog
private fun keypadOpen(s: Shot) = s.count(150, 300, 430, 575, 3, ::keyTeal) > 1500    // in-game number pad
private fun isMap(s: Shot): Boolean { val a = s.avg(1140, 720, 1255, 775); return a[2] > a[0] + 12 }
private fun isCity(s: Shot): Boolean { val a = s.avg(1140, 720, 1255, 775); return a[0] > a[2] + 30 }

/** Automatic map scan: visits the blocks ScanPlanner asks for, using the game's coordinate-search dialog. */
object AutoScan {
    @Volatile var running = false
    @Volatile var status = "متوقف"
    @Volatile var stopOnWatch = true
    @Volatile var dragMode = true             // true: pan the map by dragging, row after row; false: jump with the coordinate dialog
    @Volatile var dragDir = 1                 // 1: finger moves left -> view moves east (+X); -1 reverses it
    @Volatile var unitsPerDrag = 16.0         // map units the view moves per drag, learned from the castles that arrive
    private const val DRAG_PX = 360           // design pixels (of 1280) per drag: ~28% of the screen width, so every drag overlaps the previous view
    private const val MAX_DRAGS = 90
    private var ackXY = -1
    private var th: Thread? = null
    @Volatile private var slow = 1.0
    private val fails = HashMap<Int, Int>()

    private const val MIN_C = 24   // the map edge rows/columns (0..23) were rejected by the game's number pad / dialog
    private const val MAX_X = 508
    private const val MAX_Y = 1020
    private const val DEAD_STRIKES = 3          // consecutive checks that say the capture is down before we stop
    private const val DEAD_IDLE_MS = 300_000L   // no game frame at all for 5 min while the status is not "يلتقط"

    private val KEYS = mapOf(
        '1' to intArrayOf(192, 330), '2' to intArrayOf(289, 330), '3' to intArrayOf(387, 330),
        '4' to intArrayOf(192, 401), '5' to intArrayOf(289, 401), '6' to intArrayOf(387, 401),
        '7' to intArrayOf(192, 472), '8' to intArrayOf(289, 472), '9' to intArrayOf(387, 472),
        '0' to intArrayOf(216, 543)
    )

    /** returns null when started, otherwise a message to show the user */
    fun start(): String? {
        if (running) return null
        if (Build.VERSION.SDK_INT < 30) return "يلزم أندرويد 11 أو أحدث"
        if (ScanAccessibilityService.inst == null) return "فعّل خدمة «Lords Capture — المسح الآلي» من إعدادات إمكانية الوصول أولاً"
        running = true
        slow = 1.0
        fails.clear()
        status = "يبدأ…"
        th = Thread {
            try { if (dragMode) dragLoop() else loop() } catch (e: Throwable) { Store.log("المسح الآلي: ${e.javaClass.simpleName} ${e.message}") }
            Store.log("المسح الآلي: انتهى — آخر حالة: $status")
            running = false
            val last = status
            status = if (last == "متوقف" || last.startsWith("وجدت")) last else "متوقف — $last"
        }.also { it.isDaemon = true; it.start() }
        return null
    }

    fun stop() {
        running = false
        status = "متوقف"
    }

    /** sleeps in short slices so the stop button takes effect within ~0.1 s instead of after the current wait */
    private fun sleep(ms: Int) {
        try {
            var left = ms
            while (left > 0 && running) {
                val d = minOf(left, 100)
                Thread.sleep(d.toLong())
                left -= d
            }
        } catch (_: InterruptedException) { running = false }
    }

    private fun loop() {
        var visited = 0
        var quiet = 0
        var bad = 0
        var mprev = Store.stats().mapFrames
        var deadN = 0
        var lastGood: IntArray? = null   // last coordinates the game accepted: used to tell "rejected coordinates" from "stuck game"
        val startMs = System.currentTimeMillis()
        var known = false   // after a successful jump we are certainly on the map: colour checks are unreliable over sea/mountains
        while (running) {
            val svc = ScanAccessibilityService.inst
            if (svc == null) { status = "الخدمة غير مفعّلة"; return }
            if (checkWatch(svc, startMs)) return
            if (!svc.inGame()) { status = "في انتظار ظهور اللعبة في المقدمة…"; sleep(800); continue }
            val s0 = svc.shot()
            if (s0 == null) { status = "تعذّر التقاط الشاشة"; sleep(700); continue }
            if (!s0.landscape) { status = "الشاشة عمودية — يلزم الوضع الأفقي"; sleep(1000); continue }
            if (dialogOpen(s0) || keypadOpen(s0)) { recover(svc); continue }
            if (!known) {
                if (isMap(s0)) known = true
                else if (isCity(s0)) { svc.tap(1195, 745); sleep(3500); continue }
                else if (++bad > 3) known = true   // colour unclear (sea/mountains): assume map, a failed jump will re-check
                else { sleep(1000); continue }
            }
            val t = ScanPlanner.nextTarget()
            if (t == null) { status = "لا أهداف مستحقة الآن — ننتظر"; sleep(4000); continue }
            // at least 2 digits: single-digit coordinates (e.g. Y=8 on the first row) fail in the game's number pad
            val jx = maxOf(MIN_C, minOf(t.x, MAX_X))
            val jy = maxOf(MIN_C, minOf(t.y, MAX_Y))
            val key = t.x * 2048 + t.y
            val t0 = System.currentTimeMillis()
            if (jump(svc, jx, jy)) {
                bad = 0
                known = true
                // packets arrive asynchronously (and the game does not re-send blocks it already cached), so wait a bit
                // over sea / empty land / areas the game already cached the server sends nothing: that is normal, never a reason to stop
                val maxWait = if (quiet >= 3) 3 else 6
                var m = Store.stats().mapFrames
                var w = 0
                while (m <= mprev && w < maxWait && running) { sleep(500); w++; m = Store.stats().mapFrames }
                if (m > mprev) quiet = 0 else quiet++
                mprev = m
                lastGood = intArrayOf(jx, jy)
                val why = captureDead()
                if (why != null) {
                    if (++deadN >= DEAD_STRIKES) {
                        status = why
                        Store.log("المسح الآلي: توقف — $why")
                        return
                    }
                } else deadN = 0
                sleep(300)
                if (checkWatch(svc, startMs)) return
                ScanPlanner.markVisited(t.x, t.y)
                fails.remove(key)
                visited++
                slow = 1.0
                status = "يعمل — X:$jx Y:$jy (${visited} موقع، ${(System.currentTimeMillis() - t0) / 1000.0} ث)" +
                    (if (quiet >= 3) " • بلا حزم جديدة ($quiet): ماء/منطقة فارغة أو محمّلة سابقاً — نكمل" else "") +
                    "\n" + ScanPlanner.sweepText()
            } else {
                known = false
                slow = 1.8
                val n = (fails[key] ?: 0) + 1
                fails[key] = n
                recover(svc)
                if (n >= 2) {
                    Store.log("المسح الآلي: تخطّي X:$jx Y:$jy بعد فشلين")
                    ScanPlanner.markVisited(t.x, t.y)
                    fails.remove(key)
                }
                bad++
                val g = lastGood
                if (bad >= 6 && g != null) {
                    // many failures in a row: re-try a spot that worked before. If it works, the failing coordinates are simply rejected (sea/edge) -> skip them and go on
                    if (jump(svc, g[0], g[1])) {
                        Store.log("المسح الآلي: الإحداثيات المرفوضة تُتخطّى — الموقع السابق ما زال يعمل")
                        bad = 0
                        known = true
                        slow = 1.0
                        continue
                    }
                    recover(svc)
                }
                if (bad > 20) { status = "فشل متكرر — أوقفت المسح، راجع السجل"; return }
            }
        }
    }

    /** null while the capture is alive; otherwise why it looks dead. Silence over the sea is NOT death: only a stopped capture counts. */
    private fun captureDead(): String? {
        val st = Store.status
        if (st.contains("متوقف")) return "الالتقاط متوقف — شغّل PCAPdroid والالتقاط ($st)"
        val last = Store.lastFrameMs
        val idle = System.currentTimeMillis() - last
        if (last > 0 && idle > DEAD_IDLE_MS && !st.startsWith("يلتقط")) return "لا حزم من اللعبة منذ ${idle / 1000} ث (تحقق من PCAPdroid)"
        return null
    }

    private fun pct95(a: IntArray): Int = a[minOf(a.size - 1, (a.size * 95) / 100)]

    /**
     * Whole-kingdom scan by dragging: for each map row the view is anchored once with the coordinate dialog (exact position),
     * then dragged east in overlapping steps until the east edge. Nothing here depends on land or sea; silence is not a reason to stop.
     */
    private fun dragLoop() {
        var mprev = Store.stats().mapFrames
        var deadN = 0
        var bad = 0
        var rowFails = 0
        var rows = 0
        var quiet = 0
        var swipeFails = 0
        val startMs = System.currentTimeMillis()
        while (running) {
            val svc = ScanAccessibilityService.inst
            if (svc == null) { status = "الخدمة غير مفعّلة"; return }
            if (checkWatch(svc, startMs)) return
            if (!svc.inGame()) { status = "في انتظار ظهور اللعبة في المقدمة…"; sleep(800); continue }
            val s0 = svc.shot()
            if (s0 == null) { status = "تعذّر التقاط الشاشة"; sleep(700); continue }
            if (!s0.landscape) { status = "الشاشة عمودية — يلزم الوضع الأفقي"; sleep(1000); continue }
            if (dialogOpen(s0) || keypadOpen(s0)) { recover(svc); continue }
            if (isCity(s0)) { svc.tap(1195, 745); sleep(3500); continue }
            val row = ScanPlanner.nextDragRow()
            if (row == null) {
                status = "اكتمل مسح كل المملكة ✔ — اضغط «مسح جديد لكل الخريطة» لإعادة المسح"
                Store.log("المسح الآلي: $status")
                return
            }
            val jx = maxOf(MIN_C, minOf(row[1], MAX_X))
            val jy = maxOf(MIN_C, minOf(row[0], MAX_Y))
            // 1) anchor the row at its first unseen block
            if (!jump(svc, jx, jy)) {
                slow = 1.8
                recover(svc)
                rowFails++
                bad++
                if (rowFails >= 2) {
                    Store.log("المسح الآلي: تخطّي X:$jx Y:$jy بعد فشلين")
                    ScanPlanner.markVisited(row[1], row[0])
                    rowFails = 0
                }
                if (bad > 20) { status = "فشل متكرر — أوقفت المسح، راجع السجل"; return }
                continue
            }
            bad = 0
            rowFails = 0
            slow = 1.0
            rows++
            var x = jx.toDouble()
            ScanPlanner.markVisited(jx, jy)
            sleep(1500)
            // 2) drag east across the row
            var drags = 0
            var edgePrev = -1
            var sincePrev = 0
            var noMap = 0
            while (running && x < MAX_X && drags < MAX_DRAGS) {
                if (checkWatch(svc, startMs)) return
                if (!svc.inGame()) { status = "في انتظار ظهور اللعبة في المقدمة…"; sleep(800); continue }
                val t0 = System.currentTimeMillis()
                if (!svc.swipe(950, 420, 950 - dragDir * DRAG_PX, 420, 700)) {
                    Store.log("المسح الآلي: فشلت السحبة")
                    if (++swipeFails > 15) { status = "فشل متكرر في السحب — أوقفت المسح، راجع السجل"; return }
                    break
                }
                swipeFails = 0
                drags++
                sincePrev++
                sleep(500)   // let the map settle
                var m = Store.stats().mapFrames
                var w = 0
                val maxWait = if (quiet >= 3) 4 else 8
                while (m <= mprev && w < maxWait && running) { sleep(250); w++; m = Store.stats().mapFrames }
                if (m > mprev) { quiet = 0; noMap = 0 } else { quiet++; noMap++ }
                mprev = m
                // learn how far one drag moves the view: the right edge of the castles that just arrived
                val xs = Store.castleXsSince(t0 / 1000)
                if (xs.size >= 8) {
                    val edge = pct95(xs)
                    if (edgePrev >= 0) {
                        val dd = (edge - edgePrev).toDouble() / sincePrev
                        if (dd in 3.0..80.0) unitsPerDrag = 0.7 * unitsPerDrag + 0.3 * dd
                    }
                    edgePrev = edge
                    sincePrev = 0
                }
                val prev = x
                x += unitsPerDrag
                ScanPlanner.markSpan(prev.toInt(), minOf(x.toInt(), MAX_X), jy)
                val why = captureDead()
                if (why != null) {
                    if (++deadN >= DEAD_STRIKES) { status = why; Store.log("المسح الآلي: توقف — $why"); return }
                } else deadN = 0
                status = "سحب — صف Y:$jy • X≈${x.toInt()} • صف رقم $rows (${Math.round(unitsPerDrag * 10) / 10.0} وحدة/سحبة)" +
                    (if (noMap >= 6) " • لا حزم جديدة: ماء/منطقة فارغة، أو اتجاه السحب معكوس («↔ اتجاه السحب»)" else "") +
                    "\n" + ScanPlanner.sweepText()
                if (drags % 5 == 0) {
                    val s = svc.shot()
                    if (s != null && (dialogOpen(s) || keypadOpen(s))) { recover(svc); break }
                    if (s != null && isCity(s)) { svc.tap(1195, 745); sleep(3500); break }
                }
            }
        }
    }

    /** a watched player's castle was just captured: centre the view on it and stop */
    private fun checkWatch(svc: ScanAccessibilityService, startMs: Long): Boolean {
        if (!stopOnWatch) return false
        val h = Store.watchHit ?: return false
        if (h.atMs < startMs) return false
        val k = h.x * 2048 + h.y
        if (k == ackXY) return false   // already stopped at this castle; continue after the user restarts
        ackXY = k
        status = "وجدت ${h.name} عند X:${h.x} Y:${h.y} K${h.realm}"
        Store.log("المسح الآلي: $status — توقفت هناك")
        try { jump(svc, maxOf(MIN_C, minOf(h.x, MAX_X)), maxOf(MIN_C, minOf(h.y, MAX_Y))) } catch (_: Throwable) {}
        return true
    }

    private fun jump(svc: ScanAccessibilityService, x: Int, y: Int): Boolean {
        val k = slow
        fun d(ms: Int) = (ms * k).toInt()
        // 1) magnifier -> Y field -> digits -> OK
        val g1 = ArrayList<IntArray>()
        g1.add(intArrayOf(683, 93, 0))
        var t = d(650)
        g1.add(intArrayOf(476, 298, t))
        t += d(500)
        for (c in y.toString()) { val p = KEYS[c] ?: return false; g1.add(intArrayOf(p[0], p[1], t)); t += d(140) }
        g1.add(intArrayOf(363, 543, t + d(20)))
        if (!svc.gesture(g1)) return false
        if (!running) return false
        sleep(d(300))
        var s = svc.shot() ?: return false
        if (keypadOpen(s)) { sleep(d(500)); s = svc.shot() ?: return false }
        if (keypadOpen(s) || !dialogOpen(s)) { Store.log("المسح الآلي: فشل إدخال Y=$y"); return false }
        // 2) X field -> digits -> OK -> Go
        val g2 = ArrayList<IntArray>()
        g2.add(intArrayOf(612, 298, 0))
        t = d(500)
        for (c in x.toString()) { val p = KEYS[c] ?: return false; g2.add(intArrayOf(p[0], p[1], t)); t += d(140) }
        g2.add(intArrayOf(363, 543, t + d(20)))
        g2.add(intArrayOf(637, 444, t + d(20) + d(380)))
        if (!svc.gesture(g2)) return false
        if (!running) return false
        sleep(d(1500))
        s = svc.shot() ?: return false
        if (dialogOpen(s)) { sleep(d(800)); s = svc.shot() ?: return false }
        if (dialogOpen(s) || keypadOpen(s)) { Store.log("المسح الآلي: لم تُغلق نافذة البحث عند X=$x Y=$y"); return false }
        return true
    }

    /** closes a stuck number pad or search dialog */
    private fun recover(svc: ScanAccessibilityService) {
        repeat(3) {
            val s = svc.shot() ?: return
            when {
                keypadOpen(s) -> svc.tap(363, 543)
                dialogOpen(s) -> svc.tap(385, 130)
                else -> return
            }
            sleep(900)
        }
    }
}
