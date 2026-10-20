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
        id = "a", name = "Home: stop music, end", triggers = listOf(RoutineTrigger.ARRIVE), place = home,
        actions = listOf(RoutineAction.PauseMusic, RoutineAction.EndRide),
    )

    @Test
    fun `routines survive saving, odd names included`() {
        val list = listOf(
            Routine(id = "s", name = "Ride start, music + maps", triggers = listOf(RoutineTrigger.RIDE_START, RoutineTrigger.ARRIVE), actions = listOf(
                RoutineAction.ResumeMusic, RoutineAction.MediaVolume(70), RoutineAction.DoNotDisturb,
                RoutineAction.OpenApp("com.example.app", "My app: v2, beta|x"), RoutineAction.Wait(30), RoutineAction.OpenMaps,
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
        val both = arrive.copy(triggers = listOf(RoutineTrigger.RIDE_START, RoutineTrigger.ARRIVE))
        assertEquals(listOf(RoutineAction.PauseMusic), both.runnable(RoutineTrigger.RIDE_START))
        assertEquals(arrive.actions, both.runnable(RoutineTrigger.ARRIVE))
        assertTrue(RoutineAction.EndRide !in Routines.choices(listOf(RoutineTrigger.RIDE_START)))
        assertTrue(RoutineAction.EndRide in Routines.choices(listOf(RoutineTrigger.RIDE_END, RoutineTrigger.ARRIVE)))
    }

    @Test
    fun `routines saved with one trigger still load`() {
        val old = "a|1|RIDE_END|Old|||||pause"
        assertEquals(listOf(RoutineTrigger.RIDE_END), Routines.decode(old).single().triggers)
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
        val start = arrive.copy(id = "b", triggers = listOf(RoutineTrigger.RIDE_START))
        w.update(listOf(off, start), home.lat + 0.01, home.lon, 0)
        assertEquals(emptyList<Routine>(), w.update(listOf(off, start), home.lat, home.lon, 1_000))
    }
}
