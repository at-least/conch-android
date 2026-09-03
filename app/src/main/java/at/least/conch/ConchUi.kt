package at.least.conch

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared pieces of the app's iOS grouped-list look (Settings/Files/Mail): a
 * gray canvas, white inset cards holding rows with hairline dividers, and
 * flat (untinted) top bars. Pulled out once every list-shaped screen needed
 * the exact shapes/colors [MainActivity] established, so screens can't
 * drift a few dp apart from each other.
 */

/** iOS grouped-list section heading: small, gray, uppercase. */
@Composable
fun GroupHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 20.dp, bottom = 6.dp),
    )
}

/**
 * One rounded, flat card holding [count] rows with hairline dividers
 * between them (skipped after the last row) — the iOS grouped-table-view
 * card, as opposed to an elevated Material [androidx.compose.material3.Card]
 * per row. [dividerInset] aligns the divider under each row's text, which
 * varies with what leads the row (an icon tile, a switch, nothing).
 */
@Composable
fun GroupedCard(
    count: Int,
    modifier: Modifier = Modifier,
    dividerInset: Dp = 16.dp,
    row: @Composable (index: Int) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            for (index in 0 until count) {
                row(index)
                if (index != count - 1) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = dividerInset),
                    )
                }
            }
        }
    }
}

/**
 * Flat top-bar colors: container stays the plain background in both the
 * resting and scrolled states. Material's own defaults tint the bar once
 * content scrolls under it, which reads as a colored panel rather than the
 * plain nav bar this app is going for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun flatTopAppBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.background,
    scrolledContainerColor = MaterialTheme.colorScheme.background,
)

/** [flatTopAppBarColors] for [androidx.compose.material3.LargeTopAppBar]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun flatLargeTopAppBarColors(): TopAppBarColors = TopAppBarDefaults.largeTopAppBarColors(
    containerColor = MaterialTheme.colorScheme.background,
    scrolledContainerColor = MaterialTheme.colorScheme.background,
)
