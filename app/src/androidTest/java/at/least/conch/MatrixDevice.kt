package at.least.conch

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.net.InetSocketAddress
import java.net.Socket

/**
 * On-device view of the Docker sshd matrix (tools/sshd-matrix/run.sh) that
 * runs on the development machine / CI runner. The emulator reaches the
 * host at 10.0.2.2; a physical device needs -Pconch.matrixHost=<lan ip>
 * (and run.sh binding to that interface).
 *
 * Same opt-in semantics as the JVM DockerMatrix: without
 * -Pconch.localSshdTest=true an unreachable matrix skips; with it, it fails.
 */
object MatrixDevice {
    const val PW_AND_KEY_PORT = 2233
    const val KEY_ONLY_PORT = 2234

    private val args get() = InstrumentationRegistry.getArguments()

    val host: String get() = args.getString("conchMatrixHost") ?: "10.0.2.2"

    fun optedIn(): Boolean = args.getString("conchLocalSshdTest") == "true"

    fun reachable(port: Int = PW_AND_KEY_PORT): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 2_000) }
        true
    } catch (_: Exception) {
        false
    }

    fun requireMatrix() {
        val up = reachable()
        if (optedIn()) {
            assertTrue("sshd matrix not reachable at $host:$PW_AND_KEY_PORT from the device", up)
        } else {
            assumeTrue("sshd matrix not reachable at $host:$PW_AND_KEY_PORT (tools/sshd-matrix/run.sh)", up)
        }
    }

    /** TOFU prompt that accepts any unknown host key. */
    val acceptPrompt: KeyPrompt = { _, done -> done(true) }

    fun passwordHost(alias: String = "matrix", safExpose: Boolean = false) = Host(
        alias = alias,
        hostname = host,
        username = "pwuser",
        authType = Host.AUTH_PASSWORD,
        tmuxAutoAttach = false,
        safExpose = safExpose,
    ).apply { port = PW_AND_KEY_PORT }
}
