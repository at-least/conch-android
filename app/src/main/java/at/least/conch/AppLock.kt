package at.least.conch

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
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
                    // ERROR_CANCELED is the SYSTEM withdrawing the prompt
                    // (incoming call, rotation, app sent to background) —
                    // the next onStart re-prompts. Anything the user did
                    // (cancel, negative button) or a lockout closes the app.
                    if (code == BiometricPrompt.ERROR_CANCELED) return
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

    /**
     * Re-locks only when the whole app leaves the foreground. Android orders
     * an activity switch as `A.onPause → B.onStart → A.onStop`, so a
     * per-activity onStop hook zeroed the grace window right after the
     * next screen had passed its check — every Main↔Terminal/Settings hop
     * (and every rotation of Main) re-prompted, which is exactly what the
     * grace window exists to prevent.
     */
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                started++
            }

            override fun onActivityStopped(activity: Activity) {
                started--
                if (started <= 0) {
                    started = 0
                    onWentToBackground()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
