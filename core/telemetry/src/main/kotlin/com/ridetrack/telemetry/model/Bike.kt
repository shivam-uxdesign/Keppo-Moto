package com.ridetrack.telemetry.model

enum class FuelType { PETROL, DIESEL, ELECTRIC, OTHER }

/** How the phone is mounted. Informational; lean math relies on [MountCalibration] instead. */
enum class MountOrientation { PORTRAIT, LANDSCAPE }

data class Bike(
    val id: String,
    val make: String,
    val model: String,
    val year: Int?,
    val displacementCc: Int?,
    val weightKg: Int?,
    val fuelType: FuelType,
    val mountOrientation: MountOrientation,
    val calibration: MountCalibration?,
    val createdAtMillis: Long,
    /** Engine redline for the rev meter; null = unknown (no redline band is drawn). */
    val redlineRpm: Int? = null,
    /** File name of the bike's photo in app storage, if the rider added one. */
    val photoFile: String? = null,
) {
    val displayName: String get() = listOf(make, model).filter { it.isNotBlank() }.joinToString(" ")
}
