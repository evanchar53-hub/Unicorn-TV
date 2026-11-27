package com.unicorntv.iptv

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

data class IptvConfig(
    val m3uUrl: String = "",
    val xtreamHost: String = "",
    val xtreamUser: String = "",
    val xtreamPass: String = "",
    val epgUrl: String = "",
    val playIntroOnStart: Boolean = true,
    val maxHeightPx: Int = 720
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
        maxHeightPx = prefs.getInt("max_height_px", 720)
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
    SEARCH("Search", Icons.Default.Search),
    SETTINGS("Settings", Icons.Default.Settings)
}

@Composable
fun UnicornTvApp() {
    val context = LocalContext.current
    var config by remember { mutableStateOf(loadConfig(context)) }
    var selectedSection by remember { mutableStateOf(TvSection.SETUP) }
    var channels by remember { mutableStateOf(buildChannelsFromConfig(config)) }
    var statusMessage by remember { mutableStateOf("") }

    val nebulaBg = Brush.horizontalGradient(
        colors = listOf(Color(0xFF14072B), Color(0xFF280D54), Color(0xFF1A0B38))
    )

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(nebulaBg)
            .padding(16.dp)
    ) {
        // Left D-Pad Friendly Rail
        Column(
            modifier = Modifier
                .width(76.dp)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
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
                        .size(50.dp)
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
private fun SetupAndPlaylistPane(
    config: IptvConfig,
    channels: List<ChannelItem>,
    statusMessage: String,
    onSave: (IptvConfig) -> Unit
) {
    var m3uUrl by remember(config) { mutableStateOf(config.m3uUrl) }
    var xtreamHost by remember(config) { mutableStateOf(config.xtreamHost) }
    var xtreamUser by remember(config) { mutableStateOf(config.xtreamUser) }
    var xtreamPass by remember(config) { mutableStateOf(config.xtreamPass) }
    var epgUrl by remember(config) { mutableStateOf(config.epgUrl) }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            OutlinedTextField(
                value = m3uUrl,
                onValueChange = { m3uUrl = it },
                label = { Text("M3U / M3U8 Playlist URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = xtreamHost,
                    onValueChange = { xtreamHost = it },
                    label = { Text("Xtream Server URL") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = xtreamUser,
                    onValueChange = { xtreamUser = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = xtreamPass,
                    onValueChange = { xtreamPass = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            OutlinedTextField(
                value = epgUrl,
                onValueChange = { epgUrl = it },
                label = { Text("EPG (XMLTV) URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
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
        }
        item {
            Text(
                text = "Configured Streams (${channels.size}):",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        items(channels) { ch ->
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
