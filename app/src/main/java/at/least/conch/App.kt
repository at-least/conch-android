package at.least.conch

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Replaces Android's stripped built-in BouncyCastle provider with a full one
 * (needed for X25519/ECDSA key exchange in sshj) and initialises the
 * process-wide singletons that sessions depend on.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        SecretsStore.init(this)
        NetworkWatcher.init(this)
        CrashReporting.init(this)
        AppLock.install(this)
    }
}
