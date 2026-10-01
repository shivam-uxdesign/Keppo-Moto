package com.ridetrack.app.ui.components

import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.app.ui.theme.pressScale
import com.ridetrack.app.ui.theme.rememberHaptics

/** Quiet surface with a hairline edge. Clickable cards scale slightly while pressed. */
@Composable
fun RtCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(RtDimens.cardPadding),
    color: Color = RtColors.Surface,
    radius: Dp = RtDimens.cardRadius,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    val interaction = remember { MutableInteractionSource() }
    var m = modifier.fillMaxWidth()
    if (onClick != null) m = m.pressScale(interaction)
    m = m
        .clip(shape)
        .background(color)
        .border(1.dp, RtColors.Hairline, shape)
    if (onClick != null) m = m.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
    Column(m.padding(contentPadding), content = content)
}

/**
 * Pill button. Primary actions are cyan; pass [color]/[contentColor] for the inverse
 * (white) or danger variants.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    large: Boolean = false,
    icon: ImageVector? = null,
    color: Color = RtColors.Primary,
    contentColor: Color = RtColors.OnPrimary,
    haptic: Boolean = false,
) {
    PillButton(
        text = text, onClick = onClick, modifier = modifier, enabled = enabled,
        height = if (large) RtDimens.primaryButtonHeight else RtDimens.buttonHeight,
        icon = icon, background = color, content = contentColor, border = null, haptic = haptic,
    )
}

/** Quiet pill on a dark surface with a hairline edge. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    contentColor: Color = RtColors.TextPrimary,
) {
    PillButton(
        text = text, onClick = onClick, modifier = modifier, enabled = enabled,
        height = RtDimens.buttonHeight, icon = icon, background = RtColors.Surface,
        content = contentColor, border = RtColors.Hairline, haptic = false,
    )
}

@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    height: Dp,
    icon: ImageVector?,
    background: Color,
    content: Color,
    border: Color?,
    haptic: Boolean,
) {
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .pressScale(interaction)
            .clip(shape)
            .background(if (enabled) background else RtColors.SurfaceRaised)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button) {
                if (haptic) haptics.confirm()
                onClick()
            }
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val c = if (enabled) content else RtColors.TextTertiary
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = c, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = RtType.button, color = c, maxLines = 1)
    }
}

/** Small, muted, uppercase label. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = RtColors.TextSecondary, textAlign: TextAlign? = null) {
    Text(text.uppercase(), modifier = modifier, style = RtType.label, color = color, textAlign = textAlign, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = RtDimens.lg, bottom = RtDimens.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = RtType.label,
            color = RtColors.TextSecondary,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        trailing()
    }
}

@Composable
fun HairlineDivider(modifier: Modifier = Modifier) = HorizontalDivider(modifier, thickness = 1.dp, color = RtColors.Hairline)

/** A value with an optional unit, e.g. "24.6 km". */
@Composable
fun MetricValue(
    value: String,
    unit: String? = null,
    style: TextStyle = RtType.metricM,
    color: Color = RtColors.TextPrimary,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        Text(value, style = style, color = color, maxLines = 1)
        if (unit != null && value != Format.DASH) {
            Spacer(Modifier.width(4.dp))
            Text(unit, style = RtType.unit, color = RtColors.TextSecondary, modifier = Modifier.padding(bottom = 3.dp), maxLines = 1)
        }
    }
}

/** Label above a value. */
@Composable
fun StatBlock(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    style: TextStyle = RtType.metricM,
    color: Color = RtColors.TextPrimary,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "$label $value${unit?.let { " $it" } ?: ""}"
        },
        horizontalAlignment = horizontalAlignment,
    ) {
        Label(label)
        Spacer(Modifier.height(6.dp))
        MetricValue(value, unit, style, color)
    }
}

/** Stat in its own card; used in 2-column grids. */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, unit: String? = null, color: Color = RtColors.TextPrimary) {
    RtCard(modifier = modifier, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        StatBlock(label, value, unit = unit, color = color)
    }
}

/** [color] Unspecified = primary text. */
data class Stat(val label: String, val value: String, val unit: String? = null, val color: Color = Color.Unspecified)

/** Up to four stats in a row, separated by hairlines — no card chrome. */
@Composable
fun StatRow(stats: List<Stat>, modifier: Modifier = Modifier, style: TextStyle = RtType.metricM) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        stats.forEachIndexed { i, s ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(RtColors.Hairline))
            StatBlock(
                s.label, s.value,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (i == 0) 0.dp else 14.dp, end = 8.dp),
                unit = s.unit, style = style, color = s.color.takeOrElse { RtColors.TextPrimary },
            )
        }
    }
}

@Composable
fun TwoColumn(modifier: Modifier = Modifier, left: @Composable (Modifier) -> Unit, right: @Composable (Modifier) -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RtDimens.cardSpacing)) {
        left(Modifier.weight(1f))
        right(Modifier.weight(1f))
    }
}

enum class StatusLevel {
    OK, WARNING, ERROR, INACTIVE;

    val color: Color
        @Composable @ReadOnlyComposable get() = when (this) {
            OK -> RtColors.Ok
            WARNING -> RtColors.Warning
            ERROR -> RtColors.Error
            INACTIVE -> RtColors.TextTertiary
        }
}

/** Dot + text; state is always spelled out, never colour-only. */
@Composable
fun StatusIndicator(label: String, status: String, level: StatusLevel, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "$label: $status" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(level.color, CircleShape))
        Spacer(Modifier.width(7.dp))
        Text(if (label.isBlank()) status else "$label $status", style = RtType.caption, color = RtColors.TextSecondary)
    }
}

@Composable
fun Chip(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier = modifier
            .background(RtColors.Surface, RoundedCornerShape(50))
            .border(1.dp, RtColors.Hairline, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        } else {
            Box(Modifier.size(7.dp).background(color, CircleShape))
        }
        Spacer(Modifier.width(7.dp))
        Text(text, style = RtType.caption, color = RtColors.TextPrimary, maxLines = 1)
    }
}

@Composable
fun DemoBadge(modifier: Modifier = Modifier) {
    Chip("Demo mode", RtColors.Warning, modifier)
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = RtDimens.xl, horizontal = RtDimens.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Box(Modifier.size(56.dp).background(RtColors.Surface, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = RtColors.TextSecondary, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(RtDimens.md))
        }
        Text(title, style = RtType.headline, color = RtColors.TextPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(RtDimens.xs))
        Text(message, style = RtType.body, color = RtColors.TextSecondary, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(RtDimens.lg))
            action()
        }
    }
}

/** Screen title row with an optional back button and trailing actions. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = RtDimens.sm, bottom = RtDimens.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.padding(end = 4.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = RtColors.TextPrimary)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = if (onBack != null) RtType.headline else RtType.title,
                color = RtColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Text(subtitle, style = RtType.caption, color = RtColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions()
    }
}

@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = RtColors.TextPrimary) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 13.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = RtType.body, color = RtColors.TextSecondary, modifier = Modifier.weight(1f))
        Text(value, style = RtType.body.copy(fontFeatureSettings = "tnum"), color = valueColor, textAlign = TextAlign.End)
    }
}

/** Retained for callers that want a thin border without a card. */
val HairlineBorder: BorderStroke
    @Composable @ReadOnlyComposable get() = BorderStroke(1.dp, RtColors.Hairline)
