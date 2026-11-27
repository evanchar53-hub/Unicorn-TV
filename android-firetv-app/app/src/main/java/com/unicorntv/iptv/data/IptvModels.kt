package com.unicorntv.iptv.data

data class Channel(
    val id: String,
    val number: Int,
    val name: String,
    val category: String,
    val streamUrl: String,
    val tvgId: String,
    val resolutionBadge: String = "4K60 HDR",
    val isFavorite: Boolean = false,
    val hasCatchUp: Boolean = true
)

data class EpgProgram(
    val channelTvgId: String,
    val title: String,
    val description: String,
    val startTimeMinutes: Int,
    val durationMinutes: Int,
    val genre: String,
    val isLive: Boolean = false
)

enum class MultiViewLayout(val title: String, val slotCount: Int) {
    ONE_LARGE_THREE_SMALL("1 Large + 3 Small", 4),
    TWO_LARGE_TWO_SMALL("2 Large + 2 Small", 4),
    THREE_SMALL("3 Small", 3),
    GRID_2X2("2x2 Equal Grid", 4)
}

enum class NavSection(val label: String) {
    LIVE_TV("Live TV"),
    TV_GUIDE("TV Guide (EPG)"),
    FAVORITES("Favorites"),
    MULTI_VIEW("Multi-View"),
    MOVIES("Movies (4K)"),
    SERIES("Series"),
    SPEED_OPTIMIZER("Speed Optimizer"),
    SETUP_WIZARD("Live TV & EPG Setup")
}
