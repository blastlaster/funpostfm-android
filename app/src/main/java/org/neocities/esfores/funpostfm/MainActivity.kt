package org.neocities.esfores.funpostfm

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

private val Bg = Color(0xFF160F24)
private val Panel = Color(0xFF22183A)
private val Line = Color(0xFF3A2B5C)
private val Dim = Color(0xFFA99CC6)
private val Accent = Color(0xFFFF3D6E)
private val Auto = Color(0xFF4ECDFF)
private val Violet = Color(0xFFB03CFF)

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_PLAY = "play" }

    private var controller: MediaController? = null
    private var playing by mutableStateOf(false)
    private var buffering by mutableStateOf(false)
    private var alerts by mutableStateOf(true)
    private var playWhenConnected = false

    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        alerts = App.alertsOn(this)
        playWhenConnected = intent?.getBooleanExtra(EXTRA_PLAY, false) == true
        if (alerts) askForNotifications()

        // Fresh station info every 30 s while the app is on screen.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { App.publish(this@MainActivity, Station.check()); delay(30_000) }
            }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, secondary = Auto, background = Bg, surface = Panel)) {
                val st by App.status.collectAsState()
                Screen(st)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_PLAY, false)) { if (controller != null) play() else playWhenConnected = true }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            val c = MediaController.Builder(this@MainActivity, SessionToken(this@MainActivity, ComponentName(this@MainActivity, PlaybackService::class.java))).buildAsync().await()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = sync()
            })
            sync()
            if (playWhenConnected) { playWhenConnected = false; play() }
        }
    }

    override fun onStop() {
        controller?.release()
        controller = null
        super.onStop()
    }

    private fun sync() {
        val c = controller ?: return
        playing = c.playWhenReady && c.mediaItemCount > 0
        buffering = playing && c.playbackState == Player.STATE_BUFFERING
    }

    private fun play() {
        val c = controller ?: return
        c.setMediaItem(MediaItem.Builder().setMediaId("funpostfm").build())   // the service swaps in the live stream
        c.prepare()
        c.play()
    }

    private fun stop() {
        val c = controller ?: return
        c.stop()
        c.clearMediaItems()
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openSite() = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Station.SITE)))

    @Composable
    private fun Screen(st: Station.Status) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF3B1F5E), Bg, Bg)))) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Header(st.live)
                Spacer(Modifier.height(20.dp))
                AsyncImage(
                    model = st.song.img.ifBlank { null },
                    contentDescription = null,
                    placeholder = painterResource(R.drawable.art),
                    error = painterResource(R.drawable.art),
                    fallback = painterResource(R.drawable.art),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(260.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black),
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    if (st.live) "🔴 LIVE" + (if (st.dj.isNotBlank()) " — ${st.dj}" else "") + (if (st.show.isNotBlank()) " · ${st.show}" else "") else "AUTODJ",
                    color = if (st.live) Accent else Auto, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    st.song.title.ifBlank { if (st.live) "On the air" else if (st.via == "none") "Tuning in…" else "" },
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (st.song.artist.isNotBlank()) Text(st.song.artist, color = Dim, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (st.requestedBy.isNotBlank()) Text("requested by ${st.requestedBy}", color = Dim, fontSize = 13.sp)
                Spacer(Modifier.height(22.dp))
                PlayButton()
                Spacer(Modifier.height(8.dp))
                Text(
                    listOfNotNull(
                        when { buffering -> "Connecting…"; playing -> "Playing"; else -> null },
                        st.listeners?.takeIf { it > 0 }?.let { "👤 $it listening" },
                    ).joinToString("   ·   ").ifEmpty { " " },
                    color = Dim, fontSize = 13.sp,
                )
                Spacer(Modifier.height(22.dp))
                OutlinedButton(onClick = ::openSite, modifier = Modifier.fillMaxWidth()) {
                    Text("Requests, chat & call-in on the site ↗", color = Auto)
                }
                if (st.recent.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Card {
                        Text("Recently played", color = Dim, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        st.recent.forEach { r ->
                            Text(r.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (r.artist.isNotBlank()) Text(r.artist, color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Ping me when someone goes live", color = Color.White)
                            Text("Checks about every 15 minutes while the app is closed", color = Dim, fontSize = 12.sp)
                        }
                        Switch(
                            checked = alerts,
                            onCheckedChange = { alerts = it; App.setAlerts(this@MainActivity, it); if (it) askForNotifications() },
                            colors = SwitchDefaults.colors(checkedTrackColor = Accent),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun Header(live: Boolean) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Image(painterResource(R.drawable.logo), null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("FunpostFM", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("where funposting is a way of life", color = Dim, fontSize = 12.sp)
            }
            if (live) {
                val pulse by rememberInfiniteTransition(label = "live").animateFloat(1f, 0.5f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
                Box(Modifier.alpha(pulse).clip(RoundedCornerShape(6.dp)).background(Accent).padding(horizontal = 10.dp, vertical = 3.dp)) {
                    Text("LIVE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }

    @Composable
    private fun PlayButton() {
        val pulse by rememberInfiniteTransition(label = "buf").animateFloat(1f, 0.55f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "b")
        Box(
            Modifier.size(84.dp).alpha(if (buffering) pulse else 1f).clip(CircleShape)
                .background(Brush.linearGradient(listOf(Accent, Violet))),
            contentAlignment = Alignment.Center,
        ) {
            Button(
                onClick = { if (playing) stop() else play() },
                modifier = Modifier.fillMaxSize(),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            ) {
                Text(if (playing) "■" else "▶", fontSize = 30.sp, color = Color.White)
            }
        }
    }

    @Composable
    private fun Card(content: @Composable () -> Unit) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel.copy(alpha = 0.85f)).padding(14.dp),
            verticalArrangement = Arrangement.Top,
        ) { content() }
    }
}
