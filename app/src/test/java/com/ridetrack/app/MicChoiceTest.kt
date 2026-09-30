package com.ridetrack.app

import com.ridetrack.app.moments.MicChoice
import com.ridetrack.app.moments.MicType
import kotlin.test.Test
import kotlin.test.assertEquals

class MicChoiceTest {
    @Test
    fun `mic choices survive storage and bad values fall back to the phone`() {
        val dji = MicChoice(MicType.USB, "DJI MIC MINI")
        assertEquals(dji, MicChoice.decode(dji.encode()))
        assertEquals(MicChoice(MicType.BLUETOOTH, null), MicChoice.decode("BLUETOOTH|"))
        assertEquals(MicChoice.PHONE, MicChoice.decode(null))
        assertEquals(MicChoice.PHONE, MicChoice.decode("SATELLITE|x"))
        assertEquals("DJI MIC MINI · USB-C", dji.label)
    }
}
