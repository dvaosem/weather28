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
    val todayTier: Int = 0,      // rain tier for the rest of today: 0 dry, 1 possible, 2 rain
    val tomorrowTier: Int = 0,
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
    /** One forecast hour. prob = null when the source has no rain probability (Met.no outside the Nordics). */
    private class Hour(val time: String, val temp: Double?, val code: Int, val mm: Double, val prob: Int?)
    private class Src(val curTemp: Double, val curCode: Int, val curMm: Double, val isDay: Boolean?,
                      val days: Map<String, Day>, val hours: List<Hour>)
    private class OmResult(val src: Src, val minutely: List<Pair<String, Double>>)

    // ================= RAIN: 1:1 copy of the app's rule (weather28.html, "RAIN: one rule for the whole app") =================
    // tier 0 = dry, 1 = possible (blue drop), 2 = rain (rain/drizzle icon). Change numbers in BOTH places.
    private object RainCfg {
        const val hP2 = 40; const val hMm2 = 0.1; const val hP1 = 10; const val hMm1 = 0.05; const val hMmOnly2 = 0.2; const val hMmOnly1 = 0.05
        const val dP2 = 40.0; const val dMm2 = 0.3; const val dP1 = 10.0; const val dMm1 = 0.05; const val dMmOnly2 = 0.5; const val night = 0.5
        const val nowMm = 0.05   // "rain in X min" from 15-min data, same as the app's minutecast
    }
    private fun isRainCode(c: Int) = c in 51..57 || c in 61..65 || c in 80..82
    private fun isDryCode(c: Int) = c in 0..3 || c == 45 || c == 48
    private fun hourTier(p: Int?, mm: Double): Int =
        if (p == null) (if (mm >= RainCfg.hMmOnly2) 2 else if (mm >= RainCfg.hMmOnly1) 1 else 0)
        else if (p >= RainCfg.hP2 && mm >= RainCfg.hMm2) 2 else if (p >= RainCfg.hP1 || mm >= RainCfg.hMm1) 1 else 0
    private fun dayTier(p: Double?, mm: Double): Int =
        if (p == null) (if (mm >= RainCfg.dMmOnly2) 2 else if (mm >= RainCfg.dMm1) 1 else 0)
        else if (p >= RainCfg.dP2 && mm >= RainCfg.dMm2) 2 else if (p >= RainCfg.dP1 || mm >= RainCfg.dMm1) 1 else 0
    private fun tierCode(c: Int, tier: Int, temp: Double?, mm: Double): Int = when {
        isRainCode(c) && tier < 2 -> if (c >= 80) 2 else 3
        tier == 2 && c in 0..3 && (temp == null || temp > 1) -> if (mm < 0.5) 51 else 61
        else -> c
    }
    private fun dayBaseCode(codes: List<Int>): Int {
        codes.filter { it >= 95 }.maxOrNull()?.let { return it }
        codes.filter { it in 66..77 || it in 83..86 }.maxOrNull()?.let { return it }
        codes.filter { isRainCode(it) }.maxOrNull()?.let { return it }
        return codes.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key }).firstOrNull()?.key ?: 3
    }
    private class DayRain(val tier: Int, val code: Int?, val sumW: Double)
    /** Rain tier + icon base for one date; fromKey = "yyyy-MM-ddTHH" to count only hours still ahead (today). */
    private fun dayRain(hours: List<Hour>, date: String, fromKey: String?): DayRain {
        var pm: Double? = null; var sum = 0.0; var cnt = 0; val codes = ArrayList<Int>()
        for (h in hours) {
            if (!h.time.startsWith(date)) continue
            if (fromKey != null && h.time.substring(0, 13) < fromKey) continue
            val hr = h.time.substring(11, 13).toIntOrNull() ?: continue
            val w = if (hr >= 22 || hr < 6) RainCfg.night else 1.0
            h.prob?.let { pm = maxOf(pm ?: 0.0, it * w) }
            sum += h.mm * w; cnt++; codes += h.code
        }
        if (cnt == 0) return DayRain(0, null, 0.0)
        return DayRain(dayTier(pm, sum), dayBaseCode(codes), sum)
    }

    fun fetch(lat: Double, lon: Double, cityName: String, source: String = "openmeteo", order: List<Int> = listOf(0, 1, 2)): WeatherData? {
        val om = fetchOpenMeteo(lat, lon, null) // always: 15-min rain for "rain in X min"
        val chosen: Src = when (source) {
            "ecmwf" -> fetchEcmwf(lat, lon)
            "metno" -> fetchMetNo(lat, lon)
            "best" -> bestOf(listOf(om?.src, fetchMetNo(lat, lon), fetchEcmwf(lat, lon)), order)
            else -> om?.src
        } ?: om?.src ?: return null

        val today = LocalDate.now().toString()
        val tomorrow = LocalDate.now().plusDays(1).toString()
        val nowKey = LocalDateTime.now().toString().substring(0, 13)
        val d0 = chosen.days[today] ?: chosen.days.values.firstOrNull() ?: return null
        val d1 = chosen.days[tomorrow] ?: chosen.days.values.drop(1).firstOrNull() ?: d0

        val curHour = chosen.hours.firstOrNull { it.time.startsWith(nowKey) }
        val curTier = curHour?.let { hourTier(it.prob, it.mm) } ?: 0
        val todayCode = if (curHour == null) chosen.curCode
            else tierCode(chosen.curCode, curTier, chosen.curTemp, maxOf(curHour.mm, chosen.curMm))
        val todayRain = dayRain(chosen.hours, today, nowKey)          // widget "today" = rest of the day, like the app's today tab
        val tmr = dayRain(chosen.hours, tomorrow, null)
        val tomorrowCode = if (tmr.code == null) d1.code else tierCode(tmr.code, tmr.tier, d1.max, tmr.sumW)

        // home station (Viničné): measured temperature and measured rain beat the model for "now"
        val st = if (isHome(lat, lon, cityName)) fetchStation() else null
        var nowCode = todayCode; var nowTier = todayRain.tier
        var rainList = rainSlots(om?.minutely ?: emptyList(), chosen.hours)
        if (st != null && st.rate > 0 && (nowCode in 0..3 || nowCode == 45 || nowCode == 48 || isRainCode(nowCode)) && st.temp > 1) {
            nowCode = if (st.rate >= 2.5) 63 else if (st.rate >= 0.5) 61 else 51; nowTier = 2
            rainList = (listOf(RainSlot(System.currentTimeMillis() - 5 * 60_000L, 15, true)) + rainList).sortedBy { it.start }
        }

        return WeatherData(
            city = cityName,
            todayTemp = jsRound(st?.temp ?: chosen.curTemp),
            todayMax = jsRound(d0.max),
            todayMin = jsRound(d0.min),
            tomorrowTemp = jsRound(d1.max),
            tomorrowMax = jsRound(d1.max),
            tomorrowMin = jsRound(d1.min),
            todayCode = nowCode,
            todayIsDay = chosen.isDay ?: om?.src?.isDay ?: true,
            tomorrowCode = tomorrowCode,
            todayTier = nowTier,
            tomorrowTier = tmr.tier,
            source = source,
            rain = rainList
        )
    }

    // ---------------- home station (station.php proxy, same data as the app's station card) ----------------
    private class Station(val temp: Double, val rate: Double)
    private fun isHome(lat: Double, lon: Double, city: String): Boolean =
        Regex("^vini[cč]n[eé]$", RegexOption.IGNORE_CASE).matches(java.text.Normalizer.normalize(city.trim(), java.text.Normalizer.Form.NFC)) ||
            (Math.abs(lat - 48.269) <= 0.05 && Math.abs(lon - 17.301) <= 0.08)
    /** Fresh reading (<= 30 min old) or null. */
    private fun fetchStation(): Station? = try {
        val j = JSONObject(get("https://dvaosem.com/station.php") ?: throw Exception("no body"))
        val age = if (j.isNull("ageMin")) 999 else j.optInt("ageMin", 999)
        if (!j.optBoolean("ok") || age > 30 || j.isNull("temp")) null
        else Station(j.getDouble("temp"), if (j.isNull("precipRate")) 0.0 else j.optDouble("precipRate", 0.0))
    } catch (e: Exception) { Log.e(TAG, "station error: ${e.message}"); null }

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
                "&current=temperature_2m,weather_code,is_day,precipitation" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                "&hourly=temperature_2m,weather_code,precipitation,precipitation_probability" +
                (if (model == null) "&minutely_15=precipitation" else "") +
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
        val hours = ArrayList<Hour>()
        j.optJSONObject("hourly")?.let { h ->
            val t = h.getJSONArray("time"); val p = h.optJSONArray("precipitation"); val pr = h.optJSONArray("precipitation_probability")
            val tc = h.optJSONArray("temperature_2m"); val wc = h.optJSONArray("weather_code")
            for (i in 0 until t.length()) hours += Hour(
                t.getString(i),
                tc?.let { if (it.isNull(i)) null else it.optDouble(i) },
                wc?.optInt(i, 3) ?: 3,
                p?.optDouble(i, 0.0)?.takeIf { !it.isNaN() } ?: 0.0,
                pr?.let { if (it.isNull(i)) null else it.optInt(i, 0) })
        }
        val minutely = ArrayList<Pair<String, Double>>()
        j.optJSONObject("minutely_15")?.let { m ->
            val t = m.getJSONArray("time"); val p = m.getJSONArray("precipitation")
            for (i in 0 until t.length()) minutely += t.getString(i) to (p.optDouble(i, 0.0).takeIf { !it.isNaN() } ?: 0.0)
        }
        val src = Src(
            cur.getDouble("temperature_2m"),
            cur.optInt("weather_code", 3),
            cur.optDouble("precipitation", 0.0).takeIf { !it.isNaN() } ?: 0.0,
            if (cur.has("is_day")) cur.optInt("is_day", 1) == 1 else null,
            days, hours
        )
        OmResult(src, minutely)
    } catch (e: Exception) {
        Log.e(TAG, "Open-Meteo ($model) error: ${e.message}"); null
    }

    /** ECMWF deterministic run + rain probability from the ECMWF ensemble (% of members with >= 0.1 mm), like the app. */
    private fun fetchEcmwf(lat: Double, lon: Double): Src? {
        val base = fetchOpenMeteo(lat, lon, "ecmwf_ifs025")?.src ?: return null
        val ens = HashMap<String, Int>()
        try {
            val j = JSONObject(get("https://ensemble-api.open-meteo.com/v1/ensemble?latitude=$lat&longitude=$lon" +
                    "&hourly=precipitation&models=ecmwf_ifs025&forecast_days=3&timezone=auto") ?: throw Exception("no body"))
            val h = j.getJSONObject("hourly"); val t = h.getJSONArray("time")
            val keys = h.keys().asSequence().filter { it.startsWith("precipitation") }.map { h.getJSONArray(it) }.toList()
            for (i in 0 until t.length()) {
                var n = 0; var wet = 0
                for (a in keys) { if (a.isNull(i)) continue; n++; if (a.optDouble(i, 0.0) >= 0.1) wet++ }
                if (n > 0) ens[t.getString(i)] = Math.floor(wet * 100.0 / n + 0.5).toInt()
            }
        } catch (e: Exception) { Log.e(TAG, "ECMWF ensemble error: ${e.message}") }
        return Src(base.curTemp, base.curCode, base.curMm, base.isDay, base.days,
            base.hours.map { Hour(it.time, it.temp, it.code, it.mm, it.prob ?: ens[it.time]) })
    }

    /** "Rain in X min": 15-min Open-Meteo data first (>= 0.05 mm), then the chosen source's hours (rain only at tier 2). */
    private fun rainSlots(minutely: List<Pair<String, Double>>, hours: List<Hour>): List<RainSlot> {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val out = ArrayList<RainSlot>()
        fun epoch(t: String) = LocalDateTime.parse(t).atZone(zone).toInstant().toEpochMilli()
        var minutelyEnd = now
        for ((t, mm) in minutely) {
            val s = epoch(t)
            if (s + 15 * 60_000 <= now || s > now + 2 * 3600_000) continue
            out += RainSlot(s, 15, mm >= RainCfg.nowMm)
            minutelyEnd = s + 15 * 60_000
        }
        for (h in hours) {
            val s = epoch(h.time)
            if (s + 3600_000 <= minutelyEnd || s > now + 24 * 3600_000) continue
            out += RainSlot(maxOf(s, minutelyEnd), 60, hourTier(h.prob, h.mm) == 2)
        }
        return out.sortedBy { it.start }
    }

    // ---------------- Met.no (same as the app's loadFromMetNo: 6 h steps expanded to hours) ----------------
    private fun fetchMetNo(lat: Double, lon: Double): Src? = try {
        val url = "https://api.met.no/weatherapi/locationforecast/2.0/complete?lat=${"%.4f".format(java.util.Locale.US, lat)}&lon=${"%.4f".format(java.util.Locale.US, lon)}"
        val ts = JSONObject(get(url) ?: throw Exception("no body")).getJSONObject("properties").getJSONArray("timeseries")
        val zone = ZoneId.systemDefault()
        val hours = ArrayList<Hour>()
        var curTemp = Double.NaN; var curCode = 0; var curDay: Boolean? = null; var curMm = 0.0
        for (i in 0 until ts.length()) {
            val e = ts.getJSONObject(i)
            val data = e.getJSONObject("data")
            val temp = data.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature")
            val n1 = data.optJSONObject("next_1_hours"); val n6 = data.optJSONObject("next_6_hours")
            val blk = n1 ?: n6
            val steps = if (n1 != null) 1 else if (n6 != null) 6 else 0
            val sym = blk?.optJSONObject("summary")?.optString("symbol_code")
            val code = metnoSymbolToWmo(sym)
            val amt = blk?.optJSONObject("details")?.let { if (it.has("precipitation_amount")) it.optDouble("precipitation_amount", 0.0) else null }
            val prob = blk?.optJSONObject("details")?.let { if (it.has("probability_of_precipitation")) jsRound(it.optDouble("probability_of_precipitation")) else null }
            if (i == 0) { curTemp = temp; curCode = code; curDay = sym?.let { !it.endsWith("_night") }; curMm = amt ?: 0.0 }
            if (steps == 0) continue
            val next = if (i + 1 < ts.length()) ts.getJSONObject(i + 1).getJSONObject("data").getJSONObject("instant").getJSONObject("details").optDouble("air_temperature", temp) else temp
            val t0 = Instant.parse(e.getString("time"))
            for (s in 0 until steps) {
                val tt = round1(temp + (next - temp) * s / steps)
                val local = LocalDateTime.ofInstant(t0.plusSeconds(s * 3600L), zone).toString().substring(0, 16)
                hours += Hour(local, tt, code, if (amt == null) 0.0 else Math.round(amt / steps * 100.0) / 100.0, prob)
            }
        }
        class Acc { var mn = Double.POSITIVE_INFINITY; var mx = Double.NEGATIVE_INFINITY; val codes = LinkedHashMap<Int, Int>() }
        val byDay = LinkedHashMap<String, Acc>()
        for (h in hours) {
            val a = byDay.getOrPut(h.time.substring(0, 10)) { Acc() }
            h.temp?.let { a.mn = minOf(a.mn, it); a.mx = maxOf(a.mx, it) }
            a.codes[h.code] = (a.codes[h.code] ?: 0) + 1
        }
        val days = LinkedHashMap<String, Day>()
        for ((k, a) in byDay) if (a.mx.isFinite()) days[k] = Day(round1(a.mx), round1(a.mn), a.codes.maxByOrNull { it.value }?.key ?: 3)
        Src(curTemp, curCode, curMm, curDay, days, hours)
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
        // hours aligned by timestamp; unknown probabilities (Met.no) are left out of the median
        val maps = srcs.map { s -> s?.hours?.associateBy { it.time } }
        val hours = base.hours.map { bh ->
            val hs = maps.map { it?.get(bh.time) }
            Hour(bh.time,
                median(hs.map { it?.temp })?.let { round1(it) },
                majority(hs.map { it?.code }, order),
                round1(median(hs.map { it?.mm }) ?: 0.0),
                median(hs.map { it?.prob?.toDouble() })?.let { jsRound(it) })
        }
        return Src(
            round1(median(srcs.map { it?.curTemp }) ?: base.curTemp),
            majority(srcs.map { it?.curCode }, order),
            round1(median(srcs.map { it?.curMm }) ?: 0.0),
            srcs[0]?.isDay ?: base.isDay,
            days, hours
        )
    }

    // ---------------- rain note ----------------
    /** Short Slovak note about rain for the next hours, or null when there is no data. */
    fun rainNote(rain: List<RainSlot>, en: Boolean = false, now: Long = System.currentTimeMillis()): Pair<String, Boolean>? {
        val slots = rain.filter { it.start + it.minutes * 60_000L > now }
        if (slots.isEmpty()) return null
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        fun hm(t: Long) = fmt.format(java.util.Date(t))
        val first = slots[0]
        if (first.wet && first.start <= now) {
            val dry = slots.firstOrNull { !it.wet }
            return (if (en) (if (dry != null) "Raining · until ${hm(dry.start)}" else "Raining")
                    else (if (dry != null) "Prší · do ${hm(dry.start)}" else "Prší")) to true
        }
        val wet = slots.firstOrNull { it.wet }
        if (wet != null) {
            val mins = ((wet.start - now) / 60_000L).toInt()
            return (if (en) (if (mins in 1..59) "Rain in $mins min" else "Rain at ${hm(wet.start)}")
                    else (if (mins in 1..59) "Dážď o $mins min" else "Dážď o ${hm(wet.start)}")) to true
        }
        // Dry for the whole checked horizon (24 h). Say so, instead of a clock time that only marks the horizon end.
        return (if (en) "No rain next 24 h" else "Bez dažďa 24 h") to false
    }

    // ---------------- cache ----------------
    fun serialize(d: WeatherData): String = JSONObject().apply {
        put("city", d.city)
        put("todayTemp", d.todayTemp); put("todayMax", d.todayMax); put("todayMin", d.todayMin)
        put("tomorrowTemp", d.tomorrowTemp); put("tomorrowMax", d.tomorrowMax); put("tomorrowMin", d.tomorrowMin)
        put("todayCode", d.todayCode); put("todayIsDay", d.todayIsDay); put("tomorrowCode", d.tomorrowCode)
        put("todayTier", d.todayTier); put("tomorrowTier", d.tomorrowTier)
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
            todayTier = j.optInt("todayTier", 0), tomorrowTier = j.optInt("tomorrowTier", 0),
            source = j.optString("source", "openmeteo"),
            rain = rain
        )
    } catch (e: Exception) { null }

    // ---------------- presentation helpers ----------------
    fun tempColor(t: Int): Int = when {
        t <= -10 -> 0xFFc9a27a.toInt()
        t <= 0   -> 0xFFa0d4ff.toInt()
        t <= 5   -> 0xFFe0f0ff.toInt()
        t <= 15  -> 0xFFffffff.toInt()
        t <= 20  -> 0xFFffe066.toInt()
        t <= 28  -> 0xFFffaa00.toInt()
        else     -> 0xFFc8553d.toInt()
    }

    /** Vector icon for a WMO weather code. light = icon variant for light widget background. */
    /** Same rule as the app: blue drop on a dry-looking icon when the rain tier is >= 1. */
    fun wantsDrop(code: Int, tier: Int): Boolean = tier >= 1 && (code in 0..3 || code == 45 || code == 48 || code < 0)

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
