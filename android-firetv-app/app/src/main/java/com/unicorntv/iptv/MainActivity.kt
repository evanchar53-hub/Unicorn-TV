package com.unicorntv.iptv

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.JsonReader
import android.util.JsonToken
import android.util.Xml
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class PlaylistProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "My IPTV Playlist",
    val m3uUrl: String = "",
    val xtreamHost: String = "",
    val xtreamUser: String = "",
    val xtreamPass: String = "",
    val epgUrl: String = "",
    val updateOnStartup: Boolean = true,
    val autoDailyUpdate: Boolean = true,
    val dailyUpdateHour: Int = 4,
    val lastUpdatedMs: Long = 0L
)

data class IptvConfig(
    val playlists: List<PlaylistProfile> = emptyList(),
    val activePlaylistId: String = "",
    val imdbApiKey: String = "",
    val playIntroOnStart: Boolean = true,
    val maxHeightPx: Int = 720,
    val bufferSeconds: Int = 15,
    val maxResolutionLabel: String = "1080p",
    val lowLatencyMode: Boolean = false
) {
    val activePlaylist: PlaylistProfile?
        get() = playlists.firstOrNull { it.id == activePlaylistId } ?: playlists.firstOrNull()
}

enum class MediaKind { LIVE, MOVIE, SERIES, DVR }

data class MediaEntry(
    val id: String,
    val name: String,
    val category: String,
    val streamUrl: String,
    val epgId: String = "",
    val kind: MediaKind = MediaKind.LIVE,
    val plot: String = ""
)

data class EpgSlot(
    val title: String,
    val startMs: Long,
    val stopMs: Long
)

data class ScheduledRecording(
    val id: String = UUID.randomUUID().toString(),
    val channelName: String,
    val programTitle: String,
    val streamUrl: String,
    val startMs: Long,
    val durationMinutes: Int = 60
)

data class DvrRecordingItem(
    val title: String,
    val channelName: String,
    val filePath: String,
    val sizeMb: Long,
    val recordedAtMs: Long
)

data class ImDbMetadata(
    val imdbId: String = "",
    val title: String = "",
    val year: String = "",
    val rating: String = "",
    val genre: String = "",
    val runtime: String = "",
    val actors: String = "",
    val plot: String = ""
)

data class LoadedCatalog(
    val live: List<MediaEntry> = emptyList(),
    val movies: List<MediaEntry> = emptyList(),
    val series: List<MediaEntry> = emptyList(),
    val epgTimeline: Map<String, List<EpgSlot>> = emptyMap(),
    val error: String? = null
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UnicornTvApp()
        }
    }
}

private fun parsePlaylistsJson(raw: String): List<PlaylistProfile> {
    if (raw.isBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        val out = mutableListOf<PlaylistProfile>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                PlaylistProfile(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    name = o.optString("name", "Playlist ${i + 1}"),
                    m3uUrl = o.optString("m3uUrl", ""),
                    xtreamHost = o.optString("xtreamHost", ""),
                    xtreamUser = o.optString("xtreamUser", ""),
                    xtreamPass = o.optString("xtreamPass", ""),
                    epgUrl = o.optString("epgUrl", ""),
                    updateOnStartup = o.optBoolean("updateOnStartup", true),
                    autoDailyUpdate = o.optBoolean("autoDailyUpdate", true),
                    dailyUpdateHour = o.optInt("dailyUpdateHour", 4).coerceIn(0, 23),
                    lastUpdatedMs = o.optLong("lastUpdatedMs", 0L)
                )
            )
        }
        out
    }.getOrDefault(emptyList())
}

private fun serializePlaylistsJson(list: List<PlaylistProfile>): String {
    val arr = JSONArray()
    list.forEach { p ->
        arr.put(
            JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("m3uUrl", p.m3uUrl)
                put("xtreamHost", p.xtreamHost)
                put("xtreamUser", p.xtreamUser)
                put("xtreamPass", p.xtreamPass)
                put("epgUrl", p.epgUrl)
                put("updateOnStartup", p.updateOnStartup)
                put("autoDailyUpdate", p.autoDailyUpdate)
                put("dailyUpdateHour", p.dailyUpdateHour)
                put("lastUpdatedMs", p.lastUpdatedMs)
            }
        )
    }
    return arr.toString()
}

private fun loadConfig(context: Context): IptvConfig {
    val prefs = context.getSharedPreferences("unicorn_tv_prefs", Context.MODE_PRIVATE)
    var playlists = parsePlaylistsJson(prefs.getString("playlists_json", "") ?: "")
    var activeId = prefs.getString("active_playlist_id", "") ?: ""

    if (playlists.isEmpty()) {
        val legacyM3u = prefs.getString("m3u_url", "") ?: ""
        val legacyHost = prefs.getString("xtream_host", "") ?: ""
        val legacyUser = prefs.getString("xtream_user", "") ?: ""
        val legacyPass = prefs.getString("xtream_pass", "") ?: ""
        val legacyEpg = prefs.getString("epg_url", "") ?: ""
        if (legacyM3u.isNotBlank() || legacyHost.isNotBlank()) {
            val migrated = PlaylistProfile(
                name = "Unicorn TV Main",
                m3uUrl = legacyM3u,
                xtreamHost = legacyHost,
                xtreamUser = legacyUser,
                xtreamPass = legacyPass,
                epgUrl = legacyEpg,
                updateOnStartup = true,
                autoDailyUpdate = true,
                dailyUpdateHour = 4
            )
            playlists = listOf(migrated)
            activeId = migrated.id
        }
    }

    if (activeId.isBlank() && playlists.isNotEmpty()) {
        activeId = playlists.first().id
    }

    return IptvConfig(
        playlists = playlists,
        activePlaylistId = activeId,
        imdbApiKey = prefs.getString("imdb_api_key", "") ?: "",
        playIntroOnStart = prefs.getBoolean("play_intro", true),
        maxHeightPx = prefs.getInt("max_height_px", 720),
        bufferSeconds = prefs.getInt("buffer_seconds", 15),
        maxResolutionLabel = prefs.getString("max_resolution_label", "1080p") ?: "1080p",
        lowLatencyMode = prefs.getBoolean("low_latency_mode", false)
    )
}

private fun saveConfig(context: Context, config: IptvConfig) {
    val prefs = context.getSharedPreferences("unicorn_tv_prefs", Context.MODE_PRIVATE)
    prefs.edit()
        .putString("playlists_json", serializePlaylistsJson(config.playlists))
        .putString("active_playlist_id", config.activePlaylistId)
        .putString("imdb_api_key", config.imdbApiKey.trim())
        .putBoolean("play_intro", config.playIntroOnStart)
        .putInt("max_height_px", config.maxHeightPx)
        .putInt("buffer_seconds", config.bufferSeconds)
        .putString("max_resolution_label", config.maxResolutionLabel)
        .putBoolean("low_latency_mode", config.lowLatencyMode)
        .apply()
}

private fun getDvrDir(context: Context): File {
    val base = context.getExternalFilesDir("dvr_recordings") ?: File(context.filesDir, "dvr_recordings")
    if (!base.exists()) base.mkdirs()
    return base
}

private fun listSavedRecordings(context: Context): List<DvrRecordingItem> {
    val dir = getDvrDir(context)
    val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
    return files.map { file ->
        val cleanBase = file.nameWithoutExtension
        val parts = cleanBase.split("___", limit = 2)
        val chName = parts.getOrNull(0)?.replace('_', ' ') ?: "Live TV"
        val title = parts.getOrNull(1)?.replace('_', ' ') ?: cleanBase
        DvrRecordingItem(
            title = title,
            channelName = chName,
            filePath = file.absolutePath,
            sizeMb = (file.length() / (1024L * 1024L)).coerceAtLeast(0L),
            recordedAtMs = file.lastModified()
        )
    }
}

private fun isDailyUpdateDue(profile: PlaylistProfile, nowMs: Long = System.currentTimeMillis()): Boolean {
    if (!profile.autoDailyUpdate) return false
    val cal = Calendar.getInstance().apply {
        timeInMillis = nowMs
        set(Calendar.HOUR_OF_DAY, profile.dailyUpdateHour.coerceIn(0, 23))
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    var scheduledMs = cal.timeInMillis
    if (scheduledMs > nowMs) {
        scheduledMs -= 24L * 3600_000L
    }
    return profile.lastUpdatedMs < scheduledMs
}

private fun openHttpConnection(urlStr: String, connectTimeoutMs: Int = 20000, readTimeoutMs: Int = 90000): HttpURLConnection {
    return (URL(urlStr).openConnection() as HttpURLConnection).apply {
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        requestMethod = "GET"
        setRequestProperty("User-Agent", "UnicornTV/2.0 (AndroidTV)")
        setRequestProperty("Accept-Encoding", "identity")
    }
}

private fun httpGetSmallText(urlStr: String, timeoutMs: Int = 20000): String {
    val conn = openHttpConnection(urlStr, timeoutMs, timeoutMs)
    return try {
        val code = conn.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        BufferedReader(InputStreamReader(BufferedInputStream(conn.inputStream, 32768), Charsets.UTF_8)).use {
            it.readText()
        }
    } finally {
        conn.disconnect()
    }
}

private fun streamXtreamItems(
    urlStr: String,
    catMap: Map<String, String>,
    kind: MediaKind,
    base: String,
    user: String,
    pass: String,
    outList: MutableList<MediaEntry>
) {
    val conn = openHttpConnection(urlStr, connectTimeoutMs = 20000, readTimeoutMs = 120000)
    try {
        val code = conn.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        val reader = JsonReader(InputStreamReader(BufferedInputStream(conn.inputStream, 65536), Charsets.UTF_8))
        reader.isLenient = true
        if (reader.peek() == JsonToken.BEGIN_OBJECT) {
            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                if (reader.peek() == JsonToken.BEGIN_ARRAY && (key == "streams" || key == "vod_streams" || key == "series" || key == "data")) {
                    readJsonArrayItems(reader, catMap, kind, base, user, pass, outList)
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
        } else if (reader.peek() == JsonToken.BEGIN_ARRAY) {
            readJsonArrayItems(reader, catMap, kind, base, user, pass, outList)
        }
        reader.close()
    } finally {
        conn.disconnect()
    }
}

private fun readJsonArrayItems(
    reader: JsonReader,
    catMap: Map<String, String>,
    kind: MediaKind,
    base: String,
    user: String,
    pass: String,
    outList: MutableList<MediaEntry>
) {
    reader.beginArray()
    while (reader.hasNext()) {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            continue
        }
        var streamId = ""
        var seriesId = ""
        var altId = ""
        var name = ""
        var catId = ""
        var catNameRaw = ""
        var epgId = ""
        var ext = "mp4"

        reader.beginObject()
        while (reader.hasNext()) {
            val field = reader.nextName()
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull()
                continue
            }
            when (field) {
                "stream_id" -> streamId = readScalarAsString(reader)
                "series_id" -> seriesId = readScalarAsString(reader)
                "vod_id", "id" -> if (altId.isEmpty()) altId = readScalarAsString(reader)
                "name", "title" -> if (name.isEmpty()) name = readScalarAsString(reader)
                "category_id" -> catId = readScalarAsString(reader)
                "category_name" -> catNameRaw = readScalarAsString(reader)
                "epg_channel_id" -> epgId = readScalarAsString(reader)
                "container_extension" -> {
                    val v = readScalarAsString(reader)
                    if (v.isNotBlank()) ext = v
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val resolvedId = when (kind) {
            MediaKind.SERIES -> seriesId.ifBlank { altId }.ifBlank { streamId }
            else -> streamId.ifBlank { altId }
        }
        if (resolvedId.isBlank()) continue

        val defaultCat = when (kind) {
            MediaKind.LIVE -> "Live TV"
            MediaKind.MOVIE -> "Movies"
            MediaKind.SERIES -> "Series"
            MediaKind.DVR -> "DVR"
        }
        val category = catMap[catId] ?: catNameRaw.ifBlank { defaultCat }
        val finalName = name.ifBlank { "$defaultCat $resolvedId" }
        val url = when (kind) {
            MediaKind.LIVE -> "$base/live/$user/$pass/$resolvedId.ts"
            MediaKind.MOVIE -> "$base/movie/$user/$pass/$resolvedId.$ext"
            MediaKind.SERIES -> "$base/player_api.php?username=${Uri.encode(user)}&password=${Uri.encode(pass)}&action=get_series_info&series_id=$resolvedId"
            MediaKind.DVR -> "$base/live/$user/$pass/$resolvedId.ts"
        }

        outList.add(
            MediaEntry(
                id = resolvedId,
                name = finalName,
                category = category,
                streamUrl = url,
                epgId = epgId,
                kind = kind
            )
        )
    }
    reader.endArray()
}

private fun readScalarAsString(reader: JsonReader): String {
    return when (reader.peek()) {
        JsonToken.STRING -> reader.nextString()
        JsonToken.NUMBER -> reader.nextString()
        JsonToken.BOOLEAN -> reader.nextBoolean().toString()
        else -> {
            reader.skipValue()
            ""
        }
    }
}

private fun streamM3uFromUrl(
    m3uUrl: String,
    liveOut: MutableList<MediaEntry>,
    movieOut: MutableList<MediaEntry>,
    seriesOut: MutableList<MediaEntry>
) {
    val conn = openHttpConnection(m3uUrl, connectTimeoutMs = 20000, readTimeoutMs = 120000)
    try {
        val code = conn.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        BufferedReader(InputStreamReader(BufferedInputStream(conn.inputStream, 65536), Charsets.UTF_8), 65536).use { reader ->
            var pendingName = ""
            var pendingGroup = "General"
            var pendingTvgId = ""
            var index = 0

            while (true) {
                val rawLine = reader.readLine() ?: break
                val line = rawLine.trim()
                if (line.startsWith("#EXTINF:")) {
                    pendingGroup = extractAttr(line, "group-title").ifBlank { "General" }
                    pendingTvgId = extractAttr(line, "tvg-id")
                    pendingName = line.substringAfterLast(",").trim()
                } else if (line.startsWith("http://") || line.startsWith("https://")) {
                    index++
                    val lower = line.lowercase()
                    val groupLower = pendingGroup.lowercase()
                    val kind = when {
                        lower.contains("/movie/") || lower.endsWith(".mp4") || lower.endsWith(".mkv") || groupLower.contains("vod") || groupLower.contains("movie") -> MediaKind.MOVIE
                        lower.contains("/series/") || groupLower.contains("series") -> MediaKind.SERIES
                        else -> MediaKind.LIVE
                    }
                    val target = when (kind) {
                        MediaKind.LIVE, MediaKind.DVR -> liveOut
                        MediaKind.MOVIE -> movieOut
                        MediaKind.SERIES -> seriesOut
                    }
                    target.add(
                        MediaEntry(
                            id = "m3u_$index",
                            name = pendingName.ifBlank { "Channel $index" },
                            category = pendingGroup,
                            streamUrl = line,
                            epgId = pendingTvgId,
                            kind = kind
                        )
                    )
                    pendingName = ""
                }
            }
        }
    } finally {
        conn.disconnect()
    }
}

private fun extractAttr(line: String, attr: String): String {
    val marker = "$attr=\""
    val start = line.indexOf(marker)
    if (start == -1) return ""
    val valueStart = start + marker.length
    val end = line.indexOf('"', valueStart)
    return if (end > valueStart) line.substring(valueStart, end) else ""
}

private fun parseCategoryMap(jsonText: String): Map<String, String> {
    val map = mutableMapOf<String, String>()
    runCatching {
        val trimmed = jsonText.trim()
        if (!trimmed.startsWith("[")) return@runCatching
        val arr = JSONArray(trimmed)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("category_id").ifBlank { obj.optString("id") }
            val name = obj.optString("category_name").ifBlank { obj.optString("name") }
            if (id.isNotBlank() && name.isNotBlank()) map[id] = name
        }
    }
    return map
}

private fun cleanTitleAndYear(rawName: String): Pair<String, String?> {
    val yearMatch = Regex("""\b(19\d{2}|20\d{2})\b""").find(rawName)?.value
    val cleaned = rawName
        .replace(Regex("""^\|?[A-Z0-9 /+_-]+\|\s*"""), "")
        .replace(Regex("""\[[^\]]*\]|\([^)]*\)"""), " ")
        .replace(
            Regex("""\b(4K|UHD|FHD|HD|1080p|720p|2160p|HDR|HEVC|x264|x265|WEB-DL|BLURAY)\b""", RegexOption.IGNORE_CASE),
            " "
        )
        .replace(Regex("""\s+"""), " ")
        .trim()
    return cleaned to yearMatch
}

private suspend fun fetchImDbInfo(
    apiKey: String,
    rawTitle: String,
    isSeries: Boolean
): ImDbMetadata? = withContext(Dispatchers.IO) {
    val key = apiKey.trim()
    if (key.isEmpty()) return@withContext null

    val (cleanTitle, year) = cleanTitleAndYear(rawTitle)
    if (cleanTitle.isBlank()) return@withContext null

    val typeParam = if (isSeries) "series" else "movie"
    val yearParam = year?.let { "&y=${Uri.encode(it)}" } ?: ""
    val url = "https://www.omdbapi.com/?apikey=${Uri.encode(key)}&t=${Uri.encode(cleanTitle)}$yearParam&type=$typeParam"

    runCatching {
        val obj = JSONObject(httpGetSmallText(url, timeoutMs = 12000))
        if (!obj.optString("Response").equals("True", ignoreCase = true)) {
            return@runCatching null
        }
        ImDbMetadata(
            imdbId = obj.optString("imdbID", ""),
            title = obj.optString("Title", cleanTitle),
            year = obj.optString("Year", year ?: ""),
            rating = obj.optString("imdbRating", "N/A"),
            genre = obj.optString("Genre", ""),
            runtime = obj.optString("Runtime", ""),
            actors = obj.optString("Actors", ""),
            plot = obj.optString("Plot", "")
        )
    }.getOrNull()
}

private suspend fun fetchCatalogForPlaylist(
    playlist: PlaylistProfile,
    onProgress: (String) -> Unit
): LoadedCatalog = withContext(Dispatchers.IO) {
    val liveList = mutableListOf<MediaEntry>()
    val movieList = mutableListOf<MediaEntry>()
    val seriesList = mutableListOf<MediaEntry>()
    val warnings = mutableListOf<String>()

    val cleanHost = playlist.xtreamHost.trim().removeSuffix("/")
    val user = playlist.xtreamUser.trim()
    val pass = playlist.xtreamPass.trim()
    val hasXtream = cleanHost.isNotBlank() && user.isNotBlank() && pass.isNotBlank()

    try {
        if (hasXtream) {
            val base = if (cleanHost.startsWith("http")) cleanHost else "http://$cleanHost"
            val apiBase = "$base/player_api.php?username=${Uri.encode(user)}&password=${Uri.encode(pass)}"

            onProgress("[${playlist.name}] Streaming all Live TV channels...")
            val liveCatMap = parseCategoryMap(
                runCatching { httpGetSmallText("$apiBase&action=get_live_categories", timeoutMs = 30000) }.getOrDefault("[]")
            )
            runCatching {
                streamXtreamItems("$apiBase&action=get_live_streams", liveCatMap, MediaKind.LIVE, base, user, pass, liveList)
            }.onFailure { warnings.add("Live: ${it.message}") }

            onProgress("[${playlist.name}] Streaming all VOD Movies...")
            val vodCatMap = parseCategoryMap(
                runCatching { httpGetSmallText("$apiBase&action=get_vod_categories", timeoutMs = 30000) }.getOrDefault("[]")
            )
            runCatching {
                streamXtreamItems("$apiBase&action=get_vod_streams", vodCatMap, MediaKind.MOVIE, base, user, pass, movieList)
            }.onFailure { warnings.add("VOD: ${it.message}") }

            onProgress("[${playlist.name}] Streaming all TV Series...")
            val serCatMap = parseCategoryMap(
                runCatching { httpGetSmallText("$apiBase&action=get_series_categories", timeoutMs = 30000) }.getOrDefault("[]")
            )
            runCatching {
                streamXtreamItems("$apiBase&action=get_series", serCatMap, MediaKind.SERIES, base, user, pass, seriesList)
            }.onFailure { warnings.add("Series: ${it.message}") }
        } else if (playlist.m3uUrl.isNotBlank()) {
            onProgress("[${playlist.name}] Streaming full M3U playlist...")
            runCatching {
                streamM3uFromUrl(playlist.m3uUrl.trim(), liveList, movieList, seriesList)
            }.onFailure { warnings.add("M3U: ${it.message}") }
        }
    } catch (e: Exception) {
        warnings.add(e.localizedMessage ?: "Failed to load playlist")
    }

    onProgress("[${playlist.name}] Streaming 7-day XMLTV EPG guide...")
    val epgUrl = playlist.epgUrl.trim().ifBlank {
        if (hasXtream) {
            val base = if (cleanHost.startsWith("http")) cleanHost else "http://$cleanHost"
            "$base/xmltv.php?username=${Uri.encode(user)}&password=${Uri.encode(pass)}"
        } else ""
    }

    val timelineMap = if (epgUrl.isNotBlank()) {
        runCatching { fetchEpgTimelineMap(epgUrl) }
            .onFailure { warnings.add("EPG: ${it.message}") }
            .getOrDefault(emptyMap())
    } else {
        emptyMap()
    }

    LoadedCatalog(
        live = liveList,
        movies = movieList,
        series = seriesList,
        epgTimeline = timelineMap,
        error = warnings.takeIf { it.isNotEmpty() }?.joinToString(" | ")
    )
}

private fun fetchEpgTimelineMap(epgUrl: String): Map<String, List<EpgSlot>> {
    val nowMs = System.currentTimeMillis()
    val windowStart = nowMs - 2L * 3600_000L
    val windowEnd = nowMs + 7L * 24L * 3600_000L
    val grouped = mutableMapOf<String, MutableList<EpgSlot>>()

    val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun parseXmltvTime(raw: String): Long {
        val normalized = if (raw.contains(" ")) raw else "$raw +0000"
        return runCatching { fmt.parse(normalized)?.time ?: 0L }.getOrDefault(0L)
    }

    val conn = openHttpConnection(epgUrl, connectTimeoutMs = 20000, readTimeoutMs = 90000)
    try {
        BufferedInputStream(conn.inputStream, 65536).use { stream ->
            val parser = Xml.newPullParser()
            parser.setInput(stream, "UTF-8")
            var event = parser.eventType
            var inProgramme = false
            var progChannel = ""
            var progStart = 0L
            var progStop = 0L
            var progTitle = ""

            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "programme") {
                            inProgramme = true
                            progChannel = parser.getAttributeValue(null, "channel") ?: ""
                            progStart = parseXmltvTime(parser.getAttributeValue(null, "start") ?: "")
                            progStop = parseXmltvTime(parser.getAttributeValue(null, "stop") ?: "")
                            progTitle = ""
                        } else if (inProgramme && parser.name == "title") {
                            progTitle = parser.nextText()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "programme" && inProgramme) {
                            if (progChannel.isNotBlank() && progTitle.isNotBlank() && progStop >= windowStart && progStart <= windowEnd) {
                                val list = grouped.getOrPut(progChannel) { mutableListOf() }
                                if (list.size < 96) {
                                    list.add(EpgSlot(progTitle, progStart, progStop))
                                }
                            }
                            inProgramme = false
                        }
                    }
                }
                event = parser.next()
            }
        }
    } finally {
        conn.disconnect()
    }
    return grouped.mapValues { (_, list) -> list.sortedBy { it.startMs } }
}

private suspend fun resolveFirstSeriesEpisodeUrl(seriesInfoUrl: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val root = JSONObject(httpGetSmallText(seriesInfoUrl, timeoutMs = 25000))
        val episodesObj = root.optJSONObject("episodes") ?: return@runCatching null
        val keys = episodesObj.keys()
        if (!keys.hasNext()) return@runCatching null
        val firstSeasonArr = episodesObj.getJSONArray(keys.next())
        if (firstSeasonArr.length() == 0) return@runCatching null
        val ep = firstSeasonArr.getJSONObject(0)
        val epId = ep.optString("id")
        val ext = ep.optString("container_extension", "mp4").ifBlank { "mp4" }
        val base = seriesInfoUrl.substringBefore("/player_api.php")
        val query = seriesInfoUrl.substringAfter("?")
        val user = Regex("""username=([^&]+)""").find(query)?.groupValues?.getOrNull(1) ?: ""
        val pass = Regex("""password=([^&]+)""").find(query)?.groupValues?.getOrNull(1) ?: ""
        "$base/series/$user/$pass/$epId.$ext"
    }.getOrNull()
}

private enum class TvSection(val title: String, val icon: ImageVector) {
    SETUP("Live TV & EPG Setup", Icons.Default.LiveTv),
    GUIDE("TV Guide (EPG)", Icons.Default.CalendarMonth),
    FAVORITES("Favorites", Icons.Default.Star),
    MULTIVIEW("Multi-View", Icons.Default.GridView),
    MOVIES("Movies (VOD)", Icons.Default.Movie),
    SERIES("Series", Icons.Default.Tv),
    DVR("DVR & Recordings", Icons.Default.FiberManualRecord),
    SPEED_OPTIMIZER("Speed Optimizer", Icons.Default.Speed),
    SETTINGS("Settings", Icons.Default.Settings)
}

@Composable
fun UnicornTvApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(loadConfig(context)) }
    var showIntro by remember { mutableStateOf(config.playIntroOnStart) }
    var selectedSection by remember { mutableStateOf(TvSection.SETUP) }
    var catalog by remember { mutableStateOf(LoadedCatalog()) }
    var isLoadingCatalog by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }

    var watchingChannel by remember { mutableStateOf<MediaEntry?>(null) }
    var isFullScreenTv by remember { mutableStateOf(false) }
    var streamError by remember { mutableStateOf<String?>(null) }

    var recordingJob by remember { mutableStateOf<Job?>(null) }
    var activeRecordingLabel by remember { mutableStateOf<String?>(null) }
    var recordings by remember { mutableStateOf(listSavedRecordings(context)) }
    val scheduledRecordings = remember { mutableStateListOf<ScheduledRecording>() }

    val tvPlayer = remember(config.bufferSeconds, config.maxHeightPx) {
        val bufferMs = (config.bufferSeconds * 1000).coerceIn(5000, 40000)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(bufferMs, bufferMs * 2, 1500, 2500)
            .build()

        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(15_000L)
            .setSeekForwardIncrementMs(15_000L)
            .build()
            .apply {
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setMaxVideoSize(3840, config.maxHeightPx)
                    .build()
                playWhenReady = true
            }
    }

    fun startStreamRecording(channelName: String, programTitle: String, streamUrl: String) {
        if (streamUrl.isBlank()) return
        recordingJob?.cancel()
        val safeChannel = channelName.replace(Regex("""[^A-Za-z0-9._ -]"""), "_").replace(' ', '_').take(32)
        val safeTitle = programTitle.replace(Regex("""[^A-Za-z0-9._ -]"""), "_").replace(' ', '_').take(40)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(getDvrDir(context), "${safeChannel}___${safeTitle}_$stamp.ts")

        activeRecordingLabel = "$channelName — $programTitle"
        statusMessage = "Recording started: ${outFile.name}"

        recordingJob = scope.launch(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = openHttpConnection(streamUrl, connectTimeoutMs = 15000, readTimeoutMs = 30000)
                BufferedInputStream(conn.inputStream, 65536).use { input ->
                    BufferedOutputStream(FileOutputStream(outFile), 65536).use { output ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buf)
                            if (read == -1) break
                            output.write(buf, 0, read)
                        }
                        output.flush()
                    }
                }
            } catch (_: Exception) {
            } finally {
                conn?.disconnect()
                withContext(Dispatchers.Main) {
                    activeRecordingLabel = null
                    recordingJob = null
                    recordings = listSavedRecordings(context)
                    statusMessage = "DVR saved: ${outFile.name}"
                }
            }
        }
    }

    fun stopStreamRecording() {
        recordingJob?.cancel()
        recordingJob = null
        activeRecordingLabel = null
        recordings = listSavedRecordings(context)
    }

    DisposableEffect(tvPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                streamError = error.errorCodeName
            }
        }
        tvPlayer.addListener(listener)
        onDispose {
            recordingJob?.cancel()
            tvPlayer.removeListener(listener)
            runCatching {
                tvPlayer.stop()
                tvPlayer.release()
            }
        }
    }

    fun playEntry(entry: MediaEntry, fullScreen: Boolean) {
        streamError = null
        val url = entry.streamUrl.trim()
        val isDifferent = watchingChannel?.streamUrl != url
        watchingChannel = entry
        if (url.isNotBlank() && isDifferent) {
            tvPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            tvPlayer.prepare()
        }
        tvPlayer.playWhenReady = true
        tvPlayer.play()
        isFullScreenTv = fullScreen
    }

    fun loadPlaylistNow(playlist: PlaylistProfile, currentConfig: IptvConfig = config) {
        if (playlist.m3uUrl.isBlank() && playlist.xtreamHost.isBlank()) return
        isLoadingCatalog = true
        statusMessage = "Updating '${playlist.name}'..."
        scope.launch {
            val loaded = fetchCatalogForPlaylist(playlist) { progress ->
                statusMessage = progress
            }
            catalog = loaded
            isLoadingCatalog = false

            val nowMs = System.currentTimeMillis()
            val updatedList = currentConfig.playlists.map { item ->
                if (item.id == playlist.id) item.copy(lastUpdatedMs = nowMs) else item
            }
            val updatedCfg = currentConfig.copy(playlists = updatedList, activePlaylistId = playlist.id)
            config = updatedCfg
            saveConfig(context, updatedCfg)

            if (watchingChannel == null) {
                val firstLive = loaded.live.firstOrNull { !it.name.startsWith("#") } ?: loaded.live.firstOrNull()
                if (firstLive != null) {
                    playEntry(firstLive, fullScreen = false)
                }
            }
            statusMessage = if (loaded.error != null) {
                "'${playlist.name}' loaded with notice: ${loaded.error}"
            } else {
                "'${playlist.name}': ${loaded.live.size} Live, ${loaded.movies.size} Movies, ${loaded.series.size} Series, ${loaded.epgTimeline.size} EPG."
            }
        }
    }

    LaunchedEffect(Unit) {
        val active = config.activePlaylist
        if (active != null && (active.updateOnStartup || isDailyUpdateDue(active) || active.lastUpdatedMs == 0L)) {
            loadPlaylistNow(active, config)
        }
    }

    LaunchedEffect(config.activePlaylistId, config.playlists, scheduledRecordings.size) {
        while (true) {
            delay(30_000L)
            val nowMs = System.currentTimeMillis()
            val active = config.activePlaylist
            if (!isLoadingCatalog && active != null && isDailyUpdateDue(active, nowMs)) {
                loadPlaylistNow(active, config)
            }
            val dueRec = scheduledRecordings.firstOrNull { nowMs >= it.startMs }
            if (dueRec != null && activeRecordingLabel == null) {
                scheduledRecordings.remove(dueRec)
                startStreamRecording(dueRec.channelName, dueRec.programTitle, dueRec.streamUrl)
            }
        }
    }

    if (showIntro) {
        IntroVideoOverlay(onFinish = { showIntro = false })
        return
    }

    if (isFullScreenTv && watchingChannel != null) {
        FullScreenCleanTvOverlay(
            player = tvPlayer,
            onBackToGuide = {
                isFullScreenTv = false
                selectedSection = TvSection.GUIDE
            }
        )
        return
    }

    val nebulaBg = Brush.horizontalGradient(
        colors = listOf(Color(0xFF14072B), Color(0xFF280D54), Color(0xFF1A0B38))
    )

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(nebulaBg)
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .width(76.dp)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BrandLogoBadge()
            Text(
                text = "UTV",
                color = Color(0xFF38BDF8),
                fontWeight = FontWeight.ExtraBold,
                style = MaterialTheme.typography.titleMedium
            )
            TvSection.entries.forEach { section ->
                val isSelected = selectedSection == section
                var isFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .onFocusChanged { isFocused = it.isFocused }
                        .focusable()
                        .background(
                            when {
                                isSelected -> Color(0xFFD946EF)
                                isFocused -> Color(0xFF4C1D95)
                                else -> Color(0xFF220E47)
                            },
                            RoundedCornerShape(12.dp)
                        )
                        .border(
                            width = if (isFocused || isSelected) 2.dp else 1.dp,
                            color = if (isFocused) Color(0xFF38BDF8) else Color(0xFF7E22CE),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { selectedSection = section },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = section.icon,
                        contentDescription = section.title,
                        tint = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(20.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            val activeName = config.activePlaylist?.name ?: "No Playlist"
            Text(
                text = "Unicorn TV — ${selectedSection.title}",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "Active: $activeName  •  Live: ${catalog.live.size}  •  Movies: ${catalog.movies.size}  •  Series: ${catalog.series.size}  •  EPG: ${catalog.epgTimeline.size}",
                color = Color(0xFF67E8F9),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(10.dp))

            when (selectedSection) {
                TvSection.SETUP -> SetupAndPlaylistsPane(
                    config = config,
                    catalog = catalog,
                    isLoading = isLoadingCatalog,
                    statusMessage = statusMessage,
                    onSavePlaylist = { profile, makeActive ->
                        val exists = config.playlists.any { it.id == profile.id }
                        val updatedList = if (exists) {
                            config.playlists.map { if (it.id == profile.id) profile else it }
                        } else {
                            config.playlists + profile
                        }
                        val targetActiveId = if (makeActive || config.activePlaylistId.isBlank()) profile.id else config.activePlaylistId
                        val updatedCfg = config.copy(playlists = updatedList, activePlaylistId = targetActiveId)
                        config = updatedCfg
                        saveConfig(context, updatedCfg)
                        if (targetActiveId == profile.id) {
                            loadPlaylistNow(profile, updatedCfg)
                        } else {
                            statusMessage = "Saved playlist '${profile.name}'."
                        }
                    },
                    onActivatePlaylist = { profile ->
                        loadPlaylistNow(profile, config)
                    },
                    onDeletePlaylist = { profile ->
                        val remaining = config.playlists.filterNot { it.id == profile.id }
                        val nextActive = if (config.activePlaylistId == profile.id) {
                            remaining.firstOrNull()?.id.orEmpty()
                        } else {
                            config.activePlaylistId
                        }
                        val updatedCfg = config.copy(playlists = remaining, activePlaylistId = nextActive)
                        config = updatedCfg
                        saveConfig(context, updatedCfg)
                        statusMessage = "Removed playlist '${profile.name}'."
                        updatedCfg.activePlaylist?.let { loadPlaylistNow(it, updatedCfg) }
                    }
                )

                TvSection.GUIDE -> SevenDayTvGuidePane(
                    channels = catalog.live,
                    epgTimeline = catalog.epgTimeline,
                    watchingChannel = watchingChannel,
                    player = tvPlayer,
                    previewError = streamError,
                    activeRecordingLabel = activeRecordingLabel,
                    onStartRecord = { ch, progTitle ->
                        startStreamRecording(ch.name, progTitle, ch.streamUrl)
                    },
                    onStopRecord = { stopStreamRecording() },
                    onScheduleRecord = { ch, slot ->
                        scheduledRecordings.add(
                            ScheduledRecording(
                                channelName = ch.name,
                                programTitle = slot.title,
                                streamUrl = ch.streamUrl,
                                startMs = slot.startMs
                            )
                        )
                        statusMessage = "Scheduled DVR: ${slot.title} on ${ch.name}"
                    },
                    onGoToSetup = { selectedSection = TvSection.SETUP },
                    onTuneAndWatchFullScreen = { ch -> playEntry(ch, fullScreen = true) }
                )

                TvSection.MOVIES -> CategorizedBrowserPane(
                    items = catalog.movies,
                    imdbApiKey = config.imdbApiKey,
                    isSeries = false,
                    emptyText = "No VOD movies loaded yet. Check Setup or update your active playlist.",
                    onGoToSetup = { selectedSection = TvSection.SETUP },
                    onSelectEntry = { playEntry(it, fullScreen = true) }
                )

                TvSection.SERIES -> CategorizedBrowserPane(
                    items = catalog.series,
                    imdbApiKey = config.imdbApiKey,
                    isSeries = true,
                    emptyText = "No TV series loaded yet. Add a playlist in Setup.",
                    onGoToSetup = { selectedSection = TvSection.SETUP },
                    onSelectEntry = { seriesItem ->
                        scope.launch {
                            val epUrl = if (seriesItem.streamUrl.contains("get_series_info")) {
                                resolveFirstSeriesEpisodeUrl(seriesItem.streamUrl)
                            } else {
                                seriesItem.streamUrl
                            }
                            if (!epUrl.isNullOrBlank()) {
                                playEntry(seriesItem.copy(streamUrl = epUrl), fullScreen = true)
                            } else {
                                statusMessage = "Could not resolve episode stream for ${seriesItem.name}."
                            }
                        }
                    }
                )

                TvSection.DVR -> DvrManagerPane(
                    channels = catalog.live,
                    activeRecordingLabel = activeRecordingLabel,
                    scheduledRecordings = scheduledRecordings,
                    recordings = recordings,
                    onStartRecordChannel = { ch ->
                        startStreamRecording(ch.name, "Manual DVR Capture", ch.streamUrl)
                    },
                    onStopRecording = { stopStreamRecording() },
                    onPlayRecording = { rec ->
                        playEntry(
                            MediaEntry(
                                id = rec.filePath,
                                name = "${rec.title} (${rec.channelName})",
                                category = "DVR Recording",
                                streamUrl = Uri.fromFile(File(rec.filePath)).toString(),
                                kind = MediaKind.DVR
                            ),
                            fullScreen = true
                        )
                    },
                    onDeleteRecording = { rec ->
                        runCatching { File(rec.filePath).delete() }
                        recordings = listSavedRecordings(context)
                    }
                )

                TvSection.MULTIVIEW -> CustomizableMultiViewPane(
                    channels = catalog.live,
                    maxHeightPx = config.maxHeightPx,
                    onGoToSetup = { selectedSection = TvSection.SETUP }
                )

                TvSection.SPEED_OPTIMIZER -> SpeedOptimizerPane(
                    bufferSeconds = config.bufferSeconds,
                    maxResolutionLabel = config.maxResolutionLabel,
                    lowLatencyMode = config.lowLatencyMode,
                    onApplySettings = { bufferSec, resLabel, lowLat ->
                        val heightPx = when (resLabel) {
                            "720p" -> 720
                            "4K" -> 2160
                            else -> 1080
                        }
                        val updated = config.copy(
                            bufferSeconds = bufferSec,
                            maxResolutionLabel = resLabel,
                            lowLatencyMode = lowLat,
                            maxHeightPx = heightPx
                        )
                        config = updated
                        saveConfig(context, updated)
                        statusMessage = "Applied ${bufferSec}s buffer & $resLabel cap."
                    }
                )

                TvSection.SETTINGS -> SettingsPane(
                    config = config,
                    onUpdate = { updated ->
                        config = updated
                        saveConfig(context, updated)
                        statusMessage = "Settings updated."
                    }
                )

                TvSection.FAVORITES -> CategorizedBrowserPane(
                    items = catalog.live.take(300),
                    imdbApiKey = "",
                    isSeries = false,
                    emptyText = "No channels available yet.",
                    onGoToSetup = { selectedSection = TvSection.SETUP },
                    onSelectEntry = { playEntry(it, fullScreen = true) }
                )
            }
        }
    }
}

@Composable
private fun FullScreenCleanTvOverlay(
    player: ExoPlayer,
    onBackToGuide: () -> Unit
) {
    BackHandler { onBackToGuide() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    setShowRewindButton(true)
                    setShowFastForwardButton(true)
                    setShowPreviousButton(false)
                    setShowNextButton(false)
                }
            },
            update = { view -> view.player = player },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun SevenDayTvGuidePane(
    channels: List<MediaEntry>,
    epgTimeline: Map<String, List<EpgSlot>>,
    watchingChannel: MediaEntry?,
    player: ExoPlayer,
    previewError: String?,
    activeRecordingLabel: String?,
    onStartRecord: (MediaEntry, String) -> Unit,
    onStopRecord: () -> Unit,
    onScheduleRecord: (MediaEntry, EpgSlot) -> Unit,
    onGoToSetup: () -> Unit,
    onTuneAndWatchFullScreen: (MediaEntry) -> Unit
) {
    if (channels.isEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("No live channels loaded yet. Add or sync a playlist in Setup.", color = Color(0xFFFBBF24))
            Button(onClick = onGoToSetup) {
                Text("Go to IPTV & EPG Setup")
            }
        }
        return
    }

    var searchQuery by remember { mutableStateOf("") }
    val categories = remember(channels) {
        listOf("All (${channels.size})") + channels.map { it.category }.distinct()
    }
    var selectedCategory by remember(categories) { mutableStateOf(categories.first()) }

    val nowMs = System.currentTimeMillis()
    val dayOptions = remember(nowMs) {
        val dayFmt = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
        (0..6).map { dayOffset ->
            val startOfDay = Calendar.getInstance().apply {
                timeInMillis = nowMs
                add(Calendar.DAY_OF_YEAR, dayOffset)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val endOfDay = startOfDay + 24L * 3600_000L
            val label = if (dayOffset == 0) "Today (${dayFmt.format(Date(startOfDay))})" else dayFmt.format(Date(startOfDay))
            Triple(dayOffset, label, startOfDay to endOfDay)
        }
    }
    var selectedDayOffset by remember { mutableStateOf(0) }
    val activeDayRange = dayOptions.firstOrNull { it.first == selectedDayOffset }?.third ?: (nowMs to (nowMs + 24L * 3600_000L))

    val time24Fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val current24Time = time24Fmt.format(Date(nowMs))

    val anchorChannel = watchingChannel ?: channels.first()
    var browsedChannel by remember(anchorChannel.id) { mutableStateOf(anchorChannel) }
    var isPaused by remember { mutableStateOf(!player.playWhenReady) }

    val baseHourMs = remember(nowMs) {
        Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val col1StartMs = baseHourMs
    val col2StartMs = baseHourMs + 2L * 3600_000L
    val col3StartMs = baseHourMs + 4L * 3600_000L
    val col1Label = time24Fmt.format(Date(col1StartMs))
    val col2Label = time24Fmt.format(Date(col2StartMs))
    val col3Label = time24Fmt.format(Date(col3StartMs))

    LaunchedEffect(player) {
        while (true) {
            isPaused = !player.playWhenReady || (player.playbackState == Player.STATE_READY && !player.isPlaying)
            delay(500L)
        }
    }

    val filtered = remember(channels, selectedCategory, searchQuery, epgTimeline) {
        val byCat = if (selectedCategory.startsWith("All (")) channels
        else channels.filter { it.category == selectedCategory }

        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) {
            byCat
        } else {
            byCat.filter { entry ->
                val slots = epgTimeline[entry.epgId].orEmpty()
                entry.name.lowercase().contains(q) ||
                    entry.category.lowercase().contains(q) ||
                    slots.any { it.title.lowercase().contains(q) }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(165.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .width(275.dp)
                    .fillMaxHeight()
                    .background(Color.Black, RoundedCornerShape(10.dp))
                    .border(2.dp, Color(0xFF38BDF8), RoundedCornerShape(10.dp))
                    .clickable { onTuneAndWatchFullScreen(anchorChannel) }
            ) {
                if (previewError == null) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                this.player = player
                                useController = false
                            }
                        },
                        update = { view -> view.player = player },
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    Text(
                        text = previewError,
                        color = Color(0xFFCBD5E1),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Column(
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                val browsedSlots = epgTimeline[browsedChannel.epgId].orEmpty()
                val currentBrowsedSlot = browsedSlots.firstOrNull { nowMs in it.startMs..it.stopMs } ?: browsedSlots.firstOrNull()

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Watching: ${anchorChannel.name}",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "24h: $current24Time",
                            color = Color(0xFF67E8F9),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        Button(onClick = { onTuneAndWatchFullScreen(browsedChannel) }) {
                            Text(if (browsedChannel.id == anchorChannel.id) "Full Screen" else "Watch Selected")
                        }
                    }

                    Text(
                        text = if (currentBrowsedSlot != null) {
                            "${browsedChannel.name} • ${time24Fmt.format(Date(currentBrowsedSlot.startMs))}–${time24Fmt.format(Date(currentBrowsedSlot.stopMs))} ${currentBrowsedSlot.title}"
                        } else {
                            "Selected: ${browsedChannel.name} (${browsedChannel.category})"
                        },
                        color = Color(0xFF4ADE80),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val target = (player.currentPosition - 15_000L).coerceAtLeast(0L)
                                player.seekTo(target)
                            }
                        ) {
                            Text("⏪ -15s")
                        }
                        Button(
                            onClick = {
                                if (player.isPlaying) {
                                    player.pause()
                                    isPaused = true
                                } else {
                                    player.play()
                                    isPaused = false
                                }
                            }
                        ) {
                            Text(if (isPaused) "▶ Play" else "⏸ Pause")
                        }
                        Button(
                            onClick = {
                                if (player.duration != C.TIME_UNSET && player.duration > 0L) {
                                    player.seekTo(player.duration)
                                } else {
                                    player.seekToDefaultPosition()
                                }
                                player.play()
                                isPaused = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF15803D))
                        ) {
                            Text("● Live TV")
                        }
                        if (activeRecordingLabel != null) {
                            Button(
                                onClick = onStopRecord,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C))
                            ) {
                                Text("⏹ Stop DVR")
                            }
                        } else {
                            Button(
                                onClick = {
                                    onStartRecord(
                                        browsedChannel,
                                        currentBrowsedSlot?.title ?: "Live Capture"
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF991B1B))
                            ) {
                                Text("⏺ DVR")
                            }
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TvInputField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = "Search Guide (Watching channel stays playing)",
                        imeAction = ImeAction.Search,
                        modifier = Modifier.weight(1f)
                    )
                    if (searchQuery.isNotEmpty()) {
                        Button(onClick = { searchQuery = "" }) {
                            Text("Clear")
                        }
                    }
                }
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            items(dayOptions) { (offset, label, _) ->
                val selected = selectedDayOffset == offset
                Button(
                    onClick = { selectedDayOffset = offset },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) Color(0xFFD946EF) else Color(0xFF3B1976)
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .width(205.dp)
                    .fillMaxHeight()
            ) {
                items(categories) { cat ->
                    val active = cat == selectedCategory
                    var focused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focused = it.isFocused }
                            .focusable()
                            .background(
                                when {
                                    active -> Color(0xFFD946EF)
                                    focused -> Color(0xFF4C1D95)
                                    else -> Color(0xFF210D44)
                                },
                                RoundedCornerShape(8.dp)
                            )
                            .border(
                                width = if (focused) 2.dp else 0.dp,
                                color = Color(0xFF38BDF8),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { selectedCategory = cat }
                            .padding(horizontal = 10.dp, vertical = 9.dp)
                    ) {
                        Text(
                            text = cat,
                            color = Color.White,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1A0B38), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("Channel", color = Color(0xFF67E8F9), fontWeight = FontWeight.Bold, modifier = Modifier.width(195.dp))
                    Text(col1Label, color = Color(0xFF4ADE80), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(col2Label, color = Color(0xFF67E8F9), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(col3Label, color = Color(0xFFE2E8F0), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(6.dp))

                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                    items(filtered.take(500)) { entry ->
                        var focused by remember { mutableStateOf(false) }
                        val isWatching = anchorChannel.id == entry.id
                        val isBrowsed = browsedChannel.id == entry.id
                        val daySlots = epgTimeline[entry.epgId].orEmpty()
                            .filter { it.stopMs >= activeDayRange.first && it.startMs <= activeDayRange.second }

                        val s1 = daySlots.firstOrNull { col1StartMs in it.startMs..it.stopMs || it.startMs >= col1StartMs } ?: daySlots.getOrNull(0)
                        val s2 = daySlots.firstOrNull { col2StartMs in it.startMs..it.stopMs || it.startMs >= col2StartMs } ?: daySlots.getOrNull(1)
                        val s3 = daySlots.firstOrNull { col3StartMs in it.startMs..it.stopMs || it.startMs >= col3StartMs } ?: daySlots.getOrNull(2)

                        fun formatSlot(slot: EpgSlot?): String {
                            if (slot == null) return "—"
                            return "${time24Fmt.format(Date(slot.startMs))} ${slot.title}"
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { state ->
                                    focused = state.isFocused
                                    if (state.isFocused) {
                                        browsedChannel = entry
                                    }
                                }
                                .focusable()
                                .background(
                                    when {
                                        focused -> Color(0xFF3B1976)
                                        isWatching -> Color(0xFF2D145C)
                                        else -> Color(0xFF24104A)
                                    },
                                    RoundedCornerShape(8.dp)
                                )
                                .border(
                                    width = if (focused || isBrowsed) 2.dp else if (isWatching) 1.5.dp else 1.dp,
                                    color = if (focused) Color(0xFF38BDF8) else if (isWatching) Color(0xFFD946EF) else Color(0xFF4C1D95),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    browsedChannel = entry
                                    onTuneAndWatchFullScreen(entry)
                                }
                                .padding(horizontal = 10.dp, vertical = 9.dp)
                        ) {
                            Text(
                                text = "${if (isWatching) "▶ " else ""}${entry.name}",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(195.dp).padding(end = 8.dp)
                            )
                            Text(
                                text = formatSlot(s1),
                                color = if (s1 != null) Color(0xFF4ADE80) else Color(0xFF94A3B8),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            )
                            Text(
                                text = formatSlot(s2),
                                color = Color(0xFF67E8F9),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            )
                            Text(
                                text = formatSlot(s3),
                                color = Color(0xFFE2E8F0),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (s1 != null && selectedDayOffset > 0) {
                                Button(
                                    onClick = { onScheduleRecord(entry, s1) },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    modifier = Modifier.padding(start = 6.dp)
                                ) {
                                    Text("+DVR", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DvrManagerPane(
    channels: List<MediaEntry>,
    activeRecordingLabel: String?,
    scheduledRecordings: List<ScheduledRecording>,
    recordings: List<DvrRecordingItem>,
    onStartRecordChannel: (MediaEntry) -> Unit,
    onStopRecording: () -> Unit,
    onPlayRecording: (DvrRecordingItem) -> Unit,
    onDeleteRecording: (DvrRecordingItem) -> Unit
) {
    val timeFmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF24104A)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(14.dp)
            ) {
                Text(
                    text = "Live DVR Recorder",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                if (activeRecordingLabel != null) {
                    Text(
                        text = "● Recording in progress: $activeRecordingLabel",
                        color = Color(0xFFF87171),
                        fontWeight = FontWeight.SemiBold
                    )
                    Button(
                        onClick = onStopRecording,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C))
                    ) {
                        Text("Stop Recording")
                    }
                } else {
                    val sampleChannels = channels.filter { !it.name.trim().startsWith("#") }.take(6)
                    if (sampleChannels.isEmpty()) {
                        Text("Load Live TV channels in Setup to record streams.", color = Color(0xFFCBD5E1))
                    } else {
                        Text("Quick Record a Live Channel:", color = Color(0xFF67E8F9), style = MaterialTheme.typography.bodySmall)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(sampleChannels) { ch ->
                                Button(onClick = { onStartRecordChannel(ch) }) {
                                    Text("⏺ ${ch.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (scheduledRecordings.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A0B38)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(14.dp)) {
                    Text("Scheduled DVR (${scheduledRecordings.size})", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                    scheduledRecordings.forEach { sched ->
                        Text(
                            text = "${timeFmt.format(Date(sched.startMs))} • ${sched.channelName} — ${sched.programTitle}",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        Text(
            text = "Saved DVR Recordings (${recordings.size}):",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium
        )

        if (recordings.isEmpty()) {
            Text("No DVR recordings saved yet.", color = Color(0xFFCBD5E1))
        } else {
            recordings.forEach { rec ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF24104A)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(rec.title, color = Color.White, fontWeight = FontWeight.Bold)
                            Text(
                                text = "${rec.channelName} • ${rec.sizeMb} MB • ${timeFmt.format(Date(rec.recordedAtMs))}",
                                color = Color(0xFF67E8F9),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onPlayRecording(rec) }) {
                                Text("Play")
                            }
                            Button(
                                onClick = { onDeleteRecording(rec) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7F1D1D))
                            ) {
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomizableMultiViewPane(
    channels: List<MediaEntry>,
    maxHeightPx: Int,
    onGoToSetup: () -> Unit
) {
    val context = LocalContext.current
    if (channels.isEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Load channels in Setup before launching Multi-View.", color = Color(0xFFFBBF24))
            Button(onClick = onGoToSetup) {
                Text("Open Setup")
            }
        }
        return
    }

    val playableDefaults = remember(channels) {
        channels.filter { !it.name.trim().startsWith("#") }.ifEmpty { channels }
    }

    var screenCount by remember { mutableStateOf(2) }
    val slotChannels = remember {
        mutableStateListOf<MediaEntry?>().apply {
            repeat(4) { idx -> add(playableDefaults.getOrNull(idx)) }
        }
    }
    var activeAudioIndex by remember { mutableStateOf(0) }
    var pickingSlotIndex by remember { mutableStateOf<Int?>(null) }
    val tileErrors = remember { mutableStateMapOf<Int, String>() }

    val targetSlot = pickingSlotIndex
    if (targetSlot != null) {
        var pickerQuery by remember { mutableStateOf("") }
        val pickerList = remember(channels, pickerQuery) {
            val q = pickerQuery.trim().lowercase()
            if (q.isEmpty()) playableDefaults.take(300)
            else channels.filter { it.name.lowercase().contains(q) || it.category.lowercase().contains(q) }.take(300)
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A0B38)),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize().padding(14.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Select Channel for Screen ${targetSlot + 1}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Button(onClick = { pickingSlotIndex = null }) {
                        Text("Cancel")
                    }
                }
                TvInputField(
                    value = pickerQuery,
                    onValueChange = { pickerQuery = it },
                    label = "Filter channels by name or category",
                    imeAction = ImeAction.Search,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                    items(pickerList) { entry ->
                        var focused by remember { mutableStateOf(false) }
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (focused) Color(0xFF3B1976) else Color(0xFF24104A)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { focused = it.isFocused }
                                .focusable()
                                .clickable {
                                    tileErrors.remove(targetSlot)
                                    slotChannels[targetSlot] = entry
                                    pickingSlotIndex = null
                                }
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(entry.name, color = Color.White, fontWeight = FontWeight.Bold)
                                Text(entry.category, color = Color(0xFF67E8F9), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        return
    }

    val activeUrls = List(4) { idx -> if (idx < screenCount) slotChannels[idx]?.streamUrl?.trim().orEmpty() else "" }
    val players = remember(activeUrls, maxHeightPx) {
        activeUrls.mapIndexed { idx, stream ->
            if (stream.isEmpty()) {
                null
            } else {
                try {
                    ExoPlayer.Builder(context).build().apply {
                        trackSelectionParameters = trackSelectionParameters
                            .buildUpon()
                            .setMaxVideoSize(1280, maxHeightPx)
                            .build()
                        volume = if (idx == activeAudioIndex) 1f else 0f
                        playWhenReady = true
                        addListener(object : Player.Listener {
                            override fun onPlayerError(error: PlaybackException) {
                                tileErrors[idx] = error.errorCodeName
                            }
                        })
                        setMediaItem(MediaItem.fromUri(Uri.parse(stream)))
                        prepare()
                    }
                } catch (e: Exception) {
                    tileErrors[idx] = e.localizedMessage ?: "Player init failed"
                    null
                }
            }
        }
    }

    LaunchedEffect(activeAudioIndex, players) {
        players.forEachIndexed { idx, player ->
            player?.volume = if (idx == activeAudioIndex) 1f else 0f
        }
    }

    DisposableEffect(players) {
        onDispose {
            players.forEach { player ->
                runCatching {
                    player?.stop()
                    player?.release()
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Screens:", color = Color.White, fontWeight = FontWeight.SemiBold)
            listOf(2, 3, 4).forEach { count ->
                val selected = screenCount == count
                Button(
                    onClick = {
                        screenCount = count
                        if (activeAudioIndex >= count) activeAudioIndex = 0
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) Color(0xFFD946EF) else Color(0xFF3B1976)
                    )
                ) {
                    Text("$count Screens")
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "Tap tile for Audio • Tap 'Pick Channel' to change stream",
                color = Color(0xFFCBD5E1),
                style = MaterialTheme.typography.bodySmall
            )
        }

        val rows: List<List<Int>> = when (screenCount) {
            2 -> listOf(listOf(0, 1))
            3 -> listOf(listOf(0, 1), listOf(2))
            else -> listOf(listOf(0, 1), listOf(2, 3))
        }

        rows.forEach { rowIndices ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                rowIndices.forEach { idx ->
                    val ch = slotChannels[idx]
                    val player = players[idx]
                    val err = tileErrors[idx]

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(Color(0xFF090414), RoundedCornerShape(10.dp))
                            .border(
                                width = if (idx == activeAudioIndex) 2.dp else 1.dp,
                                color = if (idx == activeAudioIndex) Color(0xFF38BDF8) else Color(0xFF581C87),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { activeAudioIndex = idx },
                        contentAlignment = Alignment.Center
                    ) {
                        if (player != null && err == null) {
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        this.player = player
                                        useController = false
                                    }
                                },
                                update = { view -> view.player = player },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(
                                text = ch?.name?.let { "$it — ${err ?: "Unavailable"}" } ?: "Screen ${idx + 1} Empty",
                                color = Color(0xFFCBD5E1),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .fillMaxWidth()
                                .background(Color(0x9914072B))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${if (idx == activeAudioIndex) "🔊 " else ""}${ch?.name ?: "Screen ${idx + 1}"}",
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = { pickingSlotIndex = idx },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Pick Channel", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategorizedBrowserPane(
    items: List<MediaEntry>,
    imdbApiKey: String,
    isSeries: Boolean,
    emptyText: String,
    onGoToSetup: () -> Unit,
    onSelectEntry: (MediaEntry) -> Unit
) {
    if (items.isEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(emptyText, color = Color(0xFFFBBF24))
            Button(onClick = onGoToSetup) {
                Text("Go to IPTV & EPG Setup")
            }
        }
        return
    }

    var searchQuery by remember { mutableStateOf("") }
    val categories = remember(items) {
        listOf("All (${items.size})") + items.map { it.category }.distinct()
    }
    var selectedCategory by remember(categories) { mutableStateOf(categories.first()) }

    val filtered = remember(items, selectedCategory, searchQuery) {
        val byCat = if (selectedCategory.startsWith("All (")) items
        else items.filter { it.category == selectedCategory }
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) byCat else byCat.filter { it.name.lowercase().contains(q) }
    }

    var focusedEntry by remember(filtered) { mutableStateOf(filtered.firstOrNull()) }
    val imdbCache = remember { mutableStateMapOf<String, ImDbMetadata?>() }
    var isLoadingImDb by remember { mutableStateOf(false) }

    LaunchedEffect(focusedEntry?.id, imdbApiKey, isSeries) {
        val active = focusedEntry ?: return@LaunchedEffect
        if (imdbApiKey.isBlank()) {
            isLoadingImDb = false
            return@LaunchedEffect
        }
        val cacheKey = "${if (isSeries) "series" else "movie"}_${active.name}"
        if (imdbCache.containsKey(cacheKey)) {
            isLoadingImDb = false
            return@LaunchedEffect
        }
        isLoadingImDb = true
        val fetched = fetchImDbInfo(imdbApiKey, active.name, isSeries)
        imdbCache[cacheKey] = fetched
        isLoadingImDb = false
    }

    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .width(210.dp)
                .fillMaxHeight()
        ) {
            items(categories) { cat ->
                val active = cat == selectedCategory
                var focused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused }
                        .focusable()
                        .background(
                            when {
                                active -> Color(0xFFD946EF)
                                focused -> Color(0xFF4C1D95)
                                else -> Color(0xFF210D44)
                            },
                            RoundedCornerShape(8.dp)
                        )
                        .border(
                            width = if (focused) 2.dp else 0.dp,
                            color = Color(0xFF38BDF8),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { selectedCategory = cat }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = cat,
                        color = Color.White,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            TvInputField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = "Search Titles",
                imeAction = ImeAction.Search,
                modifier = Modifier.fillMaxWidth()
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filtered.take(500)) { entry ->
                    var focused by remember { mutableStateOf(false) }
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (focused) Color(0xFF3B1976) else Color(0xFF24104A)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { state ->
                                focused = state.isFocused
                                if (state.isFocused) {
                                    focusedEntry = entry
                                }
                            }
                            .focusable()
                            .border(
                                width = if (focused) 2.dp else 1.dp,
                                color = if (focused) Color(0xFF38BDF8) else Color(0xFF4C1D95),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                focusedEntry = entry
                                onSelectEntry(entry)
                            }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = entry.name,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = entry.category,
                                color = Color(0xFF67E8F9),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        val currentFocused = focusedEntry
        if (currentFocused != null && imdbApiKey.isNotBlank()) {
            val cacheKey = "${if (isSeries) "series" else "movie"}_${currentFocused.name}"
            val meta = imdbCache[cacheKey]
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .width(320.dp)
                    .fillMaxHeight()
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A0B38)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(14.dp)
                    ) {
                        Text(
                            text = if (isSeries) "IMDb Series Details" else "IMDb Movie Details",
                            color = Color(0xFF38BDF8),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall
                        )
                        if (isLoadingImDb) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                text = "Looking up ${currentFocused.name}...",
                                color = Color(0xFFCBD5E1),
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else if (meta != null) {
                            Text(
                                text = "${meta.title} (${meta.year})",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            if (meta.rating.isNotBlank() && meta.rating != "N/A") {
                                Text(
                                    text = "IMDb Rating: ★ ${meta.rating}",
                                    color = Color(0xFF4ADE80),
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            val specLine = listOf(meta.genre, meta.runtime, meta.imdbId)
                                .filter { it.isNotBlank() && it != "N/A" }
                                .joinToString("  •  ")
                            if (specLine.isNotBlank()) {
                                Text(
                                    text = specLine,
                                    color = Color(0xFF67E8F9),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (meta.actors.isNotBlank() && meta.actors != "N/A") {
                                Text(
                                    text = "Cast: ${meta.actors}",
                                    color = Color(0xFFE2E8F0),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (meta.plot.isNotBlank() && meta.plot != "N/A") {
                                Text(
                                    text = meta.plot,
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 6,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        } else {
                            Text(
                                text = currentFocused.name,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "No matching IMDb entry found for this title.",
                                color = Color(0xFFCBD5E1),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Button(
                            onClick = { onSelectEntry(currentFocused) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isSeries) "Play Episode 1" else "Play Movie")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrandLogoBadge() {
    val context = LocalContext.current
    val logoResId = remember {
        context.resources.getIdentifier("ic_launcher", "drawable", context.packageName)
    }

    if (logoResId != 0) {
        Image(
            painter = painterResource(id = logoResId),
            contentDescription = "Unicorn TV Logo",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .border(2.dp, Color(0xFF38BDF8), CircleShape)
        )
    }
}

@Composable
private fun IntroVideoOverlay(onFinish: () -> Unit) {
    val context = LocalContext.current
    val rawResId = remember {
        context.resources.getIdentifier("unicorn_intro", "raw", context.packageName)
    }

    val player = remember(rawResId) {
        if (rawResId == 0) {
            null
        } else {
            runCatching {
                ExoPlayer.Builder(context).build().apply {
                    playWhenReady = true
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED) onFinish()
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            onFinish()
                        }
                    })
                    val uri = Uri.parse("android.resource://${context.packageName}/$rawResId")
                    setMediaItem(MediaItem.fromUri(uri))
                    prepare()
                }
            }.getOrNull()
        }
    }

    LaunchedEffect(player) {
        if (player == null) onFinish()
    }

    DisposableEffect(player) {
        onDispose {
            runCatching {
                player?.stop()
                player?.release()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = false
                    }
                },
                update = { view -> view.player = player },
                modifier = Modifier.fillMaxSize()
            )
        }
        Button(
            onClick = onFinish,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
        ) {
            Text("Skip Intro")
        }
    }
}

@Composable
private fun TvInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isPassword: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedLabelColor = Color(0xFF38BDF8),
            unfocusedLabelColor = Color(0xFFCBD5E1),
            focusedContainerColor = Color(0xFF1F0C40),
            unfocusedContainerColor = Color(0xFF180933)
        ),
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                val isSelectKey =
                    event.key == Key.DirectionCenter ||
                        event.key == Key.Enter ||
                        event.key == Key.NumPadEnter
                if (isSelectKey && event.type == KeyEventType.KeyDown) {
                    focusRequester.requestFocus()
                } else if (isSelectKey && event.type == KeyEventType.KeyUp) {
                    keyboardController?.show()
                }
                false
            }
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color(0xFF38BDF8) else Color(0xFF7E22CE),
                shape = RoundedCornerShape(4.dp)
            )
    )
}

@Composable
private fun SetupAndPlaylistsPane(
    config: IptvConfig,
    catalog: LoadedCatalog,
    isLoading: Boolean,
    statusMessage: String,
    onSavePlaylist: (PlaylistProfile, makeActive: Boolean) -> Unit,
    onActivatePlaylist: (PlaylistProfile) -> Unit,
    onDeletePlaylist: (PlaylistProfile) -> Unit
) {
    val initialProfile = config.activePlaylist ?: PlaylistProfile(name = "Unicorn TV Main")
    var editingId by remember { mutableStateOf(initialProfile.id) }
    var playlistName by remember { mutableStateOf(initialProfile.name) }
    var m3uUrl by remember { mutableStateOf(initialProfile.m3uUrl) }
    var xtreamHost by remember { mutableStateOf(initialProfile.xtreamHost) }
    var xtreamUser by remember { mutableStateOf(initialProfile.xtreamUser) }
    var xtreamPass by remember { mutableStateOf(initialProfile.xtreamPass) }
    var epgUrl by remember { mutableStateOf(initialProfile.epgUrl) }
    var updateOnStartup by remember { mutableStateOf(initialProfile.updateOnStartup) }
    var autoDailyUpdate by remember { mutableStateOf(initialProfile.autoDailyUpdate) }
    var dailyUpdateHour by remember { mutableStateOf(initialProfile.dailyUpdateHour) }

    fun loadIntoEditor(p: PlaylistProfile) {
        editingId = p.id
        playlistName = p.name
        m3uUrl = p.m3uUrl
        xtreamHost = p.xtreamHost
        xtreamUser = p.xtreamUser
        xtreamPass = p.xtreamPass
        epgUrl = p.epgUrl
        updateOnStartup = p.updateOnStartup
        autoDailyUpdate = p.autoDailyUpdate
        dailyUpdateHour = p.dailyUpdateHour
    }

    val dateFmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A0B38)),
            modifier = Modifier
                .width(275.dp)
                .fillMaxHeight()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Saved Playlists (${config.playlists.size})",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Button(
                        onClick = {
                            loadIntoEditor(
                                PlaylistProfile(
                                    name = "Playlist ${config.playlists.size + 1}",
                                    updateOnStartup = true,
                                    autoDailyUpdate = true,
                                    dailyUpdateHour = 4
                                )
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("+ New")
                    }
                }

                if (config.playlists.isEmpty()) {
                    Text(
                        text = "No saved playlists yet. Fill out the form on the right and click Save & Load.",
                        color = Color(0xFFCBD5E1),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(config.playlists) { item ->
                            val isActive = item.id == config.activePlaylistId
                            val isEditing = item.id == editingId
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isActive) Color(0xFF3B1976) else Color(0xFF24104A)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        width = if (isEditing || isActive) 2.dp else 1.dp,
                                        color = if (isActive) Color(0xFF4ADE80) else if (isEditing) Color(0xFF38BDF8) else Color(0xFF4C1D95),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(10.dp)
                                ) {
                                    Text(
                                        text = "${if (isActive) "✓ " else ""}${item.name}",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val schedText = buildString {
                                        if (item.updateOnStartup) append("Startup")
                                        if (item.autoDailyUpdate) {
                                            if (isNotEmpty()) append(" + ")
                                            append("Daily ${"%02d:00".format(item.dailyUpdateHour)}")
                                        }
                                        if (isEmpty()) append("Manual update")
                                    }
                                    Text(
                                        text = "Auto: $schedText",
                                        color = Color(0xFF67E8F9),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = if (item.lastUpdatedMs > 0L) {
                                            "Updated: ${dateFmt.format(Date(item.lastUpdatedMs))}"
                                        } else {
                                            "Not synced yet"
                                        },
                                        color = Color(0xFFCBD5E1),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Button(
                                            enabled = !isLoading,
                                            onClick = {
                                                loadIntoEditor(item)
                                                onActivatePlaylist(item)
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text(if (isActive) "Sync" else "Use", style = MaterialTheme.typography.labelSmall)
                                        }
                                        Button(
                                            onClick = { loadIntoEditor(item) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text("Edit", style = MaterialTheme.typography.labelSmall)
                                        }
                                        Button(
                                            onClick = { onDeletePlaylist(item) },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7F1D1D)),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text("Del", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
        ) {
            TvInputField(
                value = playlistName,
                onValueChange = { playlistName = it },
                label = "Playlist Name (e.g., Main IPTV, Sports Backup)",
                modifier = Modifier.fillMaxWidth()
            )

            TvInputField(
                value = m3uUrl,
                onValueChange = { m3uUrl = it },
                label = "M3U / M3U8 Playlist URL (Leave blank if using Xtream below)",
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                TvInputField(
                    value = xtreamHost,
                    onValueChange = { xtreamHost = it },
                    label = "Xtream Server URL",
                    modifier = Modifier.weight(1f)
                )
                TvInputField(
                    value = xtreamUser,
                    onValueChange = { xtreamUser = it },
                    label = "Username",
                    modifier = Modifier.weight(1f)
                )
                TvInputField(
                    value = xtreamPass,
                    onValueChange = { xtreamPass = it },
                    label = "Password",
                    isPassword = true,
                    modifier = Modifier.weight(1f)
                )
            }

            TvInputField(
                value = epgUrl,
                onValueChange = { epgUrl = it },
                label = "EPG (XMLTV) URL",
                imeAction = ImeAction.Done,
                modifier = Modifier.fillMaxWidth()
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF24104A)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(12.dp)
                ) {
                    Text(
                        text = "Automatic Playlist & EPG Update Schedule",
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )

                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Update automatically on each app startup", color = Color.White)
                        Switch(
                            checked = updateOnStartup,
                            onCheckedChange = { updateOnStartup = it }
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Update automatically once every day", color = Color.White)
                        Switch(
                            checked = autoDailyUpdate,
                            onCheckedChange = { autoDailyUpdate = it }
                        )
                    }

                    if (autoDailyUpdate) {
                        Text(
                            text = "Select Daily Update Time: ${"%02d:00".format(dailyUpdateHour)}",
                            color = Color(0xFF67E8F9),
                            style = MaterialTheme.typography.bodySmall
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items((0..23).toList()) { hour ->
                                val selected = dailyUpdateHour == hour
                                Button(
                                    onClick = { dailyUpdateHour = hour },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (selected) Color(0xFFD946EF) else Color(0xFF3B1976)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("${"%02d:00".format(hour)}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = !isLoading,
                    onClick = {
                        val existing = config.playlists.firstOrNull { it.id == editingId }
                        val profile = PlaylistProfile(
                            id = editingId,
                            name = playlistName.trim().ifBlank { "My IPTV Playlist" },
                            m3uUrl = m3uUrl.trim(),
                            xtreamHost = xtreamHost.trim(),
                            xtreamUser = xtreamUser.trim(),
                            xtreamPass = xtreamPass,
                            epgUrl = epgUrl.trim(),
                            updateOnStartup = updateOnStartup,
                            autoDailyUpdate = autoDailyUpdate,
                            dailyUpdateHour = dailyUpdateHour,
                            lastUpdatedMs = existing?.lastUpdatedMs ?: 0L
                        )
                        onSavePlaylist(profile, true)
                    }
                ) {
                    Text(if (isLoading) "Updating Playlist..." else "Save & Load Playlist")
                }

                if (statusMessage.isNotBlank()) {
                    Text(statusMessage, color = Color(0xFF4ADE80), modifier = Modifier.weight(1f))
                }
            }

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Text(
                text = "Loaded Catalog: ${catalog.live.size} Live • ${catalog.movies.size} Movies • ${catalog.series.size} Series • ${catalog.epgTimeline.size} EPG Channels",
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun SpeedOptimizerPane(
    bufferSeconds: Int,
    maxResolutionLabel: String,
    lowLatencyMode: Boolean,
    onApplySettings: (bufferSec: Int, resolutionLabel: String, lowLatency: Boolean) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var isTesting by remember { mutableStateOf(false) }
    var measuredMbps by remember { mutableStateOf<Float?>(null) }
    var pingMs by remember { mutableStateOf<Int?>(null) }
    var statusText by remember { mutableStateOf("Run a quick network check or tune playback buffers for your Fire TV connection.") }

    var selectedBuffer by remember { mutableStateOf(bufferSeconds) }
    var selectedRes by remember { mutableStateOf(maxResolutionLabel) }
    var selectedLowLatency by remember { mutableStateOf(lowLatencyMode) }

    val recommendation = when {
        measuredMbps == null -> "Recommended: 15s buffer + 1080p until speed test runs."
        measuredMbps!! >= 45f -> "Recommended: 4K60 HDR supported (10s–15s buffer)."
        measuredMbps!! >= 18f -> "Recommended: 1080p60 smooth (15s–25s buffer) for steady IPTV playback."
        else -> "Recommended: 720p cap + 25s–40s buffer to prevent buffering on slower links."
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF24104A)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Connection & Throughput Check",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Button(
                        enabled = !isTesting,
                        onClick = {
                            isTesting = true
                            statusText = "Testing download throughput..."
                            coroutineScope.launch(Dispatchers.IO) {
                                val result = measureDownloadSpeedMbps()
                                withContext(Dispatchers.Main) {
                                    isTesting = false
                                    if (result != null) {
                                        measuredMbps = result.first
                                        pingMs = result.second
                                        statusText = "Measured ${"%.1f".format(result.first)} Mbps (${result.second} ms)"
                                    } else {
                                        statusText = "Could not reach speed test endpoint. Check network/VPN and try again."
                                    }
                                }
                            }
                        }
                    ) {
                        Text(if (isTesting) "Testing..." else "Run Speed Test")
                    }
                }

                if (isTesting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text(
                        text = "Download: ${measuredMbps?.let { "%.1f Mbps".format(it) } ?: "--"}",
                        color = Color(0xFF67E8F9),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Latency: ${pingMs?.let { "$it ms" } ?: "--"}",
                        color = Color(0xFF67E8F9),
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(text = statusText, color = Color(0xFFE2E8F0), style = MaterialTheme.typography.bodyMedium)
                Text(text = recommendation, color = Color(0xFF4ADE80), style = MaterialTheme.typography.bodyMedium)
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF24104A)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Stream Buffer & Resolution Cap",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Buffer:", color = Color.White, modifier = Modifier.width(90.dp))
                    listOf(5, 15, 25, 40).forEach { sec ->
                        val active = selectedBuffer == sec
                        Button(
                            onClick = { selectedBuffer = sec },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (active) Color(0xFFD946EF) else Color(0xFF3B1976)
                            )
                        ) {
                            Text("${sec}s")
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Max Res:", color = Color.White, modifier = Modifier.width(90.dp))
                    listOf("720p", "1080p", "4K").forEach { res ->
                        val active = selectedRes == res
                        Button(
                            onClick = { selectedRes = res },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (active) Color(0xFFD946EF) else Color(0xFF3B1976)
                            )
                        ) {
                            Text(res)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Low-Latency Live Mode", color = Color.White)
                    Switch(
                        checked = selectedLowLatency,
                        onCheckedChange = { selectedLowLatency = it }
                    )
                }

                Button(
                    onClick = {
                        onApplySettings(selectedBuffer, selectedRes, selectedLowLatency)
                        statusText = "Applied ${selectedBuffer}s buffer, $selectedRes max resolution, Low-Latency=${if (selectedLowLatency) "ON" else "OFF"}."
                    }
                ) {
                    Text("Apply Optimizer Profile")
                }
            }
        }
    }
}

private fun measureDownloadSpeedMbps(
    urlString: String = "https://speed.cloudflare.com/__down?bytes=10000000"
): Pair<Float, Int>? {
    return try {
        val startTime = System.nanoTime()
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            requestMethod = "GET"
        }
        conn.connect()
        val firstByteTime = System.nanoTime()
        val ping = ((firstByteTime - startTime) / 1_000_000).toInt().coerceAtLeast(1)

        val buffer = ByteArray(16 * 1024)
        var totalBytes = 0L
        conn.inputStream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                totalBytes += read
            }
        }
        conn.disconnect()

        val elapsedSec = ((System.nanoTime() - firstByteTime) / 1_000_000_000.0).coerceAtLeast(0.05)
        val mbps = ((totalBytes * 8.0) / (elapsedSec * 1_000_000.0)).toFloat()
        Pair(mbps, ping)
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun SettingsPane(
    config: IptvConfig,
    onUpdate: (IptvConfig) -> Unit
) {
    var omdbKeyDraft by remember { mutableStateOf(config.imdbApiKey) }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        TvInputField(
            value = omdbKeyDraft,
            onValueChange = { omdbKeyDraft = it },
            label = "OMDb / IMDb Lookup API Key",
            imeAction = ImeAction.Done,
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { onUpdate(config.copy(imdbApiKey = omdbKeyDraft)) }) {
            Text("Save IMDb Lookup Key")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Play Intro Video on Startup", color = Color.White)
            Switch(
                checked = config.playIntroOnStart,
                onCheckedChange = { onUpdate(config.copy(playIntroOnStart = it)) }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Multi-View Max Resolution:", color = Color.White)
            Button(onClick = { onUpdate(config.copy(maxHeightPx = 720)) }) {
                Text("720p${if (config.maxHeightPx == 720) " ✓" else ""}")
            }
            Button(onClick = { onUpdate(config.copy(maxHeightPx = 1080)) }) {
                Text("1080p${if (config.maxHeightPx == 1080) " ✓" else ""}")
            }
        }
    }
}
