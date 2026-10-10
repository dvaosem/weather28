package com.dvaosem.weather28.ui

import android.Manifest
import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.dvaosem.weather28.R
import com.dvaosem.weather28.widget.WidgetConfigActivity
import com.dvaosem.weather28.widget.AlertPrefs
import com.dvaosem.weather28.widget.AlertScheduler
import android.webkit.JavascriptInterface

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    @Volatile private var pendingUpdateUrl: String? = null

    private fun jsCall(fn: String, arg: String) {
        runOnUiThread {
            if (::webView.isInitialized) webView.evaluateJavascript("window.$fn&&window.$fn($arg)", null)
        }
    }
    private val locationPermissionRequest = 1001

    inner class AndroidBridge {
        @JavascriptInterface
        fun refreshAllWidgets() {
            val manager = android.appwidget.AppWidgetManager.getInstance(this@MainActivity)
            val ids = manager.getAppWidgetIds(
                android.content.ComponentName(this@MainActivity, com.dvaosem.weather28.widget.WeatherWidget::class.java)
            )
            runOnUiThread {
                for (id in ids) com.dvaosem.weather28.widget.WeatherWidget.updateWidget(this@MainActivity, manager, id)
            }
        }

        @JavascriptInterface
        fun openWidgetSettings() {
            val manager = AppWidgetManager.getInstance(this@MainActivity)
            val ids = manager.getAppWidgetIds(
                ComponentName(this@MainActivity, com.dvaosem.weather28.widget.WeatherWidget::class.java)
            )
            val widgetId = if (ids.isNotEmpty()) ids[0] else AppWidgetManager.INVALID_APPWIDGET_ID
            val intent = Intent(this@MainActivity, WidgetConfigActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                putExtra("from_app", true)
            }
            runOnUiThread { startActivity(intent) }
        }

        @JavascriptInterface
        fun hasWidget(): Boolean {
            val manager = AppWidgetManager.getInstance(this@MainActivity)
            val ids = manager.getAppWidgetIds(
                ComponentName(this@MainActivity, com.dvaosem.weather28.widget.WeatherWidget::class.java)
            )
            return ids.isNotEmpty()
        }

        @JavascriptInterface
        fun getAlertSettings(): String {
            val m = AlertPrefs.morningEnabled(this@MainActivity)
            val s = AlertPrefs.stormEnabled(this@MainActivity)
            val h = AlertPrefs.morningHour(this@MainActivity)
            return "{\"morning\":$m,\"storm\":$s,\"hour\":$h}"
        }

        @JavascriptInterface
        fun setAlerts(morning: Boolean, storm: Boolean, hour: Int, lat: Double, lon: Double, city: String) {
            AlertPrefs.setMorning(this@MainActivity, morning)
            AlertPrefs.setStorm(this@MainActivity, storm)
            AlertPrefs.setMorningHour(this@MainActivity, hour)
            if (lat != 0.0 && lon != 0.0) AlertPrefs.setLocation(this@MainActivity, lat, lon, city)
            // request notification permission on Android 13+
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this@MainActivity,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1002)
                }
            }
            AlertScheduler.reschedule(this@MainActivity)
        }

        @JavascriptInterface
        fun shareImage(base64: String) {
            // JS bridge beží mimo UI vlákna -> startActivity musí na UI vlákno
            runOnUiThread {
                try {
                    val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                    val dir = java.io.File(cacheDir, "shared").apply { mkdirs() }
                    val file = java.io.File(dir, "weather28.png")
                    java.io.FileOutputStream(file).use { it.write(bytes) }
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this@MainActivity, "$packageName.fileprovider", file
                    )
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, com.dvaosem.weather28.widget.WidgetPrefs.localized(this@MainActivity).getString(R.string.share_title)))
                } catch (e: Exception) { e.printStackTrace() }
            }
        }

        private fun maybeAskNotifPerm() {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this@MainActivity,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1002)
            }
        }

        @JavascriptInterface
        fun setMorningAlert(on: Boolean, h: Int, m: Int) {
            AlertPrefs.setMorning(this@MainActivity, on)
            AlertPrefs.setMorningHour(this@MainActivity, h)
            if (on) runOnUiThread { maybeAskNotifPerm() }
            AlertScheduler.reschedule(this@MainActivity)
        }

        @JavascriptInterface
        fun setStormAlert(on: Boolean) {
            AlertPrefs.setStorm(this@MainActivity, on)
            if (on) runOnUiThread { maybeAskNotifPerm() }
            AlertScheduler.reschedule(this@MainActivity)
        }

        /** Data source chosen in the app — the widget uses the same one. order = accuracy ranking "0,2,1". */
        @JavascriptInterface
        fun setSource(src: String, order: String) {
            val changed = com.dvaosem.weather28.widget.WidgetPrefs.saveSource(this@MainActivity, src, order)
            if (changed) refreshAllWidgets()
        }

        /** App language (sk/en) for widget texts, widget settings and notifications. */
        @JavascriptInterface
        fun setLang(lang: String) {
            if (com.dvaosem.weather28.widget.WidgetPrefs.saveLang(this@MainActivity, lang)) refreshAllWidgets()
        }

        @JavascriptInterface
        fun getAppBuild(): Int = UpdateManager.currentBuild(this@MainActivity)

        /** Checks GitHub Releases; answers via window.w28OnUpdate({current, latest, notes, manual, error}). */
        @JavascriptInterface
        fun checkUpdate(manual: Boolean) {
            Thread {
                val cur = UpdateManager.currentBuild(this@MainActivity)
                val rel = UpdateManager.latest()
                pendingUpdateUrl = rel?.apkUrl
                val json = org.json.JSONObject().apply {
                    put("current", cur); put("latest", rel?.build ?: 0)
                    put("notes", rel?.notes ?: ""); put("manual", manual); put("error", rel == null)
                }
                jsCall("w28OnUpdate", json.toString())
            }.start()
        }

        /** Downloads the newest APK and opens the system installer. Progress via window.w28OnUpdateProgress(p):
         *  0..100 download, 101 = installer opened, -1 = failed, -2 = needs "install unknown apps" permission. */
        @JavascriptInterface
        fun installUpdate() {
            val url = pendingUpdateUrl ?: return jsCall("w28OnUpdateProgress", "-1")
            if (!UpdateManager.canInstall(this@MainActivity)) {
                runOnUiThread { UpdateManager.openInstallPermissionSettings(this@MainActivity) }
                return jsCall("w28OnUpdateProgress", "-2")
            }
            Thread {
                val apk = UpdateManager.download(this@MainActivity, url) { p -> jsCall("w28OnUpdateProgress", p.toString()) }
                if (apk == null) { jsCall("w28OnUpdateProgress", "-1"); return@Thread }
                runOnUiThread { UpdateManager.install(this@MainActivity, apk) }
                jsCall("w28OnUpdateProgress", "101")
            }.start()
        }

        /** Share plain text (e.g. the APK download link) via the system share sheet. */
        @JavascriptInterface
        fun shareText(text: String) {
            runOnUiThread {
                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
                startActivity(Intent.createChooser(send, "Weather28"))
            }
        }

        /** Fallback: open the latest release page in the browser to download the APK manually. */
        @JavascriptInterface
        fun openUpdatePage() {
            runOnUiThread {
                startActivity(Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/dvaosem/weather28/releases/latest")))
            }
        }

        @JavascriptInterface
        fun setAlertLocation(lat: Double, lon: Double, city: String) {
            if (lat != 0.0 && lon != 0.0)
                AlertPrefs.setLocation(this@MainActivity, lat, lon, city)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webview)
        // Keep content below the status bar and above the nav bar instead of drawing under them
        ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setupWebView()
        checkLocationPermission()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            displayZoomControls = false
            builtInZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, true, false)
            }
            override fun onConsoleMessage(msg: ConsoleMessage) = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {}
        }

        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        webView.loadUrl("file:///android_asset/weather28.html")
    }

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                locationPermissionRequest
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == locationPermissionRequest) webView.reload()
    }

    override fun onResume() { super.onResume(); webView.onResume() }
    override fun onPause() { super.onPause(); webView.onPause() }
}
