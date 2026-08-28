package at.least.conch

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The foreground [SessionService] on a real Android: startForegroundService
 * → the ongoing per-session notification is really posted, the STOP action
 * removes it, and the registry drains. Robolectric shadows the
 * NotificationManager; this is the platform's own.
 */
@RunWith(AndroidJUnit4::class)
class SessionServiceInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val notifications: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= 33) {
            GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            GrantPermissionRule.grant()
        }

    private val nm get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun awaitTrue(message: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError(message)
    }

    @After
    fun tearDown() {
        SessionService.stop(context, "dev-a")
        SessionService.stop(context, "dev-b")
        SessionService.Registry.clear()
    }

    @Test
    fun `starting two sessions posts two ongoing notifications and stopping removes them`() {
        SessionService.start(context, "dev-a", "alpha")
        SessionService.start(context, "dev-b", "beta")
        awaitTrue("session notifications not posted: ${nm.activeNotifications.map { it.id }}") {
            nm.activeNotifications.count { it.isOngoing } >= 2
        }
        assertTrue(SessionService.Registry.entries().map { it.second }.containsAll(listOf("alpha", "beta")))

        SessionService.stop(context, "dev-a")
        awaitTrue("alpha's notification not removed") {
            nm.activeNotifications.count { it.isOngoing } == 1
        }
        SessionService.stop(context, "dev-b")
        awaitTrue("beta's notification not removed") {
            nm.activeNotifications.none { it.isOngoing }
        }
        awaitTrue("registry not drained") { SessionService.Registry.isEmpty() }
    }
}
