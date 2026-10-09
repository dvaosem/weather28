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
            val days = DayBitmaps(context, data, style)
            val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                RemoteViews(sizes.associateWith { buildViews(context, id, days, data, style, mode, it) })
            } else {
                buildViews(context, id, days, data, style, mode, sizes.first())
            }
            mgr.updateAppWidget(id, views)
        }

        /** Actual on-screen widget sizes in dp (portrait/landscape) as reported by the launcher. */
        private fun widgetSizes(mgr: AppWidgetManager, id: Int): List<SizeF> {
            val o = mgr.getAppWidgetOptions(id)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION")
                val l = o.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                if (!l.isNullOrEmpty()) return l.distinct().take(4)
            }
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            return if (w > 0 && h > 0) listOf(SizeF(w.toFloat(), h.toFloat())) else listOf(SizeF(170f, 80f))
        }

        /** Both compositions (icon beside / icon above) for today and tomorrow, rendered lazily once. */
        private class DayBitmaps(val ctx: Context, val data: WeatherData?, style: String) {
            val light = style == "light"
            val shadow = style == "transparent"
            private val cache = HashMap<String, android.graphics.Bitmap>()

            fun get(tomorrow: Boolean, stack: Boolean): android.graphics.Bitmap = cache.getOrPut("$tomorrow$stack") {
                val d = data
                val temp = if (d == null) null else if (tomorrow) d.tomorrowTemp else d.todayTemp
                val icon = WeatherFetcher.iconRes(
                    if (d == null) -1 else if (tomorrow) d.tomorrowCode else d.todayCode,
                    if (d == null || tomorrow) true else d.todayIsDay, light)
                WidgetRenderer.renderDay(
                    ctx, icon,
                    temp?.let { "$it°" } ?: "--°",
                    if (light) 0xFF16202C.toInt() else if (temp != null) WeatherFetcher.tempColor(temp) else 0xFFFFFFFF.toInt(),
                    d?.let { "${if (tomorrow) it.tomorrowMax else it.todayMax}°" },
                    d?.let { "${if (tomorrow) it.tomorrowMin else it.todayMin}°" },
                    light, shadow, stack)
            }

            /** Pick the composition whose temperature ends up larger in a cw x ch box. */
            fun best(tomorrow: Boolean, cw: Float, ch: Float): android.graphics.Bitmap {
                val side = get(tomorrow, false); val stack = get(tomorrow, true)
                fun scale(b: android.graphics.Bitmap) = minOf(cw / b.width, ch / b.height)
                // stacked icon is drawn a bit smaller relative to the number, so it needs a clear win
                return if (scale(stack) > scale(side) * 1.1f) stack else side
            }
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

        private fun buildViews(context: Context, appWidgetId: Int, days: DayBitmaps, data: WeatherData?,
                               style: String, mode: String, size: SizeF): RemoteViews {
            val duo = mode != "single" && size.width >= 120f
            val views = RemoteViews(context.packageName, if (duo) R.layout.widget_two else R.layout.widget_one)
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

            // Content box per day in dp (root padding 6dp each side; duo divider ~7dp)
            val cw = if (duo) (size.width - 12f - 7f) / 2f else size.width - 12f
            val ch = size.height - 12f

            views.setImageViewBitmap(R.id.today_img, days.best(false, cw, ch))
            views.setContentDescription(R.id.today_img,
                data?.let { "Dnes ${it.todayTemp}°, max ${it.todayMax}°, min ${it.todayMin}°" } ?: "Weather28")
            if (duo) {
                views.setImageViewBitmap(R.id.tomorrow_img, days.best(true, cw, ch))
                views.setContentDescription(R.id.tomorrow_img,
                    data?.let { "Zajtra max ${it.tomorrowMax}°, min ${it.tomorrowMin}°" } ?: "")
                views.setInt(R.id.widget_divider, "setBackgroundColor", if (light) 0x1F16202C else 0x2EFFFFFF)
            }
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
