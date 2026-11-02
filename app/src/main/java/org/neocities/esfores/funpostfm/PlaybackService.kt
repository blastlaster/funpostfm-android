package org.neocities.esfores.funpostfm

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The radio. Keeps playing with the app closed or the screen off, and gives
 * Android its lock-screen / notification / headset / car controls.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var poll: Job? = null
    private var retries = 0
    private var paused = false
    private var icyTitle: String? = null
    // "Keep playing when headphones or a speaker disconnect", switched while the radio runs.
    private val prefsWatch = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        if (key == "keepPlaying") (session?.player as? ExoPlayer)?.setHandleAudioBecomingNoisy(!p.getBoolean(key, false))
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(!App.keepPlaying(this))   // headphones out: stop, don't blast the room (unless asked)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) { retries = 0; startPolling() } else if (!player.playWhenReady) stopPolling()
            }

            // It's live radio: after a pause, Play reconnects to what's on now instead of
            // carrying on from the old buffer. (A plain pause keeps the lock-screen controls.)
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) { paused = player.mediaItemCount > 0; return }
                if (paused) {
                    paused = false
                    player.setMediaItem(liveItem(App.status.value))
                    player.prepare()
                }
            }

            // The stream says the song changed, in step with the audio: look now, not at the next 30 s tick.
            override fun onMetadata(metadata: Metadata) {
                for (i in 0 until metadata.length()) {
                    val e = metadata[i]
                    if (e is IcyInfo && e.title != null && e.title != icyTitle) {
                        val first = icyTitle == null
                        icyTitle = e.title
                        if (!first) startPolling(songChanged = true)
                    }
                }
            }

            // Lost the stream (bad signal, server hiccup): try again, waiting longer each time.
            override fun onPlayerError(error: PlaybackException) {
                if (++retries > 6) return
                scope.launch {
                    delay(1500L * retries * retries)
                    player.setMediaItem(liveItem(App.status.value))
                    player.prepare()
                    player.play()
                }
            }
        })
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setCallback(object : MediaSession.Callback {
                // Whatever asks to play (app, headset, car, lock screen), play the live stream fresh.
                override fun onAddMediaItems(s: MediaSession, c: MediaSession.ControllerInfo, items: MutableList<MediaItem>) =
                    com.google.common.util.concurrent.Futures.immediateFuture(items.map { liveItem(App.status.value) }.toMutableList())
            })
            .setSessionActivity(open)
            .build()
        App.prefs(this).registerOnSharedPreferenceChangeListener(prefsWatch)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    /** The stream, labelled with what's on now. A fresh address each time so a resume is live, not an old buffer. */
    private fun liveItem(st: Station.Status) = MediaItem.Builder()
        .setMediaId("funpostfm")
        .setUri(Station.STREAM + "?t=" + System.currentTimeMillis())
        .setMediaMetadata(meta(st))
        .build()

    private fun meta(st: Station.Status): MediaMetadata {
        val title = st.song.title.ifBlank { if (st.live) "Live: ${st.dj}" else "FunpostFM" }
        val artist = if (st.live) listOf(st.song.artist, "🔴 ${st.dj}").filter { it.isNotBlank() }.joinToString(" · ") else st.song.artist
        return MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist.ifBlank { "FunpostFM" })
            .setStation("FunpostFM")
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .apply { if (st.song.img.isNotBlank()) setArtworkUri(Uri.parse(st.song.img)) }
            .build()
    }

    // While playing, keep the song info on the lock screen fresh: every 30 s, and right
    // away when the stream says the song changed (then again shortly if the station's
    // info hasn't caught up yet).
    private fun startPolling(songChanged: Boolean = false) {
        if (poll?.isActive == true && !songChanged) return
        poll?.cancel()
        val before = App.status.value.song
        poll = scope.launch {
            var catchUp = if (songChanged) 5 else 0
            while (isActive) {
                val st = Station.check()
                App.publish(this@PlaybackService, st)
                val p = session?.player ?: break
                val cur = p.currentMediaItem
                if (cur != null && p.mediaItemCount > 0) {
                    val m = meta(st)
                    if (m.title != cur.mediaMetadata.title || m.artist != cur.mediaMetadata.artist || m.artworkUri != cur.mediaMetadata.artworkUri) {
                        p.replaceMediaItem(p.currentMediaItemIndex, cur.buildUpon().setMediaMetadata(m).build())
                    }
                }
                if (catchUp > 0 && st.song == before) { catchUp--; delay(2_000); continue }
                catchUp = 0
                delay(30_000)
            }
        }
    }

    private fun stopPolling() { poll?.cancel(); poll = null }

    // Swiped away from recents while stopped: go away entirely.
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        App.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsWatch)
        scope.cancel()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
