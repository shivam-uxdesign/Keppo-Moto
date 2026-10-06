package com.ridetrack.app.ui.common

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import java.io.File
import com.ridetrack.app.ui.moments.Thumb
import com.ridetrack.app.moments.RideMoments
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.ridetrack.app.ui.components.GeoPoint
import com.ridetrack.app.ui.components.RouteThumbnail
import com.ridetrack.app.ui.components.Shimmer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ridetrack.app.ui.components.DemoBadge
import com.ridetrack.app.ui.components.MetricValue
import com.ridetrack.app.ui.components.RtCard
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.Ride

/** Ride history card: name, date, distance, duration, avg/max speed, and a route sketch. */
@Composable
fun RideRow(
    ride: Ride,
    bikeName: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** Simplified route for the thumbnail; null while loading. */
    route: List<GeoPoint>? = null,
    /** The route on a dark map, once made; the plain route sketch until then. */
    map: File? = null,
    moments: RideMoments? = null,
    /** The bike's route colour: a dot by its name, and the route sketch's colour. */
    bikeColor: Color? = null,
) {
    RtCard(modifier = modifier, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ride.name,
                        style = RtType.bodyStrong,
                        color = RtColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (ride.source == DataSourceKind.DEMO) {
                        Spacer(Modifier.width(8.dp))
                        DemoBadge()
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val bike = bikeName.takeIf { !compact }
                    Text(
                        Format.rideDate(ride.startTimeMillis) + if (bike != null) " · " else "",
                        style = RtType.caption,
                        color = RtColors.TextSecondary,
                        maxLines = 1,
                    )
                    if (bike != null) {
                        if (bikeColor != null) {
                            Box(Modifier.size(7.dp).background(bikeColor, CircleShape))
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(bike, style = RtType.caption, color = RtColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(if (compact) 10.dp else 14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Bottom) {
                    MetricValue(Format.distanceValue(ride.stats.distanceM), "km", RtType.metricM)
                    MetricValue(Format.duration(ride.durationMillis), null, RtType.metricM)
                }
                if (!compact) {
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Text("Avg ${Format.speedWithUnit(ride.stats.avgSpeedMps)}", style = RtType.caption, color = RtColors.TextSecondary)
                        Spacer(Modifier.width(16.dp))
                        Text("Max ${Format.speedWithUnit(ride.stats.maxSpeedMps)}", style = RtType.caption, color = RtColors.TextSecondary)
                    }
                }
                momentsLine(moments?.count ?: 0)?.let {
                    Spacer(Modifier.height(if (compact) 6.dp else 8.dp))
                    Text(it, style = RtType.caption, color = RtColors.Primary, maxLines = 1)
                }
            }
            Spacer(Modifier.width(14.dp))
            CardStack(
                size = if (compact) 72.dp else 92.dp,
                behind = moments?.thumbs.orEmpty(),
            ) {
                when {
                    map != null -> Thumb(map, Modifier.fillMaxSize(), maxEdge = 320)
                    route == null -> Shimmer(Modifier.fillMaxSize(), radius = 16.dp)
                    else -> RouteThumbnail(
                        route,
                        Modifier.fillMaxSize().padding(8.dp),
                        color = if (ride.source == DataSourceKind.DEMO) RtColors.Warning else bikeColor ?: RtColors.Primary,
                    )
                }
            }
        }
    }
}

/** "1 moment captured", "3 moments captured"; nothing for none. Pure, so it is unit-tested. */
fun momentsLine(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "1 moment captured"
    else -> "$count moments captured"
}

/** The route picture in front, with up to two moment pictures peeking out behind it, tilted. */
@Composable
private fun CardStack(size: Dp, behind: List<File>, front: @Composable () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    // Room for the peeking cards, so they stay inside the ride card.
    Box(Modifier.padding(start = if (behind.isEmpty()) 0.dp else 12.dp, top = if (behind.isEmpty()) 0.dp else 6.dp)) {
        behind.take(2).asReversed().forEachIndexed { i, file ->
            val far = behind.size == 2 && i == 0
            Box(
                Modifier
                    .size(size)
                    .offset(x = if (far) (-4).dp else (-10).dp, y = if (far) (-6).dp else (-2).dp)
                    .rotate(if (far) 9f else -8f)
                    .clip(shape)
                    .background(RtColors.SurfaceRaised)
                    .border(1.dp, RtColors.Hairline, shape),
            ) { Thumb(file, Modifier.fillMaxSize()) }
        }
        Box(
            Modifier
                .size(size)
                .clip(shape)
                .background(RtColors.Background)
                .border(1.dp, RtColors.Hairline, shape),
            contentAlignment = Alignment.Center,
        ) { front() }
    }
}
