package com.ridetrack.app

import com.ridetrack.app.fuel.CardSms
import com.ridetrack.app.fuel.Fuel
import com.ridetrack.app.fuel.FuelFill
import com.ridetrack.app.fuel.FuelPrompt
import com.ridetrack.app.fuel.PumpStops
import com.ridetrack.telemetry.model.TelemetrySample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FuelTest {
    private val day = 86_400_000L
    private fun fill(d: Int, litres: Double, amount: Double? = null, price: Double? = null) =
        FuelFill("f$d", "b", d * day, litres, amount, price)

    @Test
    fun `km per litre is km since the last fill over this fill's litres`() {
        val fills = listOf(fill(0, 10.0), fill(7, 8.0, 760.0, 95.0), fill(14, 10.0, 950.0, 95.0))
        val rides = listOf(1 * day to 150_000.0, 3 * day to 154_000.0, 9 * day to 380_000.0, 20 * day to 50_000.0)
        val m = Fuel.mileage(fills, rides)
        assertEquals(2, m.tanks.size)
        assertEquals(38.0, m.tanks[0].kmPerLitre, 0.01) // 304 km / 8 L
        assertEquals(38.0, m.tanks[1].kmPerLitre, 0.01) // 380 km / 10 L
        assertEquals(38.0, m.kmPerLitre!!, 0.01)
        assertEquals(2.5, m.costPerKm!!, 0.01)
        val (l, cost) = m.estimate(19.0)!!
        assertEquals(0.5, l, 0.001)
        assertEquals(47.5, cost!!, 0.01)
    }

    @Test
    fun `one fill only starts the count`() {
        assertNull(Fuel.mileage(listOf(fill(0, 10.0)), listOf(day to 100_000.0)).kmPerLitre)
    }

    @Test
    fun `fills and prompts survive being stored`() {
        val f = listOf(Fuel.newFill("b", 5L, 9.5, 902.0, 95.0, "Indian Oil | Sector 12", "sms"))
        val back = Fuel.decodeFills(Fuel.encodeFills(f))
        assertEquals(9.5, back.single().litres)
        assertEquals("Indian Oil / Sector 12", back.single().station)
        val p = listOf(FuelPrompt("r", "b", 7L, "HP Petrol Pump", 500.0))
        assertEquals(p, Fuel.decodePrompts(Fuel.encodePrompts(p)))
    }

    @Test
    fun `card messages give the amount and spot a fuel merchant`() {
        val hdfc = "Rs.1,050.00 spent on HDFC Bank Card xx1234 at INDIAN OIL CORP on 06-10-26. Avl Lmt Rs 45,000"
        val p = assertNotNull(CardSms.parse(hdfc, 0))
        assertEquals(1050.0, p.amount)
        assertTrue(p.fuel)
        assertEquals("INDIAN OIL CORP", p.merchant)
        val icici = "INR 520.00 spent using ICICI Bank Card XX9876 on 06-Oct-26 on SWIGGY. Avl Limit: INR 1,20,000"
        assertEquals(false, CardSms.parse(icici, 0)!!.fuel)
        assertNull(CardSms.parse("Your OTP for txn of Rs 1050 at HPCL is 123456", 0))
        assertNull(CardSms.parse("Rs 500 credited to your account", 0))
        val pick = CardSms.pick(listOf(CardSms.parse(icici, 100)!!, CardSms.parse(hdfc, 900)!!), 120)
        assertEquals(1050.0, pick!!.amount)
    }

    @Test
    fun `a 4 minute stop is a possible fill-up, a red light and a long break aren't`() {
        val samples = ArrayList<TelemetrySample>()
        var t = 0L
        fun add(seconds: Int, speed: Double) = repeat(seconds) {
            samples += TelemetrySample(t, 28.6, 77.2, speed, null, null, null, null, null, null); t += 1_000
        }
        add(60, 12.0); add(40, 0.0); add(60, 12.0) // red light
        add(240, 0.0); add(60, 12.0) // pump
        add(1_800, 0.0); add(10, 12.0) // break
        val stops = PumpStops.find(samples)
        assertEquals(1, stops.size)
        assertEquals(239_000L, stops[0].toMillis - stops[0].fromMillis)
    }

    @Test
    fun `today's petrol price is read from Gemini's answer, and only if it's believable`() {
        assertEquals(103.44, com.ridetrack.app.fuel.FuelPriceText.parse("""{"price": 103.44, "place": "Pune", "date": "2026-10-08"}"""))
        assertEquals(94.72, com.ridetrack.app.fuel.FuelPriceText.parse("Today petrol in Delhi costs ₹94.72 per litre."))
        assertNull(com.ridetrack.app.fuel.FuelPriceText.parse("""{"price": null}"""))
        assertNull(com.ridetrack.app.fuel.FuelPriceText.parse("""{"price": 1.5}"""))
        assertEquals(4.83, com.ridetrack.app.fuel.FuelPriceText.litres(500.0, 103.44))
    }
}
