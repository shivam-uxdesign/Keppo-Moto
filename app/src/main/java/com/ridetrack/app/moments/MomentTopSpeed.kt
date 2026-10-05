package com.ridetrack.app.moments

import com.ridetrack.telemetry.model.TelemetrySample

/** The fastest the bike went between two times of a ride. Pure, so it is unit-tested. */
object MomentTopSpeed {
    /** Max speed (m/s) among [samples] from [fromMillis] to [toMillis]; null when none is known. */
    fun of(samples: List<TelemetrySample>, fromMillis: Long, toMillis: Long): Double? =
        samples.asSequence()
            .filter { it.timeMillis in fromMillis..toMillis }
            .mapNotNull { it.speedMps }
            .maxOrNull()
}
