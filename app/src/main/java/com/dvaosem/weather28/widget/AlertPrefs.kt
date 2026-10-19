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

    /** Remembers which alerts were already shown (keys like "20261008-wind" or "ma-<id>"), keeps the last 60. */
    fun wasAlerted(c: Context, key: String) = (p(c).getString("alerted", "") ?: "").split("|").contains(key)
    fun markAlerted(c: Context, key: String) {
        val list = (p(c).getString("alerted", "") ?: "").split("|").filter { it.isNotEmpty() && it != key } + key
        p(c).edit().putString("alerted", list.takeLast(60).joinToString("|")).apply()
    }

    fun cachedDistrict(c: Context, key: String): String? =
        if (p(c).getString("district_key", null) == key) p(c).getString("district", null) else null
    fun cacheDistrict(c: Context, key: String, d: String) =
        p(c).edit().putString("district_key", key).putString("district", d).apply()

    // ---- smart forecast notification (warming/cooling, frost, rain, wind, heat) ----
    const val SM_TEMP = 1; const val SM_FROST = 2; const val SM_RAIN = 4; const val SM_WIND = 8; const val SM_HEAT = 16
    fun smartEnabled(c: Context) = p(c).getBoolean("smart", false)
    fun smartHour(c: Context) = p(c).getInt("smart_hour", 19)
    fun smartMinute(c: Context) = p(c).getInt("smart_min", 0)
    fun smartMask(c: Context) = p(c).getInt("smart_mask", 31)
    fun setSmart(c: Context, on: Boolean, h: Int, m: Int, mask: Int) =
        p(c).edit().putBoolean("smart", on).putInt("smart_hour", h.coerceIn(0, 23))
            .putInt("smart_min", m.coerceIn(0, 59)).putInt("smart_mask", mask).apply()

    fun lastStormDay(c: Context) = p(c).getString("last_storm", "") ?: ""
    fun setLastStormDay(c: Context, d: String) = p(c).edit().putString("last_storm", d).apply()
}
