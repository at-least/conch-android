package at.least.conch

import android.content.Context
import android.os.Build
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * End-to-end UI test of the real add-host form ([EditHostActivity]) — the
 * only layer that catches "the form composes but is wired to the wrong
 * field / does not persist". Fills the Compose form and asserts the host and
 * its Android-Keystore-encrypted password actually land in [HostStore] /
 * [SecretsStore]. Needs no network. Cross-activity navigation is deliberately
 * avoided (brittle); the terminal half lives in
 * [TerminalActivityInstrumentedTest].
 *
 * Compose UI testing rides on Espresso, whose `onIdle` calls the hidden
 * `InputManager.getInstance()` removed on API 35+, so this is gated to API ≤
 * 34 — the level CI's instrumented job runs (ci.yml). The gate is an OUTER
 * RuleChain rule so the assumption fires before the compose rule applies (its
 * own teardown would otherwise call the crashing onIdle regardless).
 */
@RunWith(AndroidJUnit4::class)
class AddHostFormInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val compose = createAndroidComposeRule<EditHostActivity>()

    private val apiGate = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue(
                    "Compose/Espresso onIdle needs InputManager.getInstance(), removed on API 35+ (CI runs API 34)",
                    Build.VERSION.SDK_INT < 35,
                )
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(apiGate).around(compose)

    private val alias = "e2e-form-${System.currentTimeMillis()}"

    @After
    fun tearDown() {
        val store = HostStore(context)
        store.load().filter { it.alias == alias }.forEach { SecretsStore.delete("host-pw:${it.id}") }
        store.save(store.load().filterNot { it.alias == alias })
    }

    private fun editField(label: String) = compose.onNode(hasSetTextAction() and hasText(label))

    @Test
    fun `the_add-host_form_persists_the_host_and_its_keystore_password`() {
        // EditHostActivity launched with no hostId → new-host mode
        editField("Host").performScrollTo().performTextInput("example.test")
        editField("Username").performScrollTo().performTextInput("deploy")
        editField("Name").performScrollTo().performTextInput(alias)
        editField("Password").performScrollTo().performTextInput("s3cr3t-pw")
        compose.onNodeWithText("Save").performScrollTo().performClick()

        // the form wrote through to the real stores (Save also finish()es)
        val store = HostStore(context)
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && store.load().none { it.alias == alias }) {
            Thread.sleep(100)
        }
        val saved = store.load().first { it.alias == alias }
        assertEquals("example.test", saved.hostname)
        assertEquals("deploy", saved.username)
        assertEquals(Host.AUTH_PASSWORD, saved.authType)
        assertEquals(
            "password reached the Keystore-backed store",
            "s3cr3t-pw",
            SecretsStore.get("host-pw:${saved.id}"),
        )
    }
}
