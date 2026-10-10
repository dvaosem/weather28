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
import kotlin.math.roundToInt

class WeatherAlertWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    companion object { const val GUST_ALERT = 55.0 }

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
        val max = Math.floor(daily.getJSONArray("temperature_2m_max").getDouble(0) + 0.5).toInt()
        val min = Math.floor(daily.getJSONArray("temperature_2m_min").getDouble(0) + 0.5).toInt()
        val code = daily.getJSONArray("weather_code").getInt(0)
        val rain = daily.getJSONArray("precipitation_probability_max").optInt(0, 0)
        val desc = codeDesc(code, WidgetPrefs.isEn(ctx))
        val icon = codeIcon(code)

        val en = WidgetPrefs.isEn(ctx)
        val title = "$icon $city · ${if (en) "today" else "dnes"} $max° / $min°"
        val text = "$desc · ${if (en) "rain" else "zrážky"} $rain%"
        notify(ctx, 1001, title, text, "morning")
    }

    // ------------------------------------------------------------------
    // Storm check = (1) official SHMÚ warnings for the user's district via MeteoAlarm
    //             + (2) forecast signals for the next 6 h (thunder, heavy rain, gusts, snow).
    // Models often show a storm only as showers + strong gusts (e.g. 8.10.: code 80,
    // gusts 59 km/h, no thunder code), so gusts and lightning potential count too.
    // ------------------------------------------------------------------
    private fun doStormCheck(ctx: Context, lat: Double, lon: Double, city: String) {
        try { checkOfficialWarnings(ctx, lat, lon, city) } catch (e: Exception) {}
        checkForecast(ctx, lat, lon, city)
    }

    private fun get(url: String): String? = try {
        client.newCall(Request.Builder().url(url).header("User-Agent", "Weather28/1.0 (+https://dvaosem.com)").build())
            .execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    } catch (e: Exception) { null }

    private fun checkForecast(ctx: Context, lat: Double, lon: Double, city: String) {
        val url = "https://api.open-meteo.com/v1/forecast?" +
                "latitude=$lat&longitude=$lon" +
                "&hourly=weather_code,precipitation,wind_gusts_10m,cape,lightning_potential" +
                "&timezone=auto&forecast_days=2"
        val body = get(url) ?: throw Exception("forecast unavailable")
        val h = JSONObject(body).getJSONObject("hourly")
        val times = h.getJSONArray("time")
        val codes = h.getJSONArray("weather_code")
        val precip = h.optJSONArray("precipitation")
        val gusts = h.optJSONArray("wind_gusts_10m")
        val cape = h.optJSONArray("cape")
        val lpi = h.optJSONArray("lightning_potential")

        // index of the current hour (Open-Meteo hours start at 00:00, not now)
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US)
        val now = System.currentTimeMillis()
        var start = 0
        for (i in 0 until times.length()) {
            val t = try { sdf.parse(times.getString(i))?.time ?: continue } catch (e: Exception) { continue }
            if (t >= now - 30 * 60 * 1000) { start = i; break }
        }
        val end = minOf(start + 6, codes.length())

        data class Hit(val kind: String, val hour: Int, val detail: String)
        val hits = LinkedHashMap<String, Hit>()
        for (i in start until end) {
            val c = codes.optInt(i, 0)
            val p = precip?.optDouble(i, 0.0) ?: 0.0
            val g = gusts?.optDouble(i, 0.0) ?: 0.0
            val cp = cape?.optDouble(i, 0.0) ?: 0.0
            val lp = lpi?.optDouble(i, 0.0) ?: 0.0
            val rel = i - start
            if ((c in 95..99 || lp >= 1.0 || (cp >= 800 && p >= 1.0)) && "storm" !in hits)
                hits["storm"] = Hit("storm", rel, if (g >= 40) gustTxt(ctx, g) else "")
            if ((c in listOf(65, 67, 81, 82) || p >= 4.0) && "rain" !in hits)
                hits["rain"] = Hit("rain", rel, if (p >= 1) "${"%.0f".format(p)} mm/h" else "")
            if (g >= GUST_ALERT && "wind" !in hits) {
                // report the strongest gust in the window
                var mx = g; for (j in i until end) mx = maxOf(mx, gusts?.optDouble(j, 0.0) ?: 0.0)
                hits["wind"] = Hit("wind", rel, gustTxt(ctx, mx))
            }
            if ((c == 75 || c == 86) && "snow" !in hits) hits["snow"] = Hit("snow", rel, "")
        }
        if (hits.isEmpty()) return

        // one alert per day per type; storm wins, others are listed in the same notification
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        val fresh = hits.values.filter { !AlertPrefs.wasAlerted(ctx, "$today-${it.kind}") }
        if (fresh.isEmpty()) return
        fresh.forEach { AlertPrefs.markAlerted(ctx, "$today-${it.kind}") }

        val main = fresh.minByOrNull { listOf("storm", "wind", "rain", "snow").indexOf(it.kind) }!!
        val en = WidgetPrefs.isEn(ctx)
        val (icon, what) = label(main.kind, en)
        fun whenTxt(hh: Int) = if (en) (if (hh == 0) "now" else "in ~$hh h") else (if (hh == 0) "teraz" else "o ~$hh h")
        val lines = fresh.map { val (_, w) = label(it.kind, en); listOf(w, whenTxt(it.hour), it.detail).filter { s -> s.isNotEmpty() }.joinToString(" · ") }
        notify(ctx, 1002, "$icon $what ${whenTxt(main.hour)} · $city", lines.joinToString("\n"), "storm")
    }

    private fun gustTxt(ctx: Context, g: Double) =
        (if (WidgetPrefs.isEn(ctx)) "gusts up to " else "nárazy do ") + "${g.roundToInt()} km/h"

    private fun label(kind: String, en: Boolean) = when (kind) {
        "rain" -> "🌧️" to (if (en) "Heavy rain" else "Silný dážď")
        "snow" -> "❄️" to (if (en) "Heavy snow" else "Husté sneženie")
        "wind" -> "💨" to (if (en) "Strong wind" else "Silný vietor")
        else   -> "⛈️" to (if (en) "Thunderstorm" else "Búrka")
    }

    /** Official SHMÚ warnings (MeteoAlarm feed) for the district of the alert location. */
    private fun checkOfficialWarnings(ctx: Context, lat: Double, lon: Double, city: String) {
        val district = districtFor(ctx, lat, lon) ?: return
        val body = get("https://feeds.meteoalarm.org/api/v1/warnings/feeds-slovakia") ?: return
        val warnings = JSONObject(body).optJSONArray("warnings") ?: return
        val now = System.currentTimeMillis()
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
        for (i in 0 until warnings.length()) {
            val alert = warnings.getJSONObject(i).optJSONObject("alert") ?: continue
            val id = alert.optString("identifier")
            val infos = alert.optJSONArray("info") ?: continue
            var info: JSONObject? = null
            val want = if (WidgetPrefs.isEn(ctx)) "en" else "sk"
            for (k in 0 until infos.length()) if (infos.getJSONObject(k).optString("language").startsWith(want)) info = infos.getJSONObject(k)
            info = info ?: infos.optJSONObject(0) ?: continue
            val areas = info.optJSONArray("area") ?: continue
            var match = false
            for (a in 0 until areas.length()) if (areas.getJSONObject(a).optString("areaDesc").equals(district, true)) match = true
            if (!match) continue
            val expires = try { iso.parse(info.optString("expires"))?.time ?: 0L } catch (e: Exception) { 0L }
            val onset = try { iso.parse(info.optString("onset"))?.time ?: now } catch (e: Exception) { now }
            if (expires in 1..now || onset > now + 12 * 3600_000L) continue
            if (AlertPrefs.wasAlerted(ctx, "ma-$id")) continue
            AlertPrefs.markAlerted(ctx, "ma-$id")

            val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            val level = info.optJSONArray("parameter")?.let { ps ->
                (0 until ps.length()).map { ps.getJSONObject(it) }.firstOrNull { it.optString("valueName") == "awareness_level" }
                    ?.optString("value")?.substringAfter(";")?.substringBefore(";")?.trim()
            } ?: ""
            val badge = when (level) { "red" -> "🔴"; "orange" -> "🟠"; else -> "🟡" }
            val title = "$badge ${info.optString("event")} · $city"
            val text = info.optString("headline") + "\n" +
                    (if (WidgetPrefs.isEn(ctx)) "Valid " else "Platí ") +
                    "${hm.format(java.util.Date(onset))}–${hm.format(java.util.Date(expires))} · SHMÚ"
            notify(ctx, 1100 + (id.hashCode() and 0xff), title, text, "storm")
        }
    }

    /** "Pezinok" for a point in okres Pezinok — reverse geocoded once per location and cached. */
    private fun districtFor(ctx: Context, lat: Double, lon: Double): String? {
        val key = "%.2f,%.2f".format(java.util.Locale.US, lat, lon)
        AlertPrefs.cachedDistrict(ctx, key)?.let { return it }
        val body = get("https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&format=json&zoom=10&accept-language=sk") ?: return null
        val d = JSONObject(body).optJSONObject("address")?.optString("district")?.removePrefix("okres ")?.trim()
        if (d.isNullOrEmpty()) return null
        AlertPrefs.cacheDistrict(ctx, key, d)
        return d
    }

    private fun notify(ctx: Context, nid: Int, title: String, text: String, channel: String) {
        val chId = if (channel == "storm") "w28_storm" else "w28_morning"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val en = WidgetPrefs.isEn(ctx)
            val name = if (channel == "storm") (if (en) "Weather alerts" else "Výstrahy počasia")
                       else (if (en) "Morning summary" else "Ranný súhrn")
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

    private fun codeDesc(c: Int, en: Boolean): String = if (en) when (c) {
        0 -> "Clear"; 1, 2 -> "Partly cloudy"; 3 -> "Overcast"
        45, 48 -> "Fog"; in 51..57 -> "Drizzle"; in 61..67 -> "Rain"
        in 71..77 -> "Snow"; in 80..82 -> "Showers"; in 95..99 -> "Thunderstorm"
        else -> "Weather"
    } else when (c) {
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
