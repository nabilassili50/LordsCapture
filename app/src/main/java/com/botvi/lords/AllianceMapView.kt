package com.botvi.lords

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Draws every captured castle as a dot, coloured by alliance tag, auto-zoomed to the area that has data. */
class AllianceMapView(ctx: Context) : View(ctx) {
    var points: List<Store.Pt> = emptyList()

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
        if (points.isEmpty()) {
            txt.color = Color.parseColor("#8D98B3")
            canvas.drawText("لا توجد قلاع بعد — حرّك الخريطة في اللعبة", w / 2, h / 2, txt)
            return
        }
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val rx = maxOf(maxX - minX, 20).toFloat()
        val ry = maxOf(maxY - minY, 20).toFloat()
        val pad = 22f * d
        val scale = minOf((w - 2 * pad) / rx, (h - 2 * pad) / ry)
        val ox = (w - rx * scale) / 2
        val oy = (h - ry * scale) / 2
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
        for (p in points) {
            dotPaint.color = colorOf(p.tag)
            canvas.drawCircle(px(p.x), py(p.y), r, dotPaint)
        }
        for (tag in top) {
            val mine = points.filter { it.tag == tag }
            val cx = mine.map { it.x }.average().toFloat()
            val cy = mine.map { it.y }.average().toFloat()
            txt.color = colorOf(tag)
            canvas.drawText(tag + " " + mine.size, ox + (cx - minX) * scale, oy + (cy - minY) * scale - 8f * d, txt)
        }
        canvas.drawText("x $minX–$maxX   y $minY–$maxY   (${points.size} قلعة)", 8f * d, h - 6f * d, note)
    }
}
