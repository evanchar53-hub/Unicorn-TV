package com.unicorntv.iptv.ui.screens

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.ui.PlayerView
import com.unicorntv.iptv.data.Channel
import com.unicorntv.iptv.data.MultiViewLayout
import com.unicorntv.iptv.player.ResolutionPreset
import com.unicorntv.iptv.player.StreamNetworkOptimizer
import com.unicorntv.iptv.ui.theme.*

@Composable
fun MultiViewScreen(
    channels: List<Channel>,
    onExpandFullScreen: (Channel) -> Unit
) {
    var layout by remember { mutableStateOf(MultiViewLayout.ONE_LARGE_THREE_SMALL) }
    var activeAudioIndex by remember { mutableIntStateOf(0) }
    var slotChannels by remember {
        mutableStateOf(
            listOf(
                channels.getOrNull(0),
                channels.getOrNull(1),
                channels.getOrNull(2),
                channels.getOrNull(3)
            ).filterNotNull()
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Layout Switcher Bar: 1 Large + 3 Small | 2 Large + 2 Small | 3 Small | 2x2 Grid
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Text(
                text = "MULTI-VIEW DECODER",
                color = ElectricCyan,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Spacer(modifier = Modifier.weight(1f))
            MultiViewLayout.entries.forEach { option ->
                val selected = layout == option
                FilterChip(
                    selected = selected,
                    onClick = { layout = option },
                    label = { Text(option.title, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CosmicMagenta,
                        selectedLabelColor = StarlightWhite
                    )
                )
            }
        }

        when (layout) {
            MultiViewLayout.ONE_LARGE_THREE_SMALL -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(modifier = Modifier.weight(0.68f).fillMaxHeight()) {
                        slotChannels.getOrNull(0)?.let { ch ->
                            MultiViewTile(
                                channel = ch,
                                isPrimary = true,
                                isAudioActive = activeAudioIndex == 0,
                                onSelectAudio = { activeAudioIndex = 0 },
                                onPromote = {},
                                onExpand = { onExpandFullScreen(ch) }
                            )
                        }
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(0.32f).fillMaxHeight()
                    ) {
                        for (idx in 1..3) {
                            slotChannels.getOrNull(idx)?.let { ch ->
                                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                    MultiViewTile(
                                        channel = ch,
                                        isPrimary = false,
                                        isAudioActive = activeAudioIndex == idx,
                                        onSelectAudio = { activeAudioIndex = idx },
                                        onPromote = {
                                            val updated = slotChannels.toMutableList()
                                            val temp = updated[0]
                                            updated[0] = updated[idx]
                                            updated[idx] = temp
                                            slotChannels = updated
                                            activeAudioIndex = 0
                                        },
                                        onExpand = { onExpandFullScreen(ch) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            MultiViewLayout.TWO_LARGE_TWO_SMALL -> {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(0.62f).fillMaxWidth()
                    ) {
                        for (idx in 0..1) {
                            slotChannels.getOrNull(idx)?.let { ch ->
                                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    MultiViewTile(
                                        channel = ch,
                                        isPrimary = true,
                                        isAudioActive = activeAudioIndex == idx,
                                        onSelectAudio = { activeAudioIndex = idx },
                                        onPromote = {},
                                        onExpand = { onExpandFullScreen(ch) }
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(0.38f).fillMaxWidth()
                    ) {
                        for (idx in 2..3) {
                            slotChannels.getOrNull(idx)?.let { ch ->
                                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    MultiViewTile(
                                        channel = ch,
                                        isPrimary = false,
                                        isAudioActive = activeAudioIndex == idx,
                                        onSelectAudio = { activeAudioIndex = idx },
                                        onPromote = {
                                            val updated = slotChannels.toMutableList()
                                            val temp = updated[0]
                                            updated[0] = updated[idx]
                                            updated[idx] = temp
                                            slotChannels = updated
                                            activeAudioIndex = 0
                                        },
                                        onExpand = { onExpandFullScreen(ch) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            MultiViewLayout.THREE_SMALL -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    for (idx in 0..2) {
                        slotChannels.getOrNull(idx)?.let { ch ->
                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                MultiViewTile(
                                    channel = ch,
                                    isPrimary = false,
                                    isAudioActive = activeAudioIndex == idx,
                                    onSelectAudio = { activeAudioIndex = idx },
                                    onPromote = {},
                                    onExpand = { onExpandFullScreen(ch) }
                                )
                            }
                        }
                    }
                }
            }

            MultiViewLayout.GRID_2X2 -> {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    ) {
                        for (idx in 0..1) {
                            slotChannels.getOrNull(idx)?.let { ch ->
                                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    MultiViewTile(
                                        channel = ch,
                                        isPrimary = true,
                                        isAudioActive = activeAudioIndex == idx,
                                        onSelectAudio = { activeAudioIndex = idx },
                                        onPromote = {},
                                        onExpand = { onExpandFullScreen(ch) }
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    ) {
                        for (idx in 2..3) {
                            slotChannels.getOrNull(idx)?.let { ch ->
                                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    MultiViewTile(
                                        channel = ch,
                                        isPrimary = true,
                                        isAudioActive = activeAudioIndex == idx,
                                        onSelectAudio = { activeAudioIndex = idx },
                                        onPromote = {},
                                        onExpand = { onExpandFullScreen(ch) }
                                    )
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
private fun MultiViewTile(
    channel: Channel,
    isPrimary: Boolean,
    isAudioActive: Boolean,
    onSelectAudio: () -> Unit,
    onPromote: () -> Unit,
    onExpand: () -> Unit
) {
    val context = LocalContext.current
    val player = remember(channel.streamUrl, isPrimary) {
        StreamNetworkOptimizer.buildOptimizedPlayer(
            context = context,
            preset = if (isPrimary) ResolutionPreset.AUTO_ABR else ResolutionPreset.HD_720P,
            enableTunneling = isPrimary,
            isMultiViewTile = !isPrimary
        ).apply {
            setMediaItem(MediaItem.fromUri(channel.streamUrl))
            prepare()
            playWhenReady = true
        }
    }

    LaunchedEffect(isAudioActive) {
        player.volume = if (isAudioActive) 1f else 0f
    }

    DisposableEffect(channel.streamUrl) {
        onDispose { player.release() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(DeepSpacePurple)
            .border(
                width = if (isAudioActive) 2.dp else 1.dp,
                color = if (isAudioActive) ElectricCyan else RadiantViolet.copy(alpha = 0.5f),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable { onSelectAudio() }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(VoidIndigo.copy(alpha = 0.72f))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = "${channel.number} • ${channel.name}",
                color = StarlightWhite,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.weight(1f))
            if (!isPrimary) {
                TextButton(onClick = onPromote, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("Make Main", color = ElectricCyan, fontSize = 11.sp)
                }
            }
            TextButton(onClick = onExpand, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("Full", color = NeonPink, fontSize = 11.sp)
            }
        }
    }
}
