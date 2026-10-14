package com.ridetrack.app

import com.ridetrack.app.fuel.CardSms
import com.ridetrack.app.fuel.FuelPriceText
import com.ridetrack.app.fuel.FuelRegion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FuelWorldTest {
    private val us = FuelRegion.of("US")
    private val de = FuelRegion.of("DE")
    private val gb = FuelRegion.of("GB")
    private val id = FuelRegion.of("ID")
    private val my = FuelRegion.of("MY")

    @Test fun currencyFromCountry() {
        assertEquals("USD", us.currency)
        assertEquals("EUR", de.currency)
        assertEquals("$12.40", us.money(12.4))
        assertEquals("₹1,250", FuelRegion.INDIA.money(1249.6))
        assertTrue(gb.money(45.0).startsWith("£"))
        // No cents in rupiah: whole numbers.
        assertFalse(id.money(150000.0).contains("."))
        assertTrue(FuelRegion.INDIA.india && !us.india)
    }

    @Test fun numbersInAnyStyle() {
        assertEquals(1234.56, CardSms.number("1,234.56"))
        assertEquals(1234.56, CardSms.number("1.234,56"))
        assertEquals(12.5, CardSms.number("12,50"))
        assertEquals(150000.0, CardSms.number("150.000"))
        assertEquals(1250.0, CardSms.number("1,250"))
    }

    @Test fun cardSmsAbroad() {
        val t = 1_000L
        assertEquals(42.18, CardSms.parse("Chase: A charge of \$42.18 at SHELL OIL 5741 was made on your card ending 1234.", t, us)?.amount)
        assertTrue(CardSms.parse("Chase: A charge of \$42.18 at SHELL OIL 5741 was made on your card ending 1234.", t, us)!!.fuel)
        assertEquals(61.2, CardSms.parse("Zahlung mit Karte: 61,20 EUR bei ARAL Tankstelle Berlin", t, de)?.amount)
        assertEquals(150000.0, CardSms.parse("Transaksi kartu Rp 150.000 di SPBU Pertamina berhasil", t, id)?.amount)
        assertEquals(30.0, CardSms.parse("Payment of RM30.00 at PETRONAS SS2 using card 1234", t, my)?.amount)
        // Refunds, codes and other currencies aren't payments.
        assertNull(CardSms.parse("Refund of \$42.18 credited to your card", t, us))
        assertNull(CardSms.parse("Your code is 482913. Payment confirmation.", t, us))
        assertNull(CardSms.parse("Payment of 30.00 sent", t, de))
        // India is unchanged.
        assertEquals(500.0, CardSms.parse("Rs.500.00 spent on your card at INDIAN OIL", t, FuelRegion.INDIA)?.amount)
    }

    @Test fun reportShapeHasNoWords() {
        val shape = CardSms.shape("Payment of \$42.18 at SHELL", us)
        assertEquals("spend+currency+number", shape)
        assertFalse("SHELL" in shape || "42" in shape)
    }

    @Test fun pricesAbroad() {
        assertTrue("per litre" in FuelPriceText.prompt("Austin, Texas", "2026-10-14", us) && "USD" in FuelPriceText.prompt("Austin, Texas", "2026-10-14", us))
        assertEquals(0.92, FuelPriceText.parse("{\"price\": 0.92, \"place\": \"Austin\"}", us))
        // A per-gallon slip (4x the last price) isn't believed.
        assertNull(FuelPriceText.parse("{\"price\": 3.49}", us, lastKnown = 0.9))
        assertNull(FuelPriceText.parse("{\"price\": null}", us))
        // India keeps its range.
        assertEquals(103.44, FuelPriceText.parse("{\"price\": 103.44}"))
    }
}
