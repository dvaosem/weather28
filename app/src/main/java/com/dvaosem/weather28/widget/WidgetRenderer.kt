package com.dvaosem.weather28.widget

import android.content.Context
import android.graphics.*
import android.util.TypedValue
import kotlin.math.min

/**
 * Vykreslí transparentný widget Weather28 ako Bitmap (Canvas -> ImageView).
 * Pozadie ostáva číre; čitateľnosť drží jemný gradient pod textom + tieň.
 */
object WidgetRenderer {

    data class Day(
        val temp: Double,   // veľké číslo (dnes = aktuálna, zajtra = max)
        val max: Double,
        val min: Double,
        val code: Int       // WMO weathercode -> ghost ikona
    )

    data class WidgetData(
        val location: String,
        val today: Day,
        val tomorrow: Day
    )

    // ---- teplota -> farba (1:1 s weather28.html, °C) ----
    private fun tempColor(t: Double): Int = when {
        t <= -10 -> 0xFF4D9FFF.toInt()
        t <= 0   -> 0xFFA0D4FF.toInt()
        t <= 5   -> 0xFFE0F0FF.toInt()
        t <= 15  -> 0xFFFFFFFF.toInt()
        t <= 20  -> 0xFFFFE066.toInt()
        t <= 28  -> 0xFFFFAA00.toInt()
        else     -> 0xFFFF4D6D.toInt()
    }

    private const val DNES   = 0xFF7DF2C0.toInt()
    private const val ZAJTRA = 0xFFC9A6FF.toInt()
    private const val WHITE  = 0xFFFFFFFF.toInt()

    fun render(ctx: Context, w: Int, h: Int, d: WidgetData): Bitmap {
        val bmp = Bitmap.createBitmap(max(w, 1), max(h, 1), Bitmap.Config.ARGB_8888) // číry
        val c = Canvas(bmp)
        val dp = { v: Float -> v * ctx.resources.displayMetrics.density }

        // škálovací faktor podľa výšky (aby to sedelo pri rôznych veľkostiach widgetu)
        val k = h / dp(120f)               // 120dp = referenčná výška návrhu
        fun s(v: Float) = dp(v) * k

        val colW = w / 2f
        val padX = s(16f)

        // ---- lokálny gradient pod každým stĺpcom ----
        for (i in 0..1) {
            val cx = if (i == 0) colW * 0.34f else colW + colW * 0.34f
            val cy = h - s(20f)
            val r  = min(w, h) * 0.8f
            val g = RadialGradient(
                cx, cy, r,
                intArrayOf(0x8C06080E.toInt(), 0x4D06080E.toInt(), 0x0006080E),
                floatArrayOf(0f, 0.45f, 0.8f),
                Shader.TileMode.CLAMP
            )
            c.drawRect(i * colW, 0f, (i + 1) * colW, h.toFloat(), Paint().apply { shader = g })
        }

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        fun shadow(p: Paint) = p.setShadowLayer(s(5f), 0f, s(2f), 0xD9000000.toInt())

        // ---- lokalita (hore stred) ----
        text.apply {
            color = WHITE; textSize = s(12f); letterSpacing = 0.22f
            textAlign = Paint.Align.CENTER; shadow(this)
        }
        c.drawText(d.location.uppercase(), w / 2f, s(16f), text)

        drawCol(c, d.today,   "DNES",   DNES,   0f,    colW, h, padX, ::s, text)
        drawCol(c, d.tomorrow, "ZAJTRA", ZAJTRA, colW, colW, h, padX, ::s, text)
        return bmp
    }

    private fun drawCol(
        c: Canvas, day: Day, label: String, labelColor: Int,
        x0: Float, colW: Float, h: Int, padX: Float,
        s: (Float) -> Float, text: Paint
    ) {
        val left = x0 + padX
        val baseY = h - s(14f)

        // min / max (dole)
        text.apply { textAlign = Paint.Align.LEFT; letterSpacing = 0f; textSize = s(19f)
            setShadowLayer(s(4f), 0f, s(1f), 0xCC000000.toInt()) }
        val hi = "${day.max.toInt()}°"
        val sep = " / "
        text.color = tempColor(day.max); c.drawText(hi, left, baseY, text)
        var adv = text.measureText(hi)
        text.color = 0x66FFFFFF; c.drawText(sep, left + adv, baseY, text)
        adv += text.measureText(sep)
        text.color = tempColor(day.min); c.drawText("${day.min.toInt()}°", left + adv, baseY, text)

        // veľká teplota + ghost ikona za ňou
        val tempY = baseY - s(30f)
        val ghostCx = x0 + colW - s(26f)
        drawGhostIcon(c, day.code, ghostCx, tempY - s(22f), s(40f))

        text.apply { color = tempColor(day.temp); textSize = s(66f); letterSpacing = -0.03f
            setShadowLayer(s(8f), 0f, s(2f), 0xD9000000.toInt()) }
        val big = "${day.temp.toInt()}"
        c.drawText(big, left, tempY, text)
        val bigW = text.measureText(big)
        text.textSize = s(28f)
        c.drawText("°", left + bigW + s(2f), tempY - s(34f), text)

        // label (dnes/zajtra) nad teplotou
        text.apply { color = labelColor; textSize = s(11f); letterSpacing = 0.16f
            setShadowLayer(s(3f), 0f, s(1f), 0xCC000000.toInt()) }
        c.drawText(label, left, tempY - s(48f), text)
    }

    // jednoduchá biela ghost ikona podľa WMO kódu
    private fun drawGhostIcon(c: Canvas, code: Int, cx: Float, cy: Float, r: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x57FFFFFF; style = Paint.Style.FILL }
        when {
            code <= 1 -> c.drawCircle(cx, cy, r * 0.55f, p) // jasno – slnko
            code == 2 -> { c.drawCircle(cx + r*0.2f, cy - r*0.1f, r*0.4f, p); cloud(c, cx, cy + r*0.15f, r*0.9f, p) }
            code in 95..99 -> { cloud(c, cx, cy, r, p); bolt(c, cx, cy + r*0.5f, r, p) }
            code in 71..77 || code in 85..86 -> { cloud(c, cx, cy, r, p); dots(c, cx, cy + r*0.6f, r, p) }
            code in 51..67 || code in 80..82 -> { cloud(c, cx, cy, r, p); rain(c, cx, cy + r*0.6f, r, p) }
            else -> cloud(c, cx, cy, r, p) // oblačno / hmla
        }
    }
    private fun cloud(c: Canvas, cx: Float, cy: Float, r: Float, p: Paint) {
        c.drawCircle(cx - r*0.35f, cy, r*0.42f, p)
        c.drawCircle(cx + r*0.05f, cy - r*0.18f, r*0.5f, p)
        c.drawCircle(cx + r*0.4f, cy, r*0.4f, p)
        c.drawRoundRect(cx - r*0.55f, cy, cx + r*0.55f, cy + r*0.35f, r*0.2f, r*0.2f, p)
    }
    private fun rain(c: Canvas, cx: Float, cy: Float, r: Float, p: Paint) {
        for (i in -1..1) c.drawRoundRect(cx + i*r*0.3f, cy, cx + i*r*0.3f + r*0.08f, cy + r*0.3f, r*.05f, r*.05f, p)
    }
    private fun dots(c: Canvas, cx: Float, cy: Float, r: Float, p: Paint) {
        for (i in -1..1) c.drawCircle(cx + i*r*0.3f, cy + r*0.1f, r*0.07f, p)
    }
    private fun bolt(c: Canvas, cx: Float, cy: Float, r: Float, p: Paint) {
        val path = Path().apply {
            moveTo(cx + r*0.05f, cy); lineTo(cx - r*0.15f, cy + r*0.25f)
            lineTo(cx, cy + r*0.25f); lineTo(cx - r*0.1f, cy + r*0.5f)
            lineTo(cx + r*0.2f, cy + r*0.15f); lineTo(cx + r*0.03f, cy + r*0.15f); close()
        }
        c.drawPath(path, p)
    }
    private fun max(a: Int, b: Int) = if (a > b) a else b
}
