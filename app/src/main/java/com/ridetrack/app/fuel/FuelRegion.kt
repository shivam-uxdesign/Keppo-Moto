package com.ridetrack.app.fuel

import java.util.Currency
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Where the rider fills up, for money and the fuel extras: the country's currency, and whether
 * it's India (where card SMS reading and price lookup were built; elsewhere they're Beta).
 */
data class FuelRegion(val country: String, val currency: String) {
    val india: Boolean get() = country == "IN"

    private val cur: Currency? = runCatching { Currency.getInstance(currency) }.getOrNull()

    /** "₹", "$", "€", "£"; the code ("IDR") where the country has no short sign. */
    val symbol: String = cur?.let { c ->
        val local = runCatching { c.getSymbol(Locale.Builder().setLanguage("en").setRegion(country).build()) }.getOrNull()
        local?.takeIf { it.isNotBlank() } ?: c.getSymbol(Locale.US)
    } ?: currency

    // Some currencies officially have cents nobody uses (rupiah, dong, won…): shown whole.
    private val decimals: Int get() = if (currency in NO_CENTS) 0 else cur?.defaultFractionDigits?.coerceAtLeast(0) ?: 2

    /** An amount paid: "₹1,250" (rupees and currencies without cents: whole), "$12.40". */
    fun money(v: Double): String =
        if (india || decimals == 0) symbol + String.format(Locale.US, "%,d", v.roundToLong())
        else symbol + String.format(Locale.US, "%,.${decimals}f", v)

    /** A price or cost per km to the cent: "₹103.44", "$1.89". */
    fun precise(v: Double): String = symbol + String.format(Locale.US, "%,.2f", v)

    companion object {
        private val NO_CENTS = setOf("IDR", "VND", "KRW", "JPY", "CLP", "COP", "PYG", "UGX", "ISK", "HUF", "TWD", "IRR", "LAK", "MMK", "KHR", "UZS")

        /** Before the phone's country is known: India, as the app always was. */
        val INDIA = FuelRegion("IN", "INR")

        fun of(country: String): FuelRegion {
            val cc = country.uppercase(Locale.ROOT)
            val code = runCatching { Currency.getInstance(Locale("", cc)).currencyCode }.getOrNull() ?: return INDIA.takeIf { cc.isBlank() } ?: FuelRegion(cc, "USD")
            return FuelRegion(cc, code)
        }

        /** The rider's region; set at start-up from the phone. */
        @Volatile var current: FuelRegion = INDIA
    }
}

/** A fuel Beta feature didn't work outside India: what kind, never what the SMS said (for review in the error log and crash reports). */
class FuelBetaIssue(message: String) : Exception(message)
