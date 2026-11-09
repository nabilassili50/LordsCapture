package com.botvi.lords

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** Draws every captured castle as a dot, coloured by alliance tag, auto-zoomed to the area that has data. */
class AllianceMapView(ctx: Context) : View(ctx) {
    var points: List<Store.Pt> = emptyList()
    var forts: List<Store.Pt> = emptyList()
    var selected: String? = null
    var onSelect: ((String?) -> Unit)? = null

    private var tOx = 0f; private var tOy = 0f; private var tScale = 1f; private var tMinX = 0; private var tMinY = 0
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
    private val dotPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val txt = Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER; textSize = 11f * d }
    private val note = Paint().apply { isAntiAlias = true; color = Color.parseColor("#8D98B3"); textSize = 10f * d }

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
        val ox = (w - rx * scale) / 2
        val oy = (h - ry * scale) / 2
        tOx = ox; tOy = oy; tScale = scale; tMinX = minX; tMinY = minY
        fun px(x: Int) = ox + (x - minX) * scale
        fun py(y: Int) = oy + (y - minY) * scale

        val step = if (rx > 200 || ry > 200) 50 else if (rx > 80 || ry > 80) 20 else 10
        var gx = (minX / step) * step
        while (gx <= maxX + step) { canvas.drawLine(px(gx), 0f, px(gx), h, gridPaint); gx += step }
        var gy = (minY / step) * step
        while (gy <= maxY + step) { canvas.drawLine(0f, py(gy), w, py(gy), gridPaint); gy += step }

        // top alliances (by castle count) get a colour and a label
        val counts = HashMap<String, Int>()
        for (p in points) if (p.tag.isNotEmpty()) counts[p.tag] = (counts[p.tag] ?: 0) + 1
        val top = counts.entries.sortedByDescending { it.value }.take(palette.size).map { it.key }
        fun colorOf(tag: String): Int {
            val i = top.indexOf(tag)
            return if (i >= 0) palette[i] else grey
        }
        val r = 3.5f * d
        // fortresses: hollow diamond in the colour of the owning alliance (white if unknown)
        val fp = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 2f * d }
        val dia = Path()
        for (f in forts) {
            val fc = if (f.tag.isEmpty()) Color.WHITE else colorOf(f.tag)
            fp.color = if (selected != null && f.tag != selected) (fc and 0x00FFFFFF) or (60 shl 24) else fc
            val cx = px(f.x); val cy = py(f.y); val q = 6f * d
            dia.reset(); dia.moveTo(cx, cy - q); dia.lineTo(cx + q, cy); dia.lineTo(cx, cy + q); dia.lineTo(cx - q, cy); dia.close()
            canvas.drawPath(dia, fp)
        }
        val sel = selected
        for (p in points) {
            val c = colorOf(p.tag)
            if (sel != null && p.tag != sel) {
                dotPaint.color = (c and 0x00FFFFFF) or (60 shl 24)
                canvas.drawCircle(px(p.x), py(p.y), r, dotPaint)
            }
        }
        if (sel != null) {
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
            val lx = ox + (mx - minX) * scale
            val ly = oy + (my - minY) * scale
            if (mine.isEmpty()) continue
            val title = tag + " " + mine.size
            canvas.drawText(title, lx, ly - 20f * d, txt)
            val tw = txt.measureText(title)
            labelHits.add(RectF(lx - tw / 2 - 8f * d, ly - 36f * d, lx + tw / 2 + 8f * d, ly - 4f * d) to tag)
            canvas.drawText("($mx,$my)", lx, ly - 8f * d, txt)
        }
        canvas.drawText("x $minX–$maxX   y $minY–$maxY   (${points.size} قلعة • ${forts.size} حصن)", 8f * d, h - 6f * d, note)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                var hit: String? = labelHits.firstOrNull { it.first.contains(e.x, e.y) }?.second
                if (hit == null) {
                    var best = 24f * d * 24f * d
                    for (p in points) {
                        if (p.tag.isEmpty()) continue
                        val dx = tOx + (p.x - tMinX) * tScale - e.x
                        val dy = tOy + (p.y - tMinY) * tScale - e.y
                        val dd = dx * dx + dy * dy
                        if (dd < best) { best = dd; hit = p.tag }
                    }
                }
                val next = if (hit == null || hit == selected) null else hit
                selected = next
                onSelect?.invoke(next)
                invalidate()
                performClick()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()
}
