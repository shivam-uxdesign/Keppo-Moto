package com.ridetrack.app

import com.ridetrack.app.routines.ArrivalWatch
import com.ridetrack.app.routines.Routine
import com.ridetrack.app.routines.RoutineAction
import com.ridetrack.app.routines.RoutinePlace
import com.ridetrack.app.routines.RoutineTrigger
import com.ridetrack.app.routines.Routines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutinesTest {
    private val home = RoutinePlace("Home | \"flat\", 4th floor", 28.4595, 77.0266, 150)
    private val arrive = Routine(
        id = "a", name = "Home: stop music, end", trigger = RoutineTrigger.ARRIVE, place = home,
        actions = listOf(RoutineAction.PauseMusic, RoutineAction.EndRide),
    )

    @Test
    fun `routines survive saving, odd names included`() {
        val list = listOf(
            Routine(id = "s", name = "Ride start, music + maps", trigger = RoutineTrigger.RIDE_START, actions = listOf(
                RoutineAction.ResumeMusic, RoutineAction.MediaVolume(70), RoutineAction.DoNotDisturb,
                RoutineAction.OpenApp("com.example.app", "My app: v2, beta|x"), RoutineAction.OpenMaps,
            )),
            arrive.copy(enabled = false),
        )
        assertEquals(list, Routines.decode(Routines.encode(list)))
    }

    @Test
    fun `bad lines are skipped, not fatal`() {
        val good = Routines.encode(listOf(arrive))
        assertEquals(listOf(arrive), Routines.decode("nonsense\n$good\n|||"))
        assertEquals(emptyList<Routine>(), Routines.decode(null))
    }

    @Test
    fun `ending the ride only runs on arrival`() {
        val start = arrive.copy(trigger = RoutineTrigger.RIDE_START)
        assertEquals(listOf(RoutineAction.PauseMusic), start.runnable)
        assertEquals(arrive.actions, arrive.runnable)
        assertTrue(RoutineAction.EndRide !in Routines.choices(RoutineTrigger.RIDE_START))
    }

    @Test
    fun `a ride that starts at home doesn't arrive there at once`() {
        val w = ArrivalWatch(dwellMillis = 20_000)
        repeat(10) { k -> assertEquals(emptyList<Routine>(), w.update(listOf(arrive), home.lat, home.lon, k * 5_000L)) }
    }

    @Test
    fun `arriving back after riding away runs once, after stopping there`() {
        val w = ArrivalWatch(dwellMillis = 20_000)
        val list = listOf(arrive)
        // Ride away (about 1.1 km north), then back.
        assertEquals(emptyList<Routine>(), w.update(list, home.lat + 0.01, home.lon, 0))
        // Passing through the edge briefly isn't arriving.
        assertEquals(emptyList<Routine>(), w.update(list, home.lat + 0.001, home.lon, 60_000))
        assertEquals(emptyList<Routine>(), w.update(list, home.lat + 0.003, home.lon, 65_000))
        assertEquals(emptyList<Routine>(), w.update(list, home.lat, home.lon, 100_000))
        assertEquals(emptyList<Routine>(), w.update(list, home.lat, home.lon, 110_000))
        assertEquals(list, w.update(list, home.lat, home.lon, 120_000))
        assertEquals(emptyList<Routine>(), w.update(list, home.lat, home.lon, 200_000))
    }

    @Test
    fun `switched-off routines and other triggers never arrive`() {
        val w = ArrivalWatch(dwellMillis = 0)
        val off = arrive.copy(enabled = false)
        val start = arrive.copy(id = "b", trigger = RoutineTrigger.RIDE_START)
        w.update(listOf(off, start), home.lat + 0.01, home.lon, 0)
        assertEquals(emptyList<Routine>(), w.update(listOf(off, start), home.lat, home.lon, 1_000))
    }
}
