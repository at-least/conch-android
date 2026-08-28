package at.least.conch

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * The Android 15+ dataSync 6-hour cap: when the system calls onTimeout,
 * the service MUST stop (not stopping is a crash —
 * ForegroundServiceDidNotStopInTimeException) and the user must learn why
 * their background protection ended. Pins both.
 */
@RunWith(AndroidJUnit4::class)
class SessionServiceTimeoutRobolectricTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `onTimeout stops the service and posts an explanatory notification`() {
        SessionService.Registry.add("s1", "prod")
        SessionService.Registry.add("s2", "lab")

        val controller = Robolectric.buildService(SessionService::class.java, Intent())
        val service = controller.get()
        controller.create().startCommand(0, 0)

        service.onTimeout(0, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        // registry drained: no lingering sessions claim protection
        assertTrue(SessionService.Registry.isEmpty())

        // the service really stopped
        assertTrue(shadowOf(service).isStoppedBySelf)

        // user-visible explanation, distinct from the ongoing session posts
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = shadowOf(nm).allNotifications
        assertTrue("expected a timeout notification", posted.isNotEmpty())
        val timeoutTitle = posted.mapNotNull {
            it.extras.getCharSequence(Notification.EXTRA_TITLE)
        }
        assertTrue(
            "expected a 'Background protection ended' notification",
            timeoutTitle.any { "Background protection ended" in it },
        )
        SessionService.Registry.clear()
    }

    @Test
    fun `timeout notification is cancellable not ongoing`() {
        SessionService.Registry.add("s1", "prod")
        val controller = Robolectric.buildService(SessionService::class.java, Intent())
        val service = controller.get()
        controller.create().startCommand(0, 0)
        service.onTimeout(0, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val timeoutNotif = shadowOf(nm).allNotifications.last()
        assertFalse("timeout notice must be dismissible", timeoutNotif.flags and Notification.FLAG_ONGOING_EVENT != 0)
        SessionService.Registry.clear()
    }
}
