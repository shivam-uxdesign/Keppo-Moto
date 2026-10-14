package com.ridetrack.app.fuel

import com.ridetrack.telemetry.model.TelemetrySample
import java.util.Locale
import java.util.UUID

/** One fill-up. The rider fills the tank every time, so km/l = km since the last fill ÷ these litres. */
data class FuelFill(
    val id: String,
    val bikeId: String,
    val timeMillis: Long,
    val litres: Double,
    val amount: Double?,
    val pricePerLitre: Double?,
    val station: String? = null,
    val fullTank: Boolean = true,
    /** "sms" when the amount came from the card message, "typed" otherwise; "auto" saved with today's price looked up ("auto-about": the last known price). */
    val source: String = "typed",
)

/** A stop at a petrol pump that might be a fill-up, waiting for the rider to say. */
data class FuelPrompt(
    val rideId: String,
    val bikeId: String,
    val timeMillis: Long,
    val station: String,
    /** From the card SMS, when reading it is on. */
    val amount: Double? = null,
    /** Where the bike stood (to remember the station once it's confirmed). */
    val latitude: Double? = null,
    val longitude: Double? = null,
    /**
     * False: a stop of a few minutes with no station known nearby (an unmapped pump?). Only on
     * the Fuel page under "Not sure", never on Home.
     */
    val sure: Boolean = true,
    /** How it was found: "stop", "sms" (a card payment alone), "live" (asked on the spot), "button" (you tapped Filled up), "nudge", "marked" (on a ride's map afterwards). */
    val how: String = "stop",
)

/**
 * The Fuel page's numbers: this month and all time (fuel, money, fill-ups), and how the last
 * tank compares with the average ([trend]: +0.08 = 8% better). Pure.
 */
data class FuelSummary(
    val monthLitres: Double,
    val monthMoney: Double,
    val monthFills: Int,
    val allLitres: Double,
    val allMoney: Double,
    val allFills: Int,
    val trend: Double?,
) {
    companion object {
        fun of(fills: List<FuelFill>, mileage: Mileage, now: Long = System.currentTimeMillis(), zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): FuelSummary {
            val month = java.time.Instant.ofEpochMilli(now).atZone(zone).let { it.year to it.monthValue }
            val inMonth = fills.filter { f -> java.time.Instant.ofEpochMilli(f.timeMillis).atZone(zone).let { (it.year to it.monthValue) == month } }
            val avg = mileage.kmPerLitre
            val last = mileage.tanks.lastOrNull()?.kmPerLitre
            return FuelSummary(
                inMonth.sumOf { it.litres }, inMonth.mapNotNull { it.amount }.sum(), inMonth.size,
                fills.sumOf { it.litres }, fills.mapNotNull { it.amount }.sum(), fills.size,
                if (avg != null && last != null && avg > 0 && mileage.tanks.size >= 2) last / avg - 1 else null,
            )
        }
    }
}

/** One tank: from the previous full fill to [fill]. */
data class Tank(val fill: FuelFill, val km: Double, val kmPerLitre: Double, val costPerKm: Double?)

data class Mileage(val tanks: List<Tank>, val kmPerLitre: Double?, val costPerKm: Double?, val lastPrice: Double?) {
    /** About how much fuel and money a ride of [km] took. */
    fun estimate(km: Double): Pair<Double, Double?>? {
        val kmpl = kmPerLitre ?: return null
        val litres = km / kmpl
        return litres to lastPrice?.let { litres * it }
    }
}

object Fuel {
    fun newFill(bikeId: String, timeMillis: Long, litres: Double, amount: Double?, price: Double?, station: String?, source: String) =
        FuelFill(UUID.randomUUID().toString(), bikeId, timeMillis, litres, amount, price, station?.takeIf { it.isNotBlank() }, true, source)

    /**
     * Mileage for one bike from its fills and its rides ((start, distance m) of real rides on
     * it). The first fill only starts the count.
     */
    fun mileage(fills: List<FuelFill>, rides: List<Pair<Long, Double>>): Mileage {
        val sorted = fills.sortedBy { it.timeMillis }
        val tanks = sorted.zipWithNext().mapNotNull { (prev, cur) ->
            if (!prev.fullTank || !cur.fullTank || cur.litres <= 0) return@mapNotNull null
            val km = rides.filter { it.first > prev.timeMillis && it.first < cur.timeMillis }.sumOf { it.second } / 1000
            if (km <= 0) return@mapNotNull null
            Tank(cur, km, km / cur.litres, cur.amount?.let { it / km })
        }
        val km = tanks.sumOf { it.km }
        val litres = tanks.sumOf { it.fill.litres }
        val money = tanks.mapNotNull { it.fill.amount }.sum()
        val withMoney = tanks.filter { it.fill.amount != null }.sumOf { it.km }
        return Mileage(
            tanks = tanks,
            kmPerLitre = if (litres > 0) km / litres else null,
            costPerKm = if (withMoney > 0) money / withMoney else null,
            lastPrice = sorted.lastOrNull { it.pricePerLitre != null }?.pricePerLitre,
        )
    }

    // Stored one per line: id|bikeId|time|litres|amount|price|station|full|source
    fun encodeFills(fills: List<FuelFill>): String = fills.joinToString("\n") { f ->
        listOf(f.id, f.bikeId, f.timeMillis, f.litres, f.amount ?: "", f.pricePerLitre ?: "", clean(f.station), f.fullTank, f.source).joinToString("|")
    }

    fun decodeFills(value: String?): List<FuelFill> = value.orEmpty().lines().mapNotNull { line ->
        val p = line.split('|')
        if (p.size != 9) return@mapNotNull null
        FuelFill(
            p[0], p[1], p[2].toLongOrNull() ?: return@mapNotNull null, p[3].toDoubleOrNull() ?: return@mapNotNull null,
            p[4].toDoubleOrNull(), p[5].toDoubleOrNull(), p[6].ifBlank { null }, p[7] == "true", p[8],
        )
    }

    // rideId|bikeId|time|station|amount|lat|lon|sure|how (older lines have the first five)
    fun encodePrompts(prompts: List<FuelPrompt>): String = prompts.joinToString("\n") { p ->
        listOf(p.rideId, p.bikeId, p.timeMillis, clean(p.station), p.amount ?: "", p.latitude ?: "", p.longitude ?: "", p.sure, p.how).joinToString("|")
    }

    fun decodePrompts(value: String?): List<FuelPrompt> = value.orEmpty().lines().mapNotNull { line ->
        val p = line.split('|')
        if (p.size != 5 && p.size != 9) return@mapNotNull null
        FuelPrompt(
            p[0], p[1], p[2].toLongOrNull() ?: return@mapNotNull null, p[3], p[4].toDoubleOrNull(),
            latitude = p.getOrNull(5)?.toDoubleOrNull(), longitude = p.getOrNull(6)?.toDoubleOrNull(),
            sure = p.getOrNull(7)?.let { it != "false" } ?: true, how = p.getOrNull(8)?.ifBlank { null } ?: "stop",
        )
    }

    private fun clean(s: String?) = s.orEmpty().replace('|', '/').replace('\n', ' ')

    fun money(v: Double): String = FuelRegion.current.money(v)
}

/**
 * A stop long enough to fill up: where, from when to when. [guessed]: there was no GPS fix during
 * it (a metal canopy), so the position is the last one known just before.
 */
data class Stop(val fromMillis: Long, val toMillis: Long, val latitude: Double, val longitude: Double, val guessed: Boolean = false)

object PumpStops {
    /** Slower than this is stopped (m/s, about 5 km/h): GPS under a canopy wobbles above walking pace. */
    const val SLOW_MPS = 1.5
    /** Moving this long or less doesn't end a stop (inching up in the queue, a GPS jump). */
    const val BRIEF_MS = 10_000L
    /** …as long as it's this far or less (m), worked out from the speed. */
    const val BRIEF_M = 30.0
    /** With no fix during the stop, the position from up to this long before (or after) it is used. */
    const val NEAR_MS = 60_000L

    /**
     * Stops of [minMillis]–[maxMillis]: the bike slower than walking pace, allowing a few seconds
     * of creeping forward or GPS wobble (up to about 30 m). A fill-up takes a few minutes; a red
     * light is shorter, a break longer.
     */
    fun find(samples: List<TelemetrySample>, minMillis: Long = 60_000L, maxMillis: Long = 15 * 60_000L): List<Stop> {
        if (samples.isEmpty()) return emptyList()
        val out = ArrayList<Stop>()
        var runFrom = -1
        var lastSlow = -1
        var moveFrom = -1
        var moved = 0.0
        fun close(endIndex: Int) {
            if (runFrom >= 0 && endIndex >= runFrom) stopOf(samples, runFrom, endIndex, minMillis, maxMillis)?.let(out::add)
            runFrom = -1
            moveFrom = -1
            moved = 0.0
        }
        for (i in samples.indices) {
            val s = samples[i]
            val slow = (s.speedMps ?: 0.0) < SLOW_MPS
            if (slow) {
                if (runFrom < 0) runFrom = i
                lastSlow = i
                moveFrom = -1
                moved = 0.0
            } else if (runFrom >= 0) {
                if (moveFrom < 0) moveFrom = i
                val dt = if (i > 0) (s.timeMillis - samples[i - 1].timeMillis).coerceIn(0, 5_000) / 1000.0 else 1.0
                moved += (s.speedMps ?: 0.0) * dt
                // Moving on for real: the stop ended at the last slow reading.
                if (s.timeMillis - samples[moveFrom].timeMillis > BRIEF_MS || moved > BRIEF_M) close(lastSlow)
            }
        }
        close(lastSlow)
        return out
    }

    private fun stopOf(samples: List<TelemetrySample>, from: Int, to: Int, minMillis: Long, maxMillis: Long): Stop? {
        val d = samples[to].timeMillis - samples[from].timeMillis
        if (d !in minMillis..maxMillis) return null
        val fixes = (from..to).map { samples[it] }.filter { it.latitude != null && it.longitude != null }
        if (fixes.isNotEmpty()) {
            return Stop(samples[from].timeMillis, samples[to].timeMillis, fixes.map { it.latitude!! }.average(), fixes.map { it.longitude!! }.average())
        }
        // No fix all through (a metal canopy): where the bike was just before, else just after.
        val start = samples[from].timeMillis
        val end = samples[to].timeMillis
        val near = samples.subList(0, from).lastOrNull { it.latitude != null && it.longitude != null && start - it.timeMillis <= NEAR_MS }
            ?: samples.subList(to + 1, samples.size).firstOrNull { it.latitude != null && it.longitude != null && it.timeMillis - end <= NEAR_MS }
            ?: return null
        return Stop(start, end, near.latitude!!, near.longitude!!, guessed = true)
    }
}

/** A card payment read from a bank SMS. */
data class CardPayment(val amount: Double, val merchant: String?, val fuel: Boolean, val timeMillis: Long)

object CardSms {
    private val AMOUNT = Regex("(?i)(?:rs\\.?|inr|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
    private val SPEND = Regex("(?i)\\b(spent|debited|used|purchase|paid|txn|transaction|charged)\\b")
    private val CREDIT = Regex("(?i)\\b(credited|refund|reversal|received|cashback)\\b")
    private val MERCHANT = Regex("(?i)\\b(?:at|to|towards|info:?)\\s+([A-Za-z0-9][A-Za-z0-9 &.'*/-]{2,40}?)(?=\\s+(?:on|via|avl|ref|for|at)\\b|[.,]|\\s*$)")
    private val FUEL = Regex("(?i)indian\\s*oil|iocl|\\bhpcl\\b|\\bhp\\s*(?:petrol|pump|fuel|cl)|hindustan\\s*petroleum|bpcl|bharat\\s*petroleum|\\bshell\\b|nayara|jio[-\\s]?bp|petrol|fuel|filling\\s*st|\\bpump\\b|essar")

    // Outside India (Beta): banks write in many languages and currencies.
    private val SPEND_ANY = Regex("(?iu)\\b(spent|debited|debit|used|purchase|paid|payment|txn|transaction|charged|charge|compra|cargo|pago|paiement|achat|débit|zahlung|kauf|belastung|acquisto|pagamento|addebito|aankoop|betaling|zakup|płatność|transaksi|pembelian|pembayaran|pago con|harcama|ödeme|giao dịch|thanh toán|การชำระ|ซื้อ)")
    private val CREDIT_ANY = Regex("(?iu)\\b(credited|credit|refund|reversal|received|cashback|abono|reembolso|remboursement|gutschrift|erstattung|rimborso|terugbetaling|zwrot|pengembalian|iade)\\b")
    private val FUEL_ANY = Regex("(?iu)\\bshell\\b|\\bbp\\b|\\besso\\b|exxon|\\bmobil\\b|chevron|texaco|total\\s*energies|\\btotal\\b|petronas|caltex|pertamina|\\bptt\\b|bangchak|repsol|cepsa|\\bgalp\\b|\\beni\\b|agip|\\baral\\b|\\bomv\\b|lukoil|ipiranga|petrobras|\\bypf\\b|pemex|\\bq8\\b|circle\\s*k|7-eleven|ampol|\\bz\\s*energy|petro|fuel|gas\\s*station|\\bgas\\b|tankstelle|gasolinera|station[-\\s]service|\\bposto\\b|benzin|carburant|combustible|spbu|petrol")
    // No plain spaces inside: "45 12/10" must not read as 4512.
    private val NUMBER = "([0-9][0-9.,'\\u00A0]{0,15}[0-9]|[0-9])"

    /** The card payment in [body], or null if it isn't one (OTPs, credits, balances…). */
    fun parse(body: String, timeMillis: Long, region: FuelRegion = FuelRegion.current): CardPayment? {
        if (!region.india) return parseAny(body, timeMillis, region)
        if (!SPEND.containsMatchIn(body) || CREDIT.containsMatchIn(body) && !SPEND.containsMatchIn(body.substringBefore("credited"))) return null
        if (body.contains("OTP", ignoreCase = true)) return null
        val amount = AMOUNT.find(body)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: return null
        val merchant = MERCHANT.find(body)?.groupValues?.get(1)?.trim()
        return CardPayment(amount, merchant, FUEL.containsMatchIn(body), timeMillis)
    }

    private fun parseAny(body: String, timeMillis: Long, region: FuelRegion): CardPayment? {
        if (!SPEND_ANY.containsMatchIn(body) || CREDIT_ANY.containsMatchIn(body) && !SPEND_ANY.containsMatchIn(body.substringBefore(CREDIT_ANY.find(body)!!.value))) return null
        if (Regex("(?i)\\b(otp|code|código|code de|passcode)\\b").containsMatchIn(body)) return null
        val amount = amountIn(body, region) ?: return null
        val merchant = MERCHANT.find(body)?.groupValues?.get(1)?.trim()
        return CardPayment(amount, merchant, FUEL_ANY.containsMatchIn(body), timeMillis)
    }

    /** The first amount next to the region's currency (its sign or code, before or after), in any number style. */
    internal fun amountIn(body: String, region: FuelRegion): Double? {
        val marks = (listOf(region.symbol, region.currency) + extraMarks(region.currency)).distinct().filter { it.isNotBlank() }
            .sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        val before = Regex("(?i)(?:$marks)\\s?$NUMBER")
        val after = Regex("(?i)$NUMBER\\s?(?:$marks)")
        val raw = listOfNotNull(before.find(body), after.find(body)).minByOrNull { it.range.first }?.groupValues?.get(1) ?: return null
        return number(raw)?.takeIf { it > 0 }
    }

    /** Other ways banks write a currency ("US$", "Rp", "RM"…). */
    private fun extraMarks(code: String): List<String> = when (code) {
        "USD" -> listOf("US$", "$", "USD")
        "EUR" -> listOf("€", "EUR", "Eur")
        "GBP" -> listOf("£", "GBP")
        "IDR" -> listOf("Rp", "Rp.", "IDR")
        "MYR" -> listOf("RM", "MYR")
        "BRL" -> listOf("R$", "BRL")
        "THB" -> listOf("฿", "THB", "บาท")
        "PHP" -> listOf("₱", "PHP", "Php")
        "VND" -> listOf("₫", "VND", "đ")
        "AUD" -> listOf("A$", "AU$", "$")
        "CAD" -> listOf("C$", "CA$", "$")
        "NZD" -> listOf("NZ$", "$")
        "SGD" -> listOf("S$", "SGD", "$")
        "MXN" -> listOf("MX$", "$", "MXN")
        "ZAR" -> listOf("ZAR")
        "AED" -> listOf("AED", "Dhs", "د.إ")
        "TRY" -> listOf("₺", "TL", "TRY")
        "PLN" -> listOf("zł", "PLN")
        "CHF" -> listOf("CHF", "Fr.")
        "SEK", "NOK", "DKK" -> listOf("kr", code)
        "JPY" -> listOf("¥", "円", "JPY")
        "KRW" -> listOf("₩", "원", "KRW")
        "NPR", "LKR", "PKR" -> listOf("Rs", "Rs.", code)
        "BDT" -> listOf("৳", "Tk", "BDT")
        else -> emptyList()
    }

    /** "1,234.56", "1.234,56", "1 234,56", "12'500" or "1,250" as a number: the last . or , with 1–2 digits after it is the decimal point. */
    internal fun number(raw: String): Double? {
        val t = raw.replace('\u00A0', ' ').replace(" ", "").replace("'", "")
        val last = maxOf(t.lastIndexOf('.'), t.lastIndexOf(','))
        val decimal = last >= 0 && t.length - last - 1 in 1..2
        val whole = (if (decimal) t.substring(0, last) else t).replace(".", "").replace(",", "")
        val frac = if (decimal) t.substring(last + 1) else ""
        return (whole + if (frac.isNotEmpty()) ".$frac" else "").toDoubleOrNull()
    }

    /** The payment most likely to be this fill-up: fuel merchants first, then the closest in time to [atMillis]. */
    fun pick(payments: List<CardPayment>, atMillis: Long): CardPayment? =
        payments.sortedWith(compareBy<CardPayment>({ !it.fuel }, { kotlin.math.abs(it.timeMillis - atMillis) })).firstOrNull()

    /**
     * What a message looked like, for the Beta failure report: never its words, only whether it
     * had a spending word, the currency, a number. E.g. "spend+currency+number".
     */
    fun shape(body: String, region: FuelRegion): String = listOfNotNull(
        "spend".takeIf { SPEND_ANY.containsMatchIn(body) },
        "credit".takeIf { CREDIT_ANY.containsMatchIn(body) },
        "currency".takeIf { listOf(region.symbol, region.currency).any { it.isNotBlank() && body.contains(it, ignoreCase = true) } },
        "number".takeIf { Regex("[0-9]").containsMatchIn(body) },
    ).joinToString("+").ifEmpty { "plain" }
}

/** Petrol's price per litre, looked up online for [place]; [exact] = found today, else the last known one. */
data class PetrolPrice(val perLitre: Double, val place: String, val exact: Boolean)

/** Asking for today's petrol price and reading the answer. Pure, unit-tested. */
object FuelPriceText {
    fun prompt(place: String, today: String, region: FuelRegion = FuelRegion.INDIA): String =
        if (region.india) {
            "What is today's ($today) retail price of regular petrol per litre in $place, India? Search the web for today's rate in that city. " +
                "Reply with JSON only: {\"price\": 103.44, \"place\": \"the city\", \"date\": \"YYYY-MM-DD\"}, price in rupees, or null if you can't find it."
        } else {
            "What is today's ($today) retail price of regular petrol (gasoline, the most common grade) per litre in $place? Search the web for the latest price there. " +
                "If it's quoted per gallon, convert it to per litre. Give it in ${region.currency}. " +
                "Reply with JSON only: {\"price\": 1.89, \"place\": \"the city\", \"date\": \"YYYY-MM-DD\"}, or null if you can't find it."
        }

    /**
     * The price in the reply, if believable: ₹60–200 in India; elsewhere positive, and within
     * half to double the last known price when there is one (a per-gallon slip is caught).
     */
    fun parse(reply: String, region: FuelRegion = FuelRegion.INDIA, lastKnown: Double? = null): Double? {
        val fromJson = runCatching {
            val a = reply.indexOf('{')
            val b = reply.lastIndexOf('}')
            org.json.JSONObject(reply.substring(a, b + 1)).optDouble("price", Double.NaN)
        }.getOrNull()?.takeIf { !it.isNaN() }
        if (region.india) {
            val v = fromJson ?: Regex("""(?:₹|Rs\.?|INR)\s*(\d{2,3}(?:\.\d{1,2})?)""").find(reply)?.groupValues?.get(1)?.toDoubleOrNull()
            return v?.takeIf { it in 60.0..200.0 }
        }
        val v = fromJson ?: CardSms.amountIn(reply, region) ?: return null
        if (v <= 0) return null
        return v.takeIf { lastKnown == null || v in lastKnown / 2..lastKnown * 2 }
    }

    /** Litres for [amount] at [price], to two places. */
    fun litres(amount: Double, price: Double): Double = kotlin.math.round(amount / price * 100) / 100
}
