package com.ridetrack.app.fuel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.ridetrack.app.studio.writeAtomically
import java.io.File

/** One fuel check: when, on which ride, what the app saw or did. */
data class FuelLogLine(val timeMillis: Long, val rideId: String, val message: String)

/**
 * Every fuel check across rides (stops found, the pump matched or not, the card SMS found or not,
 * the price looked up), kept on the phone so a missed fill-up can be explained on the Fuel page.
 * The newest [MAX_LINES] are kept.
 */
class FuelLog(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    private val _changes = MutableStateFlow(0)
    /** Changes each time a line is added, so the Fuel page reads it again. */
    val changes: StateFlow<Int> = _changes

    @Synchronized
    fun add(rideId: String, message: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val line = "${now()}\t$rideId\t${message.replace('\n', ' ').replace('\t', ' ')}"
            val lines = if (file.exists()) file.readLines() else emptyList()
            if (lines.size >= MAX_LINES + TRIM_SLACK) file.writeAtomically((lines.takeLast(MAX_LINES - 1) + line).joinToString("\n", postfix = "\n"))
            else file.appendText(line + "\n")
        }
        _changes.value++
    }

    /** Newest first. */
    @Synchronized
    fun read(): List<FuelLogLine> = runCatching {
        if (!file.exists()) return emptyList()
        file.readLines().mapNotNull { l ->
            val p = l.split('\t', limit = 3)
            if (p.size == 3) p[0].toLongOrNull()?.let { FuelLogLine(it, p[1], p[2]) } else null
        }.asReversed()
    }.getOrDefault(emptyList())

    companion object {
        const val MAX_LINES = 500
        /** Trimmed in batches, not on every line. */
        private const val TRIM_SLACK = 100
    }
}
