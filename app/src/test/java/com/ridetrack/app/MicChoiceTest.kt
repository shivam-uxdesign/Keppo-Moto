package com.ridetrack.app

import com.ridetrack.app.moments.MicChoice
import com.ridetrack.app.moments.MicType
import com.ridetrack.app.moments.Microphones
import kotlin.test.Test
import kotlin.test.assertEquals

class MicChoiceTest {
    @Test
    fun `mic choices survive storage and bad values fall back to the phone`() {
        val dji = MicChoice(MicType.USB, "DJI MIC MINI")
        assertEquals(dji, MicChoice.decode(dji.encode()))
        assertEquals(MicChoice(MicType.BLUETOOTH, null), MicChoice.decode("BLUETOOTH|"))
        assertEquals(MicChoice.AUTO, MicChoice.decode(null))
        assertEquals(MicChoice.AUTO, MicChoice.decode("SATELLITE|x"))
        assertEquals(MicChoice.PHONE, MicChoice.decode(MicChoice.PHONE.encode()))
        assertEquals("DJI MIC MINI · USB-C", dji.label)
    }

    private val dji = MicChoice(MicType.USB, "DJI MIC MINI")
    private val headset = MicChoice(MicType.BLUETOOTH, "HS1_5DC7")

    @Test
    fun `automatic prefers the USB-C mic, then the headset, then the phone`() {
        assertEquals(dji, Microphones.pick(listOf(headset, dji), MicChoice.AUTO))
        assertEquals(headset, Microphones.pick(listOf(headset), MicChoice.AUTO))
        assertEquals(MicChoice.PHONE, Microphones.pick(emptyList(), MicChoice.AUTO))
    }

    @Test
    fun `a chosen USB mic that's unplugged falls back to the phone, never the headset`() {
        assertEquals(MicChoice.PHONE, Microphones.pick(listOf(headset), dji))
        assertEquals(dji, Microphones.pick(listOf(headset, dji), dji))
        // Same kind, different name (another receiver): still that kind.
        assertEquals(MicChoice(MicType.USB, "Other"), Microphones.pick(listOf(MicChoice(MicType.USB, "Other")), dji))
        assertEquals(MicChoice.PHONE, Microphones.pick(listOf(dji, headset), MicChoice.PHONE))
    }
}
