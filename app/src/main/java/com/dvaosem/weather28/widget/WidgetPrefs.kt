package com.dvaosem.weather28.widget

import android.content.Context
import android.content.SharedPreferences

object WidgetPrefs {

    private const val PREFS = "weather28_widget_prefs"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getCity(ctx: Context, id: Int) = prefs(ctx).getString("city_$id", "Bratislava") ?: "Bratislava"
    fun getLat(ctx: Context, id: Int) = prefs(ctx).getFloat("lat_$id", 48.1486f).toDouble()
    fun getLon(ctx: Context, id: Int) = prefs(ctx).getFloat("lon_$id", 17.1077f).toDouble()
    fun getUnit(ctx: Context, id: Int) = prefs(ctx).getString("unit_$id", "C") ?: "C"
    fun getStyle(ctx: Context, id: Int) = prefs(ctx).getString("style_$id", "dark") ?: "dark"
    fun getSize(ctx: Context, id: Int) = prefs(ctx).getString("size_$id", "M") ?: "M"
    fun getInterval(ctx: Context, id: Int) = prefs(ctx).getInt("interval_$id", 30)
    fun getUseGps(ctx: Context, id: Int) = prefs(ctx).getBoolean("gps_$id", false)
    fun getCache(ctx: Context, id: Int) = prefs(ctx).getString("cache_$id", null)

    fun save(ctx: Context, id: Int, city: String, lat: Double, lon: Double, unit: String, style: String) {
        prefs(ctx).edit().apply {
            putString("city_$id", city)
            putFloat("lat_$id", lat.toFloat())
            putFloat("lon_$id", lon.toFloat())
            putString("unit_$id", unit)
            putString("style_$id", style)
            apply()
        }
    }

    fun saveSize(ctx: Context, id: Int, size: String) = prefs(ctx).edit().putString("size_$id", size).apply()
    fun saveInterval(ctx: Context, id: Int, minutes: Int) = prefs(ctx).edit().putInt("interval_$id", minutes).apply()
    fun saveUseGps(ctx: Context, id: Int, v: Boolean) = prefs(ctx).edit().putBoolean("gps_$id", v).apply()
    fun getOpacity(ctx: Context, id: Int) = prefs(ctx).getInt("opacity_$id", 80)
    fun saveOpacity(ctx: Context, id: Int, v: Int) = prefs(ctx).edit().putInt("opacity_$id", v).apply()

    // intenzita liquid glass efektu (len pre "transparent" štýl)
    fun getGlass(ctx: Context, id: Int) = prefs(ctx).getInt("glass_$id", 20)
    fun saveGlass(ctx: Context, id: Int, v: Int) = prefs(ctx).edit().putInt("glass_$id", v).apply()

    fun getMode(ctx: Context, id: Int) = prefs(ctx).getString("mode_$id", "duo") ?: "duo"
    fun saveMode(ctx: Context, id: Int, mode: String) = prefs(ctx).edit().putString("mode_$id", mode).apply()

    fun saveCache(ctx: Context, id: Int, data: String) = prefs(ctx).edit().putString("cache_$id", data).apply()

    fun remove(ctx: Context, id: Int) {
        prefs(ctx).edit().apply {
            remove("city_$id"); remove("lat_$id"); remove("lon_$id")
            remove("unit_$id"); remove("style_$id"); remove("size_$id")
            remove("interval_$id"); remove("gps_$id"); remove("cache_$id")
            apply()
        }
    }
}
