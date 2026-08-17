package at.least.conch

import android.content.Context
import android.content.SharedPreferences
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import io.sentry.SentryOptions

/**
 * Opt-in crash reporting via a self-hosted Sentry server.
 *
 * Privacy rules for an SSH client:
 *  - OFF by default; nothing is sent until the user enables it in settings.
 *  - If no DSN was compiled in, stays disabled regardless of the toggle.
 *  - No PII, no session tracking, no tracing/breadcrumbs — crash stacks only.
 *  - BeforeSend scrubs hostnames/ports/endpoints from messages and tags
 *    (SshConnectionFactory error strings embed host:port).
 */
object CrashReporting {

    private const val PREFS = "conchapp_settings"
    private const val KEY_ENABLED = "crashReportsEnabled"

    private lateinit var prefs: SharedPreferences

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (isAvailable() && isEnabled()) initSdk()
    }

    private fun initSdk() {
        SentryAndroid.init(appContext) { options ->
            options.dsn = BuildConfig.SENTRY_DSN
            options.isEnableAutoSessionTracking = false
            options.tracesSampleRate = 0.0
            options.isSendDefaultPii = false
            options.isAttachThreads = false
            options.isAttachStacktrace = true
            options.setBeforeBreadcrumb { _, _ -> null } // drop all breadcrumbs
            options.setBeforeSend { event, _ -> scrub(event) }
        }
    }

    /** True when a DSN was compiled into this build. */
    fun isAvailable(): Boolean = BuildConfig.SENTRY_DSN.isNotBlank()

    fun isEnabled(): Boolean = isAvailable() && prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        if (isAvailable()) {
            if (on) {
                initSdk()
            } else {
                Sentry.close()
            }
        }
    }

    /** Captures a non-fatal error (host details already scrubbed by beforeSend). */
    fun report(t: Throwable) {
        if (isEnabled()) Sentry.captureException(t)
    }

    // ---------------------------------------------------------------- scrub

    /** Pure, unit-testable host-detail scrubber. */
    object Scrubber {
        private val IP_PORT = Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?\\b")
        private val HOST_PORT = Regex("\\b([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}(:\\d+)?\\b")
        private val BRACKET_HOST = Regex("\\[[^\\]]+]:\\d+")

        fun scrub(text: String?): String? = text?.let {
            it.replace(BRACKET_HOST, "[host]:port")
                .replace(IP_PORT, "host:port")
                .replace(HOST_PORT, "host:port")
        }

        fun scrubThrowable(source: Throwable): Throwable = ScrubbedThrowable(source)

        /** Wraps a throwable, masking host:port patterns in messages (stack frames untouched). */
        private class ScrubbedThrowable(source: Throwable) : Throwable(
            scrub(source.message),
            source.cause?.let { ScrubbedThrowable(it) },
        ) {
            init {
                stackTrace = source.stackTrace
            }
        }
    }

    private fun scrub(event: io.sentry.SentryEvent): io.sentry.SentryEvent? {
        event.message?.let { m ->
            val s = m.message
            if (s != null) m.message = Scrubber.scrub(s)
        }
        event.throwable?.let { t ->
            event.throwable = Scrubber.scrubThrowable(t)
        }
        event.tags?.keys?.toList()?.forEach { k ->
            event.setTag(k, Scrubber.scrub(event.tags!![k]) ?: "scrubbed")
        }
        event.extras?.keys?.toList()?.forEach { k ->
            (event.extras!![k] as? String)?.let { v -> event.setExtra(k, Scrubber.scrub(v) ?: v) }
        }
        return event
    }
}
