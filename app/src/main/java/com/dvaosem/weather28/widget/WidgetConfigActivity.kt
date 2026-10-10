package com.dvaosem.weather28.widget

import android.content.Context
import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.dvaosem.weather28.R
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class WidgetConfigActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var fromApp = false
    private var selectedLat = 48.1486
    private var selectedLon = 17.1077
    private var selectedCity = "Bratislava"
    private val GPS_PERM = 2001

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(WidgetPrefs.localized(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        fromApp = intent?.getBooleanExtra("from_app", false) ?: false
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            if (fromApp) {
                // Get first available widget ID
                val manager = AppWidgetManager.getInstance(this)
                val ids = manager.getAppWidgetIds(android.content.ComponentName(this, WeatherWidget::class.java))
                if (ids.isNotEmpty()) appWidgetId = ids[0] else { finish(); return }
            } else { finish(); return }
        }
        setResult(RESULT_CANCELED)
        setContentView(R.layout.activity_widget_config)
        setupUI()
    }

    private fun setupUI() {
        val etCity = findViewById<EditText>(R.id.et_city)
        val btnSearch = findViewById<Button>(R.id.btn_search)
        val btnGps = findViewById<Button>(R.id.btn_gps)
        val tvResult = findViewById<TextView>(R.id.tv_search_result)
        val rgUnit = findViewById<RadioGroup>(R.id.rg_unit)
        val rgStyle = findViewById<RadioGroup>(R.id.rg_style)
        val rgInterval = findViewById<RadioGroup>(R.id.rg_interval)
        val rgMode = findViewById<RadioGroup>(R.id.rg_mode)
        val btnSave = findViewById<Button>(R.id.btn_save)
        val progress = findViewById<ProgressBar>(R.id.progress_search)
        val sbOpacity = findViewById<SeekBar>(R.id.sb_opacity)
        val tvOpacityVal = findViewById<TextView>(R.id.tv_opacity_val)
        val tvOpacityLabel = findViewById<TextView>(R.id.tv_opacity_label)
        val sbGlass = findViewById<SeekBar>(R.id.sb_glass)
        val tvGlassVal = findViewById<TextView>(R.id.tv_glass_val)
        val tvGlassLabel = findViewById<TextView>(R.id.tv_glass_label)

        // Editing an existing widget (from the app or long-press → Settings on the home screen)
        if (fromApp || WidgetPrefs.prefs(this).contains("style_$appWidgetId")) btnSave.text = getString(R.string.cfg_save)

        // Pre-fill saved values
        etCity.setText(WidgetPrefs.getCity(this, appWidgetId))
        selectedCity = WidgetPrefs.getCity(this, appWidgetId)
        selectedLat = WidgetPrefs.getLat(this, appWidgetId)
        selectedLon = WidgetPrefs.getLon(this, appWidgetId)
        tvResult.text = "📍 $selectedCity"

        when (WidgetPrefs.getUnit(this, appWidgetId)) {
            "F" -> rgUnit.check(R.id.rb_fahrenheit)
            else -> rgUnit.check(R.id.rb_celsius)
        }
        when (WidgetPrefs.getStyle(this, appWidgetId)) {
            "light" -> rgStyle.check(R.id.rb_light)
            "transparent" -> rgStyle.check(R.id.rb_transparent)
            else -> rgStyle.check(R.id.rb_dark)
        }
        when (WidgetPrefs.getInterval(this, appWidgetId)) {
            15 -> rgInterval.check(R.id.rb_15min)
            60 -> rgInterval.check(R.id.rb_60min)
            else -> rgInterval.check(R.id.rb_30min)
        }
        when (WidgetPrefs.getMode(this, appWidgetId)) {
            "single" -> rgMode.check(R.id.rb_mode_single)
            else -> rgMode.check(R.id.rb_mode_duo)
        }

        // Opacity setup (dark/light)
        val opSteps = intArrayOf(0, 20, 40, 60, 80, 95)
        val savedOp = WidgetPrefs.getOpacity(this, appWidgetId)
        sbOpacity.progress = opSteps.indexOf(savedOp).let { if (it < 0) 4 else it }
        tvOpacityVal.text = "${opSteps[sbOpacity.progress]}%"

        // Glass intensity setup (transparent)
        val glassSteps = intArrayOf(0, 20, 40, 60, 80, 95)
        val savedGlass = WidgetPrefs.getGlass(this, appWidgetId)
        sbGlass.progress = glassSteps.indexOf(savedGlass).let { if (it < 0) 1 else it }
        tvGlassVal.text = "${glassSteps[sbGlass.progress]}%"

        fun updateOpacityVisibility() {
            val isTransparent = rgStyle.checkedRadioButtonId == R.id.rb_transparent
            val opVis = if (isTransparent) View.GONE else View.VISIBLE
            sbOpacity.visibility = opVis
            tvOpacityVal.visibility = opVis
            tvOpacityLabel.visibility = opVis
            val glassVis = if (isTransparent) View.VISIBLE else View.GONE
            sbGlass.visibility = glassVis
            tvGlassVal.visibility = glassVis
            tvGlassLabel.visibility = glassVis
        }
        updateOpacityVisibility()
        rgStyle.setOnCheckedChangeListener { _, _ -> updateOpacityVisibility() }
        sbOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tvOpacityVal.text = "${opSteps[p]}%"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        sbGlass.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tvGlassVal.text = "${glassSteps[p]}%"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        btnSearch.setOnClickListener {
            val q = etCity.text.toString().trim()
            if (q.isEmpty()) return@setOnClickListener
            progress.visibility = View.VISIBLE
            btnSearch.isEnabled = false
            tvResult.text = getString(R.string.cfg_searching)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val url = "https://geocoding-api.open-meteo.com/v1/search?name=${java.net.URLEncoder.encode(q, "UTF-8")}&count=1&language=sk&format=json"
                    val resp = client.newCall(Request.Builder().url(url).build()).execute()
                    val json = org.json.JSONObject(resp.body?.string() ?: "")
                    val results = json.optJSONArray("results")
                    withContext(Dispatchers.Main) {
                        progress.visibility = View.GONE
                        btnSearch.isEnabled = true
                        if (results != null && results.length() > 0) {
                            val r = results.getJSONObject(0)
                            selectedLat = r.getDouble("latitude")
                            selectedLon = r.getDouble("longitude")
                            selectedCity = r.getString("name")
                            tvResult.text = "✅ $selectedCity, ${r.optString("country_code", "")}"
                        } else tvResult.text = getString(R.string.cfg_not_found)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        progress.visibility = View.GONE
                        btnSearch.isEnabled = true
                        tvResult.text = getString(R.string.cfg_error, e.message ?: "")
                    }
                }
            }
        }

        btnGps.setOnClickListener { requestGps(tvResult) }

        btnSave.setOnClickListener {
            val unit = if (rgUnit.checkedRadioButtonId == R.id.rb_fahrenheit) "F" else "C"
            val style = when (rgStyle.checkedRadioButtonId) {
                R.id.rb_light -> "light"
                R.id.rb_transparent -> "transparent"
                else -> "dark"
            }
            val interval = when (rgInterval.checkedRadioButtonId) {
                R.id.rb_15min -> 15; R.id.rb_60min -> 60; else -> 30
            }
            val mode = if (rgMode.checkedRadioButtonId == R.id.rb_mode_single) "single" else "duo"

            WidgetPrefs.save(this, appWidgetId, selectedCity, selectedLat, selectedLon, unit, style)
            AlertPrefs.setLocation(this, selectedLat, selectedLon, selectedCity)
            WidgetPrefs.saveInterval(this, appWidgetId, interval)
            WidgetPrefs.saveMode(this, appWidgetId, mode)
            val opSteps2 = intArrayOf(0, 20, 40, 60, 80, 95)
            WidgetPrefs.saveOpacity(this, appWidgetId, opSteps2[sbOpacity.progress])
            WidgetPrefs.saveGlass(this, appWidgetId, opSteps2[sbGlass.progress])

            scheduleRefresh(interval)
            WeatherWidget.updateWidget(this, AppWidgetManager.getInstance(this), appWidgetId)

            if (!fromApp) {
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            }
            finish()
        }
    }

    private fun requestGps(tvResult: TextView) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), GPS_PERM)
            return
        }
        tvResult.text = getString(R.string.cfg_locating)
        val fusedClient = LocationServices.getFusedLocationProviderClient(this)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            fusedClient.lastLocation.addOnSuccessListener { loc: Location? ->
                if (loc != null) {
                    selectedLat = loc.latitude
                    selectedLon = loc.longitude
                    reverseGeocode(loc.latitude, loc.longitude, tvResult)
                } else tvResult.text = getString(R.string.cfg_no_location)
            }
        }
    }

    private fun reverseGeocode(lat: Double, lon: Double, tvResult: TextView) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = "https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&format=json&accept-language=sk"
                val resp = client.newCall(Request.Builder().url(url).addHeader("User-Agent", "Weather28App").build()).execute()
                val json = org.json.JSONObject(resp.body?.string() ?: "")
                val addr = json.optJSONObject("address")
                val city = addr?.optString("city")?.takeIf { it.isNotEmpty() }
                    ?: addr?.optString("town")?.takeIf { it.isNotEmpty() }
                    ?: addr?.optString("village")?.takeIf { it.isNotEmpty() }
                    ?: "Moja poloha"
                selectedCity = city
                withContext(Dispatchers.Main) { tvResult.text = "✅ $city (GPS)" }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvResult.text = "✅ GPS (${String.format("%.2f", lat)}, ${String.format("%.2f", lon)})"
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == GPS_PERM && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            requestGps(findViewById(R.id.tv_search_result))
        }
    }

    private fun scheduleRefresh(intervalMinutes: Int) {
        val work = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(
            intervalMinutes.toLong(), TimeUnit.MINUTES
        ).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "widget_refresh_$appWidgetId", ExistingPeriodicWorkPolicy.UPDATE, work)
    }
}
