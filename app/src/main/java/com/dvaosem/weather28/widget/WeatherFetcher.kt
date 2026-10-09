package com.dvaosem.weather28.widget

import android.util.Log
import com.dvaosem.weather28.R
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** One rain-forecast slot (15 min from minutely data, 60 min from hourly data). */
data class RainSlot(val start: Long, val minutes: Int, val wet: Boolean)

data class WeatherData(
    val city: String,
    val todayTemp: Int,       // current temperature
    val todayMax: Int,
    val todayMin: Int,
    val tomorrowTemp: Int,    // tomorrow max (big number)
    val tomorrowMax: Int,
    val tomorrowMin: Int,
    val todayCode: Int = -1,
    val todayIsDay: Boolean = true,
    val tomorrowCode: Int = -1,
    val source: String = "openmeteo",
    val rain: List<RainSlot> = emptyList()
)

/**
 * Fetches the widget data from the same source the app uses (Open-Meteo, ECMWF, Met.no or
 * "Best of" = median of all three) with the same rounding as the app (JS Math.round),
 * so the widget and the app show the same numbers.
 */
object WeatherFetcher {

    private const val TAG = "WeatherFetcher"
    private const val UA = "Weather28/1.0 (+https://dvaosem.com)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private data class Day(val max: Double, val min: Double, val code: Int)
    private class Src(val curTemp: Double, val curCode: Int, val isDay: Boolean?, val days: Map<String, Day>)
    private class OmResult(val src: Src, val rain: List<RainSlot>)

    fun fetch(lat: Double, lon: Double, cityName: String, source: String = "openmeteo", order: List<Int> = listOf(0, 1, 2)): WeatherData? {
        val om = fetchOpenMeteo(lat, lon, null) // always: base + rain forecast
        val chosen: Src? = when (source) {
            "ecmwf" -> fetchOpenMeteo(lat, lon, "ecmwf_ifs025")?.src
            "metno" -> fetchMetNo(lat, lon)
            "best" -> bestOf(listOf(om?.src, fetchMetNo(lat, lon), fetchOpenMeteo(lat, lon, "ecmwf_ifs025")?.src), order)
            else -> om?.src
        } ?: om?.src ?: return null

        val today = LocalDate.now().toString()
        val tomorrow = LocalDate.now().plusDays(1).toString()
        val d0 = chosen.days[today] ?: chosen.days.values.firstOrNull() ?: return null
        val d1 = chosen.days[tomorrow] ?: chosen.days.values.drop(1).firstOrNull() ?: d0

        return WeatherData(
            city = cityName,
            todayTemp = jsRound(chosen.curTemp),
            todayMax = jsRound(d0.max),
            todayMin = jsRound(d0.min),
            tomorrowTemp = jsRound(d1.max),
            tomorrowMax = jsRound(d1.max),
            tomorrowMin = jsRound(d1.min),
            todayCode = chosen.curCode,
            todayIsDay = chosen.isDay ?: om?.src?.isDay ?: true,
            tomorrowCode = d1.code,
            source = source,
            rain = om?.rain ?: emptyList()
        )
    }

    /** Same as JavaScript Math.round (half up), unlike Kotlin's round() (half even) or toInt() (truncates). */
    private fun jsRound(v: Double): Int = Math.floor(v + 0.5).toInt()
    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0

    private fun get(url: String): String? = try {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    } catch (e: Exception) {
        Log.e(TAG, "GET failed: $url", e); null
    }

    // ---------------- Open-Meteo (and ECMWF via models=) ----------------
    private fun fetchOpenMeteo(lat: Double, lon: Double, model: String?): OmResult? = try {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code,is_day" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                (if (model == null) "&minutely_15=precipitation&hourly=precipitation,precipitation_probability" else "") +
                "&forecast_days=3&timezone=auto" +
                (if (model != null) "&models=$model" else "")
        val j = JSONObject(get(url) ?: throw Exception("no body"))
        val cur = j.getJSONObject("current")
        val daily = j.getJSONObject("daily")
        val times = daily.getJSONArray("time")
        val days = LinkedHashMap<String, Day>()
        for (i in 0 until times.length()) {
            val mx = daily.getJSONArray("temperature_2m_max").optDouble(i, Double.NaN)
            val mn = daily.getJSONArray("temperature_2m_min").optDouble(i, Double.NaN)
            if (mx.isNaN() || mn.isNaN()) continue
            days[times.getString(i)] = Day(mx, mn, daily.getJSONArray("weather_code").optInt(i, 3))
        }
        val src = Src(
            cur.getDouble("temperature_2m"),
            cur.optInt("weather_code", 3),
            if (cur.has("is_day")) cur.optInt("is_day", 1) == 1 else null,
            days
        )
        OmResult(src, if (model == null) rainSlots(j) else emptyList())
    } catch (e: Exception) {
        Log.e(TAG, "Open-Meteo ($model) error: ${e.message}"); null
    }

    private fun rainSlots(j: JSONObject): List<RainSlot> {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val out = ArrayList<RainSlot>()
        fun epoch(t: String) = LocalDateTime.parse(t).atZone(zone).toInstant().toEpochMilli()
        var minutelyEnd = now
        j.optJSONObject("minutely_15")?.let { m ->
            val t = m.getJSONArray("time"); val p = m.getJSONArray("precipitation")
            for (i in 0 until t.length()) {
                val s = epoch(t.getString(i))
                if (s + 15 * 60_000 <= now || s > now + 2 * 3600_000) continue
                out += RainSlot(s, 15, p.optDouble(i, 0.0) >= 0.1)
                minutelyEnd = s + 15 * 60_000
            }
        }
        j.optJSONObject("hourly")?.let { h ->
            val t = h.getJSONArray("time"); val p = h.getJSONArray("precipitation")
            val pr = h.optJSONArray("precipitation_probability")
            for (i in 0 until t.length()) {
                val s = epoch(t.getString(i))
                if (s + 3600_000 <= minutelyEnd || s > now + 12 * 3600_000) continue
                val mm = p.optDouble(i, 0.0); val prob = pr?.optInt(i, 0) ?: 0
                out += RainSlot(maxOf(s, minutelyEnd), 60, mm >= 0.2 || (prob >= 70 && mm >= 0.1))
            }
        }
        return out.sortedBy { it.start }
    }

    // ---------------- Met.no (aggregated like the app's loadFromMetNo) ----------------
    private fun fetchMetNo(lat: Double, lon: Double): Src? = try {
        val url = "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=${"%.4f".format(java.util.Locale.US, lat)}&lon=${"%.4f".format(java.util.Locale.US, lon)}"
        val ts = JSONObject(get(url) ?: throw Exception("no body")).getJSONObject("properties").getJSONArray("timeseries")
        val zone = ZoneId.systemDefault()
        class Acc { var mn = Double.POSITIVE_INFINITY; var mx = Double.NEGATIVE_INFINITY; val codes = LinkedHashMap<Int, Int>() }
        val byDay = LinkedHashMap<String, Acc>()
        var curTemp = Double.NaN; var curCode = 0; var curDay: Boolean? = null
        for (i in 0 until ts.length()) {
            val e = ts.getJSONObject(i)
            val data = e.getJSONObject("data")
            val temp = data.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature")
            val sym = data.optJSONObject("next_1_hours")?.optJSONObject("summary")?.optString("symbol_code")
                ?: data.optJSONObject("next_6_hours")?.optJSONObject("summary")?.optString("symbol_code")
            val code = metnoSymbolToWmo(sym)
            if (i == 0) { curTemp = temp; curCode = code; curDay = sym?.let { !it.endsWith("_night") } }
            val day = Instant.parse(e.getString("time")).atZone(zone).toLocalDate().toString()
            val a = byDay.getOrPut(day) { Acc() }
            a.mn = minOf(a.mn, temp); a.mx = maxOf(a.mx, temp)
            a.codes[code] = (a.codes[code] ?: 0) + 1
        }
        val days = LinkedHashMap<String, Day>()
        for ((k, a) in byDay) days[k] = Day(round1(a.mx), round1(a.mn), a.codes.maxByOrNull { it.value }?.key ?: 3)
        Src(curTemp, curCode, curDay, days)
    } catch (e: Exception) {
        Log.e(TAG, "Met.no error: ${e.message}"); null
    }

    private fun metnoSymbolToWmo(sym: String?): Int {
        if (sym.isNullOrEmpty()) return 0
        val s = sym.replace(Regex("_(day|night|polartwilight)$"), "")
        return when (s) {
            "clearsky" -> 0; "fair" -> 1; "partlycloudy" -> 2; "cloudy" -> 3; "fog" -> 45
            "lightrain" -> 51; "rain" -> 63; "heavyrain" -> 65
            "lightrainshowers" -> 80; "rainshowers" -> 81; "heavyrainshowers" -> 82
            "lightsleet", "sleet", "heavysleet" -> 67
            "lightsleetshowers", "sleetshowers" -> 83; "heavysleetshowers" -> 84
            "lightsnow" -> 71; "snow" -> 73; "heavysnow" -> 75
            "lightsnowshowers", "snowshowers" -> 85; "heavysnowshowers" -> 86
            "heavyrainandthunder", "heavysleetandthunder", "heavysnowandthunder",
            "heavyrainshowersandthunder", "heavysleetshowersandthunder", "heavysnowshowersandthunder" -> 99
            "sleetandthunder", "snowandthunder", "sleetshowersandthunder", "snowshowersandthunder" -> 96
            else -> if (s.contains("thunder")) 95 else 0
        }
    }

    // ---------------- Best of (median + majority code, like the app's loadBestOf) ----------------
    private fun median(v: List<Double?>): Double? {
        val s = v.filterNotNull().filter { it.isFinite() }.sorted()
        if (s.isEmpty()) return null
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    private fun majority(codes: List<Int?>, order: List<Int>): Int {
        val v = codes.withIndex().filter { it.value != null }
        if (v.isEmpty()) return 0
        val cnt = v.groupingBy { it.value!! }.eachCount().entries.sortedByDescending { it.value }
        if (cnt.size == 1 || cnt[0].value > cnt[1].value) return cnt[0].key
        for (i in order) v.firstOrNull { it.index == i }?.let { return it.value!! }
        return v[0].value!!
    }

    private fun bestOf(srcs: List<Src?>, order: List<Int>): Src? {
        val ok = srcs.filterNotNull()
        if (ok.isEmpty()) return null
        if (ok.size == 1) return ok[0]
        val base = srcs[0] ?: ok[0]
        val days = LinkedHashMap<String, Day>()
        for (date in base.days.keys) {
            val ds = srcs.map { it?.days?.get(date) }
            days[date] = Day(
                round1(median(ds.map { it?.max }) ?: continue),
                round1(median(ds.map { it?.min }) ?: continue),
                majority(ds.map { it?.code }, order)
            )
        }
        return Src(
            round1(median(srcs.map { it?.curTemp }) ?: base.curTemp),
            majority(srcs.map { it?.curCode }, order),
            srcs[0]?.isDay ?: base.isDay,
            days
        )
    }

    // ---------------- rain note ----------------
    /** Short Slovak note about rain for the next hours, or null when there is no data. */
    fun rainNote(rain: List<RainSlot>, now: Long = System.currentTimeMillis()): Pair<String, Boolean>? {
        val slots = rain.filter { it.start + it.minutes * 60_000L > now }
        if (slots.isEmpty()) return null
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        fun hm(t: Long) = fmt.format(java.util.Date(t))
        val first = slots[0]
        if (first.wet && first.start <= now) {
            val dry = slots.firstOrNull { !it.wet }
            return (if (dry != null) "Prší · do ${hm(dry.start)}" else "Prší") to true
        }
        val wet = slots.firstOrNull { it.wet }
        if (wet != null) {
            val mins = ((wet.start - now) / 60_000L).toInt()
            return (if (mins in 1..59) "Dážď o $mins min" else "Dážď o ${hm(wet.start)}") to true
        }
        val last = slots.last()
        return "Bez dažďa do ${hm(last.start + last.minutes * 60_000L)}" to false
    }

    // ---------------- cache ----------------
    fun serialize(d: WeatherData): String = JSONObject().apply {
        put("city", d.city)
        put("todayTemp", d.todayTemp); put("todayMax", d.todayMax); put("todayMin", d.todayMin)
        put("tomorrowTemp", d.tomorrowTemp); put("tomorrowMax", d.tomorrowMax); put("tomorrowMin", d.tomorrowMin)
        put("todayCode", d.todayCode); put("todayIsDay", d.todayIsDay); put("tomorrowCode", d.tomorrowCode)
        put("source", d.source)
        put("rain", JSONArray().apply { d.rain.forEach { put(JSONArray().put(it.start).put(it.minutes).put(it.wet)) } })
    }.toString()

    fun deserialize(s: String): WeatherData? = try {
        val j = JSONObject(s)
        val rain = ArrayList<RainSlot>()
        j.optJSONArray("rain")?.let { a ->
            for (i in 0 until a.length()) a.getJSONArray(i).let { rain += RainSlot(it.getLong(0), it.getInt(1), it.getBoolean(2)) }
        }
        WeatherData(
            city = j.optString("city", ""),
            todayTemp = j.getInt("todayTemp"), todayMax = j.getInt("todayMax"), todayMin = j.getInt("todayMin"),
            tomorrowTemp = j.getInt("tomorrowTemp"), tomorrowMax = j.getInt("tomorrowMax"), tomorrowMin = j.getInt("tomorrowMin"),
            todayCode = j.optInt("todayCode", -1), todayIsDay = j.optBoolean("todayIsDay", true),
            tomorrowCode = j.optInt("tomorrowCode", -1),
            source = j.optString("source", "openmeteo"),
            rain = rain
        )
    } catch (e: Exception) { null }

    // ---------------- presentation helpers ----------------
    fun tempColor(t: Int): Int = when {
        t <= -10 -> 0xFF4d9fff.toInt()
        t <= 0   -> 0xFFa0d4ff.toInt()
        t <= 5   -> 0xFFe0f0ff.toInt()
        t <= 15  -> 0xFFffffff.toInt()
        t <= 20  -> 0xFFffe066.toInt()
        t <= 28  -> 0xFFffaa00.toInt()
        else     -> 0xFFff4d6d.toInt()
    }

    /** Vector icon for a WMO weather code. light = icon variant for light widget background. */
    fun iconRes(code: Int, isDay: Boolean, light: Boolean): Int = when (code) {
        0 -> if (isDay) (if (light) R.drawable.wi_clear_day_l else R.drawable.wi_clear_day)
             else (if (light) R.drawable.wi_clear_night_l else R.drawable.wi_clear_night)
        1, 2 -> if (isDay) (if (light) R.drawable.wi_partly_day_l else R.drawable.wi_partly_day)
                else (if (light) R.drawable.wi_partly_night_l else R.drawable.wi_partly_night)
        45, 48 -> if (light) R.drawable.wi_fog_l else R.drawable.wi_fog
        51, 53, 55, 56, 57 -> if (light) R.drawable.wi_drizzle_l else R.drawable.wi_drizzle
        61, 63, 65, 80, 81, 82 -> if (light) R.drawable.wi_rain_l else R.drawable.wi_rain
        66, 67, 83, 84 -> if (light) R.drawable.wi_sleet_l else R.drawable.wi_sleet
        71, 73, 75, 77, 85, 86 -> if (light) R.drawable.wi_snow_l else R.drawable.wi_snow
        95, 96, 99 -> if (light) R.drawable.wi_thunder_l else R.drawable.wi_thunder
        else -> if (light) R.drawable.wi_cloudy_l else R.drawable.wi_cloudy
    }
}
