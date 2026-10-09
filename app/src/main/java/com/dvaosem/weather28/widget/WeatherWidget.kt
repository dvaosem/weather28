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
            val style = WidgetPrefs.getStyle(context, appWidgetId)
            val mode = WidgetPrefs.getMode(context, appWidgetId)

            val cached = WidgetPrefs.getCache(context, appWidgetId)?.let { WeatherFetcher.deserialize(it) }
            appWidgetManager.updateAppWidget(appWidgetId, buildResponsiveViews(context, appWidgetId, cached, style, mode))

            CoroutineScope(Dispatchers.IO).launch {
                val data = WeatherFetcher.fetch(lat, lon, city)
                withContext(Dispatchers.Main) {
                    if (data != null) WidgetPrefs.saveCache(context, appWidgetId, WeatherFetcher.serialize(data))
                    appWidgetManager.updateAppWidget(appWidgetId, buildResponsiveViews(context, appWidgetId, data ?: cached, style, mode))
                }
            }
        }

        private fun buildResponsiveViews(context: Context, appWidgetId: Int, data: WeatherData?, style: String, mode: String): RemoteViews {
            val single = mode == "single"
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // System picks the largest entry that fits the widget's current size.
                val map = linkedMapOf(
                    SizeF(40f, 40f) to buildViews(context, appWidgetId, data, style, "tiny"),
                    SizeF(96f, 40f) to buildViews(context, appWidgetId, data, style, "row")
                )
                if (!single) map[SizeF(130f, 40f)] = buildViews(context, appWidgetId, data, style, "duo")
                RemoteViews(map)
            } else {
                buildViews(context, appWidgetId, data, style, if (single) "row" else "duo")
            }
        }

        private fun getLayout(style: String, kind: String): Int = when (style) {
            "light" -> when (kind) { "tiny" -> R.layout.widget_light_tiny; "row" -> R.layout.widget_light_row; else -> R.layout.widget_light_duo }
            "transparent" -> when (kind) { "tiny" -> R.layout.widget_transparent_tiny; "row" -> R.layout.widget_transparent_row; else -> R.layout.widget_transparent_duo }
            else -> when (kind) { "tiny" -> R.layout.widget_dark_tiny; "row" -> R.layout.widget_dark_row; else -> R.layout.widget_dark_duo }
        }

        /** "18°  10°" — max bright, min dimmed. */
        private fun minMaxText(max: Int, min: Int, light: Boolean): CharSequence {
            val maxStr = "$max°"
            val sb = android.text.SpannableStringBuilder()
            sb.append(maxStr)
            sb.append("  $min°")
            val dim = if (light) 0xFF6B7A8C.toInt() else 0xA6FFFFFF.toInt()
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

        private fun buildViews(context: Context, appWidgetId: Int, data: WeatherData?, style: String, kind: String): RemoteViews {
            val views = RemoteViews(context.packageName, getLayout(style, kind))
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

            if (data == null) {
                views.setTextViewText(R.id.today_temp, "--°")
                views.setImageViewResource(R.id.today_icon, WeatherFetcher.iconRes(-1, true, light))
                views.setTextViewText(R.id.today_minmax, "")
                if (kind == "duo") {
                    views.setTextViewText(R.id.tomorrow_temp, "--°")
                    views.setImageViewResource(R.id.tomorrow_icon, WeatherFetcher.iconRes(-1, true, light))
                    views.setTextViewText(R.id.tomorrow_minmax, "")
                }
                return views
            }

            views.setTextViewText(R.id.today_temp, "${data.todayTemp}°")
            if (!light) views.setTextColor(R.id.today_temp, WeatherFetcher.tempColor(data.todayTemp))
            views.setImageViewResource(R.id.today_icon, WeatherFetcher.iconRes(data.todayCode, data.todayIsDay, light))
            views.setTextViewText(R.id.today_minmax, minMaxText(data.todayMax, data.todayMin, light))

            if (kind == "duo") {
                views.setTextViewText(R.id.tomorrow_temp, "${data.tomorrowTemp}°")
                if (!light) views.setTextColor(R.id.tomorrow_temp, WeatherFetcher.tempColor(data.tomorrowTemp))
                views.setImageViewResource(R.id.tomorrow_icon, WeatherFetcher.iconRes(data.tomorrowCode, true, light))
                views.setTextViewText(R.id.tomorrow_minmax, minMaxText(data.tomorrowMax, data.tomorrowMin, light))
            }
            return views
        }
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
