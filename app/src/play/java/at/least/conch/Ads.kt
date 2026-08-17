package at.least.conch

import androidx.compose.runtime.Composable

/**
 * Play build: banner slot on the host list.
 *
 * REPUTATION-FIRST PHASE (deliberate): ads are compiled out until the app
 * has traction — flip ENABLED once the growth threshold is met (see
 * BACKLOG #17). AdMob/Billing wiring notes live there too.
 */
object Ads {
    const val ENABLED = false

    @Composable
    fun Banner() {
        if (!ENABLED) return
        if (PurchaseState.isAdFree()) return
        // TODO(billing): wire AdMob banner here — requires
        //   1. play-services-ads dependency (play flavor only)
        //   2. AdMob app ID in this flavor's manifest meta-data
        //   3. BillingManager to verify the remove-ads purchase, then PurchaseState.setAdFree()
    }
}
