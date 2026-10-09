package com.dvaosem.weather28.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * In-app updates from GitHub Releases of dvaosem/weather28.
 * CI publishes every build as release "build-<run number>" with weather28.apk attached,
 * and the app's versionCode is the same run number.
 */
object UpdateManager {

    private const val REPO = "dvaosem/weather28"
    private const val TAG = "UpdateManager"

    data class Release(val build: Int, val apkUrl: String, val notes: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun currentBuild(ctx: Context): Int {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    }

    /** Latest published release, or null when it can't be reached (offline, private repo...). */
    fun latest(): Release? = try {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Weather28")
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) { Log.w(TAG, "latest: HTTP ${r.code}"); return null }
            val j = JSONObject(r.body?.string() ?: return null)
            val build = j.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return null
            val assets = j.optJSONArray("assets") ?: return null
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break }
            }
            Release(build, url ?: return null, j.optString("body").take(300))
        }
    } catch (e: Exception) {
        Log.e(TAG, "latest failed", e); null
    }

    /** Downloads the APK into cache/updates; reports progress 0..100. */
    fun download(ctx: Context, url: String, onProgress: (Int) -> Unit): File? = try {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "weather28.apk")
        val req = Request.Builder().url(url).header("User-Agent", "Weather28").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val body = r.body ?: return null
            val total = body.contentLength()
            var done = 0L; var last = -1
            body.byteStream().use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        o.write(buf, 0, n); done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) { last = p; onProgress(p) }
                        }
                    }
                }
            }
        }
        out
    } catch (e: Exception) {
        Log.e(TAG, "download failed", e); null
    }

    /** Android requires the user to allow installs from this app once. */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(activity: Activity) {
        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${activity.packageName}")))
    }

    fun install(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
