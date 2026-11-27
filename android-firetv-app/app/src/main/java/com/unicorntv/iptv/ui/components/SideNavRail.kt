package com.unicorntv.iptv.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unicorntv.iptv.data.NavSection
import com.unicorntv.iptv.ui.theme.*

@Composable
fun SideNavRail(
    activeSection: NavSection,
    onSelectSection: (NavSection) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val items: List<Pair<NavSection, ImageVector>> = listOf(
        NavSection.LIVE_TV to Icons.Default.LiveTv,
        NavSection.TV_GUIDE to Icons.Default.CalendarViewWeek,
        NavSection.FAVORITES to Icons.Default.Star,
        NavSection.MULTI_VIEW to Icons.Default.GridView,
        NavSection.MOVIES to Icons.Default.Movie,
        NavSection.SERIES to Icons.Default.Tv,
        NavSection.SPEED_OPTIMIZER to Icons.Default.Speed,
        NavSection.SETUP_WIZARD to Icons.Default.SettingsInputAntenna
    )

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(if (expanded) 230.dp else 76.dp)
            .animateContentSize()
            .background(VoidIndigo.copy(alpha = 0.94f))
            .border(1.dp, RadiantViolet.copy(alpha = 0.35f))
            .padding(vertical = 18.dp, horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = if (expanded) "UNICORN TV" else "UTV",
            color = ElectricCyan,
            fontWeight = FontWeight.ExtraBold,
            fontSize = if (expanded) 18.sp else 14.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        )

        items.forEach { (section, icon) ->
            var isFocused by remember { mutableStateOf(false) }
            val isSelected = activeSection == section

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        when {
                            isSelected -> CosmicMagenta.copy(alpha = 0.28f)
                            isFocused -> ElectricCyan.copy(alpha = 0.18f)
                            else -> Color.Transparent
                        }
                    )
                    .border(
                        width = if (isFocused || isSelected) 1.dp else 0.dp,
                        color = if (isFocused) ElectricCyan else CosmicMagenta,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .onFocusChanged { focusState ->
                        isFocused = focusState.isFocused
                        if (focusState.isFocused) expanded = true
                    }
                    .clickable {
                        expanded = !expanded
                        onSelectSection(section)
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = section.label,
                    tint = if (isSelected || isFocused) ElectricCyan else StarlightWhite.copy(alpha = 0.78f),
                    modifier = Modifier.size(22.dp)
                )
                if (expanded) {
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = section.label,
                        color = StarlightWhite,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}
