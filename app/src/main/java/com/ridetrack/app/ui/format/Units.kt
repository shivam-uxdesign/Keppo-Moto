package com.ridetrack.app.ui.format

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Kilometres or miles, for everything the rider reads (stored values stay metric). */
enum class UnitSystem(val label: String) {
    METRIC("Kilometres"),
    IMPERIAL("Miles"),
}

/**
 * The units in use. Set at start-up and whenever the rider changes them (Profile › Appearance);
 * "Automatic" follows the phone's country. Read by [Format] and the share and Reel renderers.
 */
object Units {
    /** Compose state, so screens on display redraw when the rider switches. */
    var system: UnitSystem by mutableStateOf(UnitSystem.METRIC)
    /** The country the volume follows: the US buys fuel in US gallons; the UK buys in litres. */
    @Volatile var country: String = ""

    val miles: Boolean get() = system == UnitSystem.IMPERIAL

    /** Countries that measure roads in miles. */
    private val MILES = setOf("US", "GB", "LR", "MM")

    fun defaultFor(country: String): UnitSystem = if (country.uppercase() in MILES) UnitSystem.IMPERIAL else UnitSystem.METRIC

    /** "km" or "mi". */
    val distanceUnit: String get() = if (miles) "mi" else "km"

    /** "km/h" or "mph". */
    val speedUnit: String get() = if (miles) "mph" else "km/h"

    /** Metres in the rider's distance unit. */
    fun distance(meters: Double): Double = if (miles) meters / METERS_PER_MILE else meters / 1000.0

    /** Kilometres in the rider's distance unit (odometers, reminders). */
    fun fromKm(km: Double): Double = if (miles) km * 1000.0 / METERS_PER_MILE else km

    /** The rider's distance unit back to kilometres (what they type into an odometer). */
    fun toKm(v: Double): Double = if (miles) v * METERS_PER_MILE / 1000.0 else v

    /** A speed kept in km/h (Studio's clips) in the rider's unit, rounded. */
    fun fromKmh(kmh: Double): Int = kotlin.math.round(if (miles) kmh * 1000.0 / METERS_PER_MILE else kmh).toInt()

    /** "62 km/h" or "39 mph", from km/h. */
    fun kmhText(kmh: Double): String = "${fromKmh(kmh)} $speedUnit"

    /** Metres per second in the rider's speed unit. */
    fun speed(mps: Double): Double = if (miles) mps * 3600.0 / METERS_PER_MILE else mps * 3.6

    // Fuel: the US in gallons and mpg; the UK in litres at the pump, mpg (imperial gallons) for economy.
    private val usGallons: Boolean get() = miles && country.uppercase() == "US"

    /** "L" or "gal". */
    val volumeUnit: String get() = if (usGallons) "gal" else "L"

    /** Litres in the rider's volume unit. */
    fun volume(litres: Double): Double = if (usGallons) litres / US_GALLON else litres

    /** The rider's volume unit back to litres. */
    fun toLitres(v: Double): Double = if (usGallons) v * US_GALLON else v

    /** A price per litre as the rider's pump shows it (per gallon in the US). */
    fun pricePerVolume(perLitre: Double): Double = if (usGallons) perLitre * US_GALLON else perLitre

    fun pricePerLitre(perVolume: Double): Double = if (usGallons) perVolume / US_GALLON else perVolume

    /** "km/l" or "mpg". */
    val economyUnit: String get() = if (miles) "mpg" else "km/l"

    /** Km per litre as the rider reads economy: mpg in US or imperial gallons when in miles. */
    fun economy(kmPerLitre: Double): Double = when {
        !miles -> kmPerLitre
        usGallons -> kmPerLitre * 1000.0 / METERS_PER_MILE * US_GALLON
        else -> kmPerLitre * 1000.0 / METERS_PER_MILE * IMPERIAL_GALLON
    }

    const val METERS_PER_MILE = 1609.344
    const val US_GALLON = 3.785411784
    const val IMPERIAL_GALLON = 4.54609
}
