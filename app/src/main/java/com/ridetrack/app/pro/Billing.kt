package com.ridetrack.app.pro

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Keppo Moto Pro through Google Play Billing: one subscription ([PRODUCT]) with a monthly and a
 * yearly base plan, each with a one-week free trial offer. Play keeps the purchase on the rider's
 * Google account, so asking Play what they own (on every launch, and Restore purchase) brings
 * Pro back after a reinstall or on a new phone.
 *
 * Play Console set-up: subscription `keppo_pro`; base plans `monthly` (1 month) and `yearly`
 * (1 year), auto-renewing; on each, an offer `free-week` (free trial, 7 days, new customers).
 */
class Billing(context: Context, private val scope: CoroutineScope, private val onActive: (Boolean) -> Unit) : PurchasesUpdatedListener {
    /** A plan as Play prices it for this rider: [price] is ready to show ("₹299", "$9.99"). */
    data class Plan(
        val basePlanId: String,
        val price: String,
        val priceMicros: Long,
        val currency: String,
        /** ISO 8601: P1M or P1Y. */
        val period: String,
        /** Days free before the first charge, when this rider can still have the trial. */
        val trialDays: Int?,
        val offerToken: String,
        internal val details: ProductDetails,
    ) {
        val yearly: Boolean get() = period == "P1Y"
    }

    private val client = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private val _plans = MutableStateFlow<List<Plan>>(emptyList())
    /** The plans to offer, once Play has answered (empty: not on Play, or no connection). */
    val plans: StateFlow<List<Plan>> = _plans.asStateFlow()

    private val _ready = MutableStateFlow(false)
    /** Connected to Play Billing. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    fun start() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                _ready.value = result.responseCode == BillingClient.BillingResponseCode.OK
                if (_ready.value) scope.launch { refresh() }
                else Log.i(TAG, "Billing unavailable: ${result.debugMessage}")
            }

            override fun onBillingServiceDisconnected() {
                // Reconnects by itself (enableAutoServiceReconnection).
                _ready.value = false
            }
        })
    }

    /**
     * Asks Play what the rider owns and what the plans cost. Returns whether they have Pro, or
     * null when Play couldn't be asked (no connection, not installed from Play).
     */
    suspend fun refresh(): Boolean? {
        if (!client.isReady) return null
        loadPlans()
        val r = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build())
        if (r.billingResult.responseCode != BillingClient.BillingResponseCode.OK) return null
        return handle(r.purchasesList)
    }

    private suspend fun loadPlans() {
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            listOf(QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT).setProductType(BillingClient.ProductType.SUBS).build()),
        ).build()
        val r = client.queryProductDetails(params)
        val details = r.productDetailsList?.firstOrNull { it.productId == PRODUCT } ?: return
        _plans.value = Plans.from(details)
    }

    /** Opens Play's payment sheet for [plan]. False when it couldn't open. */
    fun buy(activity: Activity, plan: Plan): Boolean {
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(
            listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(plan.details).setOfferToken(plan.offerToken).build()),
        ).build()
        return client.launchBillingFlow(activity, params).responseCode == BillingClient.BillingResponseCode.OK
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) scope.launch { handle(purchases) }
        else if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) Log.i(TAG, "Purchase not completed: ${result.responseCode} ${result.debugMessage}")
    }

    /** Pro while a purchase of [PRODUCT] is paid for; new ones are acknowledged (Play refunds them otherwise, after 3 days). */
    private suspend fun handle(purchases: List<Purchase>): Boolean {
        val paid = purchases.filter { PRODUCT in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }
        paid.filter { !it.isAcknowledged }.forEach { p ->
            val r = client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build())
            if (r.responseCode != BillingClient.BillingResponseCode.OK) Log.w(TAG, "Couldn't acknowledge the purchase: ${r.debugMessage}")
        }
        val active = paid.isNotEmpty()
        onActive(active)
        return active
    }

    companion object {
        private const val TAG = "Billing"
        const val PRODUCT = "keppo_pro"

        /** Play's page where the rider manages or cancels the subscription. */
        fun manageUrl(packageName: String) = "https://play.google.com/store/account/subscriptions?sku=$PRODUCT&package=$packageName"
    }
}

/** The plans in a subscription's offers: one per base plan, with the free trial when the rider can have it. Pure. */
internal object Plans {
    fun from(details: ProductDetails): List<Billing.Plan> {
        val offers = details.subscriptionOfferDetails.orEmpty()
        return offers.groupBy { it.basePlanId }.mapNotNull { (base, list) ->
            // Play only lists the trial offer when the rider can still have it.
            val offer = list.firstOrNull { o -> o.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L } } ?: list.firstOrNull { it.offerId == null } ?: list.first()
            val phases = offer.pricingPhases.pricingPhaseList
            val paid = phases.lastOrNull { it.priceAmountMicros > 0L } ?: return@mapNotNull null
            val trial = phases.firstOrNull { it.priceAmountMicros == 0L }
            Billing.Plan(base, paid.formattedPrice, paid.priceAmountMicros, paid.priceCurrencyCode, paid.billingPeriod, trial?.let { days(it.billingPeriod) }, offer.offerToken, details)
        }.sortedByDescending { it.yearly }
    }

    /** Days in an ISO 8601 period (P7D, P1W, P1M). */
    fun days(period: String): Int? {
        val m = Regex("P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)W)?(?:(\\d+)D)?").matchEntire(period) ?: return null
        val (y, mo, w, d) = m.destructured
        return (y.toIntOrNull() ?: 0) * 365 + (mo.toIntOrNull() ?: 0) * 30 + (w.toIntOrNull() ?: 0) * 7 + (d.toIntOrNull() ?: 0)
    }
}
