package com.example.billing

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
import com.example.data.DialerPreferences
import com.example.data.firebase.DeviceIdentityProvider
import com.example.data.firebase.DeviceTrialRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BillingUiState(
    val isConnected: Boolean = false,
    val isPremiumUser: Boolean = false,
    val formattedPrice: String = "₹299/month",
    val hasFreeTrialOffer: Boolean = true,
    val statusMessage: String? = null
)

/**
 * Handles Google Play Billing Library subscriptions for product ID: monthly_premium_299.
 * Binds purchases with device fingerprint obfuscatedAccountId and syncs premium entitlement
 * to Firestore (/users/{uid} and /device_trials/{deviceHash}) so trials cannot be reused.
 */
class BillingManager(
    context: Context,
    private val prefs: DialerPreferences
) : PurchasesUpdatedListener {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deviceHash: String by lazy {
        prefs.deviceIdHash.ifBlank { DeviceIdentityProvider.getDeviceHash(appContext) }
    }

    private val _billingState = MutableStateFlow(
        BillingUiState(isPremiumUser = prefs.isPremiumUser)
    )
    val billingState: StateFlow<BillingUiState> = _billingState.asStateFlow()

    private var premiumProductDetails: ProductDetails? = null

    private val billingClient: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .enablePrepaidPlans()
                .build()
        )
        .build()

    fun startConnection() {
        if (billingClient.isReady) {
            querySubscriptionProduct()
            queryActivePurchases()
            return
        }

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    _billingState.update { it.copy(isConnected = true, statusMessage = null) }
                    querySubscriptionProduct()
                    queryActivePurchases()
                } else {
                    Log.w(TAG, "Billing setup failed: ${billingResult.debugMessage}")
                    _billingState.update {
                        it.copy(
                            isConnected = false,
                            statusMessage = billingResult.debugMessage.takeIf { msg -> msg.isNotBlank() }
                        )
                    }
                }
            }

            override fun onBillingServiceDisconnected() {
                _billingState.update { it.copy(isConnected = false) }
            }
        })
    }

    fun querySubscriptionProduct() {
        if (!billingClient.isReady) return

        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCT_ID_MONTHLY_PREMIUM)
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product))
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val details = productDetailsList.firstOrNull { it.productId == PRODUCT_ID_MONTHLY_PREMIUM }
                premiumProductDetails = details
                val preferredOffer = selectPreferredOffer(details)
                val paidPhase = preferredOffer
                    ?.pricingPhases
                    ?.pricingPhaseList
                    ?.lastOrNull { it.priceAmountMicros > 0L }
                val hasFreeTrial = preferredOffer
                    ?.pricingPhases
                    ?.pricingPhaseList
                    ?.any { it.priceAmountMicros == 0L } == true

                val formattedPrice = paidPhase?.formattedPrice
                if (!formattedPrice.isNullOrBlank()) {
                    _billingState.update {
                        it.copy(
                            formattedPrice = "$formattedPrice/month",
                            hasFreeTrialOffer = hasFreeTrial
                        )
                    }
                }
            } else {
                Log.w(TAG, "queryProductDetailsAsync failed: ${billingResult.debugMessage}")
            }
        }
    }

    fun queryActivePurchases() {
        if (!billingClient.isReady) return

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val validPurchases = purchases.filter { purchase ->
                    purchase.products.contains(PRODUCT_ID_MONTHLY_PREMIUM) &&
                        purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                }
                if (validPurchases.isNotEmpty()) {
                    validPurchases.forEach { handleVerifiedPurchase(it) }
                }
            }
        }
    }

    fun launchSubscriptionFlow(activity: Activity): Boolean {
        val details = premiumProductDetails
        if (!billingClient.isReady || details == null) {
            if (!billingClient.isReady) startConnection()
            return false
        }

        val offerToken = selectPreferredOffer(details)?.offerToken
        if (offerToken.isNullOrBlank()) {
            Log.w(TAG, "No subscription offerToken available for $PRODUCT_ID_MONTHLY_PREMIUM")
            return false
        }

        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offerToken)
            .build()

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .setObfuscatedAccountId(deviceHash)
            .build()

        val result = billingClient.launchBillingFlow(activity, billingFlowParams)
        return result.responseCode == BillingClient.BillingResponseCode.OK
    }

    /**
     * Prevents Play Billing free-trial reuse on devices that already consumed their 7-day trial:
     * - If trial already used or expired on this device, selects the paid ₹299/month base offer directly.
     * - Otherwise prefers an offer containing a free trial pricing phase if available.
     */
    private fun selectPreferredOffer(details: ProductDetails?): ProductDetails.SubscriptionOfferDetails? {
        val offers = details?.subscriptionOfferDetails ?: return null
        val trialAlreadyUsedOrExpired = prefs.isTrialExpired() || prefs.trialBlockedByDevice
        if (trialAlreadyUsedOrExpired) {
            return offers.firstOrNull { offer ->
                offer.pricingPhases.pricingPhaseList.none { it.priceAmountMicros == 0L }
            } ?: offers.firstOrNull()
        }
        return offers.firstOrNull { offer ->
            offer.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L }
        } ?: offers.firstOrNull()
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    if (purchase.products.contains(PRODUCT_ID_MONTHLY_PREMIUM)) {
                        handleVerifiedPurchase(purchase)
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                _billingState.update { it.copy(statusMessage = "Purchase cancelled") }
            }
            else -> {
                _billingState.update {
                    it.copy(
                        statusMessage = billingResult.debugMessage.ifBlank {
                            "Billing error ${billingResult.responseCode}"
                        }
                    )
                }
            }
        }
    }

    private fun handleVerifiedPurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return

        if (!purchase.isAcknowledged) {
            val ackParams = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            billingClient.acknowledgePurchase(ackParams) { ackResult ->
                if (ackResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    markUserAsPremium(purchase.purchaseToken)
                }
            }
        } else {
            markUserAsPremium(purchase.purchaseToken)
        }
    }

    private fun markUserAsPremium(purchaseToken: String) {
        scope.launch {
            val result = runCatching {
                DeviceTrialRepository(appContext).recordPremiumPurchase(
                    deviceHash = deviceHash,
                    productId = PRODUCT_ID_MONTHLY_PREMIUM,
                    purchaseToken = purchaseToken
                )
            }.getOrElse { Result.failure(it) }
            if (result.isSuccess) {
                prefs.isPremiumUser = true
                _billingState.update {
                    it.copy(isPremiumUser = true, statusMessage = "Premium unlocked!")
                }
            } else {
                _billingState.update {
                    it.copy(statusMessage = "Please complete UPI verification in Account for admin approval.")
                }
            }
        }
    }

    fun clearStatusMessage() {
        _billingState.update { it.copy(statusMessage = null) }
    }

    fun endConnection() {
        scope.cancel()
        if (billingClient.isReady) {
            billingClient.endConnection()
        }
    }

    companion object {
        private const val TAG = "BillingManager"
        const val PRODUCT_ID_MONTHLY_PREMIUM = "monthly_premium_299"
    }
}
