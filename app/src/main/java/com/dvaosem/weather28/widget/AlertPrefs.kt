package com.dvaosem.weather28.widget

import android.content.Context

object AlertPrefs {
    private const val P = "weather28_alerts"
    private fun p(c: Context) = c.getSharedPreferences(P, Context.MODE_PRIVATE)

    fun morningEnabled(c: Context) = p(c).getBoolean("morning", false)
    fun stormEnabled(c: Context) = p(c).getBoolean("storm", false)
    fun setMorning(c: Context, v: Boolean) = p(c).edit().putBoolean("morning", v).apply()
    fun setStorm(c: Context, v: Boolean) = p(c).edit().putBoolean("storm", v).apply()

    fun getLat(c: Context) = p(c).getFloat("lat", 48.1486f).toDouble()
    fun getLon(c: Context) = p(c).getFloat("lon", 17.1077f).toDouble()
    fun getCity(c: Context) = p(c).getString("city", "Bratislava") ?: "Bratislava"
    fun setLocation(c: Context, lat: Double, lon: Double, city: String) =
        p(c).edit().putFloat("lat", lat.toFloat()).putFloat("lon", lon.toFloat())
            .putString("city", city).apply()

    fun morningHour(c: Context) = p(c).getInt("morning_hour", 7)
    fun setMorningHour(c: Context, h: Int) = p(c).edit().putInt("morning_hour", h).apply()

    fun lastStormDay(c: Context) = p(c).getString("last_storm", "") ?: ""
    fun setLastStormDay(c: Context, d: String) = p(c).edit().putString("last_storm", d).apply()
}
