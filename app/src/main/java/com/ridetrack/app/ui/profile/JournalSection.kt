package com.ridetrack.app.ui.profile

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.ridetrack.app.ui.components.RtCard
import com.ridetrack.app.ui.components.SectionHeader
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType

/** Profile › Keppo Journal: share finished rides with the journal on this phone. */
@Composable
internal fun JournalSection(s: ProfileUiState, vm: ProfileViewModel) {
    val installed = remember { vm.journalInstalled() }
    SectionHeader("Keppo Journal")
    RtCard {
        ToggleRow(
            "Share rides with Keppo Journal",
            "Finished rides and their moments appear in Keppo Journal on this phone right after you save them. " +
                "Read-only: the journal can't change anything here. Across devices, rides also arrive through your Google Drive backup.",
            s.settings.journalSharing,
            vm::setJournalSharing,
        )
        if (s.settings.journalSharing) {
            Spacer(Modifier.height(RtDimens.xs))
            val last = s.settings.journalLastReadMillis
            Text(
                when {
                    last != null -> "Keppo Journal last read your rides ${DateUtils.getRelativeTimeSpanString(last)}."
                    installed -> "Not connected yet. In Keppo Journal, open Settings › Connected apps › Keppo Moto."
                    else -> "Install Keppo Journal, then connect it in Settings › Connected apps › Keppo Moto."
                },
                style = RtType.caption,
                color = RtColors.TextSecondary,
            )
        }
    }
}
