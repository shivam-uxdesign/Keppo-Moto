package com.ridetrack.app.journal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.ridetrack.app.RideTrackApp
import com.ridetrack.app.data.RideLookup
import com.ridetrack.app.ui.components.PrimaryButton
import com.ridetrack.app.ui.components.SecondaryButton
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.launch

/**
 * Keppo Journal's DELETE_RIDE (docs/keppo-ride-format.md, "Delete a ride from the journal").
 * Only Keppo Journal may ask. Moto confirms in its own words, then moves the ride to Recently
 * deleted: RESULT_OK if it did, RESULT_CANCELED otherwise (cancelled, unknown or already deleted).
 */
class DeleteRideActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rideId = intent.getStringExtra(JournalSource.EXTRA_RIDE_ID)
        if (callingActivity?.packageName != JournalSource.JOURNAL_PACKAGE || rideId.isNullOrBlank()) return cancel()
        val container = (application as RideTrackApp).container
        lifecycleScope.launch {
            if (container.rides.lookup(rideId) != RideLookup.LIVE) return@launch cancel()
            val (videos, photos) = container.trash.counts(rideId)
            setKeppoDialog(onDismiss = ::cancel) {
                Text("Delete this ride?", style = RtType.headline, color = RtColors.TextPrimary)
                Text(confirmText(videos, photos), style = RtType.body, color = RtColors.TextSecondary)
                Spacer(Modifier.height(RtDimens.sm))
                PrimaryButton("Delete", { delete(rideId) }, Modifier.fillMaxWidth(), color = RtColors.Error, contentColor = RtColors.OnInverse)
                SecondaryButton("Cancel", ::cancel, Modifier.fillMaxWidth())
            }
        }
    }

    private fun delete(rideId: String) {
        val container = (application as RideTrackApp).container
        lifecycleScope.launch {
            setResult(if (container.trash.deleteRide(rideId)) RESULT_OK else RESULT_CANCELED)
            finish()
        }
    }

    private fun cancel() {
        setResult(RESULT_CANCELED)
        finish()
    }

    companion object {
        /** "It moves to Recently deleted in Keppo Moto, with its 3 videos and 5 photos, for 30 days…" */
        fun confirmText(videos: Int, photos: Int): String {
            val parts = listOfNotNull(
                videos.takeIf { it > 0 }?.let { if (it == 1) "1 video" else "$it videos" },
                photos.takeIf { it > 0 }?.let { if (it == 1) "1 photo" else "$it photos" },
            )
            val with = if (parts.isEmpty()) "" else ", with its ${parts.joinToString(" and ")},"
            return "It moves to Recently deleted in Keppo Moto$with for 30 days. After that it's gone for good."
        }
    }
}
