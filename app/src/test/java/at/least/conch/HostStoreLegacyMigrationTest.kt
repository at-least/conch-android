package at.least.conch

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * HostStore legacy plaintext-password migration — previously untestable on
 * the JVM because it writes through SecretsStore (Android Keystore).
 * mockkObject(SecretsStore) swaps the singleton for an in-memory map, the
 * first genuinely-mocked seam in this suite (everything else runs against
 * real files or a real in-process sshd).
 */
class HostStoreLegacyMigrationTest {

    private fun newContext(): Pair<File, Context> {
        val dir = Files.createTempDirectory("conch-legacy").toFile()
        val context = mockk<Context> {
            every { filesDir } returns dir
        }
        return File(dir, "hosts.json") to context
    }

    @Test
    fun `legacy plaintext password migrates into SecretsStore and the re-save drops the field`() {
        val (file, context) = newContext()
        file.writeText(
            """[{"id":"h1","hostname":"h.example.com","username":"u","password":"legacy-secret"}]"""
        )
        val secrets = mutableMapOf<String, String>()

        mockkObject(SecretsStore) {
            every { SecretsStore.get(any()) } answers { secrets[firstArg()] }
            every { SecretsStore.put(any(), any()) } answers { secrets[firstArg()] = secondArg() }

            val hosts = HostStore(context).load()
            assertEquals(1, hosts.size)
            assertEquals("h1", hosts[0].id)
            assertEquals("legacy-secret", secrets["host-pw:h1"])
        }

        val rewritten = file.readText()
        assertFalse("plaintext must not survive in hosts.json", rewritten.contains("legacy-secret"))
        assertFalse("password field must be dropped on re-save", rewritten.contains("\"password\""))
        assertTrue(rewritten.contains("\"hostname\":\"h.example.com\""))
    }

    @Test
    fun `existing keystore secret is never overwritten by a legacy password`() {
        val (file, context) = newContext()
        file.writeText(
            """[{"id":"h1","hostname":"h","username":"u","password":"stale-legacy"}]"""
        )

        mockkObject(SecretsStore) {
            every { SecretsStore.get(any()) } returns "already-stored"
            every { SecretsStore.put(any(), any()) } answers { throw IllegalStateException("must not overwrite") }

            val hosts = HostStore(context).load()
            assertEquals(1, hosts.size)
            verify(exactly = 0) { SecretsStore.put(any(), any()) }
        }
    }

    @Test
    fun `file without legacy passwords is not rewritten`() {
        val (file, context) = newContext()
        file.writeText("""[{"id":"h1","hostname":"h","username":"u"}]""")
        val before = file.readText()

        mockkObject(SecretsStore) {
            every { SecretsStore.get(any()) } returns null
            every { SecretsStore.put(any(), any()) } answers { throw IllegalStateException("no migration expected") }

            val hosts = HostStore(context).load()
            assertEquals(1, hosts.size)
        }
        assertEquals(before, file.readText())
    }
}
