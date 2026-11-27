package com.unicorntv.iptv

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class IptvConfig(
    val m3uUrl: String = "",
    val xtreamHost: String = "",
    val xtreamUser: String = "",
    val xtreamPass: String = "",
    val epgUrl: String = "",
    val playIntroOnStart: Boolean = true,
    val maxHeightPx: Int = 720,
    val bufferSeconds: Int = 15,
    val maxResolutionLabel: String = "1080p",
    val lowLatencyMode: Boolean = false
)

data class ChannelItem(
    val name: String,
    val group: String,
    val streamUrl: String
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
        .putBoolean("play_intro", config.playIntroOnStart)
        .putInt("max_height_px", config.maxHeightPx)
        .putInt("buffer_seconds", config.bufferSeconds)
        .putString("max_resolution_label", config.maxResolutionLabel)
        .putBoolean("low_latency_mode", config.lowLatencyMode)
        .apply()
}

private fun buildChannelsFromConfig(config: IptvConfig): List<ChannelItem> {
    val list = mutableListOf<ChannelItem>()
    if (config.m3uUrl.isNotBlank()) {
        list.add(ChannelItem("Custom M3U Stream 1", "M3U Playlist", config.m3uUrl.trim()))
    }
    if (config.xtreamHost.isNotBlank() && config.xtreamUser.isNotBlank() && config.xtreamPass.isNotBlank()) {
        val cleanHost = config.xtreamHost.trim().removeSuffix("/")
        val base = if (cleanHost.startsWith("http")) cleanHost else "http://$cleanHost"
        val user = config.xtreamUser.trim()
        val pass = config.xtreamPass.trim()
        list.add(ChannelItem("Xtream Ch 101", "Live TV", "$base/live/$user/$pass/101.m3u8"))
        list.add(ChannelItem("Xtream Ch 102", "Live TV", "$base/live/$user/$pass/102.m3u8"))
        list.add(ChannelItem("Xtream Ch 103", "Live TV", "$base/live/$user/$pass/103.m3u8"))
        list.add(ChannelItem("Xtream Ch 104", "Live TV", "$base/live/$user/$pass/104.m3u8"))
    }
    return list
}

private enum class TvSection(val title: String, val icon: ImageVector) {
    SETUP("Live TV & EPG Setup", Icons.Default.LiveTv),
    GUIDE("TV Guide (EPG)", Icons.Default.CalendarMonth),
    FAVORITES("Favorites", Icons.Default.Star),
    MULTIVIEW("Multi-View (4x)", Icons.Default.GridView),
    MOVIES("Movies (VOD)", Icons.Default.Movie),
    SERIES("Series", Icons.Default.Tv),
    SPEED_OPTIMIZER("Speed Optimizer", Icons.Default.Speed),
    SETTINGS("Settings", Icons.Default.Settings)
}

@Composable
fun UnicornTvApp() {
    val context = LocalContext.current
    var config by remember { mutableStateOf(loadConfig(context)) }
    var showIntro by remember { mutableStateOf(config.playIntroOnStart) }
    var selectedSection by remember { mutableStateOf(TvSection.SETUP) }
    var channels by remember { mutableStateOf(buildChannelsFromConfig(config)) }
    var statusMessage by remember { mutableStateOf("") }

    if (showIntro) {
        IntroVideoOverlay(
            onFinish = { showIntro = false }
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
        // Left D-Pad Friendly Rail (8 sections)
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

        // Main Content Pane
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
                text = "Ready for 720p–4K60 HDR streams, XMLTV EPG guide, PiP, and Multi-View.",
                color = Color(0xFF67E8F9),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(14.dp))

            when (selectedSection) {
                TvSection.SETUP -> SetupAndPlaylistPane(
                    config = config,
                    channels = channels,
                    statusMessage = statusMessage,
                    onSave = { updated ->
                        config = updated
                        saveConfig(context, updated)
                        channels = buildChannelsFromConfig(updated)
                        statusMessage = "Saved! Loaded ${channels.size} stream(s)."
                    }
                )

                TvSection.MULTIVIEW -> SafeMultiViewPane(
                    channels = channels,
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
                        channels = buildChannelsFromConfig(updated)
                        statusMessage = "Settings updated."
                    }
                )

                else -> ChannelListPane(
                    section = selectedSection,
                    channels = channels,
                    epgUrl = config.epgUrl,
                    onGoToSetup = { selectedSection = TvSection.SETUP }
                )
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
            try {
                ExoPlayer.Builder(context).build().apply {
                    playWhenReady = true
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED) {
                                onFinish()
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            onFinish()
                        }
                    })
                    val uri = Uri.parse("android.resource://${context.packageName}/$rawResId")
                    setMediaItem(MediaItem.fromUri(uri))
                    prepare()
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    LaunchedEffect(player) {
        if (player == null) {
            onFinish()
        }
    }

    DisposableEffect(player) {
        onDispose {
            try {
                player?.stop()
                player?.release()
            } catch (_: Exception) {
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
        keyboardActions = KeyboardActions(
            onDone = { keyboardController?.hide() }
        ),
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
            .onFocusChanged { state ->
                focused = state.isFocused
                if (state.isFocused) {
                    keyboardController?.show()
                }
            }
            .onPreviewKeyEvent { event ->
                if (
                    event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
                ) {
                    focusRequester.requestFocus()
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
    channels: List<ChannelItem>,
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
            label = "M3U / M3U8 Playlist URL",
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
                Text("Save & Apply IPTV / EPG")
            }
            if (statusMessage.isNotBlank()) {
                Text(statusMessage, color = Color(0xFF4ADE80))
            }
        }

        Text(
            text = "Configured Streams (${channels.size}):",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 6.dp)
        )
        channels.forEach { ch ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF24104A)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(ch.name, color = Color.White, fontWeight = FontWeight.Bold)
                    Text(ch.streamUrl, color = Color(0xFFCBD5E1), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ChannelListPane(
    section: TvSection,
    channels: List<ChannelItem>,
    epgUrl: String,
    onGoToSetup: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (section == TvSection.GUIDE) {
            Text(
                text = if (epgUrl.isBlank()) "No EPG URL set yet." else "Active EPG Source: $epgUrl",
                color = Color(0xFFE2E8F0)
            )
        }
        if (channels.isEmpty()) {
            Text("No streams configured yet. Add an M3U URL or Xtream Codes in Setup.", color = Color(0xFFFBBF24))
            Button(onClick = onGoToSetup) {
                Text("Go to IPTV & EPG Setup")
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(channels) { ch ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF24104A)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(ch.name, color = Color.White, fontWeight = FontWeight.Bold)
                                Text(ch.group, color = Color(0xFF67E8F9), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SafeMultiViewPane(
    channels: List<ChannelItem>,
    maxHeightPx: Int,
    onGoToSetup: () -> Unit
) {
    val context = LocalContext.current
    val slots = remember(channels) {
        List(4) { index -> channels.getOrNull(index) }
    }
    var activeAudioIndex by remember { mutableStateOf(0) }
    val tileErrors = remember { mutableStateMapOf<Int, String>() }

    if (channels.isEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Add at least one stream in Setup before launching Multi-View.", color = Color(0xFFFBBF24))
            Button(onClick = onGoToSetup) {
                Text("Open Setup")
            }
        }
        return
    }

    val players = remember(slots, maxHeightPx) {
        slots.mapIndexed { idx, item ->
            val stream = item?.streamUrl?.trim().orEmpty()
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
                try {
                    player?.stop()
                    player?.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        for (row in 0..1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                for (col in 0..1) {
                    val idx = row * 2 + col
                    val ch = slots[idx]
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
                                text = ch?.name?.let { "$it — ${err ?: "Unavailable"}" } ?: "Empty Slot ${idx + 1}",
                                color = Color(0xFFCBD5E1)
                            )
                        }
                    }
                }
            }
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
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
