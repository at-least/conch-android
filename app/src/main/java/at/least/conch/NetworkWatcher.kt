package at.least.conch

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Source of the "a usable network is back" signal, as [SessionReconnector]
 * consumes it. The interface exists so the reconnector's own tests can drive
 * the signal without Android; [NetworkWatcher] is the real implementation.
 */
interface NetworkSignal {
    fun interface Listener {
        /** @return true if this listener acted on the signal. */
        fun onNetworkAvailable(): Boolean
    }

    fun addListener(listener: Listener)

    fun removeListener(listener: Listener)
}

/**
 * Process-wide "a usable network is back" signal for live sessions.
 *
 * Mobile networks drop and hand over constantly — the exponential backoff
 * ([ReconnectPolicy]) climbs to a 30s cap while the device is offline, so
 * without this the terminal can stay dark for half a minute AFTER the radio
 * is already up. Every [SessionReconnector] subscribes itself for its own
 * lifetime and pulls its pending retry forward; the scheduler makes that a
 * no-op unless a retry is actually waiting, so a burst of handover callbacks
 * costs nothing.
 *
 * One platform callback for the whole process, registered with the first
 * listener and unregistered with the last (`ACCESS_NETWORK_STATE`).
 * Callbacks are delivered on the main looper, so listeners may touch UI
 * state directly. [init] seeds the application context from [App], the same
 * way `SecretsStore.init` does.
 */
object NetworkWatcher : NetworkSignal {

    private val listeners = CopyOnWriteArrayList<NetworkSignal.Listener>()

    @Volatile
    private var appContext: Context? = null

    private var manager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    @Synchronized
    override fun addListener(listener: NetworkSignal.Listener) {
        listeners.addIfAbsent(listener)
        if (callback != null) return
        val cm = appContext?.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            // Also fires right after registration when a default network
            // already exists; harmless, since a retry is only pulled forward
            // when one is actually waiting.
            override fun onAvailable(network: Network) = dispatch()
        }
        try {
            cm.registerDefaultNetworkCallback(cb, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            // A denied/unavailable connectivity service must never take a
            // session down with it — reconnects just fall back to backoff.
            CrashReporting.report(e)
            return
        }
        manager = cm
        callback = cb
    }

    @Synchronized
    override fun removeListener(listener: NetworkSignal.Listener) {
        listeners.remove(listener)
        if (listeners.isNotEmpty()) return
        val cb = callback ?: return
        try {
            manager?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
        callback = null
        manager = null
    }

    /** Fan out to every live session; one throwing listener must not silence the rest. */
    @VisibleForTesting
    fun dispatch() {
        listeners.forEach { runCatching { it.onNetworkAvailable() } }
    }

    @VisibleForTesting
    fun resetCache() {
        listeners.clear()
        callback = null
        manager = null
    }
}
