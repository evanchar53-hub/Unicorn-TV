package com.unicorntv.iptv

import android.content.Context
import android.net.Uri
import android.os.Bundle
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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class IptvConfig(
    val m3uUrl: String = "",
    val xtreamHost: String = "",
    val xtreamUser: String = "",
    val xtreamPass: String = "",
    val epgUrl: String = "",
    val imdbApiKey: String = "",
    val playIntroOnStart: Boolean = true,
    val maxHeightPx: Int = 720,
    val bufferSeconds: Int = 15,
    val maxResolutionLabel: String = "1080p",
    val lowLatencyMode: Boolean = false
)

enum class MediaKind { LIVE, MOVIE, SERIES }

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

private fun loadConfig(context: Context): IptvConfig {
    val prefs = context.getSharedPreferences("unicorn_tv_prefs", Context.MODE_PRIVATE)
    return IptvConfig(
        m3uUrl = prefs.getString("m3u_url", "") ?: "",
        xtreamHost = prefs.getString("xtream_host", "") ?: "",
        xtreamUser = prefs.getString("xtream_user", "") ?: "",
        xtreamPass = prefs.getString("xtream_pass", "") ?: "",
        epgUrl = prefs.getString("epg_url", "") ?: "",
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
        .putString("m3u_url", config.m3uUrl.trim())
        .putString("xtream_host", config.xtreamHost.trim())
        .putString("xtream_user", config.xtreamUser.trim())
        .putString("xtream_pass", config.xtreamPass)
        .putString("epg_url", config.epgUrl.trim())
        .putString("imdb_api_key", config.imdbApiKey.trim())
        .putBoolean("play_intro", config.playIntroOnStart)
        .putInt("max_height_px", config.maxHeightPx)
        .putInt("buffer_seconds", config.bufferSeconds)
        .putString("max_resolution_label", config.maxResolutionLabel)
        .putBoolean("low_latency_mode", config.lowLatencyMode)
        .apply()
}

private fun httpGetText(urlStr: String, connectTimeoutMs: Int = 20000, readTimeoutMs: Int = 90000): String {
    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        requestMethod = "GET"
        setRequestProperty("User-Agent", "UnicornTV/2.0 (AndroidTV)")
        setRequestProperty("Accept-Encoding", "identity")
    }
    return try {
        val code = conn.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        BufferedReader(InputStreamReader(BufferedInputStream(conn.inputStream, 65536), Charsets.UTF_8), 65536).use {
            it.readText()
        }
    } finally {
        conn.disconnect()
    }
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
        val obj = JSONObject(httpGetText(url, connectTimeoutMs = 10000, readTimeoutMs = 15000))
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

private suspend fun fetchFullCatalog(
    config: IptvConfig,
    onProgress: (String) -> Unit
): LoadedCatalog = withContext(Dispatchers.IO) {
    val liveList = mutableListOf<MediaEntry>()
    val movieList = mutableListOf<MediaEntry>()
    val seriesList = mutableListOf<MediaEntry>()
    val warnings = mutableListOf<String>()

    val cleanHost = config.xtreamHost.trim().removeSuffix("/")
    val user = config.xtreamUser.trim()
    val pass = config.xtreamPass.trim()

    try {
        if (cleanHost.isNotBlank() && user.isNotBlank() && pass.isNotBlank()) {
            val base = if (cleanHost.startsWith("http")) cleanHost else "http://$cleanHost"
            val apiBase = "$base/player_api.php?username=${Uri.encode(user)}&password=${Uri.encode(pass)}"

            onProgress("Loading Live TV channels...")
            val liveCatMap = parseCategoryMap(
                runCatching { httpGetText("$apiBase&action=get_live_categories", readTimeoutMs = 45000) }.getOrDefault("[]")
            )
            runCatching {
                val liveRaw = httpGetText("$apiBase&action=get_live_streams", readTimeoutMs = 90000)
                parseJsonArrayEntries(liveRaw).forEach { obj ->
                    val streamId = firstNonBlank(obj.optString("stream_id"), obj.optString("id"), obj.optString("num"))
                    if (streamId.isBlank()) return@forEach
                    val name = obj.optString("name", "Channel $streamId")
                    val catId = obj.optString("category_id")
                    val catName = liveCatMap[catId] ?: obj.optString("category_name").ifBlank { "Live TV" }
                    val epgId = obj.optString("epg_channel_id", "")
                    liveList.add(
                        MediaEntry(
                            id = streamId,
                            name = name,
                            category = catName,
                            streamUrl = "$base/live/$user/$pass/$streamId.ts",
                            epgId = epgId,
                            kind = MediaKind.LIVE
                        )
                    )
                }
            }.onFailure { warnings.add("Live: ${it.message}") }

            onProgress("Loading VOD Movies (large catalog, please wait)...")
            val vodCatMap = parseCategoryMap(
                runCatching { httpGetText("$apiBase&action=get_vod_categories", readTimeoutMs = 45000) }.getOrDefault("[]")
            )
            runCatching {
                val vodRaw = httpGetText("$apiBase&action=get_vod_streams", readTimeoutMs = 120000)
                parseJsonArrayEntries(vodRaw).forEach { obj ->
                    val streamId = firstNonBlank(obj.optString("stream_id"), obj.optString("vod_id"), obj.optString("id"))
                    if (streamId.isBlank()) return@forEach
                    val name = obj.optString("name", obj.optString("title", "Movie $streamId"))
                    val catId = obj.optString("category_id")
                    val catName = vodCatMap[catId] ?: obj.optString("category_name").ifBlank { "Movies" }
                    val ext = obj.optString("container_extension", "mp4").ifBlank { "mp4" }
                    movieList.add(
                        MediaEntry(
                            id = streamId,
                            name = name,
                            category = catName,
                            streamUrl = "$base/movie/$user/$pass/$streamId.$ext",
                            kind = MediaKind.MOVIE,
                            plot = obj.optString("plot", "")
                        )
                    )
                }
            }.onFailure { warnings.add("VOD: ${it.message}") }

            onProgress("Loading TV Series...")
            val serCatMap = parseCategoryMap(
                runCatching { httpGetText("$apiBase&action=get_series_categories", readTimeoutMs = 45000) }.getOrDefault("[]")
            )
            runCatching {
                val serRaw = httpGetText("$apiBase&action=get_series", readTimeoutMs = 90000)
                parseJsonArrayEntries(serRaw).forEach { obj ->
                    val seriesId = firstNonBlank(obj.optString("series_id"), obj.optString("id"))
                    if (seriesId.isBlank()) return@forEach
                    val name = obj.optString("name", obj.optString("title", "Series $seriesId"))
                    val catId = obj.optString("category_id")
                    val catName = serCatMap[catId] ?: obj.optString("category_name").ifBlank { "Series" }
                    seriesList.add(
                        MediaEntry(
                            id = seriesId,
                            name = name,
                            category = catName,
                            streamUrl = "$apiBase&action=get_series_info&series_id=$seriesId",
                            kind = MediaKind.SERIES,
                            plot = obj.optString("plot", "")
                        )
                    )
                }
            }.onFailure { warnings.add("Series: ${it.message}") }
        }

        if (config.m3uUrl.isNotBlank() && (liveList.isEmpty() || movieList.isEmpty())) {
            onProgress("Parsing M3U fallback...")
            val m3uLive = mutableListOf<MediaEntry>()
            val m3uMovies = mutableListOf<MediaEntry>()
            val m3uSeries = mutableListOf<MediaEntry>()
            val rawM3u = httpGetText(config.m3uUrl.trim(), readTimeoutMs = 90000)
            parseM3uIntoLists(rawM3u, m3uLive, m3uMovies, m3uSeries)
            if (liveList.isEmpty()) liveList.addAll(m3uLive)
            if (movieList.isEmpty()) movieList.addAll(m3uMovies)
            if (seriesList.isEmpty()) seriesList.addAll(m3uSeries)
        }
    } catch (e: Exception) {
        warnings.add(e.localizedMessage ?: "Failed to load playlist")
    }

    onProgress("Downloading XMLTV EPG guide...")
    val epgUrl = config.epgUrl.trim().ifBlank {
        if (cleanHost.isNotBlank() && user.isNotBlank() && pass.isNotBlank()) {
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

private fun firstNonBlank(vararg values: String): String =
    values.firstOrNull { it.isNotBlank() && it != "null" } ?: ""

private fun parseJsonArrayEntries(raw: String): List<JSONObject> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return emptyList()
    val arr = if (trimmed.startsWith("[")) {
        JSONArray(trimmed)
    } else if (trimmed.startsWith("{")) {
        val obj = JSONObject(trimmed)
        obj.optJSONArray("streams")
            ?: obj.optJSONArray("vod_streams")
            ?: obj.optJSONArray("series")
            ?: obj.optJSONArray("data")
            ?: JSONArray()
    } else {
        JSONArray()
    }
    val out = ArrayList<JSONObject>(arr.length())
    for (i in 0 until arr.length()) {
        val item = arr.optJSONObject(i)
        if (item != null) out.add(item)
    }
    return out
}

private fun parseCategoryMap(jsonText: String): Map<String, String> {
    val map = mutableMapOf<String, String>()
    parseJsonArrayEntries(jsonText).forEach { obj ->
        val id = firstNonBlank(obj.optString("category_id"), obj.optString("id"))
        val name = firstNonBlank(obj.optString("category_name"), obj.optString("name"))
        if (id.isNotBlank() && name.isNotBlank()) map[id] = name
    }
    return map
}

private fun parseM3uIntoLists(
    content: String,
    liveOut: MutableList<MediaEntry>,
    movieOut: MutableList<MediaEntry>,
    seriesOut: MutableList<MediaEntry>
) {
    var pendingName = ""
    var pendingGroup = "General"
    var pendingTvgId = ""
    var index = 0

    content.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.startsWith("#EXTINF:")) {
            pendingGroup = Regex("""group-title="([^"]*)"""").find(line)?.groupValues?.getOrNull(1)?.ifBlank { "General" } ?: "General"
            pendingTvgId = Regex("""tvg-id="([^"]*)"""").find(line)?.groupValues?.getOrNull(1) ?: ""
            pendingName = line.substringAfterLast(",").trim().ifBlank { "Stream ${index + 1}" }
        } else if (line.startsWith("http://") || line.startsWith("https://")) {
            index++
            val lower = line.lowercase()
            val groupLower = pendingGroup.lowercase()
            val kind = when {
                lower.contains("/movie/") || lower.endsWith(".mp4") || lower.endsWith(".mkv") || groupLower.contains("vod") || groupLower.contains("movie") -> MediaKind.MOVIE
                lower.contains("/series/") || groupLower.contains("series") -> MediaKind.SERIES
                else -> MediaKind.LIVE
            }
            val entry = MediaEntry(
                id = "m3u_$index",
                name = pendingName.ifBlank { "Channel $index" },
                category = pendingGroup,
                streamUrl = line,
                epgId = pendingTvgId,
                kind = kind
            )
            when (kind) {
                MediaKind.LIVE -> liveOut.add(entry)
                MediaKind.MOVIE -> movieOut.add(entry)
                MediaKind.SERIES -> seriesOut.add(entry)
            }
            pendingName = ""
        }
    }
}

private fun fetchEpgTimelineMap(epgUrl: String): Map<String, List<EpgSlot>> {
    val nowMs = System.currentTimeMillis()
    val windowStart = nowMs - 2 * 3600_000L
    val windowEnd = nowMs + 8 * 3600_000L
    val grouped = mutableMapOf<String, MutableList<EpgSlot>>()

    val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun parseXmltvTime(raw: String): Long {
        val normalized = if (raw.contains(" ")) raw else "$raw +0000"
        return runCatching { fmt.parse(normalized)?.time ?: 0L }.getOrDefault(0L)
    }

    val conn = (URL(epgUrl).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20000
        readTimeout = 90000
        requestMethod = "GET"
    }
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
                                if (list.size < 6) {
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
        val root = JSONObject(httpGetText(seriesInfoUrl, readTimeoutMs = 30000))
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

    // Full-screen player entry + currently watching channel persisted when returning to TV Guide
    var fullScreenItem by remember { mutableStateOf<MediaEntry?>(null) }
    var lastWatchedLiveChannel by remember { mutableStateOf<MediaEntry?>(null) }

    fun refreshCatalog(cfg: IptvConfig) {
        if (cfg.m3uUrl.isBlank() && cfg.xtreamHost.isBlank()) return
        isLoadingCatalog = true
        statusMessage = "Connecting to server..."
        scope.launch {
            val loaded = fetchFullCatalog(cfg) { progress ->
                statusMessage = progress
            }
            catalog = loaded
            isLoadingCatalog = false
            if (lastWatchedLiveChannel == null) {
                lastWatchedLiveChannel = loaded.live.firstOrNull { !it.name.startsWith("#") } ?: loaded.live.firstOrNull()
            }
            statusMessage = if (loaded.error != null) {
                "Loaded with notice: ${loaded.error}"
            } else {
                "Loaded ${loaded.live.size} Live, ${loaded.movies.size} Movies, ${loaded.series.size} Series (${loaded.epgTimeline.size} EPG channels)."
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshCatalog(config)
    }

    if (showIntro) {
        IntroVideoOverlay(onFinish = { showIntro = false })
        return
    }

    fullScreenItem?.let { current ->
        FullScreenPlayerOverlay(
            entry = current,
            config = config,
            onBackToGuide = {
                if (current.kind == MediaKind.LIVE) {
                    lastWatchedLiveChannel = current
                    selectedSection = TvSection.GUIDE
                }
                fullScreenItem = null
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
            verticalArrangement = Arrangement.spacedBy(10.dp)
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
                        .size(48.dp)
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
            Text(
                text = "Unicorn TV — ${selectedSection.title}",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "Live: ${catalog.live.size}  •  Movies: ${catalog.movies.size}  •  Series: ${catalog.series.size}  •  EPG: ${catalog.epgTimeline.size}",
                color = Color(0xFF67E8F9),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(10.dp))

            when (selectedSection) {
                TvSection.SETUP -> SetupAndPlaylistPane(
                    config = config,
                    catalog = catalog,
                    isLoading = isLoadingCatalog,
                    statusMessage = statusMessage,
                    onSave = { updated ->
                        config = updated
                        saveConfig(context, updated)
                        refreshCatalog(updated)
                    }
                )

                TvSection.GUIDE -> RealTvGuidePane(
                    channels = catalog.live,
                    epgTimeline = catalog.epgTimeline,
                    activeChannel = lastWatchedLiveChannel,
                    config = config,
                    onActiveChannelChange = { lastWatchedLiveChannel = it },
                    onGoToSetup = { selectedSection = TvSection.SETUP },
                    onOpenFullScreen = { ch ->
                        lastWatchedLiveChannel = ch
                        fullScreenItem = ch
                    }
                )

                TvSection.MOVIES -> CategorizedBrowserPane(
                    items = catalog.movies,
                    imdbApiKey = config.imdbApiKey,
                    isSeries = false,
                    emptyText = "No VOD movies loaded yet. Check Setup or tap Save & Apply to reload VOD.",
                    onGoToSetup = { selectedSection = TvSection.SETUP },
                    onSelectEntry = { fullScreenItem = it }
                )

                TvSection.SERIES -> CategorizedBrowserPane(
                    items = catalog.series,
                    imdbApiKey = config.imdbApiKey,
                    isSeries = true,
                    emptyText = "No TV series loaded yet. Enter Xtream or M3U details in Setup.",
                    onGoToSetup = { selectedSection = TvSection.SETUP },
                    onSelectEntry = { seriesItem ->
                        scope.launch {
                            val epUrl = if (seriesItem.streamUrl.contains("get_series_info")) {
                                resolveFirstSeriesEpisodeUrl(seriesItem.streamUrl)
                            } else {
                                seriesItem.streamUrl
                            }
                            if (!epUrl.isNullOrBlank()) {
                                fullScreenItem = seriesItem.copy(streamUrl = epUrl)
                            } else {
                                statusMessage = "Could not resolve episode stream for ${seriesItem.name}."
                            }
                        }
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
                    items = catalog.live.take(30),
                    imdbApiKey = "",
                    isSeries = false,
                    emptyText = "No channels available yet.",
                    onGoToSetup = { selectedSection = TvSection.SETUP },
                    onSelectEntry = {
                        lastWatchedLiveChannel = it
                        fullScreenItem = it
                    }
                )
            }
        }
    }
}

@Composable
private fun RealTvGuidePane(
    channels: List<MediaEntry>,
    epgTimeline: Map<String, List<EpgSlot>>,
    activeChannel: MediaEntry?,
    config: IptvConfig,
    onActiveChannelChange: (MediaEntry) -> Unit,
    onGoToSetup: () -> Unit,
    onOpenFullScreen: (MediaEntry) -> Unit
) {
    if (channels.isEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("No live channels loaded yet. Enter Xtream or M3U details in Setup.", color = Color(0xFFFBBF24))
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

    val previewChannel = activeChannel ?: channels.first()
    var previewError by remember(previewChannel.streamUrl) { mutableStateOf<String?>(null) }

    val nowMs = System.currentTimeMillis()
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

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

    val context = LocalContext.current
    val previewPlayer = remember(previewChannel.streamUrl, config.bufferSeconds, config.maxHeightPx) {
        val url = previewChannel.streamUrl.trim()
        if (url.isBlank()) {
            null
        } else {
            runCatching {
                val bufferMs = (config.bufferSeconds * 1000).coerceIn(5000, 40000)
                val loadControl = DefaultLoadControl.Builder()
                    .setBufferDurationsMs(bufferMs, bufferMs * 2, 1500, 2500)
                    .build()

                ExoPlayer.Builder(context)
                    .setLoadControl(loadControl)
                    .build()
                    .apply {
                        trackSelectionParameters = trackSelectionParameters
                            .buildUpon()
                            .setMaxVideoSize(1280, config.maxHeightPx.coerceAtMost(1080))
                            .build()
                        playWhenReady = true
                        addListener(object : Player.Listener {
                            override fun onPlayerError(error: PlaybackException) {
                                previewError = error.errorCodeName
                            }
                        })
                        setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                        prepare()
                    }
            }.getOrNull()
        }
    }

    DisposableEffect(previewPlayer) {
        onDispose {
            runCatching {
                previewPlayer?.stop()
                previewPlayer?.release()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        // Top Header: Live Video Preview Window + Search Bar (stays playing while typing in Search)
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(155.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .width(275.dp)
                    .fillMaxHeight()
                    .background(Color.Black, RoundedCornerShape(10.dp))
                    .border(2.dp, Color(0xFF38BDF8), RoundedCornerShape(10.dp))
                    .clickable { onOpenFullScreen(previewChannel) }
            ) {
                if (previewPlayer != null && previewError == null) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                this.player = previewPlayer
                                useController = false
                            }
                        },
                        update = { view -> view.player = previewPlayer },
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    Text(
                        text = previewError ?: "Preview unavailable",
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
                val slots = epgTimeline[previewChannel.epgId].orEmpty()
                val currentSlot = slots.firstOrNull { nowMs in it.startMs..it.stopMs } ?: slots.firstOrNull()
                val nextSlot = slots.firstOrNull { it.startMs > (currentSlot?.startMs ?: nowMs) }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = previewChannel.name,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = { onOpenFullScreen(previewChannel) }) {
                            Text("Full Screen")
                        }
                    }
                    Text(
                        text = if (currentSlot != null) {
                            "Now (${timeFmt.format(Date(currentSlot.startMs))}–${timeFmt.format(Date(currentSlot.stopMs))}): ${currentSlot.title}"
                        } else {
                            "Now: No programme info (${previewChannel.category})"
                        },
                        color = Color(0xFF4ADE80),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (nextSlot != null) {
                        Text(
                            text = "Next (${timeFmt.format(Date(nextSlot.startMs))}): ${nextSlot.title}",
                            color = Color(0xFF67E8F9),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
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
                        label = "Search Guide (Video keeps playing)",
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

        // Bottom: TiviMate-style Categories + Time-Slot EPG Grid
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
                // Time-Slot Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1A0B38), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("Channel", color = Color(0xFF67E8F9), fontWeight = FontWeight.Bold, modifier = Modifier.width(200.dp))
                    Text("Now (${timeFmt.format(Date(nowMs))})", color = Color(0xFF4ADE80), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("Up Next", color = Color(0xFF67E8F9), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(6.dp))

                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                    items(filtered.take(250)) { entry ->
                        var focused by remember { mutableStateOf(false) }
                        val isWatching = previewChannel.id == entry.id
                        val slots = epgTimeline[entry.epgId].orEmpty()
                        val nowSlot = slots.firstOrNull { nowMs in it.startMs..it.stopMs } ?: slots.firstOrNull()
                        val nextSlot = slots.firstOrNull { it.startMs > (nowSlot?.startMs ?: nowMs) }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { state ->
                                    focused = state.isFocused
                                    if (state.isFocused) {
                                        onActiveChannelChange(entry)
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
                                    width = if (focused) 2.dp else if (isWatching) 1.5.dp else 1.dp,
                                    color = if (focused) Color(0xFF38BDF8) else if (isWatching) Color(0xFFD946EF) else Color(0xFF4C1D95),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    onActiveChannelChange(entry)
                                    onOpenFullScreen(entry)
                                }
                                .padding(horizontal = 10.dp, vertical = 9.dp)
                        ) {
                            Text(
                                text = entry.name,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(200.dp).padding(end = 8.dp)
                            )
                            Text(
                                text = nowSlot?.title ?: "No information",
                                color = if (nowSlot != null) Color(0xFF4ADE80) else Color(0xFF94A3B8),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            )
                            Text(
                                text = nextSlot?.let { "${timeFmt.format(Date(it.startMs))} ${it.title}" } ?: "—",
                                color = Color(0xFFE2E8F0),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
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

    // Channel Picker Dialog for an individual square
    val targetSlot = pickingSlotIndex
    if (targetSlot != null) {
        var pickerQuery by remember { mutableStateOf("") }
        val pickerList = remember(channels, pickerQuery) {
            val q = pickerQuery.trim().lowercase()
            if (q.isEmpty()) playableDefaults.take(150)
            else channels.filter { it.name.lowercase().contains(q) || it.category.lowercase().contains(q) }.take(150)
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
        // Screen Count Selector (2, 3, or 4 Screens)
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

        // Dynamic Grid for 2, 3, or 4 Screens
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

                        // Top overlay bar per square: Channel name + Pick Channel button
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
                items(filtered.take(300)) { entry ->
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
private fun FullScreenPlayerOverlay(
    entry: MediaEntry,
    config: IptvConfig,
    onBackToGuide: () -> Unit
) {
    val context = LocalContext.current
    BackHandler { onBackToGuide() }

    val player = remember(entry.streamUrl, config.bufferSeconds, config.maxHeightPx) {
        val bufferMs = (config.bufferSeconds * 1000).coerceIn(5000, 50000)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(bufferMs, bufferMs * 2, 2000, 3000)
            .build()

        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply {
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setMaxVideoSize(3840, config.maxHeightPx)
                    .build()
                playWhenReady = true
                setMediaItem(MediaItem.fromUri(Uri.parse(entry.streamUrl)))
                prepare()
            }
    }

    DisposableEffect(player) {
        onDispose {
            runCatching {
                player.stop()
                player.release()
            }
        }
    }

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
                }
            },
            update = { view -> view.player = player },
            modifier = Modifier.fillMaxSize()
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(20.dp)
                .background(Color(0xAA14072B), RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Button(onClick = onBackToGuide) {
                Text("← Back to Guide")
            }
            Text(
                text = "${entry.name} (${entry.category})",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
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
private fun SetupAndPlaylistPane(
    config: IptvConfig,
    catalog: LoadedCatalog,
    isLoading: Boolean,
    statusMessage: String,
    onSave: (IptvConfig) -> Unit
) {
    var m3uUrl by remember { mutableStateOf(config.m3uUrl) }
    var xtreamHost by remember { mutableStateOf(config.xtreamHost) }
    var xtreamUser by remember { mutableStateOf(config.xtreamUser) }
    var xtreamPass by remember { mutableStateOf(config.xtreamPass) }
    var epgUrl by remember { mutableStateOf(config.epgUrl) }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        TvInputField(
            value = m3uUrl,
            onValueChange = { m3uUrl = it },
            label = "M3U / M3U8 Playlist URL (Optional if using Xtream)",
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

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Button(
                enabled = !isLoading,
                onClick = {
                    onSave(
                        config.copy(
                            m3uUrl = m3uUrl,
                            xtreamHost = xtreamHost,
                            xtreamUser = xtreamUser,
                            xtreamPass = xtreamPass,
                            epgUrl = epgUrl
                        )
                    )
                }
            ) {
                Text(if (isLoading) "Loading Catalog..." else "Save & Apply IPTV / EPG")
            }
            if (statusMessage.isNotBlank()) {
                Text(statusMessage, color = Color(0xFF4ADE80), modifier = Modifier.weight(1f))
            }
        }

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Text(
            text = "Summary: ${catalog.live.size} Live Channels, ${catalog.movies.size} Movies, ${catalog.series.size} Series, ${catalog.epgTimeline.size} EPG Channels",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 6.dp)
        )
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
