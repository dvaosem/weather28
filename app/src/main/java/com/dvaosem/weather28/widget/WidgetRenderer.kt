package com.dvaosem.weather28.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.dvaosem.weather28.R

/**
 * Draws one day of the widget (icon + temperature + min/max) into a bitmap.
 * Launchers often ignore custom fonts and text autosizing inside RemoteViews,
 * so the widget shows these bitmaps in fitCenter ImageViews instead: the real
 * app fonts are always used and the content always scales to the widget size.
 */
object WidgetRenderer {

    private const val U = 220f // base temperature text size in px (bitmap resolution)

    private var bebas: Typeface? = null
    private var inter: Typeface? = null

    private fun bebas(ctx: Context): Typeface = bebas ?: (try {
        ResourcesCompat.getFont(ctx, R.font.bebas_neue)
    } catch (e: Exception) { null } ?: Typeface.create("sans-serif-condensed", Typeface.BOLD)).also { bebas = it }

    private fun inter(ctx: Context): Typeface = inter ?: (try {
        ResourcesCompat.getFont(ctx, R.font.inter_semibold)
    } catch (e: Exception) { null } ?: Typeface.DEFAULT_BOLD).also { inter = it }

    /**
     * @param stack true = icon above temperature (tall/square spaces),
     *              false = icon left of temperature (wide spaces).
     */
    fun renderDay(
        ctx: Context,
        iconRes: Int,
        temp: String,
        tempColor: Int,
        max: String?,
        min: String?,
        light: Boolean,
        shadow: Boolean,
        stack: Boolean
    ): Bitmap {
        val pad = U * 0.08f

        val tempPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = bebas(ctx); textSize = U; color = tempColor
            if (shadow) setShadowLayer(U * 0.05f, 0f, U * 0.015f, 0x8C000000.toInt())
        }
        val tb = Rect(); tempPaint.getTextBounds(temp, 0, temp.length, tb)
        val tempW = tb.width().toFloat(); val tempH = tb.height().toFloat()

        val hasMm = !max.isNullOrEmpty() && !min.isNullOrEmpty()
        val mmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = inter(ctx); textSize = U * 0.38f
            if (shadow) setShadowLayer(U * 0.035f, 0f, U * 0.01f, 0x8C000000.toInt())
        }
        val mmGap = U * 0.14f
        var mmW = 0f; var mmH = 0f; val mb = Rect()
        if (hasMm) {
            mmW = mmPaint.measureText(max) + mmGap + mmPaint.measureText(min)
            val s = "$max$min"; mmPaint.getTextBounds(s, 0, s.length, mb); mmH = mb.height().toFloat()
        }

        val iconSize = if (stack) tempH * 1.2f else tempH * 1.35f
        val iconGap = U * 0.05f
        val mmTopGap = if (hasMm) U * 0.18f else 0f

        val w: Float; val h: Float
        if (stack) {
            w = maxOf(iconSize, tempW, mmW) + 2 * pad
            h = iconSize + U * 0.10f + tempH + mmTopGap + mmH + 2 * pad
        } else {
            val rowW = iconSize + iconGap + tempW
            w = maxOf(rowW, mmW) + 2 * pad
            h = maxOf(iconSize, tempH) + mmTopGap + mmH + 2 * pad
        }

        val bmp = Bitmap.createBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        var y = pad
        if (stack) {
            drawIcon(ctx, c, iconRes, (w - iconSize) / 2f, y, iconSize, shadow)
            y += iconSize + U * 0.10f
            c.drawText(temp, (w - tempW) / 2f - tb.left, y - tb.top, tempPaint)
            y += tempH
        } else {
            val rowH = maxOf(iconSize, tempH)
            val x0 = (w - (iconSize + iconGap + tempW)) / 2f
            drawIcon(ctx, c, iconRes, x0, y + (rowH - iconSize) / 2f, iconSize, shadow)
            c.drawText(temp, x0 + iconSize + iconGap - tb.left, y + (rowH - tempH) / 2f - tb.top, tempPaint)
            y += rowH
        }

        if (hasMm) {
            y += mmTopGap
            val baseline = y - mb.top
            var x = (w - mmW) / 2f
            mmPaint.color = if (light) 0xFF16202C.toInt() else 0xFFFFFFFF.toInt()
            c.drawText(max!!, x, baseline, mmPaint)
            x += mmPaint.measureText(max) + mmGap
            mmPaint.color = if (light) 0xFF6B7A8C.toInt() else 0xB3FFFFFF.toInt()
            c.drawText(min!!, x, baseline, mmPaint)
        }
        return bmp
    }

    /** One line like "💧 Dážď o 15:00" — droplet drawn as a path, text in Inter. */
    fun renderRain(ctx: Context, text: String, wet: Boolean, light: Boolean, shadow: Boolean): Bitmap {
        val ts = U * 0.42f
        val color = when {
            wet && light -> 0xFF2E86E8.toInt()
            wet -> 0xFF7CC4FF.toInt()
            light -> 0xFF6B7A8C.toInt()
            else -> 0xCCFFFFFF.toInt()
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = inter(ctx); textSize = ts; this.color = color
            if (shadow) setShadowLayer(U * 0.035f, 0f, U * 0.01f, 0x8C000000.toInt())
        }
        val b = Rect(); p.getTextBounds(text, 0, text.length, b)
        val capH = b.height().toFloat()
        val dropH = capH * 1.05f; val dropW = dropH * 0.72f; val gap = ts * 0.35f
        val pad = U * 0.06f
        val w = dropW + gap + p.measureText(text) + 2 * pad
        val h = maxOf(dropH, capH) + 2 * pad
        val bmp = Bitmap.createBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // droplet: pointed top, round bottom
        val cx = pad + dropW / 2f; val top = (h - dropH) / 2f; val r = dropW / 2f
        val path = android.graphics.Path().apply {
            moveTo(cx, top)
            cubicTo(cx + r * 0.35f, top + dropH * 0.32f, cx + r, top + dropH * 0.48f, cx + r, top + dropH - r)
            arcTo(cx - r, top + dropH - 2 * r, cx + r, top + dropH, 0f, 180f, false)
            cubicTo(cx - r, top + dropH * 0.48f, cx - r * 0.35f, top + dropH * 0.32f, cx, top)
            close()
        }
        c.drawPath(path, p)
        c.drawText(text, pad + dropW + gap - b.left, (h - capH) / 2f - b.top, p)
        return bmp
    }

    private fun drawIcon(ctx: Context, c: Canvas, res: Int, x: Float, y: Float, size: Float, shadow: Boolean) {
        val d = ContextCompat.getDrawable(ctx, res) ?: return
        val s = size.toInt().coerceAtLeast(1)
        val iconBmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, s, s); d.draw(Canvas(iconBmp))
        if (shadow) {
            val blur = Paint().apply { maskFilter = BlurMaskFilter(size * 0.05f, BlurMaskFilter.Blur.NORMAL) }
            val off = IntArray(2)
            val alpha = iconBmp.extractAlpha(blur, off)
            val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
            c.drawBitmap(alpha, x + off[0], y + off[1] + size * 0.02f, sp)
            alpha.recycle()
        }
        c.drawBitmap(iconBmp, x, y, null)
        iconBmp.recycle()
    }
}
