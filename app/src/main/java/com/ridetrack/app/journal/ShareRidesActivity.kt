package com.ridetrack.app.journal

import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.ridetrack.app.RideTrackApp
import com.ridetrack.app.ui.components.PrimaryButton
import com.ridetrack.app.ui.components.SecondaryButton
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.launch

/**
 * Keppo Journal's one-tap "Connect" (see docs/keppo-ride-format.md, "Connecting in one tap").
 * Only Keppo Journal may ask; anyone else gets RESULT_CANCELED without seeing anything.
 * On Allow, sharing turns on and the journal gets a persistable read grant on the shared rides.
 */
class ShareRidesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (callingActivity?.packageName != JournalSource.JOURNAL_PACKAGE) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        setKeppoDialog(onDismiss = ::notNow) {
            Text("Share your rides with Keppo Journal?", style = RtType.headline, color = RtColors.TextPrimary)
            Text(
                "Your finished rides and their moments will appear in Keppo Journal on this phone. " +
                    "It can read them, not change them. You can stop this anytime in Profile › Keppo Journal.",
                style = RtType.body,
                color = RtColors.TextSecondary,
            )
            Spacer(Modifier.height(RtDimens.sm))
            PrimaryButton("Allow", ::allow, Modifier.fillMaxWidth())
            SecondaryButton("Not now", ::notNow, Modifier.fillMaxWidth())
        }
    }

    private fun allow() {
        val container = (application as RideTrackApp).container
        lifecycleScope.launch {
            container.settings.setJournalSharing(true)
            container.settings.setJournalConnected(System.currentTimeMillis())
            container.journal.onSharingChanged()
            val tree = DocumentsContract.buildTreeDocumentUri(JournalSource.authority(this@ShareRidesActivity), JournalTree.ROOT)
            setResult(
                RESULT_OK,
                // PREFIX makes the grant cover everything inside the folder, not just the root URI.
                Intent().setData(tree).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                ),
            )
            finish()
        }
    }

    private fun notNow() {
        setResult(RESULT_CANCELED)
        finish()
    }
}
