package com.ridetrack.app.ui.bike

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.ridetrack.app.AppContainer
import com.ridetrack.app.data.BikePhotos
import com.ridetrack.app.ui.common.Odometer
import com.ridetrack.telemetry.model.Bike
import com.ridetrack.telemetry.model.FuelType
import com.ridetrack.telemetry.model.MountOrientation
import com.ridetrack.telemetry.model.SensorAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToLong

data class BikesUiState(
    val loading: Boolean = true,
    val bikes: List<Bike> = emptyList(),
    val selectedId: String? = null,
    val sensors: SensorAvailability = SensorAvailability(false, false, false),
    val hasGps: Boolean = false,
    val message: String? = null,
    /** Current odometer per bike id, in km; null = not set. */
    val odometers: Map<String, Double?> = emptyMap(),
    /** "Ridden today" etc. per bike id. */
    val lastRidden: Map<String, String> = emptyMap(),
)

class BikesViewModel(private val c: AppContainer) : ViewModel() {
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<BikesUiState> = combine(c.bikes.observeBikes(), c.settings.settings, message, c.rides.observeCompleted()) { bikes, settings, msg, rides ->
        val now = java.time.ZonedDateTime.now()
        val real = rides.filter { it.source != com.ridetrack.telemetry.model.DataSourceKind.DEMO }
        BikesUiState(
            odometers = bikes.associate { it.id to Odometer.readingKm(it, rides) },
            lastRidden = bikes.associate { b -> b.id to com.ridetrack.app.ui.home.lastRiddenLabel(real.filter { it.bikeId == b.id }.maxOfOrNull { it.startTimeMillis }, now) },
            loading = false,
            bikes = bikes,
            selectedId = bikes.firstOrNull { it.id == settings.selectedBikeId }?.id ?: bikes.firstOrNull()?.id,
            sensors = c.sensorInventory.availability(),
            hasGps = c.sensorInventory.hasGpsHardware(),
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BikesUiState())

    fun select(id: String) {
        viewModelScope.launch { c.settings.setSelectedBike(id) }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            if (!c.bikes.deleteIfUnused(id)) {
                message.value = "This bike has recorded rides, so it can't be deleted."
            }
        }
    }

    fun dismissMessage() {
        message.value = null
    }
}

data class BikeForm(
    val make: String = "",
    val model: String = "",
    val year: String = "",
    val displacementCc: String = "",
    val weightKg: String = "",
    val fuelType: FuelType = FuelType.PETROL,
    val mountOrientation: MountOrientation = MountOrientation.PORTRAIT,
    val redlineRpm: String = "",
    val photoFile: String? = null,
    val photoBusy: Boolean = false,
    /** Whole km; blank = not set. */
    val odometerKm: String = "",
) {
    val odometerError: Boolean get() = odometerKm.isNotBlank() && (odometerKm.toLongOrNull() ?: -1) !in 0..2_000_000
    val redlineError: Boolean get() = redlineRpm.isNotBlank() && (redlineRpm.toIntOrNull() ?: 0) !in 2_000..25_000
    val yearError: Boolean get() = year.isNotBlank() && (year.toIntOrNull() ?: 0) !in 1900..2100
    val ccError: Boolean get() = displacementCc.isNotBlank() && (displacementCc.toIntOrNull() ?: 0) !in 1..5000
    val weightError: Boolean get() = weightKg.isNotBlank() && (weightKg.toIntOrNull() ?: 0) !in 1..2000
    val isValid: Boolean get() = (make.isNotBlank() || model.isNotBlank()) && !yearError && !ccError && !weightError && !redlineError && !odometerError && !photoBusy
}

class BikeEditViewModel(private val c: AppContainer, private val bikeId: String?) : ViewModel() {
    private val _form = MutableStateFlow(BikeForm())
    val form: StateFlow<BikeForm> = _form.asStateFlow()
    private var existing: Bike? = null
    val isNew: Boolean = bikeId == null
    private val id = bikeId ?: UUID.randomUUID().toString()
    /** Photos imported in this session; unsaved ones are cleaned up. */
    private val imported = mutableListOf<String>()
    private var saved = false
    /** The odometer as shown when the form opened; unchanged means keep the stored reading. */
    private var initialOdometer = ""

    init {
        if (bikeId != null) {
            viewModelScope.launch {
                c.bikes.get(bikeId)?.let { b ->
                    existing = b
                    val reading = Odometer.readingKm(b, c.rides.observeCompleted().first())
                    initialOdometer = reading?.let { String.format(Locale.US, "%d", it.roundToLong()) }.orEmpty()
                    _form.value = BikeForm(
                        make = b.make,
                        model = b.model,
                        year = b.year?.toString().orEmpty(),
                        displacementCc = b.displacementCc?.toString().orEmpty(),
                        weightKg = b.weightKg?.toString().orEmpty(),
                        fuelType = b.fuelType,
                        mountOrientation = b.mountOrientation,
                        redlineRpm = b.redlineRpm?.toString().orEmpty(),
                        photoFile = b.photoFile,
                        odometerKm = initialOdometer,
                    )
                }
            }
        }
    }

    fun update(f: (BikeForm) -> BikeForm) = _form.update(f)

    fun pickPhoto(uri: Uri) {
        _form.update { it.copy(photoBusy = true) }
        viewModelScope.launch {
            val name = BikePhotos.import(c.appContext, uri, id)
            if (name != null) imported += name
            _form.update { it.copy(photoFile = name ?: it.photoFile, photoBusy = false) }
        }
    }

    fun removePhoto() = _form.update { it.copy(photoFile = null) }

    override fun onCleared() {
        // Drop photos that were picked but never saved (or replaced before saving).
        val keep = if (saved) _form.value.photoFile else existing?.photoFile
        val orphans = imported.filter { it != keep }
        if (orphans.isNotEmpty()) {
            c.appScope.launch { orphans.forEach { BikePhotos.delete(c.appContext, it) } }
        }
    }

    /** Saves and returns the bike id, or null if the form is invalid. */
    fun save(onSaved: (String) -> Unit) {
        val f = _form.value
        if (!f.isValid) return
        val prev = existing
        // A new reading restarts the count; rides from now on are added on top of it.
        val (odometer, odometerAt) = when {
            f.odometerKm.isBlank() -> null to null
            prev != null && f.odometerKm == initialOdometer -> prev.odometerKm to prev.odometerSetAtMillis
            else -> f.odometerKm.toLong().toDouble() to System.currentTimeMillis()
        }
        val bike = Bike(
            id = prev?.id ?: id,
            make = f.make.trim(),
            model = f.model.trim(),
            year = f.year.toIntOrNull(),
            displacementCc = f.displacementCc.toIntOrNull(),
            weightKg = f.weightKg.toIntOrNull(),
            fuelType = f.fuelType,
            mountOrientation = f.mountOrientation,
            calibration = prev?.calibration.takeIf { prev?.mountOrientation == f.mountOrientation },
            createdAtMillis = prev?.createdAtMillis ?: System.currentTimeMillis(),
            redlineRpm = f.redlineRpm.toIntOrNull(),
            photoFile = f.photoFile,
            odometerKm = odometer,
            odometerSetAtMillis = odometerAt,
        )
        viewModelScope.launch {
            c.bikes.save(bike)
            saved = true
            if (prev?.photoFile != null && prev.photoFile != bike.photoFile) BikePhotos.delete(c.appContext, prev.photoFile)
            val selected = c.settings.settings.first().selectedBikeId
            if (selected == null || prev == null) c.settings.setSelectedBike(bike.id)
            onSaved(bike.id)
        }
    }
}
