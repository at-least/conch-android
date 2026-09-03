package at.least.conch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer

/**
 * Third-party OSS licenses: the dependency tree (generated at build time)
 * plus the bundled font, which is a resource rather than a dependency and
 * so has to be credited by hand.
 */
class LicensesActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ConchTheme { LicensesScreen() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun LicensesScreen() {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Open source licenses") },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = flatTopAppBarColors(),
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            var showFontLicense by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxSize().padding(padding)) {
                ListItem(
                    modifier = Modifier.clickable { showFontLicense = true },
                    headlineContent = { Text("JetBrains Mono Nerd Font") },
                    supportingContent = { Text("SIL Open Font License 1.1 — bundled terminal font") },
                )
                HorizontalDivider()
                LibrariesContainer(modifier = Modifier.fillMaxSize())
            }
            if (showFontLicense) {
                val text = remember {
                    resources.openRawResource(R.raw.jetbrains_mono_ofl).bufferedReader().use { it.readText() }
                }
                AlertDialog(
                    onDismissRequest = { showFontLicense = false },
                    confirmButton = { TextButton(onClick = { showFontLicense = false }) { Text("Close") } },
                    title = { Text("JetBrains Mono Nerd Font") },
                    text = {
                        Text(
                            text,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.verticalScroll(rememberScrollState()).padding(end = 4.dp),
                        )
                    },
                )
            }
        }
    }
}
