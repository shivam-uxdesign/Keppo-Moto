package com.ridetrack.app.fuel

import com.ridetrack.telemetry.model.TelemetrySample
import java.util.Locale
import java.util.UUID
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
)

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

    // rideId|bikeId|time|station|amount
    fun encodePrompts(prompts: List<FuelPrompt>): String = prompts.joinToString("\n") { p ->
        listOf(p.rideId, p.bikeId, p.timeMillis, clean(p.station), p.amount ?: "").joinToString("|")
    }

    fun decodePrompts(value: String?): List<FuelPrompt> = value.orEmpty().lines().mapNotNull { line ->
        val p = line.split('|')
        if (p.size != 5) return@mapNotNull null
        FuelPrompt(p[0], p[1], p[2].toLongOrNull() ?: return@mapNotNull null, p[3], p[4].toDoubleOrNull())
    }

    private fun clean(s: String?) = s.orEmpty().replace('|', '/').replace('\n', ' ')

    fun money(v: Double): String = "₹" + String.format(Locale.US, "%,d", v.roundToInt())
}

/** A stop long enough to fill up: where, from when to when. */
data class Stop(val fromMillis: Long, val toMillis: Long, val latitude: Double, val longitude: Double)

object PumpStops {
    /**
     * Stops of [minMillis]–[maxMillis] with a position: the bike below 1 m/s, staying within
     * about 80 m. A fill-up takes a few minutes; a red light is shorter, a break longer.
     */
    fun find(samples: List<TelemetrySample>, minMillis: Long = 60_000L, maxMillis: Long = 15 * 60_000L): List<Stop> {
        val out = ArrayList<Stop>()
        var run = ArrayList<TelemetrySample>()
        fun close() {
            val fixes = run.filter { it.latitude != null && it.longitude != null }
            if (run.size >= 2 && fixes.isNotEmpty()) {
                val d = run.last().timeMillis - run.first().timeMillis
                val lat = fixes.map { it.latitude!! }.average()
                val lon = fixes.map { it.longitude!! }.average()
                val spread = fixes.maxOf { metres(lat, lon, it.latitude!!, it.longitude!!) }
                if (d in minMillis..maxMillis && spread < 80) out += Stop(run.first().timeMillis, run.last().timeMillis, lat, lon)
            }
            run = ArrayList()
        }
        for (s in samples) {
            if ((s.speedMps ?: 0.0) < 1.0) run += s else close()
        }
        close()
        return out
    }

    private fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * 111_320.0
        val dLon = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians(lat1))
        return sqrt(dLat * dLat + dLon * dLon)
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

    /** The card payment in [body], or null if it isn't one (OTPs, credits, balances…). */
    fun parse(body: String, timeMillis: Long): CardPayment? {
        if (!SPEND.containsMatchIn(body) || CREDIT.containsMatchIn(body) && !SPEND.containsMatchIn(body.substringBefore("credited"))) return null
        if (body.contains("OTP", ignoreCase = true)) return null
        val amount = AMOUNT.find(body)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: return null
        val merchant = MERCHANT.find(body)?.groupValues?.get(1)?.trim()
        return CardPayment(amount, merchant, FUEL.containsMatchIn(body), timeMillis)
    }

    /** The payment most likely to be this fill-up: fuel merchants first, then the closest in time to [atMillis]. */
    fun pick(payments: List<CardPayment>, atMillis: Long): CardPayment? =
        payments.sortedWith(compareBy<CardPayment>({ !it.fuel }, { kotlin.math.abs(it.timeMillis - atMillis) })).firstOrNull()
}

/** Petrol's price per litre, looked up online for [place]; [exact] = found today, else the last known one. */
data class PetrolPrice(val perLitre: Double, val place: String, val exact: Boolean)

/** Asking for today's petrol price and reading the answer. Pure, unit-tested. */
object FuelPriceText {
    fun prompt(place: String, today: String): String =
        "What is today's ($today) retail price of regular petrol per litre in $place, India? Search the web for today's rate in that city. " +
            "Reply with JSON only: {\"price\": 103.44, \"place\": \"the city\", \"date\": \"YYYY-MM-DD\"}, price in rupees, or null if you can't find it."

    /** The price in the reply, if it's a believable petrol price in rupees (₹60–200). */
    fun parse(reply: String): Double? {
        val fromJson = runCatching {
            val a = reply.indexOf('{')
            val b = reply.lastIndexOf('}')
            org.json.JSONObject(reply.substring(a, b + 1)).optDouble("price", Double.NaN)
        }.getOrNull()?.takeIf { !it.isNaN() }
        val v = fromJson ?: Regex("""(?:₹|Rs\.?|INR)\s*(\d{2,3}(?:\.\d{1,2})?)""").find(reply)?.groupValues?.get(1)?.toDoubleOrNull()
        return v?.takeIf { it in 60.0..200.0 }
    }

    /** Litres for [amount] at [price], to two places. */
    fun litres(amount: Double, price: Double): Double = kotlin.math.round(amount / price * 100) / 100
}
