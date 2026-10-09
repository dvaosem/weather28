package com.dvaosem.weather28.widget

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class WeatherData(
    val city: String,
    val todayTemp: Int,       // current temperature
    val todayMax: Int,
    val todayMin: Int,
    val todayIcon: String,
    val todayDesc: String,
    val todayRain: Int,
    val todayWind: Int,
    val tomorrowTemp: Int,    // tomorrow max
    val tomorrowMax: Int,
    val tomorrowMin: Int,
    val tomorrowIcon: String,
    val tomorrowDesc: String,
    val tomorrowRain: Int,
    val tomorrowWind: Int
)

object WeatherFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun fetch(lat: Double, lon: Double, cityName: String): WeatherData? {
        return try {
            val url = "https://api.open-meteo.com/v1/forecast?" +
                    "latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code" +
                    "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max,wind_speed_10m_max" +
                    "&timezone=auto" +
                    "&forecast_days=2"

            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null
            Log.d("WeatherFetcher", body.take(300))
            val json = JSONObject(body)

            val current = json.getJSONObject("current")
            val currentTemp = current.getDouble("temperature_2m").toInt()
            val currentCode = current.getInt("weather_code")

            val daily = json.getJSONObject("daily")
            val maxTemps = daily.getJSONArray("temperature_2m_max")
            val minTemps = daily.getJSONArray("temperature_2m_min")
            val codes = daily.getJSONArray("weather_code")
            val rains = daily.getJSONArray("precipitation_probability_max")
            val winds = daily.getJSONArray("wind_speed_10m_max")

            WeatherData(
                city = cityName,
                todayTemp = currentTemp,
                todayMax = maxTemps.getDouble(0).toInt(),
                todayMin = minTemps.getDouble(0).toInt(),
                todayIcon = wmoToEmoji(currentCode),
                todayDesc = wmoToDesc(currentCode),
                todayRain = if (rains.isNull(0)) 0 else rains.getInt(0),
                todayWind = winds.getDouble(0).toInt(),
                tomorrowTemp = maxTemps.getDouble(1).toInt(),
                tomorrowMax = maxTemps.getDouble(1).toInt(),
                tomorrowMin = minTemps.getDouble(1).toInt(),
                tomorrowIcon = wmoToEmoji(codes.getInt(1)),
                tomorrowDesc = wmoToDesc(codes.getInt(1)),
                tomorrowRain = if (rains.isNull(1)) 0 else rains.getInt(1),
                tomorrowWind = winds.getDouble(1).toInt()
            )
        } catch (e: Exception) {
            Log.e("WeatherFetcher", "Error: ${e.message}", e)
            null
        }
    }


    fun serialize(d: WeatherData): String {
        return org.json.JSONObject().apply {
            put("city", d.city)
            put("todayTemp", d.todayTemp); put("todayMax", d.todayMax); put("todayMin", d.todayMin)
            put("todayIcon", d.todayIcon); put("todayDesc", d.todayDesc)
            put("todayRain", d.todayRain); put("todayWind", d.todayWind)
            put("tomorrowTemp", d.tomorrowTemp); put("tomorrowMax", d.tomorrowMax); put("tomorrowMin", d.tomorrowMin)
            put("tomorrowIcon", d.tomorrowIcon); put("tomorrowDesc", d.tomorrowDesc)
            put("tomorrowRain", d.tomorrowRain); put("tomorrowWind", d.tomorrowWind)
        }.toString()
    }

    fun deserialize(s: String): WeatherData? {
        return try {
            val j = org.json.JSONObject(s)
            WeatherData(
                city = j.getString("city"),
                todayTemp = j.getInt("todayTemp"), todayMax = j.getInt("todayMax"), todayMin = j.getInt("todayMin"),
                todayIcon = j.getString("todayIcon"), todayDesc = j.getString("todayDesc"),
                todayRain = j.getInt("todayRain"), todayWind = j.getInt("todayWind"),
                tomorrowTemp = j.getInt("tomorrowTemp"), tomorrowMax = j.getInt("tomorrowMax"), tomorrowMin = j.getInt("tomorrowMin"),
                tomorrowIcon = j.getString("tomorrowIcon"), tomorrowDesc = j.getString("tomorrowDesc"),
                tomorrowRain = j.getInt("tomorrowRain"), tomorrowWind = j.getInt("tomorrowWind")
            )
        } catch (e: Exception) { null }
    }

    fun tempColor(t: Int): Int {
        return when {
            t <= -10 -> 0xFF4d9fff.toInt()
            t <= 0   -> 0xFFa0d4ff.toInt()
            t <= 5   -> 0xFFe0f0ff.toInt()
            t <= 15  -> 0xFFffffff.toInt()
            t <= 20  -> 0xFFffe066.toInt()
            t <= 28  -> 0xFFffaa00.toInt()
            else     -> 0xFFff4d6d.toInt()
        }
    }

    private fun wmoToEmoji(code: Int): String = when (code) {
        0 -> "☀️"; 1 -> "🌤️"; 2 -> "⛅"; 3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55 -> "🌦️"
        61, 63, 65 -> "🌧️"
        66, 67 -> "🌨️"
        71, 73, 75, 77 -> "❄️"
        80, 81, 82 -> "🌦️"
        85, 86 -> "🌨️"
        95 -> "⛈️"; 96, 99 -> "⛈️"
        else -> "🌡️"
    }

    private fun wmoToDesc(code: Int): String = when (code) {
        0 -> "Jasno"; 1 -> "Prevažne jasno"; 2 -> "Polojasno"; 3 -> "Zamračené"
        45, 48 -> "Hmla"
        51, 53, 55 -> "Mrholenie"
        61, 63, 65 -> "Dážď"
        66, 67 -> "Ľadový dážď"
        71, 73, 75, 77 -> "Sneh"
        80, 81, 82 -> "Prehánky"
        85, 86 -> "Sneh. prehánky"
        95 -> "Búrka"; 96, 99 -> "Búrka"
        else -> "Premenlivé"
    }
}
