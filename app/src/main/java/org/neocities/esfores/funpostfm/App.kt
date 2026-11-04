package org.neocities.esfores.funpostfm

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CH_LIVE, getString(R.string.channel_live), NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = getString(R.string.channel_live_desc) }
            )
        }
        scheduleChecks(this)
    }

    companion object {
        const val CH_LIVE = "live"
        private const val PREFS = "fpfm"

        private val _status = MutableStateFlow(Station.Status())
        /** The latest look at the station, from whoever checked last. */
        val status: StateFlow<Station.Status> = _status

        fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun alertsOn(c: Context) = prefs(c).getBoolean("alerts", true)
        /** Headphones or a Bluetooth speaker gone: keep playing (out loud) instead of stopping. */
        fun keepPlaying(c: Context) = prefs(c).getBoolean("keepPlaying", false)
        fun setKeepPlaying(c: Context, on: Boolean) = prefs(c).edit().putBoolean("keepPlaying", on).apply()
        fun theme(c: Context) = prefs(c).getString("theme", "funpost") ?: "funpost"
        fun setTheme(c: Context, id: String) = prefs(c).edit().putString("theme", id).apply()

        /** This phone's own voter id for skip votes (random, nothing personal in it). */
        fun voter(c: Context): String = prefs(c).getString("voter", null) ?: java.util.UUID.randomUUID().toString().also {
            prefs(c).edit().putString("voter", it).apply()
        }

        /** 👍/👎 given on this phone, oldest first: song id → true 👍, false 👎, null (voted, but not saying which). */
        fun votes(c: Context): Map<Long, Boolean?> = (prefs(c).getString("votes", "") ?: "").split(',').mapNotNull { e ->
            val kv = e.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            kv[0].toLongOrNull()?.let { it to when (kv[1]) { "1" -> true; "0" -> false; else -> null } }
        }.toMap()

        fun rememberVote(c: Context, id: Long, like: Boolean?) {
            val list = votes(c).filterKeys { it != id }.toList() + (id to like)
            prefs(c).edit().putString("votes", list.takeLast(60).joinToString(",") { (k, v) -> "$k:" + when (v) { true -> "1"; false -> "0"; null -> "?" } }).apply()
        }

        fun setAlerts(c: Context, on: Boolean) {
            prefs(c).edit().putBoolean("alerts", on).apply()
            scheduleChecks(c)
        }

        /** Android lets an app look in at most every 15 minutes while it's closed. */
        fun scheduleChecks(c: Context) {
            val wm = WorkManager.getInstance(c)
            if (alertsOn(c)) {
                wm.enqueueUniquePeriodicWork("live-check", ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<LiveCheckWorker>(15, TimeUnit.MINUTES).build())
            } else {
                wm.cancelUniqueWork("live-check")
            }
        }

        /** Share a fresh status, and ping once per show when someone goes live. */
        fun publish(c: Context, st: Station.Status) {
            if (st.via != "none") _status.value = st
            if (!st.live) return
            val key = "live:${st.dj}:" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val p = prefs(c)
            if (p.getString("seenShow", "") == key) return
            p.edit().putString("seenShow", key).apply()
            if (alertsOn(c)) notifyLive(c, st)
        }

        private fun notifyLive(c: Context, st: Station.Status) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(c, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val open = PendingIntent.getActivity(c, 1,
                Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_PLAY, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val who = if (st.dj.isNotBlank()) "${st.dj} is on the air" else "Someone is on the air"
            val n = NotificationCompat.Builder(c, CH_LIVE)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(c.getString(R.string.live_title))
                .setContentText(who + (if (st.show.isNotBlank()) ": ${st.show}" else "") + ". Tap to listen.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(c).notify(1, n)
        }
    }
}
