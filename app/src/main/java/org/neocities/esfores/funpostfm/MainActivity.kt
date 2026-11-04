package org.neocities.esfores.funpostfm

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

/** A look for the app: its colours, light or dark. */
data class Look(
    val id: String, val name: String, val dark: Boolean,
    val bg: Color, val top: Color, val panel: Color, val line: Color,
    val text: Color, val dim: Color, val accent: Color, val accent2: Color, val auto: Color,
    val onAccent: Color = Color.White,
)

val LOOKS = listOf(
    Look("funpost", "FunpostFM", true, Color(0xFF160F24), Color(0xFF3B1F5E), Color(0xFF22183A), Color(0xFF3A2B5C), Color.White, Color(0xFFA99CC6), Color(0xFFFF3D6E), Color(0xFFB03CFF), Color(0xFF4ECDFF)),
    Look("midnight", "Midnight", true, Color(0xFF0A0A0F), Color(0xFF1B1B26), Color(0xFF15151E), Color(0xFF2A2A38), Color(0xFFEDEDF2), Color(0xFF8D8DA3), Color(0xFFFF4D6D), Color(0xFF7A5CFF), Color(0xFF4ECDFF)),
    Look("daylight", "Daylight", false, Color(0xFFF7F4FC), Color(0xFFE3D7F7), Color(0xFFFFFFFF), Color(0xFFDCD2EC), Color(0xFF1E1430), Color(0xFF6B5F85), Color(0xFFE0245E), Color(0xFF8A2BE2), Color(0xFF0077B6)),
    Look("synthwave", "Synthwave", true, Color(0xFF12002A), Color(0xFF4A0B6B), Color(0xFF240840), Color(0xFF4C1C70), Color(0xFFFFF0FF), Color(0xFFC9A0E0), Color(0xFFFF2E97), Color(0xFFFF8A00), Color(0xFF00E5FF)),
    Look("matrix", "Matrix", true, Color(0xFF000700), Color(0xFF002400), Color(0xFF001300), Color(0xFF0B3B0B), Color(0xFFC8FFC8), Color(0xFF5FAF5F), Color(0xFF39FF14), Color(0xFF00B140), Color(0xFF7CFF7C), onAccent = Color.Black),
    Look("amber", "Amber", true, Color(0xFF120A00), Color(0xFF2E1B00), Color(0xFF1F1300), Color(0xFF3F2A00), Color(0xFFFFD9A0), Color(0xFFB08850), Color(0xFFFFB000), Color(0xFFFF7A00), Color(0xFFFFCC66), onAccent = Color.Black),
)

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_PLAY = "play" }

    private var controller: MediaController? = null
    private var playing by mutableStateOf(false)
    private var buffering by mutableStateOf(false)
    private var lost by mutableStateOf(false)         // the stream broke and couldn't be got back
    private var lag by mutableLongStateOf(0L)         // how far behind the broadcast what you hear is (ms)
    private var votes by mutableStateOf<Map<Long, Boolean?>>(emptyMap())
    private var tally by mutableStateOf<Triple<Long, Int?, Int?>?>(null)   // song id, 👍, 👎 as the panel last said
    private var alerts by mutableStateOf(true)
    private var keepPlaying by mutableStateOf(false)
    private var look by mutableStateOf(LOOKS[0])
    private var menu by mutableStateOf(false)
    private var playWhenConnected = false

    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alerts = App.alertsOn(this)
        keepPlaying = App.keepPlaying(this)
        votes = App.votes(this)
        look = LOOKS.firstOrNull { it.id == App.theme(this) } ?: LOOKS[0]
        bars()
        playWhenConnected = intent?.getBooleanExtra(EXTRA_PLAY, false) == true
        if (alerts) askForNotifications()

        // Fresh station info every 30 s while the app is on screen; a look for a new version now and then.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Updates.check(this@MainActivity) }
                while (true) { App.publish(this@MainActivity, Station.check()); delay(30_000) }
            }
        }

        setContent {
            val l = look
            MaterialTheme(colorScheme = if (l.dark) darkColorScheme(primary = l.accent, secondary = l.auto, background = l.bg, surface = l.panel)
                else lightColorScheme(primary = l.accent, secondary = l.auto, background = l.bg, surface = l.panel)) {
                val st by App.status.collectAsState()
                val up by Updates.state.collectAsState()
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(l.top, l.bg, l.bg)))) {
                    if (menu) Menu(up) else Screen(st, up)
                }
                BackHandler(enabled = menu) { menu = false }
            }
        }
    }

    /** Status and navigation bars to match the look (dark icons on a light one). */
    private fun bars() {
        val t = android.graphics.Color.TRANSPARENT
        val style = if (look.dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        window.decorView.setBackgroundColor(look.bg.toArgb())
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

    /**
     * The button shows what the player is really doing. Headphones unplugged, a lost stream,
     * another app taking the sound: the radio is silent, so the button says ▶ (one tap to start again),
     * the same rule as the lock-screen controls.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun sync() {
        val c = controller ?: return
        playing = c.mediaItemCount > 0 && !Util.shouldShowPlayButton(c)
        buffering = playing && c.playbackState == Player.STATE_BUFFERING
        lost = c.playerError != null && c.playbackState == Player.STATE_IDLE
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

    private fun skip(st: Station.Status) {
        val s = st.skip
        if (s == null) {
            val why = if (st.live) "Someone’s live — no skipping a live show." else "Skipping works on AutoDJ songs while the station’s PC is on."
            Toast.makeText(this, why, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val said = Station.skipVote(st.engine, App.voter(this@MainActivity), s.musicId)
            Toast.makeText(this@MainActivity, said, Toast.LENGTH_SHORT).show()
            App.publish(this@MainActivity, Station.check())
        }
    }

    private fun rate(st: Station.Status, like: Boolean) {
        val id = st.song.id
        if (id in votes) { Toast.makeText(this, "You already voted for this one.", Toast.LENGTH_SHORT).show(); return }
        lifecycleScope.launch {
            val v = Station.rate(st.panel, id, like)
            if (v.counted) {
                App.rememberVote(this@MainActivity, id, if (v.already) null else like)
                votes = App.votes(this@MainActivity)
                if (v.up != null || v.down != null) tally = Triple(id, v.up, v.down)
            }
            Toast.makeText(this@MainActivity, v.said, Toast.LENGTH_SHORT).show()
        }
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openSite() = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Station.SITE)))

    private fun updateAction(up: Updates.State) {
        when (up.status) {
            "available", "error" -> if (up.url.isNotBlank()) lifecycleScope.launch { Updates.download(this@MainActivity) } else lifecycleScope.launch { Updates.check(this@MainActivity, asked = true) }
            "ready" -> Updates.install(this)
            "downloading", "checking" -> {}
            else -> lifecycleScope.launch { Updates.check(this@MainActivity, asked = true) }
        }
    }

    /* ---------- the radio: one screen, nothing to scroll ---------- */
    @Composable
    private fun Screen(st: Station.Status, up: Updates.State) {
        val l = look
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Header(st.live)
            if (up.status in listOf("available", "downloading", "ready")) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(l.panel).border(1.dp, l.auto, RoundedCornerShape(10.dp))
                        .clickable { updateAction(up) }.padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        when (up.status) { "ready" -> "⬆ FunpostFM ${up.version} is ready — tap to install"; "downloading" -> "⬆ ${up.message}"; else -> "⬆ FunpostFM ${up.version} is out — tap to update" },
                        color = l.auto, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            // The picture takes whatever room is left, square, never more than 300 dp.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val side = min(min(maxWidth, maxHeight), 300.dp)
                AsyncImage(
                    model = st.song.img.ifBlank { null },
                    contentDescription = null,
                    placeholder = painterResource(R.drawable.art),
                    error = painterResource(R.drawable.art),
                    fallback = painterResource(R.drawable.art),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(side).clip(RoundedCornerShape(16.dp)).background(Color.Black),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                if (st.live) "🔴 LIVE" + (if (st.dj.isNotBlank()) " — ${st.dj}" else "") + (if (st.show.isNotBlank()) " · ${st.show}" else "") else "AUTODJ",
                color = if (st.live) l.accent else l.auto, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                st.song.title.ifBlank { if (st.live) "On the air" else if (st.via == "none") "Tuning in…" else " " },
                color = l.text, fontWeight = FontWeight.Bold, fontSize = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
            Text(st.song.artist.ifBlank { " " }, color = l.dim, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (st.requestedBy.isNotBlank()) "requested by ${st.requestedBy}" else " ", color = l.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            SongBar(st)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                RoundButton("💬", "Requests, chat & call-in", enabled = true) { openSite() }
                PlayButton()
                RoundButton("⏭", "Vote to skip", enabled = st.skip != null) { skip(st) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                listOfNotNull(
                    when { buffering -> "Connecting…"; playing -> "Playing"; lost -> "Lost the stream — ▶ to try again"; else -> null },
                    st.listeners?.takeIf { it > 0 }?.let { "👤 $it listening" },
                    st.skip?.takeIf { it.votes > 0 }?.let { "⏭ ${it.votes}/${it.needed}" },
                ).joinToString("   ·   ").ifEmpty { " " },
                color = l.dim, fontSize = 13.sp, maxLines = 1,
            )
        }
    }

    /**
     * How far into the AutoDJ song (length and time left), and 👍/👎 for it. Counted from when
     * the station says it started, less what the player is holding back, so it matches what you hear.
     */
    @Composable
    private fun SongBar(st: Station.Status) {
        val l = look
        val s = st.song
        val timed = !st.live && s.at > 0 && s.length > 0
        val canVote = !st.live && s.id > 0 && st.panel.isNotBlank()
        if (!timed && !canVote) return
        var now by remember { mutableLongStateOf(Station.serverNow()) }
        LaunchedEffect(s.at, timed) {
            var asked = false
            while (timed) {
                now = Station.serverNow()
                lag = controller?.takeIf { playing }?.totalBufferedDuration ?: 0L
                // The song should be over: look at what's on now instead of waiting for the next tick.
                if (!asked && now - s.at > s.length + 4_000) { asked = true; App.publish(this@MainActivity, Station.check()) }
                delay(1_000)
            }
        }
        val heard = (now - s.at - lag).coerceIn(0L, s.length.coerceAtLeast(0L))
        Spacer(Modifier.height(10.dp))
        if (timed) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(l.line)) {
                Box(Modifier.fillMaxWidth(heard.toFloat() / s.length).height(4.dp).background(Brush.horizontalGradient(listOf(l.accent, l.accent2))))
            }
            Spacer(Modifier.height(6.dp))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (timed) "${mmss(heard)} / ${mmss(s.length)}" else "", color = l.dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (canVote) {
                val mine = votes[s.id]
                val voted = s.id in votes
                val t = tally?.takeIf { it.first == s.id }
                VoteChip("👍", t?.second, chosen = mine == true, dim = voted && mine != true, desc = "I like this one") { rate(st, true) }
                Spacer(Modifier.width(8.dp))
                VoteChip("👎", t?.third, chosen = mine == false, dim = voted && mine != false, desc = "Not for me") { rate(st, false) }
            }
            Text(if (timed) "−${mmss(s.length - heard)} left" else "", color = l.dim, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
    }

    private fun mmss(ms: Long): String { val t = ms / 1000; return "${t / 60}:${(t % 60).toString().padStart(2, '0')}" }

    @Composable
    private fun VoteChip(label: String, count: Int?, chosen: Boolean, dim: Boolean, desc: String, onClick: () -> Unit) {
        val l = look
        Row(
            Modifier.height(40.dp).alpha(if (dim) 0.45f else 1f).clip(RoundedCornerShape(20.dp))
                .background(if (chosen) l.accent.copy(alpha = 0.25f) else l.panel)
                .border(1.dp, if (chosen) l.accent else l.line, RoundedCornerShape(20.dp))
                .clickable(onClickLabel = desc, onClick = onClick).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 17.sp)
            if (count != null) { Spacer(Modifier.width(6.dp)); Text("$count", color = l.text, fontSize = 13.sp) }
        }
    }

    @Composable
    private fun Header(live: Boolean) {
        val l = look
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Image(painterResource(R.drawable.logo), null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("FunpostFM", color = l.text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("where funposting is a way of life", color = l.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (live) {
                val pulse by rememberInfiniteTransition(label = "live").animateFloat(1f, 0.5f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
                Box(Modifier.alpha(pulse).clip(RoundedCornerShape(6.dp)).background(l.accent).padding(horizontal = 10.dp, vertical = 3.dp)) {
                    Text("LIVE", color = l.onAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Spacer(Modifier.width(8.dp))
            }
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { menu = true }, contentAlignment = Alignment.Center) {
                Text("☰", color = l.text, fontSize = 22.sp)
            }
        }
    }

    @Composable
    private fun PlayButton() {
        val l = look
        val pulse by rememberInfiniteTransition(label = "buf").animateFloat(1f, 0.55f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "b")
        Box(
            Modifier.size(84.dp).alpha(if (buffering) pulse else 1f).clip(CircleShape)
                .background(Brush.linearGradient(listOf(l.accent, l.accent2))),
            contentAlignment = Alignment.Center,
        ) {
            Button(
                onClick = { if (playing) stop() else play() },
                modifier = Modifier.fillMaxSize(),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            ) {
                Text(if (playing) "■" else "▶", fontSize = 30.sp, color = l.onAccent)
            }
        }
    }

    @Composable
    private fun RoundButton(label: String, desc: String, enabled: Boolean, onClick: () -> Unit) {
        val l = look
        Box(
            Modifier.size(56.dp).alpha(if (enabled) 1f else 0.4f).clip(CircleShape).background(l.panel)
                .border(1.dp, l.line, CircleShape).clickable(onClickLabel = desc, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text(label, fontSize = 22.sp, color = l.text) }
    }

    /* ---------- the menu: everything that needs more room ---------- */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Menu(up: Updates.State) {
        val l = look
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable { menu = false }, contentAlignment = Alignment.Center) {
                    Text("←", color = l.text, fontSize = 22.sp)
                }
                Spacer(Modifier.width(6.dp))
                Text("Menu", color = l.text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = ::openSite, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, l.line)) {
                Text("Requests, chat & call-in on the site ↗", color = l.auto)
            }

            Spacer(Modifier.height(14.dp))
            Card {
                Text("Theme", color = l.text, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LOOKS.forEach { t ->
                        val on = t.id == l.id
                        Row(
                            Modifier.clip(RoundedCornerShape(20.dp)).background(t.bg)
                                .border(if (on) 2.dp else 1.dp, if (on) t.accent else t.line, RoundedCornerShape(20.dp))
                                .clickable { look = t; App.setTheme(this@MainActivity, t.id); bars() }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(14.dp).clip(CircleShape).background(Brush.linearGradient(listOf(t.accent, t.accent2))))
                            Spacer(Modifier.width(8.dp))
                            Text(t.name, color = t.text, fontSize = 14.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Card {
                Toggle("Ping me when someone goes live", "Checks about every 15 minutes while the app is closed", alerts) {
                    alerts = it; App.setAlerts(this@MainActivity, it); if (it) askForNotifications()
                }
                Spacer(Modifier.height(12.dp))
                Toggle("Keep playing when headphones or a speaker disconnect", "Off: the radio stops, so it doesn't suddenly play out loud", keepPlaying) {
                    keepPlaying = it; App.setKeepPlaying(this@MainActivity, it)
                }
            }

            Spacer(Modifier.height(14.dp))
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Updates", color = l.text, fontWeight = FontWeight.Bold)
                        Text(
                            "Version ${Updates.current(this@MainActivity)} · " + when (up.status) {
                                "checking" -> "checking…"
                                "none" -> "up to date"
                                "available" -> "${up.version} is out"
                                "downloading" -> up.message
                                "ready" -> up.message.ifBlank { "${up.version} downloaded" }
                                "error" -> up.message
                                else -> "checks by itself every few hours"
                            },
                            color = l.dim, fontSize = 12.sp,
                        )
                    }
                    OutlinedButton(onClick = { updateAction(up) }, enabled = up.status != "checking" && up.status != "downloading", border = BorderStroke(1.dp, l.line)) {
                        Text(when (up.status) { "available" -> "Update"; "ready" -> "Install"; "error" -> "Try again"; else -> "Check" }, color = l.auto)
                    }
                }
            }
        }
    }

    @Composable
    private fun Toggle(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val l = look
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = l.text)
                Text(sub, color = l.dim, fontSize = 12.sp)
            }
            Spacer(Modifier.width(10.dp))
            Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = l.accent, checkedThumbColor = l.onAccent))
        }
    }

    @Composable
    private fun Card(content: @Composable () -> Unit) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(look.panel.copy(alpha = 0.85f)).padding(14.dp),
            verticalArrangement = Arrangement.Top,
        ) { content() }
    }
}
