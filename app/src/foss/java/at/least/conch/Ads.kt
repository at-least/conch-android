package at.least.conch

import androidx.compose.runtime.Composable

/**
 * FOSS build (F-Droid / direct APK): no ads, ever. No proprietary SDKs.
 */
object Ads {
    const val ENABLED = false

    @Composable
    fun Banner() {
        // intentionally empty — FOSS builds never render ads
    }
}
