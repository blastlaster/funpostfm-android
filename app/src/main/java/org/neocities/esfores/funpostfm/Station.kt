package org.neocities.esfores.funpostfm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Where FunpostFM is, and one look at what it's doing (same rules as the desktop app and the add-on). */
object Station {
    const val SITE = "https://esfores.neocities.org/"
    const val POINTER = "https://esfores.neocities.org/onair/station.json"
    const val STREAM = "https://ec4.yesstreaming.net:3670/stream"
    private const val PANEL_API = "https://ec4.yesstreaming.net:3650/api/v2"

    data class Song(val artist: String, val title: String, val img: String = "") {
        val line get() = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" — ")
    }

    data class Status(
        val live: Boolean = false,
        val dj: String = "",
        val show: String = "",
        val song: Song = Song("", ""),
        val requestedBy: String = "",
        val listeners: Int? = null,
        val recent: List<Song> = emptyList(),
        val via: String = "none",   // studio | panel | none
    )

    private fun get(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.useCaches = false
        c.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (c.responseCode !in 200..299) error("${c.responseCode} from ${c.url.host}")
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    private fun abs(src: String?, base: String): String =
        if (src.isNullOrBlank()) "" else runCatching { URL(URL(base), src).toString() }.getOrDefault("")

    private fun JSONObject.str(k: String) = if (isNull(k)) "" else optString(k, "")

    /**
     * A panel history row as a song. Untagged songs carry only raw metadata shaped
     * "DJ: artist - title - album", sometimes with parts empty: pick it apart like the engine does.
     */
    private fun rowSong(r: JSONObject, base: String): Song {
        var meta = r.str("metadata")
        val dj = r.str("dj_name")
        if (dj.isNotBlank() && meta.startsWith("$dj:")) meta = meta.substring(dj.length + 1)
        val parts = meta.split(" - ").map { it.trim() }
        val title = r.str("title").ifBlank { listOf(1, 2, 0).mapNotNull { parts.getOrNull(it) }.firstOrNull { it.isNotBlank() } ?: "" }
        val artist = r.str("author").ifBlank { if (parts.size > 1) parts[0] else "" }
        return Song(artist, title.replace(Regex("""\s*\[Requested by [^\]]*\]\s*$""", RegexOption.IGNORE_CASE), ""), abs(r.str("img_medium_url").ifBlank { r.str("img_url") }, base))
    }

    /**
     * The song the station panel says is on now. The panel hears about a new song within a
     * second or two; the studio PC only catches up every 20 s or so. Null when unreachable.
     */
    suspend fun panelSong(): Song? = withContext(Dispatchers.IO) {
        runCatching {
            rowSong(get("$PANEL_API/history/?format=json&limit=1&offset=0&server=1").getJSONArray("results").getJSONObject(0), PANEL_API)
        }.getOrNull()
    }

    /** Never throws: a station that can't be reached is simply not live. */
    suspend fun check(): Status = withContext(Dispatchers.IO) {
        var engine = ""
        var panel = PANEL_API
        var server = 1
        runCatching {
            val p = get("$POINTER?t=${System.currentTimeMillis()}")
            engine = p.str("engine").trimEnd('/')
            p.optJSONObject("station")?.optJSONObject("panel")?.let {
                if (it.str("api").isNotBlank()) panel = it.str("api").trimEnd('/')
                server = it.optInt("server", 1)
            }
        }

        // The studio PC knows best.
        if (engine.isNotBlank()) {
            runCatching {
                val s = get("$engine/api/status")
                val live = s.optBoolean("live")
                val auto = s.optJSONObject("autodj")
                val np = if (live) s.optJSONObject("nowPlaying") else auto
                val recent = auto?.optJSONArray("recent")
                // On AutoDJ the panel knows about a new song sooner than the studio does.
                val fresh = if (!live) panelSong() else null
                val studioSong = Song(np?.str("artist") ?: "", np?.let { it.str("title").ifBlank { it.str("song") } } ?: "", abs(np?.str("img"), engine))
                val newer = fresh != null && fresh.title.isNotBlank() && fresh.title != studioSong.title
                return@withContext Status(
                    live = live,
                    dj = s.str("dj"),
                    show = s.str("show"),
                    song = if (newer) fresh!! else studioSong,
                    requestedBy = if (newer) "" else np?.str("requestedBy") ?: "",
                    listeners = if (s.has("listeners") && !s.isNull("listeners")) s.optInt("listeners") else null,
                    recent = ((if (newer) listOf(studioSong) else emptyList()) + (0 until (recent?.length() ?: 0)).map { i ->
                        val r = recent!!.getJSONObject(i)
                        Song(r.str("artist"), r.str("title"), abs(r.str("img"), engine))
                    }).take(5),
                    via = "studio",
                )
            }
        }

        // Studio off: the station panel's history says what played.
        runCatching {
            val h = get("$panel/history/?format=json&limit=6&offset=0&server=$server")
            val rows = h.optJSONArray("results")
            if (rows != null && rows.length() > 0) {
                fun song(r: JSONObject) = rowSong(r, panel)
                val row = rows.getJSONObject(0)
                // A live DJ shows up as a row that isn't a library song or a jingle.
                val dj = if (row.str("dj_name").isNotBlank() && row.isNull("all_music_id") && row.isNull("jingle_id") && row.optLong("length", 0) == 0L) row.str("dj_name") else ""
                return@withContext Status(
                    live = dj.isNotBlank(),
                    dj = dj,
                    song = song(row),
                    listeners = if (row.isNull("n_listeners")) null else row.optInt("n_listeners"),
                    recent = (1 until rows.length()).map { song(rows.getJSONObject(it)) },
                    via = "panel",
                )
            }
        }
        Status()
    }
}
