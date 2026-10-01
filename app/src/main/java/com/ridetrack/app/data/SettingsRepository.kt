package com.ridetrack.app.data

import com.ridetrack.app.backup.BackupFormat
import kotlinx.coroutines.flow.first
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.ridetrack.app.share.MomentField
import com.ridetrack.app.share.MomentLayout

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
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

/** App colours. Riding screens (live ride, HUD, viewfinder, crash alert) stay dark in every mode. */
enum class AppTheme(val label: String) { SYSTEM("Match phone"), DARK("Dark"), LIGHT("Light") }

enum class PhotoInterval(val label: String, val minutes: Int) {
    OFF("Off", 0), FIVE("5 min", 5), TEN("10 min", 10), FIFTEEN("15 min", 15), THIRTY("30 min", 30),
}

enum class VideoQuality(val label: String, val height: Int, val bitrate: Int) {
    HD("720p", 720, 5_000_000),
    FULL_HD("1080p", 1080, 8_000_000),
}

/** Moments: rider-facing clips around notable events, plus periodic photos. Opt-in. */
data class MomentSettings(
    val enabled: Boolean = false,
    val braking: Boolean = true,
    val acceleration: Boolean = true,
    val lean: Boolean = true,
    val photos: PhotoInterval = PhotoInterval.FIFTEEN,
    val quality: VideoQuality = VideoQuality.HD,
    /** Braking at least this hard starts a clip (G). */
    val brakeG: Double = DEFAULT_BRAKE_G,
    /** Accelerating at least this hard starts a clip (G). */
    val accelG: Double = DEFAULT_ACCEL_G,
    /** Leaning past this angle starts a clip (degrees). */
    val leanDeg: Int = DEFAULT_LEAN_DEG,
    /** Seconds kept before and after each event. */
    val clipSeconds: Int = DEFAULT_CLIP_SECONDS,
    /** Microphone for clips, as MicChoice.encode(); null = Automatic (USB-C, then headset, then phone). */
    val mic: String? = null,
) {
    val anyTrigger: Boolean get() = braking || acceleration || lean

    companion object {
        const val DEFAULT_BRAKE_G = 0.5
        const val DEFAULT_ACCEL_G = 0.3
        const val DEFAULT_LEAN_DEG = 20
        const val DEFAULT_CLIP_SECONDS = 10
        val BRAKE_CHOICES = listOf(0.3, 0.4, 0.5, 0.6, 0.7, 0.8)
        val ACCEL_CHOICES = listOf(0.2, 0.3, 0.4, 0.5, 0.6)
        val LEAN_CHOICES = listOf(15, 20, 25, 30, 35, 40)
        val CLIP_CHOICES = listOf(5, 10, 15)
    }
}

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

/** Google Drive backup. Its own keys (prefix `backup_`) are never themselves backed up. */
data class BackupSettings(
    /** Google account Drive access was granted for; null = not connected. */
    val email: String? = null,
    val allowMobileData: Boolean = false,
    val videosOnlyWhileCharging: Boolean = false,
    val lastSuccessMillis: Long? = null,
    val lastBytes: Long? = null,
    /**
     * This phone's data may be written to the backup. False right after connecting to a Drive
     * that already holds a backup, until the rider restores or chooses to merge, so a fresh
     * phone can't overwrite the saved settings and contacts.
     */
    val adopted: Boolean = false,
    /** The rider closed the "restore from Drive" card on Home. */
    val restoreCardDismissed: Boolean = false,
) {
    val connected: Boolean get() = email != null
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
    val appTheme: AppTheme = AppTheme.SYSTEM,
    val moments: MomentSettings = MomentSettings(),
    /** Last-used moment share graphic: which details are on, and the layout. */
    val momentShareFields: Set<MomentField> = MomentField.DEFAULT,
    val momentShareLayout: MomentLayout = MomentLayout.MINIMAL,
    val safety: SafetySettings = SafetySettings(),
    val backup: BackupSettings = BackupSettings(),
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

private const val BACKUP_PREFIX = "backup_"

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
        val appTheme = stringPreferencesKey("app_theme")
        val momentsEnabled = booleanPreferencesKey("moments_enabled")
        val momentsBraking = booleanPreferencesKey("moments_braking")
        val momentsAccel = booleanPreferencesKey("moments_accel")
        val momentsLean = booleanPreferencesKey("moments_lean")
        val momentsPhotos = stringPreferencesKey("moments_photos")
        val momentsQuality = stringPreferencesKey("moments_quality")
        val momentsBrakeG = doublePreferencesKey("moments_brake_g")
        val momentsAccelG = doublePreferencesKey("moments_accel_g")
        val momentsLeanDeg = intPreferencesKey("moments_lean_deg")
        val momentsClipSeconds = intPreferencesKey("moments_clip_seconds")
        val momentsMic = stringPreferencesKey("moments_mic")
        val momentShareFields = stringSetPreferencesKey("moment_share_fields")
        val momentShareLayout = stringPreferencesKey("moment_share_layout")
        val safetyCrash = booleanPreferencesKey("safety_crash")
        val safetySensitivity = stringPreferencesKey("safety_sensitivity")
        val safetyGpsVideo = booleanPreferencesKey("safety_gps_video")
        val safetyContacts = stringPreferencesKey("safety_contacts")
        val safetyBlood = stringPreferencesKey("safety_blood")
        val safetyAllergies = stringPreferencesKey("safety_allergies")
        val safetyNotes = stringPreferencesKey("safety_notes")
        val safetyName = stringPreferencesKey("safety_name")
        val backupEmail = stringPreferencesKey("backup_email")
        val backupMobileData = booleanPreferencesKey("backup_mobile_data")
        val backupVideosCharging = booleanPreferencesKey("backup_videos_charging")
        val backupLastSuccess = longPreferencesKey("backup_last_success")
        val backupLastBytes = longPreferencesKey("backup_last_bytes")
        val backupAdopted = booleanPreferencesKey("backup_adopted")
        val backupRestoreDismissed = booleanPreferencesKey("backup_restore_dismissed")
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
            appTheme = enumOf(p[Keys.appTheme], AppTheme.SYSTEM),
            moments = MomentSettings(
                enabled = p[Keys.momentsEnabled] ?: false,
                braking = p[Keys.momentsBraking] ?: true,
                acceleration = p[Keys.momentsAccel] ?: true,
                lean = p[Keys.momentsLean] ?: true,
                photos = enumOf(p[Keys.momentsPhotos], PhotoInterval.FIFTEEN),
                quality = enumOf(p[Keys.momentsQuality], VideoQuality.HD),
                brakeG = p[Keys.momentsBrakeG] ?: MomentSettings.DEFAULT_BRAKE_G,
                accelG = p[Keys.momentsAccelG] ?: MomentSettings.DEFAULT_ACCEL_G,
                leanDeg = p[Keys.momentsLeanDeg] ?: MomentSettings.DEFAULT_LEAN_DEG,
                clipSeconds = p[Keys.momentsClipSeconds] ?: MomentSettings.DEFAULT_CLIP_SECONDS,
                mic = p[Keys.momentsMic],
            ),
            momentShareFields = p[Keys.momentShareFields]
                ?.mapNotNull { n -> MomentField.entries.firstOrNull { it.name == n } }
                ?.toSet()
                ?: MomentField.DEFAULT,
            momentShareLayout = enumOf(p[Keys.momentShareLayout], MomentLayout.MINIMAL),
            safety = SafetySettings(
                crashDetection = p[Keys.safetyCrash] ?: false,
                sensitivity = enumOf(p[Keys.safetySensitivity], CrashSensitivity.NORMAL),
                gpsLostVideo = p[Keys.safetyGpsVideo] ?: true,
                contacts = EmergencyContact.decode(p[Keys.safetyContacts]),
                medical = MedicalInfo(
                    bloodGroup = p[Keys.safetyBlood]?.takeIf { it in MedicalInfo.BLOOD_GROUPS },
                    allergies = p[Keys.safetyAllergies].orEmpty(),
                    notes = p[Keys.safetyNotes].orEmpty(),
                ),
                riderName = p[Keys.safetyName].orEmpty(),
            ),
            backup = BackupSettings(
                email = p[Keys.backupEmail],
                allowMobileData = p[Keys.backupMobileData] ?: false,
                videosOnlyWhileCharging = p[Keys.backupVideosCharging] ?: false,
                lastSuccessMillis = p[Keys.backupLastSuccess],
                lastBytes = p[Keys.backupLastBytes],
                adopted = p[Keys.backupAdopted] ?: false,
                restoreCardDismissed = p[Keys.backupRestoreDismissed] ?: false,
            ),
        )
    }

    suspend fun setSafety(s: SafetySettings) = context.dataStore.edit {
        it[Keys.safetyCrash] = s.crashDetection
        it[Keys.safetySensitivity] = s.sensitivity.name
        it[Keys.safetyGpsVideo] = s.gpsLostVideo
        it[Keys.safetyContacts] = EmergencyContact.encode(s.contacts)
        val blood = s.medical.bloodGroup
        if (blood == null) it.remove(Keys.safetyBlood) else it[Keys.safetyBlood] = blood
        it[Keys.safetyAllergies] = s.medical.allergies
        it[Keys.safetyNotes] = s.medical.notes
        it[Keys.safetyName] = s.riderName
    }

    suspend fun setBackupConnected(email: String?, adopted: Boolean) = context.dataStore.edit {
        if (email == null) {
            it.remove(Keys.backupEmail)
            it.remove(Keys.backupLastSuccess)
            it.remove(Keys.backupLastBytes)
        } else {
            it[Keys.backupEmail] = email
        }
        it[Keys.backupAdopted] = adopted
    }
    suspend fun setBackupAdopted(adopted: Boolean) = context.dataStore.edit { it[Keys.backupAdopted] = adopted }
    suspend fun setBackupMobileData(v: Boolean) = context.dataStore.edit { it[Keys.backupMobileData] = v }
    suspend fun setBackupVideosCharging(v: Boolean) = context.dataStore.edit { it[Keys.backupVideosCharging] = v }
    suspend fun setBackupResult(atMillis: Long, bytes: Long) = context.dataStore.edit {
        it[Keys.backupLastSuccess] = atMillis
        it[Keys.backupLastBytes] = bytes
    }
    suspend fun dismissRestoreCard() = context.dataStore.edit { it[Keys.backupRestoreDismissed] = true }

    /** Every stored preference except the backup's own, for `settings.json`. */
    suspend fun exportPrefs(): List<BackupFormat.Pref> = context.dataStore.data.first().asMap()
        .filterKeys { !it.name.startsWith(BACKUP_PREFIX) }
        .map { (k, v) -> BackupFormat.Pref(k.name, v) }

    /** Writes preferences back from a backup (the backup's own keys are left alone). */
    suspend fun importPrefs(prefs: List<BackupFormat.Pref>) = context.dataStore.edit { m ->
        prefs.filterNot { it.key.startsWith(BACKUP_PREFIX) }.forEach { p ->
            @Suppress("UNCHECKED_CAST")
            when (val v = p.value) {
                is Boolean -> m[booleanPreferencesKey(p.key)] = v
                is Int -> m[intPreferencesKey(p.key)] = v
                is Long -> m[longPreferencesKey(p.key)] = v
                is Float -> m[floatPreferencesKey(p.key)] = v
                is Double -> m[doublePreferencesKey(p.key)] = v
                is String -> m[stringPreferencesKey(p.key)] = v
                is Set<*> -> m[stringSetPreferencesKey(p.key)] = v as Set<String>
            }
        }
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
    suspend fun setAppTheme(theme: AppTheme) = context.dataStore.edit { it[Keys.appTheme] = theme.name }

    suspend fun setMomentShare(fields: Set<MomentField>, layout: MomentLayout) = context.dataStore.edit {
        it[Keys.momentShareFields] = fields.map { f -> f.name }.toSet()
        it[Keys.momentShareLayout] = layout.name
    }

    suspend fun setMoments(m: MomentSettings) = context.dataStore.edit {
        it[Keys.momentsEnabled] = m.enabled
        it[Keys.momentsBraking] = m.braking
        it[Keys.momentsAccel] = m.acceleration
        it[Keys.momentsLean] = m.lean
        it[Keys.momentsPhotos] = m.photos.name
        it[Keys.momentsQuality] = m.quality.name
        it[Keys.momentsBrakeG] = m.brakeG
        it[Keys.momentsAccelG] = m.accelG
        it[Keys.momentsLeanDeg] = m.leanDeg
        it[Keys.momentsClipSeconds] = m.clipSeconds
        val mic = m.mic
        if (mic == null) it.remove(Keys.momentsMic) else it[Keys.momentsMic] = mic
    }
}
