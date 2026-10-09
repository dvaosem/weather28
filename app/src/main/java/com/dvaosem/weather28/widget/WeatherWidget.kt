package com.dvaosem.weather28.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.SizeF
import android.widget.RemoteViews
import com.dvaosem.weather28.R
import com.dvaosem.weather28.ui.MainActivity
import kotlinx.coroutines.*

class WeatherWidget : AppWidgetProvider() {

    companion object {

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val city = WidgetPrefs.getCity(context, appWidgetId)
            val lat = WidgetPrefs.getLat(context, appWidgetId)
            val lon = WidgetPrefs.getLon(context, appWidgetId)

            val cached = WidgetPrefs.getCache(context, appWidgetId)?.let { WeatherFetcher.deserialize(it) }
            render(context, appWidgetManager, appWidgetId, cached)

            CoroutineScope(Dispatchers.IO).launch {
                val data = WeatherFetcher.fetch(lat, lon, city)
                withContext(Dispatchers.Main) {
                    if (data != null) WidgetPrefs.saveCache(context, appWidgetId, WeatherFetcher.serialize(data))
                    render(context, appWidgetManager, appWidgetId, data ?: cached)
                }
            }
        }

        /** Re-draw from cache only (used on resize). */
        fun renderCached(context: Context, mgr: AppWidgetManager, id: Int) {
            render(context, mgr, id, WidgetPrefs.getCache(context, id)?.let { WeatherFetcher.deserialize(it) })
        }

        private fun render(context: Context, mgr: AppWidgetManager, id: Int, data: WeatherData?) {
            val style = WidgetPrefs.getStyle(context, id)
            val mode = WidgetPrefs.getMode(context, id)
            val sizes = widgetSizes(mgr, id)
            val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                RemoteViews(sizes.associateWith { buildViews(context, id, data, style, mode, it) })
            } else {
                buildViews(context, id, data, style, mode, sizes.first())
            }
            mgr.updateAppWidget(id, views)
        }

        /** Actual on-screen widget sizes in dp (portrait/landscape) as reported by the launcher. */
        private fun widgetSizes(mgr: AppWidgetManager, id: Int): List<SizeF> {
            val o = mgr.getAppWidgetOptions(id)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION")
                val l = o.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                if (!l.isNullOrEmpty()) return l.distinct().take(8)
            }
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            return if (w > 0 && h > 0) listOf(SizeF(w.toFloat(), h.toFloat())) else listOf(SizeF(170f, 80f))
        }

        private fun getLayout(style: String, duo: Boolean): Int = when (style) {
            "light" -> if (duo) R.layout.widget_light_duo else R.layout.widget_light_single
            "transparent" -> if (duo) R.layout.widget_transparent_duo else R.layout.widget_transparent_single
            else -> if (duo) R.layout.widget_dark_duo else R.layout.widget_dark_single
        }

        // ---- sizing: fit the biggest possible text into the real widget size ----
        private const val LINE = 1.2f      // line height / text size (no font padding)
        private const val MM_RATIO = 0.42f // min/max size relative to temperature

        private data class Spec(val temp: Float, val mm: Float, val icon: Float, val stacked: Boolean, val gap: Float)

        private fun tempEm(t: String) = t.fold(0f) { a, c -> a + when { c.isDigit() -> 0.55f; c == '°' -> 0.36f; c == '-' -> 0.36f; else -> 0.5f } }
        private fun mmEm(t: String) = t.fold(0f) { a, c -> a + when { c.isDigit() -> 0.6f; c == '°' -> 0.4f; c == ' ' -> 0.28f; c == '-' -> 0.4f; else -> 0.55f } }

        private fun spec(cw: Float, ch: Float, temps: List<String>, mms: List<String>, canStack: Boolean): Spec {
            val te = maxOf(temps.maxOf { tempEm(it) }, 1.3f)
            val me = maxOf(mms.maxOf { mmEm(it) }, 3.2f)
            val mmH = MM_RATIO * LINE
            // icon beside the number
            val side = minOf(cw / (0.9f + 0.08f + te), (ch - 3f) / (LINE + mmH))
            // icon above the number
            val stack = if (canStack) minOf(cw / te, (ch - 5f) / (0.85f + LINE + mmH)) else 0f
            val stacked = stack > side * 1.08f
            val t = (if (stacked) stack else side).coerceIn(10f, 120f) * 0.96f
            val mm = minOf((t * MM_RATIO).coerceIn(9f, 28f), cw * 0.95f / me)
            return Spec(t, mm, if (stacked) t * 0.85f else t * 0.9f, stacked, t * 0.08f)
        }

        /** "18°  10°" — max bright, min dimmed. */
        private fun minMaxText(max: Int, min: Int, light: Boolean): CharSequence {
            val maxStr = "$max°"
            val sb = android.text.SpannableStringBuilder(maxStr).append("  $min°")
            val dim = if (light) 0xFF6B7A8C.toInt() else 0xB3FFFFFF.toInt()
            sb.setSpan(android.text.style.ForegroundColorSpan(dim), maxStr.length, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return sb
        }

        private fun opacityBg(opacity: Int): Int = when (opacity) {
            0 -> R.drawable.widget_bg_op00
            20 -> R.drawable.widget_bg_op20
            40 -> R.drawable.widget_bg_op40
            60 -> R.drawable.widget_bg_op60
            95 -> R.drawable.widget_bg_op95
            else -> R.drawable.widget_bg_op80
        }
        private fun lightOpacityBg(opacity: Int): Int = when (opacity) {
            0 -> R.drawable.widget_bg_lop00
            20 -> R.drawable.widget_bg_lop20
            40 -> R.drawable.widget_bg_lop40
            60 -> R.drawable.widget_bg_lop60
            95 -> R.drawable.widget_bg_lop95
            else -> R.drawable.widget_bg_lop80
        }
        private fun glassBg(intensity: Int): Int = when (intensity) {
            0 -> R.drawable.widget_bg_glass00
            20 -> R.drawable.widget_bg_glass20
            40 -> R.drawable.widget_bg_glass40
            60 -> R.drawable.widget_bg_glass60
            95 -> R.drawable.widget_bg_glass95
            else -> R.drawable.widget_bg_glass80
        }

        private fun buildViews(context: Context, appWidgetId: Int, data: WeatherData?, style: String, mode: String, size: SizeF): RemoteViews {
            val duo = mode != "single" && size.width >= 120f
            val views = RemoteViews(context.packageName, getLayout(style, duo))
            val light = style == "light"

            // Background: opacity (dark/light) or liquid-glass intensity (transparent)
            if (style == "transparent") {
                views.setInt(R.id.widget_root, "setBackgroundResource", glassBg(WidgetPrefs.getGlass(context, appWidgetId)))
            } else {
                val op = WidgetPrefs.getOpacity(context, appWidgetId)
                views.setInt(R.id.widget_root, "setBackgroundResource", if (light) lightOpacityBg(op) else opacityBg(op))
            }

            // Open app on tap
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
                context, appWidgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))

            val todayTemp = data?.let { "${it.todayTemp}°" } ?: "--°"
            val tomTemp = data?.let { "${it.tomorrowTemp}°" } ?: "--°"
            val todayMm = data?.let { "${it.todayMax}°  ${it.todayMin}°" } ?: "--°  --°"
            val tomMm = data?.let { "${it.tomorrowMax}°  ${it.tomorrowMin}°" } ?: "--°  --°"

            // Content box in dp (root padding 6dp each side; duo also has a 7dp divider)
            val cw = if (duo) (size.width - 12f - 7f) / 2f - 2f else size.width - 16f
            val ch = size.height - 12f
            val canStack = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val sp = spec(cw, ch, if (duo) listOf(todayTemp, tomTemp) else listOf(todayTemp),
                if (duo) listOf(todayMm, tomMm) else listOf(todayMm), canStack)

            fun fill(temp: Int, iconTop: Int, icon: Int, mm: Int, tempText: String, tempValue: Int?, iconRes: Int, mmText: CharSequence) {
                val u = android.util.TypedValue.COMPLEX_UNIT_DIP
                views.setTextViewText(temp, tempText)
                if (!light && tempValue != null) views.setTextColor(temp, WeatherFetcher.tempColor(tempValue))
                views.setTextViewTextSize(temp, u, sp.temp)
                views.setTextViewText(mm, mmText)
                views.setTextViewTextSize(mm, u, sp.mm)
                views.setImageViewResource(icon, iconRes)
                views.setImageViewResource(iconTop, iconRes)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val shown = if (sp.stacked) iconTop else icon
                    views.setViewVisibility(iconTop, if (sp.stacked) android.view.View.VISIBLE else android.view.View.GONE)
                    views.setViewVisibility(icon, if (sp.stacked) android.view.View.GONE else android.view.View.VISIBLE)
                    views.setViewLayoutWidth(shown, sp.icon, u)
                    views.setViewLayoutHeight(shown, sp.icon, u)
                    views.setViewLayoutMargin(temp, RemoteViews.MARGIN_START, if (sp.stacked) 0f else sp.gap, u)
                }
            }

            fill(R.id.today_temp, R.id.today_icon_top, R.id.today_icon, R.id.today_minmax,
                todayTemp, data?.todayTemp,
                WeatherFetcher.iconRes(data?.todayCode ?: -1, data?.todayIsDay ?: true, light),
                if (data != null) minMaxText(data.todayMax, data.todayMin, light) else "")
            if (duo) fill(R.id.tomorrow_temp, R.id.tomorrow_icon_top, R.id.tomorrow_icon, R.id.tomorrow_minmax,
                tomTemp, data?.tomorrowTemp,
                WeatherFetcher.iconRes(data?.tomorrowCode ?: -1, true, light),
                if (data != null) minMaxText(data.tomorrowMax, data.tomorrowMin, light) else "")
            return views
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        renderCached(context, appWidgetManager, appWidgetId)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateWidget(context, appWidgetManager, id)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) WidgetPrefs.remove(context, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == "com.dvaosem.weather28.WIDGET_UPDATE") {
            val manager = AppWidgetManager.getInstance(context)
            val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                updateWidget(context, manager, widgetId)
            } else {
                onUpdate(context, manager, manager.getAppWidgetIds(ComponentName(context, WeatherWidget::class.java)))
            }
        }
    }
}
