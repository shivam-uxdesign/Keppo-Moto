package com.ridetrack.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** One theme's colours. Values come from the Keppo Moto design system (dark first; light passes WCAG AA). */
@Immutable
data class RtPalette(
    val isDark: Boolean,
    val background: Color,
    val liveBackground: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val outline: Color,
    val hairline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val inverse: Color,
    val onInverse: Color,
    val left: Color,
    val right: Color,
    val accel: Color,
    val brake: Color,
    val gForce: Color,
    val paused: Color,
    val warning: Color,
    val error: Color,
    val ok: Color,
    val recording: Color,
)

val DarkPalette = RtPalette(
    isDark = true,
    background = Color(0xFF0A0A0B),
    liveBackground = Color(0xFF000000),
    surface = Color(0xFF141416),
    surfaceRaised = Color(0xFF1C1C1F),
    outline = Color(0xFF2A2A2E),
    hairline = Color(0x14FFFFFF),
    textPrimary = Color(0xFFF4F4F5),
    textSecondary = Color(0xFF8B8B93),
    textTertiary = Color(0xFF85858E),
    primary = Color(0xFF69C8CB),
    onPrimary = Color(0xFF04292A),
    primaryContainer = Color(0xFF1E3E40),
    inverse = Color(0xFFF4F4F5),
    onInverse = Color(0xFF0A0A0B),
    left = Color(0xFFA5A1FF),
    right = Color(0xFFFB7185),
    accel = Color(0xFF4ADE80),
    brake = Color(0xFFFB7185),
    gForce = Color(0xFFFBBF24),
    paused = Color(0xFFA5A1FF),
    warning = Color(0xFFFBBF24),
    error = Color(0xFFF43F5E),
    ok = Color(0xFF4ADE80),
    recording = Color(0xFFFF5A5A),
)

val LightPalette = RtPalette(
    isDark = false,
    background = Color(0xFFF7F7F5),
    liveBackground = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFEFEFEC),
    outline = Color(0xFFDCDCD8),
    hairline = Color(0x0F000000),
    textPrimary = Color(0xFF0A0A0B),
    textSecondary = Color(0xFF55555C),
    textTertiary = Color(0xFF6B6B73),
    primary = Color(0xFF0B7377),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDEEEF),
    inverse = Color(0xFF0A0A0B),
    onInverse = Color(0xFFF4F4F5),
    left = Color(0xFF5246D6),
    right = Color(0xFFC2183F),
    accel = Color(0xFF117033),
    brake = Color(0xFFC2183F),
    gForce = Color(0xFF8F5300),
    paused = Color(0xFF5246D6),
    warning = Color(0xFF8F5300),
    error = Color(0xFFC8102E),
    ok = Color(0xFF117033),
    recording = Color(0xFFC8102E),
)

val LocalRtPalette = staticCompositionLocalOf { DarkPalette }

/**
 * Premium Minimal palette: near-black (or warm paper) canvas, one teal accent, hairlines instead of
 * heavy cards. Semantic colours only where they carry meaning (lean side, braking, G).
 * Read inside composition; follows the app theme, or dark on riding screens ([RideDark]).
 */
object RtColors {
    val Background: Color @Composable @ReadOnlyComposable get() = LocalRtPalette.current.background
    /** Live ride screen: pure black for maximum contrast and OLED power saving. */
    val LiveBackground: Color @Composable @ReadOnlyComposable get() = LocalRtPalette.current.liveBackground
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalRtPalette.current.surface
    val SurfaceRaised: Color @Composable @ReadOnlyComposable get() = LocalRtPalette.current.surfaceRaised
    /** Tracks, chart grids, inactive controls. */
    val Outline: Color @Composable @ReadOnlyComposable get() = LocalRtPalette.current.outline
    /** 1dp dividers and card edges. */
    val Hairline: Color @Composable @ReadOnlyComposable get() = LocalRtPalette.current.hairline
    val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalRtPalette.current.textPrimary
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalRtPalette.current.textSecondary
    /** Timestamps and hints; still 4.5:1 on every surface. */
    val TextTertiary: Color @Composable @ReadOnlyComposable get() = LocalRtPalette.current.textTertiary
    /** Keppo Teal. Deep teal in light so it passes AA as text. */
    val Primary: Color @Composable @ReadOnlyComposable get() = LocalRtPalette.current.primary
    val OnPrimary: Color @Composable @ReadOnlyComposable get() = LocalRtPalette.current.onPrimary
    val PrimaryContainer: Color @Composable @ReadOnlyComposable get() = LocalRtPalette.current.primaryContainer
    /** High-emphasis action (e.g. Start ride). */
    val Inverse: Color @Composable @ReadOnlyComposable get() = LocalRtPalette.current.inverse
    val OnInverse: Color @Composable @ReadOnlyComposable get() = LocalRtPalette.current.onInverse
    /** Left lean / left-side telemetry. */
    val Left: Color @Composable @ReadOnlyComposable get() = LocalRtPalette.current.left
    /** Right lean / right-side telemetry. */
    val Right: Color @Composable @ReadOnlyComposable get() = LocalRtPalette.current.right
    val Accel: Color @Composable @ReadOnlyComposable get() = LocalRtPalette.current.accel
    val Brake: Color @Composable @ReadOnlyComposable get() = LocalRtPalette.current.brake
    val GForce: Color @Composable @ReadOnlyComposable get() = LocalRtPalette.current.gForce
    val Paused: Color @Composable @ReadOnlyComposable get() = LocalRtPalette.current.paused
    val Warning: Color @Composable @ReadOnlyComposable get() = LocalRtPalette.current.warning
    val Error: Color @Composable @ReadOnlyComposable get() = LocalRtPalette.current.error
    val Ok: Color @Composable @ReadOnlyComposable get() = LocalRtPalette.current.ok
    /** The REC dot and viewfinder edge. */
    val Recording: Color @Composable @ReadOnlyComposable get() = LocalRtPalette.current.recording
}

object RtDimens {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp

    val screenPadding = 16.dp
    val cardPadding = 20.dp
    val cardRadius = 24.dp
    val heroRadius = 28.dp
    val cardSpacing = 12.dp
    val buttonHeight = 56.dp
    val primaryButtonHeight = 64.dp
    val screenPaddingWide = 20.dp
    val iconSize = 20.dp
    val minTouch = 48.dp
}

private fun RtPalette.scheme() = (if (isDark) darkColorScheme() else lightColorScheme()).copy(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = if (isDark) primary else Color(0xFF04292A),
    secondary = left,
    secondaryContainer = primaryContainer,
    onSecondaryContainer = textPrimary,
    inverseSurface = inverse,
    inverseOnSurface = onInverse,
    onSecondary = if (isDark) Color.Black else Color.White,
    background = background,
    onBackground = textPrimary,
    surface = background,
    onSurface = textPrimary,
    surfaceVariant = surface,
    onSurfaceVariant = textSecondary,
    surfaceContainerLowest = background,
    surfaceContainerLow = surface,
    surfaceContainer = surface,
    surfaceContainerHigh = surfaceRaised,
    surfaceContainerHighest = surfaceRaised,
    outline = outline,
    outlineVariant = outline,
    error = error,
    onError = if (isDark) Color.Black else Color.White,
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** App theme: [dark] follows the rider's Appearance setting (system by default). */
@Composable
fun RideTrackTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    RtTheme(if (dark) DarkPalette else LightPalette, content)
}

/** Riding screens (live ride, HUD, viewfinder, crash alert) stay dark in every theme: glare and night vision beat consistency. */
@Composable
fun RideDark(content: @Composable () -> Unit) = RtTheme(DarkPalette, content)

@Composable
private fun RtTheme(palette: RtPalette, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRtPalette provides palette) {
        MaterialTheme(
            colorScheme = palette.scheme(),
            typography = RtType.material,
            shapes = shapes,
            content = content,
        )
    }
}
