package org.neocities.esfores.funpostfm

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * New versions from the GitHub releases (the APK isn't from a store, so nothing else updates it).
 * Looks at most every 6 hours while the app is open, or when asked; a new version downloads
 * with Android's own downloader, then Android's installer takes over (same signing key, so it
 * installs over this one and keeps the settings).
 */
object Updates {
    private const val LATEST = "https://api.github.com/repos/blastlaster/funpostfm-android/releases/latest"
    private const val EVERY = 6 * 3600_000L

    data class State(
        val status: String = "idle",   // idle | checking | none | available | downloading | ready | error
        val version: String = "",
        val url: String = "",
        val message: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun current(c: Context): String =
        runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "0"

    /** "0.10.1" > "0.9.3", "v" or not. */
    private fun newer(a: String, b: String): Boolean {
        val x = a.trimStart('v').split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.trimStart('v').split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrNull(i) ?: 0) - (y.getOrNull(i) ?: 0)
            if (d != 0) return d > 0
        }
        return false
    }

    suspend fun check(c: Context, asked: Boolean = false) {
        val p = App.prefs(c)
        val s = _state.value.status
        if (s == "downloading" || s == "ready" || s == "checking") return
        if (!asked && System.currentTimeMillis() - p.getLong("updateChecked", 0) < EVERY) {
            // Already found one earlier: say so again.
            val v = p.getString("updateVersion", "") ?: ""
            if (v.isNotBlank() && newer(v, current(c))) _state.value = State("available", v, p.getString("updateUrl", "") ?: "")
            return
        }
        _state.value = State("checking")
        _state.value = withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(LATEST).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val r = try {
                    if (conn.responseCode !in 200..299) error("GitHub said ${conn.responseCode}")
                    JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                } finally { conn.disconnect() }
                val v = r.optString("tag_name").trimStart('v')
                val assets = r.optJSONArray("assets")
                val url = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk") }?.optString("browser_download_url") ?: ""
                p.edit().putLong("updateChecked", System.currentTimeMillis()).putString("updateVersion", v).putString("updateUrl", url).apply()
                if (v.isNotBlank() && url.isNotBlank() && newer(v, current(c))) State("available", v, url)
                else State("none").also { clean(c) }   // up to date: the downloaded installer isn't needed any more
            }.getOrElse { State("error", message = "Couldn’t check for updates.") }
        }
    }

    private fun clean(c: Context) = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.listFiles()?.forEach { if (it.name.endsWith(".apk")) it.delete() }

    private fun apk(c: Context, v: String) = File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "FunpostFM-$v.apk")

    /** Download the new version, then hand it to Android's installer. */
    suspend fun download(c: Context) {
        val st = _state.value
        if (st.status != "available" && st.status != "error" || st.url.isBlank()) return
        val dm = c.getSystemService(DownloadManager::class.java)
        clean(c)
        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(st.url))
                .setTitle("FunpostFM ${st.version}")
                .setMimeType("application/vnd.android.package-archive")
                .setDestinationUri(Uri.fromFile(apk(c, st.version)))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        )
        _state.value = st.copy(status = "downloading", message = "Downloading…")
        while (true) {
            delay(700)
            val q = dm.query(DownloadManager.Query().setFilterById(id)) ?: break
            val (status, got, total) = q.use {
                if (!it.moveToFirst()) Triple(DownloadManager.STATUS_FAILED, 0L, 0L)
                else Triple(
                    it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                )
            }
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    _state.value = st.copy(status = "ready", message = "Downloaded")
                    App.prefs(c).edit().putLong("updateDownload", id).apply()
                    install(c)
                    return
                }
                DownloadManager.STATUS_FAILED -> { _state.value = st.copy(status = "error", message = "The download didn’t work. Try again."); return }
                else -> if (total > 0) _state.value = st.copy(status = "downloading", message = "Downloading… ${got * 100 / total}%")
            }
        }
    }

    /** Android's installer, for the downloaded version. The first time, Android asks to allow installs from this app. */
    fun install(c: Context) {
        if (Build.VERSION.SDK_INT >= 26 && !c.packageManager.canRequestPackageInstalls()) {
            _state.value = _state.value.copy(status = "ready", message = "Allow FunpostFM to install updates, then come back and tap Install.")
            c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val dm = c.getSystemService(DownloadManager::class.java)
        val uri = dm.getUriForDownloadedFile(App.prefs(c).getLong("updateDownload", -1)) ?: run {
            _state.value = _state.value.copy(status = "available", message = "")
            return
        }
        c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
