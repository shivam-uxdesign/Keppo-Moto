package com.ridetrack.app

import com.ridetrack.app.journal.DeleteRideActivity
import com.ridetrack.app.journal.JournalTree
import com.ridetrack.app.trash.Deletion
import com.ridetrack.app.trash.DeletionLog
import com.ridetrack.app.trash.RecentlyDeleted
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecentlyDeletedTest {
    @Test
    fun `deleted json follows keppo deleted v1 and round-trips`() {
        val log = DeletionLog()
            .rideDeleted("r1", 100)
            .momentDeleted("m1", "r2", 200)
        val o = JSONObject(log.toJson())
        assertEquals("keppo.deleted", o.getString("format"))
        assertEquals(1, o.getInt("v"))
        assertEquals(100, o.getJSONObject("rides").getJSONObject("r1").getLong("deletedAt"))
        assertTrue(o.getJSONObject("rides").getJSONObject("r1").isNull("restoredAt"))
        assertEquals("r2", o.getJSONObject("moments").getJSONObject("m1").getString("rideId"))
        assertEquals(log, DeletionLog.parse(log.toJson()))
    }

    @Test
    fun `entries are only updated, never removed`() {
        val log = DeletionLog().rideDeleted("r1", 100).rideRestored("r1", 150)
        assertFalse(log.rides.getValue("r1").isDeleted)
        val again = log.rideDeleted("r1", 300)
        assertEquals(Deletion(300, restoredAt = 150), again.rides.getValue("r1"))
        assertTrue(again.rides.getValue("r1").isDeleted)
        val purged = again.ridePurged("r1", 400)
        assertEquals(400, purged.rides.getValue("r1").purgedAt)
        // Restoring or purging something never logged is a no-op.
        assertEquals(purged, purged.rideRestored("nope", 500).momentPurged("nope", 500))
    }

    @Test
    fun `days left rounds up and stops at zero`() {
        val day = 24L * 60 * 60 * 1000
        assertEquals(30, RecentlyDeleted.daysLeft(0, 1))
        assertEquals(1, RecentlyDeleted.daysLeft(0, 29 * day + 1))
        assertEquals(0, RecentlyDeleted.daysLeft(0, 30 * day))
        assertEquals(0, RecentlyDeleted.daysLeft(0, 40 * day))
    }

    @Test
    fun `journal delete confirmation names what goes with the ride`() {
        assertEquals(
            "It moves to Recently deleted in Keppo Moto, with its 3 videos and 1 photo, for 30 days. After that it's gone for good.",
            DeleteRideActivity.confirmText(3, 1),
        )
        assertEquals(
            "It moves to Recently deleted in Keppo Moto for 30 days. After that it's gone for good.",
            DeleteRideActivity.confirmText(0, 0),
        )
    }

    @Test
    fun `deleted json sits at the root of the shared folder`() {
        assertEquals(JournalTree.Doc.Deletions, JournalTree.parse("deleted.json"))
        assertEquals("deleted.json", JournalTree.id(JournalTree.Doc.Deletions))
        assertTrue(JournalTree.isChild("root", "deleted.json"))
        assertFalse(JournalTree.isChild("ride:r1", "deleted.json"))
        assertNull(JournalTree.parse("file:r1/../deleted.json"))
    }
}
