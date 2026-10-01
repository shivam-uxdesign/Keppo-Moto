package com.ridetrack.app

import com.ridetrack.app.backup.BackupFormat
import com.ridetrack.app.backup.BackupPlanner
import com.ridetrack.app.backup.DriveQuery
import com.ridetrack.app.backup.LocalRide
import com.ridetrack.app.backup.Manifest
import com.ridetrack.app.backup.ManifestFile
import com.ridetrack.app.backup.ManifestRide
import com.ridetrack.app.backup.RideBundle
import com.ridetrack.app.data.db.BikeEntity
import com.ridetrack.app.data.db.EventEntity
import com.ridetrack.app.data.db.MomentEntity
import com.ridetrack.app.data.db.RideEntity
import com.ridetrack.app.data.db.SampleEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackupTest {
    private val ride = RideEntity(
        id = "r1", bikeId = "b1", name = "Sunday at Nahan", status = "COMPLETED", source = "PHONE",
        startTimeMillis = 1_000, endTimeMillis = 9_000, lastUpdateMillis = 9_000, distanceM = 86_400.5, movingMillis = 7_000,
        stoppedMillis = 1_000, maxSpeedMps = 31.2, maxAccelG = 0.4, maxBrakeG = null, peakG = 0.9, maxLeftLeanDeg = 38.0,
        maxRightLeanDeg = 31.0, avgLeanDeg = null, stopCount = 2, leftTurns = 5, rightTurns = 4, brakeEvents = 1, accelEvents = 0, leanEvents = 3,
    )
    private val events = listOf(EventEntity(rideId = "r1", type = "HARD_BRAKE", timeMillis = 2_000, latitude = 28.6, longitude = null, speedMps = 12.0, value = -0.6))
    private val moments = listOf(
        MomentEntity("m1", "r1", "CLIP", "HARD_BRAKE", 2_000, 28.6, 77.2, 12.0, -0.6, "m1.mp4", "m1.jpg", 20_000, true, 1_500, null),
        MomentEntity("m2", "r1", "PHOTO", "", 3_000, null, null, null, null, "m2.jpg", null, null, false, null, "MANUAL"),
    )

    @Test
    fun `ride json round-trips, nulls stay null`() {
        val json = BackupFormat.rideJson(RideBundle(ride, events, moments), "KTM Duke 390")
        val back = BackupFormat.parseRide(json)
        assertEquals(ride, back.ride)
        assertEquals(events, back.events)
        assertEquals(moments, back.moments)
        assertTrue(json.contains("\"format\": \"keppo.ride\""))
    }

    @Test
    fun `samples round-trip through gzip`() {
        val samples = listOf(
            SampleEntity(rideId = "r1", timeMillis = 1_000, latitude = 28.61, longitude = 77.21, speedMps = 10.0, altitudeM = 216.0,
                headingDeg = 90.0, longitudinalG = 0.1, lateralG = -0.2, leanDeg = 12.5, gpsAccuracyM = 4.0, rpm = 5_000.0, gear = 3),
            SampleEntity(rideId = "r1", timeMillis = 1_100, latitude = null, longitude = null, speedMps = null, altitudeM = null,
                headingDeg = null, longitudinalG = null, lateralG = null, leanDeg = null, gpsAccuracyM = null),
        )
        val out = ByteArrayOutputStream()
        BackupFormat.writeSamples(samples, out)
        assertEquals(samples, BackupFormat.readSamples("r1", ByteArrayInputStream(out.toByteArray())))
    }

    @Test
    fun `bikes and settings round-trip`() {
        val bikes = listOf(BikeEntity("b1", "KTM", "Duke 390", 2023, 373, 168, "PETROL", "PORTRAIT", 0.1, 0.9, 0.2, 5, 1, 10_500, "b1.jpg", 12_000.0, 7))
        assertEquals(bikes, BackupFormat.parseBikes(BackupFormat.bikesJson(bikes)))
        val prefs = listOf(
            BackupFormat.Pref("auto_pause", true), BackupFormat.Pref("hud_opacity", 90), BackupFormat.Pref("moments_brake_g", 0.5),
            BackupFormat.Pref("safety_contacts", "Mum\t+91 98450 12345"), BackupFormat.Pref("live_metrics", setOf("G_FORCE", "MAX_LEAN")),
            BackupFormat.Pref("some_long", 42L), BackupFormat.Pref("some_float", 1.5f),
        )
        assertEquals(prefs.sortedBy { it.key }, BackupFormat.parseSettings(BackupFormat.settingsJson(prefs)))
    }

    @Test
    fun `manifest round-trips`() {
        val m = Manifest(5, "0.1.0", mapOf("r1" to ManifestRide("abc", "dev1", mapOf("m1.mp4" to ManifestFile(10), "m2.jpg" to ManifestFile(3, 99)), null)),
            mapOf("b1.jpg" to ManifestFile(7)))
        assertEquals(m, Manifest.parse(m.toJson()))
    }

    private val now = 100L * 24 * 60 * 60 * 1000

    @Test
    fun `new ride uploads data and media, unchanged ride uploads nothing`() {
        val local = listOf(LocalRide("r1", "fp", mapOf("m1.mp4" to 10L, "m1.jpg" to 2L)))
        val first = BackupPlanner.plan(local, Manifest(), "dev", now)
        assertEquals(listOf("r1"), first.rideData)
        assertEquals(setOf("r1" to "m1.mp4", "r1" to "m1.jpg"), first.media.toSet())

        val m = Manifest(rides = mapOf("r1" to ManifestRide("fp", "dev", mapOf("m1.mp4" to ManifestFile(10), "m1.jpg" to ManifestFile(2)))))
        assertTrue(BackupPlanner.plan(local, m, "dev", now).isEmpty)
    }

    @Test
    fun `renamed ride re-uploads data only, new moment uploads only that file`() {
        val m = Manifest(rides = mapOf("r1" to ManifestRide("old", "dev", mapOf("m1.mp4" to ManifestFile(10)))))
        val p = BackupPlanner.plan(listOf(LocalRide("r1", "new", mapOf("m1.mp4" to 10L, "m3.jpg" to 4L))), m, "dev", now)
        assertEquals(listOf("r1"), p.rideData)
        assertEquals(listOf("r1" to "m3.jpg"), p.media)
    }

    @Test
    fun `deleting on this phone tombstones, then purges after 30 days`() {
        val m = Manifest(rides = mapOf(
            "r1" to ManifestRide("fp", "dev", mapOf("m1.mp4" to ManifestFile(10), "m2.jpg" to ManifestFile(3))),
            "r2" to ManifestRide("fp2", "dev"),
        ))
        val p = BackupPlanner.plan(listOf(LocalRide("r1", "fp", mapOf("m1.mp4" to 10L))), m, "dev", now)
        assertEquals(listOf("r2"), p.tombstoneRides)
        assertEquals(listOf("r1" to "m2.jpg"), p.tombstoneFiles)
        assertTrue(p.purgeRides.isEmpty())

        val day = 24L * 60 * 60 * 1000
        val tomb = Manifest(rides = mapOf(
            "r1" to ManifestRide("fp", "dev", mapOf("m2.jpg" to ManifestFile(3, deletedAt = now - 31 * day))),
            "r2" to ManifestRide("fp2", "dev", deletedAt = now - 31 * day),
            "r3" to ManifestRide("fp3", "dev", deletedAt = now - 2 * day),
        ))
        val later = BackupPlanner.plan(listOf(LocalRide("r1", "fp", emptyMap())), tomb, "dev", now)
        assertEquals(listOf("r2"), later.purgeRides)
        assertEquals(listOf("r1" to "m2.jpg"), later.purgeFiles)
    }

    @Test
    fun `a new phone that hasn't restored never deletes the backup`() {
        val m = Manifest(rides = mapOf("r1" to ManifestRide("fp", "old-phone", mapOf("m1.mp4" to ManifestFile(10)))))
        val p = BackupPlanner.plan(emptyList(), m, "new-phone", now)
        assertTrue(p.tombstoneRides.isEmpty())
        assertEquals(listOf("r1"), BackupPlanner.toRestore(m, emptySet()))
    }

    @Test
    fun `restored ride is taken over once all its files are back`() {
        val m = Manifest(rides = mapOf("r1" to ManifestRide("fp", "old-phone", mapOf("m1.mp4" to ManifestFile(10)))))
        val partial = BackupPlanner.plan(listOf(LocalRide("r1", "fp", emptyMap())), m, "new-phone", now)
        assertTrue(partial.claim.isEmpty() && partial.tombstoneFiles.isEmpty())
        val full = BackupPlanner.plan(listOf(LocalRide("r1", "fp", mapOf("m1.mp4" to 10L))), m, "new-phone", now)
        assertEquals(listOf("r1"), full.claim)
    }

    @Test
    fun `restore skips rides already on the phone and deleted ones`() {
        val m = Manifest(rides = mapOf(
            "r1" to ManifestRide("a", "d"), "r2" to ManifestRide("b", "d"), "r3" to ManifestRide("c", "d", deletedAt = 5),
        ))
        assertEquals(listOf("r2"), BackupPlanner.toRestore(m, setOf("r1")))
    }

    @Test
    fun `folders are found by tag, not name`() {
        assertEquals(
            "mimeType = 'application/vnd.google-apps.folder' and trashed = false and appProperties has { key='keppo' and value='moto' }",
            DriveQuery.folderByTag("moto"),
        )
    }
}
