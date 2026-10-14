package com.ridetrack.app.pro

import android.content.Context
import com.ridetrack.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the rider has Pro: Studio's Reels and everything that uses Gemini. Free riders get
 * rides, moments, the pop-up, safety, fuel and the journal, with no Gemini at all (captions are
 * typed by hand, the petrol price is asked), apart from one free Reel a week.
 *
 * Until billing ships, everyone is Pro (the beta). Test builds can act as a free rider to try
 * the free app. Billing and access codes will set [pro] from real purchases.
 */
class Entitlements(context: Context) {
    private val prefs = context.getSharedPreferences("entitlements", Context.MODE_PRIVATE)
    private val _pro = MutableStateFlow(compute())

    /** Pro right now. */
    val pro: StateFlow<Boolean> = _pro.asStateFlow()
    val isPro: Boolean get() = _pro.value

    /** Test builds: act as a free rider. */
    var actAsFree: Boolean
        get() = BuildConfig.TEST_BUILD && prefs.getBoolean(ACT_AS_FREE, false)
        set(v) {
            prefs.edit().putBoolean(ACT_AS_FREE, v).apply()
            _pro.value = compute()
        }

    private fun compute(): Boolean = !actAsFree && BETA_ALL_ACCESS

    private companion object {
        const val ACT_AS_FREE = "actAsFree"
        /** Everyone has everything until the subscription is in the app. */
        const val BETA_ALL_ACCESS = true
    }
}
