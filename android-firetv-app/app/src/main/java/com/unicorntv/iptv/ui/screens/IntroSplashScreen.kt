package com.unicorntv.iptv.ui.screens

import android.net.Uri
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.unicorntv.iptv.ui.theme.ElectricCyan
import com.unicorntv.iptv.ui.theme.StarlightWhite
import com.unicorntv.iptv.ui.theme.VoidIndigo

@Composable
fun IntroSplashScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val introUri = remember {
        // Place the generated intro video at app/src/main/res/raw/unicorn_intro.mp4
        Uri.parse("android.resource://${context.packageName}/raw/unicorn_intro")
    }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(introUri))
            prepare()
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        onFinished()
                    }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    // Gracefully continue if intro raw asset hasn't been bundled yet
                    onFinished()
                }
            })
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VoidIndigo)
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

        OutlinedButton(
            onClick = onFinished,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(32.dp)
        ) {
            Text(
                text = "Skip Intro",
                color = ElectricCyan,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
