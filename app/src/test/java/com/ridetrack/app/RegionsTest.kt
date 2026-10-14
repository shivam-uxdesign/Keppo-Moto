package com.ridetrack.app

import com.ridetrack.app.diag.Regions
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegionsTest {
    @Test
    fun `crash reports need consent in the EU, EEA, UK and Switzerland, and when the country is unknown`() {
        listOf("DE", "fr", "GB", "IE", "NO", "CH").forEach { assertTrue(Regions.needsConsent(it), it) }
        assertTrue(Regions.needsConsent(""))
        listOf("IN", "US", "AE", "BR", "JP").forEach { assertFalse(Regions.needsConsent(it), it) }
    }
}
