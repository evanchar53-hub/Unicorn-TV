package com.unicorntv.iptv

import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unicorntv.iptv.data.Channel
import com.unicorntv.iptv.data.NavSection
import com.unicorntv.iptv.ui.components.SideNavRail
import com.unicorntv.iptv.ui.screens.IntroSplashScreen
import com.unicorntv.iptv.ui.screens.MultiViewScreen
import com.unicorntv.iptv.ui.theme.*

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sampleChannels = listOf(
            Channel("1", 101, "Nebula Sports 4K", "Sports", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", "nebula.sports.4k"),
            Channel("2", 102, "Cosmic Cinema HDR", "Movies", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", "cosmic.cinema.hdr"),
            Channel("3", 103, "Starlight News 24/7", "News", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", "starlight.news"),
            Channel("4", 104, "Aether Sci-Fi UHD", "Sci-Fi", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", "aether.scifi")
        )

        setContent {
            UnicornTvTheme {
                var showIntro by remember { mutableStateOf(true) }
                var activeSection by remember { mutableStateOf(NavSection.LIVE_TV) }

                if (showIntro) {
                    IntroSplashScreen(onFinished = { showIntro = false })
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(NebulaBackgroundBrush)
                    ) {
                        SideNavRail(
                            activeSection = activeSection,
                            onSelectSection = { activeSection = it }
                        )

                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            when (activeSection) {
                                NavSection.MULTI_VIEW -> MultiViewScreen(
                                    channels = sampleChannels,
                                    onExpandFullScreen = { activeSection = NavSection.LIVE_TV }
                                )
                                else -> Column(modifier = Modifier.padding(24.dp)) {
                                    Text(
                                        text = "Unicorn TV — ${activeSection.label}",
                                        color = StarlightWhite,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Ready for 720p–4K60 HDR streams, XMLTV EPG guide, PiP, and Multi-View.",
                                        color = ElectricCyan,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }
}
