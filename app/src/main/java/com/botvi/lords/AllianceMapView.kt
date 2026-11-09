package com.botvi.lords

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration

/**
 * Draws every captured castle as a dot, coloured by alliance tag.
 * Zoom: pinch, drag to pan, or the +/- buttons (levels x1..x24). Tap a dot/label to select its alliance.
 */
class AllianceMapView(ctx: Context) : View(ctx) {
    var points: List<Store.Pt> = emptyList()
    var forts: List<Store.Pt> = emptyList()
    var selected: String? = null
    var highlight: Store.Pt? = null
    var onSelect: ((String?) -> Unit)? = null
    var onZoom: ((Float) -> Unit)? = null

    var zoom = 1f
        private set
    private var panX = 0f
    private var panY = 0f

    // base (zoom = 1) transform, refreshed on every draw
    private var tOx = 0f; private var tOy = 0f; private var tScale = 1f; private var tMinX = 0; private var tMinY = 0
    private var haveBase = false
    private val labelHits = ArrayList<Pair<RectF, String>>()

    private val d = ctx.resources.displayMetrics.density
    private val palette = intArrayOf(
        Color.parseColor("#4C8DFF"), Color.parseColor("#35C37D"), Color.parseColor("#FFB020"),
        Color.parseColor("#FF5A5F"), Color.parseColor("#B07CFF"), Color.parseColor("#2EC4D6"),
        Color.parseColor("#FF8FB1"), Color.parseColor("#C8D65A")
    )
    private val grey = Color.parseColor("#5B6684")
    private val bgPaint = Paint().apply { isAntiAlias = true; color = Color.parseColor("#11182A"); style = Paint.Style.FILL }
    private val gridPaint = Paint().apply { color = Color.parseColor("#1E2740"); strokeWidth = 1f }
    private val axis = Paint().apply { isAntiAlias = true; color = Color.parseColor("#5B6684"); textSize = 9f * d }
    private val dotPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val txt = Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER; textSize = 11f * d }
    private val note = Paint().apply { isAntiAlias = true; color = Color.parseColor("#8D98B3"); textSize = 10f * d }

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f; private var downY = 0f; private var lastX = 0f; private var lastY = 0f
    private var moved = false
    private var scaling = false
    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { scaling = true; return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomAt(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    // ---- transform ----
    private fun sx(x: Int): Float = (tOx + (x - tMinX) * tScale - width / 2f) * zoom + width / 2f + panX
    private fun sy(y: Int): Float = (tOy + (y - tMinY) * tScale - height / 2f) * zoom + height / 2f + panY

    private fun clampPan() {
        if (zoom <= 1.001f) { panX = 0f; panY = 0f; return }
        val mx = (zoom - 1f) * width / 2f + 40f * d
        val my = (zoom - 1f) * height / 2f + 40f * d
        panX = panX.coerceIn(-mx, mx)
        panY = panY.coerceIn(-my, my)
    }

    private fun zoomAt(f: Float, fx: Float, fy: Float) {
        val z1 = (zoom * f).coerceIn(1f, 24f)
        val cx = width / 2f; val cy = height / 2f
        panX = fx - cx - (fx - cx - panX) * z1 / zoom
        panY = fy - cy - (fy - cy - panY) * z1 / zoom
        zoom = z1
        clampPan()
        onZoom?.invoke(zoom)
        invalidate()
    }

    fun zoomIn() = zoomAt(2f, width / 2f, height / 2f)
    fun zoomOut() = zoomAt(0.5f, width / 2f, height / 2f)

    fun resetZoom() {
        zoom = 1f; panX = 0f; panY = 0f
        onZoom?.invoke(zoom)
        invalidate()
    }

    /** Zoom in and centre the map on a castle. */
    fun focus(p: Store.Pt, z: Float = 6f) {
        if (!haveBase) return
        zoom = maxOf(zoom, z).coerceAtMost(24f)
        val bx = tOx + (p.x - tMinX) * tScale
        val by = tOy + (p.y - tMinY) * tScale
        panX = -(bx - width / 2f) * zoom
        panY = -(by - height / 2f) * zoom
        clampPan()
        onZoom?.invoke(zoom)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRoundRect(RectF(0f, 0f, w, h), 12f * d, 12f * d, bgPaint)
        if (points.isEmpty() && forts.isEmpty()) {
            txt.color = Color.parseColor("#8D98B3")
            canvas.drawText("لا توجد قلاع بعد — حرّك الخريطة في اللعبة", w / 2, h / 2, txt)
            return
        }
        val all = points + forts
        val minX = all.minOf { it.x }
        val maxX = all.maxOf { it.x }
        val minY = all.minOf { it.y }
        val maxY = all.maxOf { it.y }
        val rx = maxOf(maxX - minX, 20).toFloat()
        val ry = maxOf(maxY - minY, 20).toFloat()
        val pad = 22f * d
        val scale = minOf((w - 2 * pad) / rx, (h - 2 * pad) / ry)
        tOx = (w - rx * scale) / 2
        tOy = (h - ry * scale) / 2
        tScale = scale; tMinX = minX; tMinY = minY; haveBase = true
        fun px(x: Int) = sx(x)
        fun py(y: Int) = sy(y)

        canvas.save()
        canvas.clipRect(0f, 0f, w, h)

        // grid: finer with zoom, coordinate numbers from x2
        val vis = maxOf(rx, ry) / zoom
        val step = when { vis > 200 -> 50; vis > 80 -> 20; vis > 30 -> 10; vis > 12 -> 5; else -> 2 }
        var gx = (minX / step) * step
        while (gx <= maxX + step) {
            val s = px(gx)
            if (s >= -1f && s <= w + 1f) {
                canvas.drawLine(s, 0f, s, h, gridPaint)
                if (zoom >= 2f) canvas.drawText(gx.toString(), s + 2f * d, 10f * d, axis)
            }
            gx += step
        }
        var gy = (minY / step) * step
        while (gy <= maxY + step) {
            val s = py(gy)
            if (s >= -1f && s <= h + 1f) {
                canvas.drawLine(0f, s, w, s, gridPaint)
                if (zoom >= 2f) canvas.drawText(gy.toString(), 2f * d, s - 2f * d, axis)
            }
            gy += step
        }

        // top alliances (by castle count) get a colour and a label
        val counts = HashMap<String, Int>()
        for (p in points) if (p.tag.isNotEmpty()) counts[p.tag] = (counts[p.tag] ?: 0) + 1
        val top = counts.entries.sortedByDescending { it.value }.take(palette.size).map { it.key }
        fun colorOf(tag: String): Int {
            val i = top.indexOf(tag)
            return if (i >= 0) palette[i] else grey
        }
        val r = (3.5f + (zoom - 1f) * 0.4f).coerceAtMost(7f) * d
        val sel = selected

        // fortresses: hollow diamond in the colour of the owning alliance (white if unknown)
        val fp = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 2f * d }
        val dia = Path()
        for (f in forts) {
            val fc = if (f.tag.isEmpty()) Color.WHITE else colorOf(f.tag)
            fp.color = if (sel != null && f.tag != sel) (fc and 0x00FFFFFF) or (60 shl 24) else fc
            val cx = px(f.x); val cy = py(f.y); val q = 6f * d
            dia.reset(); dia.moveTo(cx, cy - q); dia.lineTo(cx + q, cy); dia.lineTo(cx, cy + q); dia.lineTo(cx - q, cy); dia.close()
            canvas.drawPath(dia, fp)
        }

        if (sel != null) {
            for (p in points) if (p.tag != sel) {
                dotPaint.color = (colorOf(p.tag) and 0x00FFFFFF) or (60 shl 24)
                canvas.drawCircle(px(p.x), py(p.y), r, dotPaint)
            }
            val ring = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Color.WHITE }
            for (p in points) if (p.tag == sel) {
                dotPaint.color = colorOf(p.tag)
                canvas.drawCircle(px(p.x), py(p.y), r * 1.6f, dotPaint)
                canvas.drawCircle(px(p.x), py(p.y), r * 1.6f, ring)
            }
        } else {
            for (p in points) {
                dotPaint.color = colorOf(p.tag)
                canvas.drawCircle(px(p.x), py(p.y), r, dotPaint)
            }
        }

        labelHits.clear()
        for (tag in (if (sel != null) listOf(sel) else top)) {
            val mine = points.filter { it.tag == tag }
            if (mine.isEmpty()) continue
            val mx = mine.map { it.x }.sorted()[mine.size / 2]     // median: robust against far-away members
            val my = mine.map { it.y }.sorted()[mine.size / 2]
            txt.color = colorOf(tag)
            val lx = px(mx)
            val ly = py(my)
            val title = tag + " " + mine.size
            canvas.drawText(title, lx, ly - 20f * d, txt)
            canvas.drawText("($mx,$my)", lx, ly - 8f * d, txt)
            val tw = txt.measureText(title)
            labelHits.add(RectF(lx - tw / 2 - 8f * d, ly - 36f * d, lx + tw / 2 + 8f * d, ly - 4f * d) to tag)
        }

        highlight?.let { hp ->
            val hx = px(hp.x); val hy = py(hp.y)
            val hr = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = Color.parseColor("#FFE14D") }
            canvas.drawCircle(hx, hy, 11f * d, hr)
            canvas.drawLine(hx - 17f * d, hy, hx - 11f * d, hy, hr); canvas.drawLine(hx + 11f * d, hy, hx + 17f * d, hy, hr)
            canvas.drawLine(hx, hy - 17f * d, hx, hy - 11f * d, hr); canvas.drawLine(hx, hy + 11f * d, hx, hy + 17f * d, hr)
            txt.color = Color.parseColor("#FFE14D")
            val label = hp.name + " (" + hp.x + "," + hp.y + ")"
            val lw = txt.measureText(label)
            val lx = hx.coerceIn(lw / 2 + 4f * d, w - lw / 2 - 4f * d)
            canvas.drawText(label, lx, (hy - 22f * d).coerceAtLeast(14f * d), txt)
        }
        canvas.restore()

        val zt = if (zoom >= 10f) zoom.toInt().toString() else String.format(java.util.Locale.US, "%.1f", zoom)
        canvas.drawText("x $minX–$maxX   y $minY–$maxY   (${points.size} قلعة • ${forts.size} حصن)  ×$zt", 8f * d, h - 6f * d, note)
    }

    private fun handleTap(x: Float, y: Float) {
        var hit: String? = labelHits.firstOrNull { it.first.contains(x, y) }?.second
        if (hit == null) {
            var best = 24f * d * 24f * d
            for (p in points) {
                if (p.tag.isEmpty()) continue
                val dx = sx(p.x) - x
                val dy = sy(p.y) - y
                val dd = dx * dx + dy * dy
                if (dd < best) { best = dd; hit = p.tag }
            }
        }
        val next = if (hit == null || hit == selected) null else hit
        selected = next
        onSelect?.invoke(next)
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                moved = false; scaling = false
                parent?.requestDisallowInterceptTouchEvent(zoom > 1.001f)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                moved = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaling && e.pointerCount == 1) {
                    if (!moved && Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) > slop) moved = true
                    if (moved) {
                        panX += e.x - lastX
                        panY += e.y - lastY
                        clampPan()
                        invalidate()
                    }
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val i = if (e.actionIndex == 0) 1 else 0
                if (i < e.pointerCount) { lastX = e.getX(i); lastY = e.getY(i) }
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && !scaling) handleTap(e.x, e.y)
                scaling = false
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> scaling = false
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
