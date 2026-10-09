package com.dvaosem.weather28.widget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.dvaosem.weather28.R
import com.dvaosem.weather28.ui.MainActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WeatherAlertWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override fun doWork(): Result {
        val ctx = applicationContext
        // use the first widget's location, or default Bratislava
        val manager = android.appwidget.AppWidgetManager.getInstance(ctx)
        val ids = manager.getAppWidgetIds(
            android.content.ComponentName(ctx, WeatherWidget::class.java)
        )
        val id = if (ids.isNotEmpty()) ids[0] else 0
        val lat = AlertPrefs.getLat(ctx)
        val lon = AlertPrefs.getLon(ctx)
        val city = AlertPrefs.getCity(ctx)

        val type = inputData.getString("type") ?: "morning"

        try {
            if (type == "morning") {
                if (!AlertPrefs.morningEnabled(ctx)) return Result.success()
                doMorning(ctx, lat, lon, city)
            } else {
                if (!AlertPrefs.stormEnabled(ctx)) return Result.success()
                doStormCheck(ctx, lat, lon, city)
            }
        } catch (e: Exception) {
            return Result.retry()
        }
        return Result.success()
    }

    private fun doMorning(ctx: Context, lat: Double, lon: Double, city: String) {
        val url = "https://api.open-meteo.com/v1/forecast?" +
                "latitude=$lat&longitude=$lon" +
                "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max" +
                "&timezone=auto&forecast_days=1"
        val body = client.newCall(Request.Builder().url(url).build()).execute().body?.string() ?: return
        val daily = JSONObject(body).getJSONObject("daily")
        val max = daily.getJSONArray("temperature_2m_max").getDouble(0).toInt()
        val min = daily.getJSONArray("temperature_2m_min").getDouble(0).toInt()
        val code = daily.getJSONArray("weather_code").getInt(0)
        val rain = daily.getJSONArray("precipitation_probability_max").optInt(0, 0)
        val desc = codeDesc(code)
        val icon = codeIcon(code)

        val title = "$icon $city · dnes $max° / $min°"
        val text = "$desc · zrážky $rain%"
        notify(ctx, 1001, title, text, "morning")
    }

    private fun doStormCheck(ctx: Context, lat: Double, lon: Double, city: String) {
        val url = "https://api.open-meteo.com/v1/forecast?" +
                "latitude=$lat&longitude=$lon" +
                "&hourly=weather_code&timezone=auto&forecast_days=2"
        val body = client.newCall(Request.Builder().url(url).build()).execute().body?.string() ?: return
        val hourly = JSONObject(body).getJSONObject("hourly")
        val codes = hourly.getJSONArray("weather_code")
        val times = hourly.getJSONArray("time")

        // nájdi index aktuálnej hodiny (Open-Meteo hodiny začínajú o 00:00, nie od teraz)
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US)
        val now = System.currentTimeMillis()
        var start = 0
        for (i in 0 until times.length()) {
            val t = try { sdf.parse(times.getString(i))?.time ?: continue } catch (e: Exception) { continue }
            if (t >= now - 30 * 60 * 1000) { start = i; break }
        }

        // skontroluj najbližších 6 hodín: búrka / silný dážď / husté sneženie
        val end = minOf(start + 6, codes.length())
        var hitHour = -1
        var kind = ""
        for (i in start until end) {
            val c = codes.getInt(i)
            val k = when (c) {
                95, 96, 99 -> "storm"
                65, 67, 81, 82 -> "rain"   // silný dážď / prudké prehánky
                75, 86 -> "snow"           // husté sneženie
                else -> null
            }
            if (k != null) { hitHour = i - start; kind = k; break }
        }
        if (hitHour < 0) return

        // jedna výstraha denne na daný typ
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        if (AlertPrefs.lastStormDay(ctx) == "$today-$kind") return
        AlertPrefs.setLastStormDay(ctx, "$today-$kind")

        val whenTxt = if (hitHour == 0) "teraz" else "o ~$hitHour h"
        val (icon, what) = when (kind) {
            "rain" -> "🌧️" to "Silný dážď"
            "snow" -> "❄️" to "Husté sneženie"
            else   -> "⛈️" to "Búrka"
        }
        notify(ctx, 1002, "$icon Výstraha · $city", "$what $whenTxt", "storm")
    }

    private fun notify(ctx: Context, nid: Int, title: String, text: String, channel: String) {
        val chId = if (channel == "storm") "w28_storm" else "w28_morning"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val name = if (channel == "storm") "Výstrahy počasia" else "Ranný súhrn"
            val imp = if (channel == "storm") NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
            nm.createNotificationChannel(NotificationChannel(chId, name, imp))
        }
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(ctx, nid, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notif = NotificationCompat.Builder(ctx, chId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(if (channel == "storm") NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(nid, notif) } catch (e: SecurityException) {}
    }

    private fun codeDesc(c: Int): String = when (c) {
        0 -> "Jasno"; 1, 2 -> "Polojasno"; 3 -> "Zamračené"
        45, 48 -> "Hmla"; in 51..57 -> "Mrholenie"; in 61..67 -> "Dážď"
        in 71..77 -> "Sneženie"; in 80..82 -> "Prehánky"; in 95..99 -> "Búrka"
        else -> "Počasie"
    }
    private fun codeIcon(c: Int): String = when (c) {
        0 -> "☀️"; 1, 2 -> "🌤️"; 3 -> "☁️"; 45, 48 -> "🌫️"
        in 51..67 -> "🌧️"; in 71..77 -> "❄️"; in 80..82 -> "🌦️"
        in 95..99 -> "⛈️"; else -> "🌡️"
    }
}
