package com.ridetrack.app

import com.ridetrack.app.studio.FrameAt
import com.ridetrack.app.studio.PictureCuts
import com.ridetrack.app.studio.TransitionKind
import com.ridetrack.app.studio.Vibe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PictureCutsTest {
    private fun frame(t: Long, inKind: TransitionKind = TransitionKind.STYLE, outKind: TransitionKind = TransitionKind.STYLE) =
        FrameAt(Vibe.VLOG, t, 4_000, hasPrev = true, hasNext = true, inKind = inKind, inMs = 600, outKind = outKind, outMs = 600)

    @Test
    fun `a picture transition runs to the cut on the way out and from it on the way in`() {
        // Out: the last 300 ms, 0 → 1 at the cut.
        assertNull(PictureCuts.at(frame(3_600, outKind = TransitionKind.SPLIT)))
        assertEquals(0.5f, PictureCuts.at(frame(3_850, outKind = TransitionKind.SPLIT))!!.cov, 0.001f)
        assertEquals(TransitionKind.SPLIT, PictureCuts.at(frame(3_999, outKind = TransitionKind.SPLIT))!!.kind)
        // In: the first 300 ms, 1 at the cut → 0.
        assertEquals(1f, PictureCuts.at(frame(0, inKind = TransitionKind.LUMA))!!.cov, 0.001f)
        assertEquals(0.5f, PictureCuts.at(frame(150, inKind = TransitionKind.BLUR))!!.cov, 0.001f)
        assertNull(PictureCuts.at(frame(300, inKind = TransitionKind.BLUR)))
    }

    @Test
    fun `other transitions, and Instant, leave the picture alone`() {
        assertNull(PictureCuts.at(frame(0, inKind = TransitionKind.FADE)))
        assertNull(PictureCuts.at(frame(3_999, outKind = TransitionKind.CUT)))
        assertFalse(PictureCuts.touches(frame(0, TransitionKind.MIRROR, TransitionKind.VISOR)))
        assertTrue(PictureCuts.touches(frame(0, TransitionKind.FADE, TransitionKind.BLUR)))
        // No cut on that side: nothing, even with a picture transition set.
        assertNull(PictureCuts.at(frame(0, inKind = TransitionKind.SPLIT).copy(hasPrev = false)))
    }
}
