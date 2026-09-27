package com.ridetrack.app.ui.common

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
                Text(
                    listOfNotNull(Format.rideDate(ride.startTimeMillis), bikeName.takeIf { !compact }).joinToString(" · "),
                    style = RtType.caption,
                    color = RtColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
            }
            Spacer(Modifier.width(14.dp))
            Box(
                Modifier
                    .size(if (compact) 72.dp else 92.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(RtColors.Background)
                    .border(1.dp, RtColors.Hairline, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (route == null) {
                    Shimmer(Modifier.fillMaxSize(), radius = 16.dp)
                } else {
                    RouteThumbnail(
                        route,
                        Modifier.fillMaxSize().padding(8.dp),
                        color = if (ride.source == DataSourceKind.DEMO) RtColors.Warning else RtColors.Primary,
                    )
                }
            }
        }
    }
}
