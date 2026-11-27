package com.unicorntv.iptv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

enum class ResolutionPreset(val label: String, val maxWidth: Int, val maxHeight: Int) {
    AUTO_ABR("Auto (AI ABR 720p-4K)", Int.MAX_VALUE, Int.MAX_VALUE),
    UHD_4K("4K UHD (2160p60 HDR)", 3840, 2160),
    FHD_1080P("1080p Full HD", 1920, 1080),
    HD_720P("720p Data Saver", 1280, 720)
}

object StreamNetworkOptimizer {

    // Shared HTTP/2 connection pool to maximize IPTV CDN throughput
    private val sharedOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @OptIn(UnstableApi::class)
    fun buildOptimizedPlayer(
        context: Context,
        preset: ResolutionPreset = ResolutionPreset.AUTO_ABR,
        enableTunneling: Boolean = true,
        isMultiViewTile: Boolean = false
    ): ExoPlayer {
        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setMaxVideoSize(preset.maxWidth, preset.maxHeight)
                .setForceHighestSupportedBitrate(preset == ResolutionPreset.UHD_4K)
                .setTunnelingEnabled(enableTunneling && !isMultiViewTile)
                .build()
        }

        // Larger buffer for primary 4K stream; lean buffer for Multi-View secondary tiles
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (isMultiViewTile) 4_000 else 15_000,
                if (isMultiViewTile) 12_000 else 50_000,
                1_500,
                3_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val dataSourceFactory = OkHttpDataSource.Factory(sharedOkHttpClient)
            .setUserAgent("UnicornTV-Android-FireTV/2.0")

        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        return ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }
}
