package com.ridetrack.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.components.RtCard
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.home.BikeCare
import com.ridetrack.app.ui.nav.LocalNavigate
import com.ridetrack.app.ui.nav.Routes
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/** The rider's achievements, worked out from what's already in the app; null while loading. */
@Composable
fun rememberAchievements(): List<Achievement>? {
    val c = appContainer()
    var list by remember { mutableStateOf<List<Achievement>?>(null) }
    LaunchedEffect(Unit) {
        list = withContext(Dispatchers.IO) {
            val settings = c.settings.settings.first()
            val reels = c.reels.reels.value.filter { it.deletedAt == null }
            Achievements.of(
                AchievementInputs(
                    rides = c.rides.observeCompleted().first(),
                    reelTimes = reels.map { it.createdAt },
                    seriesEpisodes = reels.count { it.seriesId != null },
                    fillTimes = com.ridetrack.app.fuel.Fuel.decodeFills(settings.fuelLog).map { it.timeMillis },
                    careReminders = BikeCare.decode(settings.bikeCare).size,
                    crashAlertsReady = settings.safety.crashDetection && settings.safety.contacts.isNotEmpty(),
                    mountCalibrated = c.bikes.observeBikes().first().any { it.calibration != null },
                ),
            )
        }
    }
    return list
}

/** "7 of 22 earned" for Profile's row. */
fun achievementsSummary(list: List<Achievement>?): String =
    list?.let { "${it.count(Achievement::earned)} of ${it.size} earned" } ?: " "

/** Earned first (newest first, with the ride to open), then the ones still to reach, closest first. */
@Composable
fun AchievementsPage() {
    val list = rememberAchievements() ?: return
    val nav = LocalNavigate.current
    val earned = list.filter { it.earned }.sortedByDescending { it.earnedAt ?: 0L }
    val toGo = list.filter { !it.earned }.sortedByDescending { it.fraction }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            if (earned.isEmpty()) "Nothing earned yet. Your first ride starts it." else "${earned.size} of ${list.size} earned",
            style = RtType.body, color = RtColors.TextSecondary,
        )
        if (earned.isNotEmpty()) RtCard {
            Text("EARNED", style = RtType.label, color = RtColors.TextTertiary)
            earned.forEach { a ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .then(if (a.rideId != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open the ride") { nav(Routes.detail(a.rideId)) } else Modifier)
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Badge(true)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.title, style = RtType.bodyStrong, color = RtColors.TextPrimary)
                        Text(
                            listOfNotNull(a.earnedAt?.let { Format.rideDate(it) }, a.group, if (a.rideId != null) "Open the ride" else null).joinToString(" · "),
                            style = RtType.caption, color = RtColors.TextSecondary,
                        )
                    }
                }
            }
        }
        if (toGo.isNotEmpty()) RtCard {
            Text("STILL TO REACH", style = RtType.label, color = RtColors.TextTertiary)
            toGo.forEach { a ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Badge(false)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(a.title, style = RtType.bodyStrong, color = RtColors.TextPrimary)
                        Text(a.detail, style = RtType.caption, color = RtColors.TextSecondary)
                        if (a.target > 1) {
                            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(RtColors.Outline.copy(alpha = 0.5f))) {
                                Box(Modifier.fillMaxWidth(a.fraction).height(6.dp).clip(RoundedCornerShape(3.dp)).background(RtColors.Primary))
                            }
                            Text("${amount(a.progress)} of ${amount(a.target)} ${a.unit}".trim(), style = RtType.caption, color = RtColors.TextTertiary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Badge(earned: Boolean) {
    Box(
        Modifier.size(36.dp).background(if (earned) RtColors.Primary.copy(alpha = 0.16f) else RtColors.Outline.copy(alpha = 0.4f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (earned) Icons.Rounded.EmojiEvents else Icons.Rounded.Lock,
            contentDescription = if (earned) "Earned" else "Not yet",
            tint = if (earned) RtColors.Primary else RtColors.TextTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}

private fun amount(v: Double): String = if (v >= 100 || v % 1.0 == 0.0) String.format(Locale.US, "%,d", v.roundToInt()) else String.format(Locale.US, "%.1f", v)
