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
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                RemoteViews(mapOf(
                    SizeF(56f, 56f)   to buildViews(context, appWidgetId, data, style, "xxs", mode),
                    SizeF(100f, 60f)  to buildViews(context, appWidgetId, data, style, "xs", mode),
                    SizeF(160f, 80f)  to buildViews(context, appWidgetId, data, style, "s",  mode),
                    SizeF(220f, 100f) to buildViews(context, appWidgetId, data, style, "m",  mode),
                    SizeF(300f, 130f) to buildViews(context, appWidgetId, data, style, "l",  mode),
                ))
            } else {
                buildViews(context, appWidgetId, data, style, "m", mode)
            }
        }

        private fun getLayout(style: String, size: String, mode: String): Int {
            val single = mode == "single"
            return when {
                single && style == "dark"        && size == "xxs" -> R.layout.widget_dark_single_xxs
                single && style == "transparent" && size == "xxs" -> R.layout.widget_transparent_single_xxs
                single && style == "light"       && size == "xxs" -> R.layout.widget_light_single_xxs
                // 1x1 nemá zmysel pre "double" (dnes+zajtra) mód – použi najkompaktnejší double layout
                !single && size == "xxs"                          -> getLayout(style, "xs", mode)
                single && style == "dark"        && size == "xs" -> R.layout.widget_dark_single_xs
                single && style == "dark"        && size == "s"  -> R.layout.widget_dark_single_s
                single && style == "dark"        && size == "m"  -> R.layout.widget_dark_single_m
                single && style == "dark"        && size == "l"  -> R.layout.widget_dark_single_l
                single && style == "transparent" && size == "xs" -> R.layout.widget_transparent_single_xs
                single && style == "transparent" && size == "s"  -> R.layout.widget_transparent_single_s
                single && style == "transparent" && size == "m"  -> R.layout.widget_transparent_single_m
                single && style == "transparent" && size == "l"  -> R.layout.widget_transparent_single_l
                single && style == "light"       && size == "xs" -> R.layout.widget_light_single_xs
                single && style == "light"       && size == "s"  -> R.layout.widget_light_single_s
                single && style == "light"       && size == "m"  -> R.layout.widget_light_single_m
                single && style == "light"       && size == "l"  -> R.layout.widget_light_single_l
                style == "transparent" && size == "xs" -> R.layout.widget_transparent_xs
                style == "transparent" && size == "s"  -> R.layout.widget_transparent_s
                style == "transparent" && size == "m"  -> R.layout.widget_transparent_m
                style == "transparent" && size == "l"  -> R.layout.widget_transparent_l
                style == "light"       && size == "xs" -> R.layout.widget_light_xs
                style == "light"       && size == "s"  -> R.layout.widget_light_s
                style == "light"       && size == "m"  -> R.layout.widget_light_m
                style == "light"       && size == "l"  -> R.layout.widget_light_l
                style == "dark"        && size == "xs" -> R.layout.widget_dark_xs
                style == "dark"        && size == "s"  -> R.layout.widget_dark_s
                style == "dark"        && size == "l"  -> R.layout.widget_dark_l
                else                                   -> R.layout.widget_dark_m
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
        private fun glassBg(intensity: Int): Int = when (intensity) {
            0 -> R.drawable.widget_bg_glass00
            20 -> R.drawable.widget_bg_glass20
            40 -> R.drawable.widget_bg_glass40
            60 -> R.drawable.widget_bg_glass60
            95 -> R.drawable.widget_bg_glass95
            else -> R.drawable.widget_bg_glass80
        }

        private fun buildViews(context: Context, appWidgetId: Int, data: WeatherData?, style: String, size: String, mode: String): RemoteViews {
            val views = RemoteViews(context.packageName, getLayout(style, size, mode))

            // Apply opacity background (dark/light), alebo intenzitu liquid glass (transparent)
            try {
                if (style == "transparent") {
                    val glass = WidgetPrefs.getGlass(context, appWidgetId)
                    views.setInt(R.id.widget_root, "setBackgroundResource", glassBg(glass))
                } else {
                    val op = WidgetPrefs.getOpacity(context, appWidgetId)
                    views.setInt(R.id.widget_root, "setBackgroundResource", opacityBg(op))
                }
            } catch (e: Exception) {}

            // Open app on click
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
                context, appWidgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))

            // Refresh button
            val refreshIntent = Intent(context, WeatherWidget::class.java).apply {
                action = "com.dvaosem.weather28.WIDGET_UPDATE"
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            try {
                views.setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(
                    context, appWidgetId + 5000, refreshIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ))
            } catch (e: Exception) {}


            // Data
            if (data == null) {
                views.setTextViewText(R.id.today_temp, "--°")
                views.setTextViewText(R.id.today_icon, "⏳")
                try { views.setTextViewText(R.id.today_icon_bg, "⏳") } catch (e: Exception) {}
                views.setTextViewText(R.id.today_minmax, "")
                try { views.setTextViewText(R.id.tomorrow_temp, "--°") } catch (e: Exception) {}
                try { views.setTextViewText(R.id.tomorrow_icon, "") } catch (e: Exception) {}
                try { views.setTextViewText(R.id.tomorrow_icon_bg, "") } catch (e: Exception) {}
                try { views.setTextViewText(R.id.tomorrow_minmax, "") } catch (e: Exception) {}
            } else {
                try { views.setTextViewText(R.id.widget_city, data.city) } catch (e: Exception) {}
                views.setTextViewText(R.id.today_temp, "${data.todayTemp}°")
                views.setTextColor(R.id.today_temp, WeatherFetcher.tempColor(data.todayTemp))
                views.setTextViewText(R.id.today_icon, data.todayIcon)
                try { views.setTextViewText(R.id.today_icon_bg, data.todayIcon) } catch (e: Exception) {}
                views.setTextViewText(R.id.today_minmax, "${data.todayMax}° / ${data.todayMin}°")
                views.setTextColor(R.id.today_minmax, 0xFF00e5a0.toInt())
                try { views.setTextViewText(R.id.tomorrow_temp, "${data.tomorrowTemp}°") } catch (e: Exception) {}
                try { views.setTextColor(R.id.tomorrow_temp, WeatherFetcher.tempColor(data.tomorrowTemp)) } catch (e: Exception) {}
                try { views.setTextViewText(R.id.tomorrow_icon, data.tomorrowIcon) } catch (e: Exception) {}
                try { views.setTextViewText(R.id.tomorrow_icon_bg, data.tomorrowIcon) } catch (e: Exception) {}
                try { views.setTextViewText(R.id.tomorrow_minmax, "${data.tomorrowMax}° / ${data.tomorrowMin}°") } catch (e: Exception) {}
                try { views.setTextColor(R.id.tomorrow_minmax, 0xFFff4d6d.toInt()) } catch (e: Exception) {}
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
