package com.ridetrack.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Secondary metrics the rider can choose to show on the live ride screen. */
enum class LiveMetric(val label: String) {
    ACCELERATION("Acceleration"),
    BRAKING("Braking"),
    G_FORCE("G-force"),
    MAX_LEAN("Max lean"),
    MAX_SPEED("Max speed"),
    HEADING("Heading"),
    ALTITUDE("Altitude"),
    ;

    companion object {
        const val MAX_VISIBLE = 4
        val DEFAULT = setOf(G_FORCE, MAX_LEAN)
    }
}

enum class HudLayout(val label: String, val description: String) {
    MINIMAL("Minimal", "Speed and lean."),
    TOURING("Touring", "Speed, distance, duration and average speed."),
    SPORT("Sport", "Speed, lean, G-force and max lean."),
    TELEMETRY("Telemetry", "Speed, lean, acceleration, G-force and heading."),
}

enum class HudSize(val label: String, val scale: Float) { SMALL("Small", 0.85f), MEDIUM("Medium", 1f), LARGE("Large", 1.18f) }

enum class HudTheme(val label: String) { DARK("Dark"), HIGH_CONTRAST("High contrast") }

enum class MapStyle(val label: String) { DARK("Dark"), SATELLITE("Satellite") }

/** Floating pop-up HUD shown over other apps during a ride. */
data class HudSettings(
    val enabled: Boolean = true,
    val layout: HudLayout = HudLayout.MINIMAL,
    val size: HudSize = HudSize.MEDIUM,
    /** Background opacity, 60..100 %. */
    val opacity: Int = 90,
    val theme: HudTheme = HudTheme.DARK,
    /** Last dragged window position in px; null = default (top right). */
    val x: Int? = null,
    val y: Int? = null,
    /** The rider chose "Not now" on the permission explanation. */
    val promptDismissed: Boolean = false,
) {
    companion object {
        const val MIN_OPACITY = 60
        const val MAX_OPACITY = 100
    }
}

data class Settings(
    val autoPause: Boolean = true,
    val demoMode: Boolean = false,
    /** Demo rides also simulate an OBD link (RPM + gear) to exercise the rev meter. */
    val demoObd: Boolean = true,
    val liveMetrics: Set<LiveMetric> = LiveMetric.DEFAULT,
    val showGForceIndicator: Boolean = false,
    val selectedBikeId: String? = null,
    val hud: HudSettings = HudSettings(),
    val mapStyle: MapStyle = MapStyle.DARK,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val autoPause = booleanPreferencesKey("auto_pause")
        val demoMode = booleanPreferencesKey("demo_mode")
        val demoObd = booleanPreferencesKey("demo_obd")
        val liveMetrics = stringSetPreferencesKey("live_metrics")
        val gIndicator = booleanPreferencesKey("g_indicator")
        val selectedBike = stringPreferencesKey("selected_bike")
        val hudEnabled = booleanPreferencesKey("hud_enabled")
        val hudLayout = stringPreferencesKey("hud_layout")
        val hudSize = stringPreferencesKey("hud_size")
        val hudOpacity = intPreferencesKey("hud_opacity")
        val hudTheme = stringPreferencesKey("hud_theme")
        val hudX = intPreferencesKey("hud_x")
        val hudY = intPreferencesKey("hud_y")
        val hudPromptDismissed = booleanPreferencesKey("hud_prompt_dismissed")
        val mapStyle = stringPreferencesKey("map_style")
    }

    private inline fun <reified T : Enum<T>> enumOf(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            autoPause = p[Keys.autoPause] ?: true,
            demoMode = p[Keys.demoMode] ?: false,
            demoObd = p[Keys.demoObd] ?: true,
            liveMetrics = p[Keys.liveMetrics]
                ?.mapNotNull { name -> LiveMetric.entries.firstOrNull { it.name == name } }
                ?.toSet()
                ?: LiveMetric.DEFAULT,
            showGForceIndicator = p[Keys.gIndicator] ?: false,
            selectedBikeId = p[Keys.selectedBike],
            hud = HudSettings(
                enabled = p[Keys.hudEnabled] ?: true,
                layout = enumOf(p[Keys.hudLayout], HudLayout.MINIMAL),
                size = enumOf(p[Keys.hudSize], HudSize.MEDIUM),
                opacity = (p[Keys.hudOpacity] ?: 90).coerceIn(HudSettings.MIN_OPACITY, HudSettings.MAX_OPACITY),
                theme = enumOf(p[Keys.hudTheme], HudTheme.DARK),
                x = p[Keys.hudX],
                y = p[Keys.hudY],
                promptDismissed = p[Keys.hudPromptDismissed] ?: false,
            ),
            mapStyle = enumOf(p[Keys.mapStyle], MapStyle.DARK),
        )
    }

    suspend fun setAutoPause(enabled: Boolean) = context.dataStore.edit { it[Keys.autoPause] = enabled }
    suspend fun setDemoMode(enabled: Boolean) = context.dataStore.edit { it[Keys.demoMode] = enabled }
    suspend fun setDemoObd(enabled: Boolean) = context.dataStore.edit { it[Keys.demoObd] = enabled }
    suspend fun setGForceIndicator(enabled: Boolean) = context.dataStore.edit { it[Keys.gIndicator] = enabled }
    suspend fun setSelectedBike(id: String) = context.dataStore.edit { it[Keys.selectedBike] = id }
    suspend fun setLiveMetrics(metrics: Set<LiveMetric>) =
        context.dataStore.edit { it[Keys.liveMetrics] = metrics.map { m -> m.name }.toSet() }

    suspend fun setHudEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.hudEnabled] = enabled }
    suspend fun setHudLayout(layout: HudLayout) = context.dataStore.edit { it[Keys.hudLayout] = layout.name }
    suspend fun setHudSize(size: HudSize) = context.dataStore.edit { it[Keys.hudSize] = size.name }
    suspend fun setHudOpacity(percent: Int) =
        context.dataStore.edit { it[Keys.hudOpacity] = percent.coerceIn(HudSettings.MIN_OPACITY, HudSettings.MAX_OPACITY) }
    suspend fun setHudTheme(theme: HudTheme) = context.dataStore.edit { it[Keys.hudTheme] = theme.name }
    suspend fun setHudPosition(x: Int, y: Int) = context.dataStore.edit {
        it[Keys.hudX] = x
        it[Keys.hudY] = y
    }
    suspend fun setHudPromptDismissed(dismissed: Boolean) = context.dataStore.edit { it[Keys.hudPromptDismissed] = dismissed }
    suspend fun setMapStyle(style: MapStyle) = context.dataStore.edit { it[Keys.mapStyle] = style.name }
}
