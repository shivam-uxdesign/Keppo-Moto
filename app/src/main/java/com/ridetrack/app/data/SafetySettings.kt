package com.ridetrack.app.data

/** Someone to text if Keppo Moto thinks you've crashed. */
data class EmergencyContact(val name: String, val phone: String) {
    companion object {
        const val MAX = 3

        /** One contact per line, name and number separated by a tab. */
        fun encode(list: List<EmergencyContact>): String =
            list.take(MAX).joinToString("\n") { "${clean(it.name)}\t${clean(it.phone)}" }

        fun decode(value: String?): List<EmergencyContact> =
            value.orEmpty().lines().mapNotNull { line ->
                val name = line.substringBefore('\t').trim()
                val phone = line.substringAfter('\t', "").trim()
                if (phone.isEmpty()) null else EmergencyContact(name.ifEmpty { phone }, phone)
            }.take(MAX)

        private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').trim()
    }
}

/** Optional details shown on the crash screen and added to the alert. */
data class MedicalInfo(val bloodGroup: String? = null, val allergies: String = "", val notes: String = "") {
    val isEmpty: Boolean get() = bloodGroup == null && allergies.isBlank() && notes.isBlank()

    companion object {
        val BLOOD_GROUPS = listOf("A+", "A−", "B+", "B−", "O+", "O−", "AB+", "AB−")
    }
}

enum class CrashSensitivity(val label: String, val impactG: Double) { NORMAL("Normal", 4.0), HIGH("High", 3.0) }

data class SafetySettings(
    val crashDetection: Boolean = false,
    val sensitivity: CrashSensitivity = CrashSensitivity.NORMAL,
    val gpsLostVideo: Boolean = true,
    val contacts: List<EmergencyContact> = emptyList(),
    val medical: MedicalInfo = MedicalInfo(),
    /** The rider's name for the alert text ("Shivam on Pulsar NS200"). */
    val riderName: String = "",
)
