package com.ridetrack.app.hud

import java.util.Locale

/**
 * The pop-up's top row, as text. Pure, so it is unit-tested.
 *
 * Left is the ride: "Riding 00:00", "Stopped 00:00", "Paused 00:00", or amber "GPS lost" while
 * it is lost. Right is the camera: "cam" when idle, "cam 00:12" while filming (an event clip
 * counts from its look-back), "3", "2", "1" before a photo, then "photo".
 */
object HudTopRow {
    enum class Tone { TEXT, MUTED, DIM, WARNING, PAUSED, REC, ACCENT }

    data class Label(val text: String, val tone: Tone)

    /** How long "photo" stays after a photo is taken. */
    const val PHOTO_SHOWN_MILLIS = 1_500L

    fun left(data: HudData): Label = when (data.status) {
        HudStatus.GPS_LOST -> Label("GPS lost", Tone.WARNING)
        HudStatus.STOPPED -> Label("Stopped " + clock(data.stoppedForMillis ?: 0), Tone.PAUSED)
        HudStatus.PAUSED -> Label("Paused " + clock(data.elapsedMillis ?: 0), Tone.WARNING)
        HudStatus.RECORDING -> Label("Riding " + clock(data.elapsedMillis ?: 0), Tone.TEXT)
    }

    fun right(data: HudData): Label {
        val now = data.nowMillis
        val video = data.video
        val clipStart = data.clipStartMillis
        val photoAt = data.photoAtMillis
        val photoTaken = data.photoTakenAtMillis
        return when {
            video != null -> Label("cam " + clock(video.elapsedMillis), if (video.paused) Tone.WARNING else Tone.REC)
            clipStart != null -> Label("cam " + clock(now - clipStart), Tone.REC)
            photoAt != null && now < photoAt -> Label(((photoAt - now + 999) / 1000).coerceIn(1, 3).toString(), Tone.ACCENT)
            photoTaken != null && now >= photoTaken && now - photoTaken < PHOTO_SHOWN_MILLIS -> Label("photo", Tone.ACCENT)
            data.camera == CameraIndicator.OFF -> Label("cam", Tone.DIM)
            else -> Label("cam", Tone.MUTED)
        }
    }

    /** "00:42", "12:05", "1:02:05". */
    fun clock(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
    }
}
