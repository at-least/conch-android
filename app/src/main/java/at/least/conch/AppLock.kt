package at.least.conch

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity

/**
 * App-lock via BiometricPrompt (fingerprint / face, device-credential
 * fallback). OFF by default; enabled from Settings. Applies to activities
 * that extend FragmentActivity (MainActivity / TerminalActivity gates).
 */
object AppLock {

    /** iOS parity: app lock ships OFF; users opt in from Settings. */
    const val DEFAULT_ENABLED = false

    /** iOS parity: 30s unlock grace window (activity switches don't re-prompt). */
    internal const val GRACE_MS = 30_000L

    /**
     * Pure grace-window check (iOS AppLockTests parity): 0 means
     * locked/relocked, otherwise unlocked for [GRACE_MS] after the timestamp.
     */
    fun withinGrace(unlockedSinceMs: Long, nowMs: Long): Boolean =
        unlockedSinceMs > 0 && nowMs - unlockedSinceMs < GRACE_MS

    fun isEnabled(context: Context): Boolean = SettingsStore.appLockEnabled(context)

    fun setEnabled(context: Context, on: Boolean) {
        SettingsStore.setAppLockEnabled(context, on)
    }

    /** Whether this device can authenticate (biometrics or lock-screen credential). */
    fun canAuthenticate(context: Context): Boolean {
        val bm = BiometricManager.from(context)
        return bm.canAuthenticate(authenticators()) == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun authenticators(): Int =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    private var unlockedSince = 0L

    /**
     * Shows the biometric gate. Once unlocked, stays unlocked for
     * [GRACE_MS] so switching activities doesn't re-prompt constantly;
     * re-locks after going to the background beyond the grace window.
     */
    fun lockIfNeeded(activity: FragmentActivity) {
        if (!isEnabled(activity) || !canAuthenticate(activity)) return
        if (withinGrace(unlockedSince, System.currentTimeMillis())) return
        val prompt = BiometricPrompt(
            activity,
            androidx.core.content.ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlockedSince = System.currentTimeMillis()
                }

                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    // cancelled / too many failures -> close the app
                    activity.finishAffinity()
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Conch is locked")
            .setSubtitle("Confirm it's you to access your servers")
            .setAllowedAuthenticators(authenticators())
            .build()
        prompt.authenticate(info)
    }

    fun onWentToBackground() {
        unlockedSince = 0L
    }
}
