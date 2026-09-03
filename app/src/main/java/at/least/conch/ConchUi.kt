package at.least.conch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared pieces of the app's iOS grouped-list look (Settings/Files/Mail): a
 * gray canvas, white inset cards holding rows with hairline dividers, and
 * flat (untinted) top bars. Pulled out once every list-shaped screen needed
 * the exact shapes/colors [MainActivity] established, so screens can't
 * drift a few dp apart from each other.
 */

/** Metrics every grouped-list screen shares, so a new screen can't guess them. */
object GroupedListDefaults {
    /** Page inset around a grouped list: cards sit in from the screen edge. */
    val PagePadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

    /** Gap between one section's card and the next section's header. */
    val SectionSpacing = 24.dp

    /** Divider inset for a plain row — under the text, clear of the edge. */
    val TextDividerInset = 16.dp

    /**
     * Divider inset for a row led by an [IconTile] — derived from the tile's
     * width plus the [androidx.compose.material3.ListItem] gutters, so it
     * lands under the text rather than under the tile.
     */
    val IconRowDividerInset = 60.dp

    /** As [IconRowDividerInset], for a row led by a bare glyph (no tile). */
    val GlyphRowDividerInset = 56.dp
}

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
 * The rounded, flat surface a grouped card is drawn on. [shape] is only
 * passed by the lazy list, where each row is its own surface and the
 * rounding belongs to the first and last row rather than the whole card.
 */
@Composable
fun FlatCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
        content = content,
    )
}

/**
 * The hairline between two rows of a grouped card. [inset] aligns it under
 * the row's text, which varies with what leads the row — see
 * [GroupedListDefaults].
 */
@Composable
fun RowDivider(inset: Dp = GroupedListDefaults.TextDividerInset) {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(start = inset),
    )
}

/**
 * Rows inside a [GroupedCard] must cancel [androidx.compose.material3.ListItem]'s
 * own container color: the card already paints the surface, and a row that
 * keeps its default paints a second one on top of it.
 */
@Composable
fun groupedRowColors(): ListItemColors =
    ListItemDefaults.colors(containerColor = Color.Transparent)

/**
 * One rounded, flat card holding [count] rows with hairline dividers
 * between them (skipped after the last row) — the iOS grouped-table-view
 * card, as opposed to an elevated Material [androidx.compose.material3.Card]
 * per row. [dividerInset] aligns the divider under each row's text, which
 * varies with what leads the row (an icon tile, a switch, nothing).
 *
 * This composes every row eagerly, so it is for cards with a fixed, small
 * set of rows. A card backed by a data list belongs in [groupedItems], which
 * gives the same look while letting the list virtualize.
 */
@Composable
fun GroupedCard(
    count: Int,
    modifier: Modifier = Modifier,
    dividerInset: Dp = GroupedListDefaults.TextDividerInset,
    row: @Composable (index: Int) -> Unit,
) {
    FlatCard(modifier) {
        Column {
            for (index in 0 until count) {
                row(index)
                if (index != count - 1) RowDivider(dividerInset)
            }
        }
    }
}

/**
 * [GroupedCard] for a card whose rows are known at the call site. The rows
 * carry their own count, so there is no hand-written `count` to fall out of
 * step with a `when (index)` chain.
 */
@Composable
fun GroupedCard(
    rows: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier,
    dividerInset: Dp = GroupedListDefaults.TextDividerInset,
) {
    GroupedCard(rows.size, modifier, dividerInset) { index -> rows[index]() }
}

/**
 * A grouped card whose rows are real lazy-list items, so a long list only
 * composes what's on screen. Each row is its own [FlatCard] with the corners
 * that its position implies; abutting at zero spacing they read as the one
 * rounded card [GroupedCard] draws.
 *
 * The enclosing `LazyColumn` must not add spacing between items — the rows of
 * a card touch. Space sections apart with padding on the section header
 * instead.
 */
fun LazyListScope.groupedItems(
    count: Int,
    key: (index: Int) -> Any,
    dividerInset: Dp = GroupedListDefaults.TextDividerInset,
    row: @Composable (index: Int) -> Unit,
) {
    items(count, key = { index -> key(index) }) { index ->
        val shape = when {
            count == 1 -> MaterialTheme.shapes.medium
            index == 0 -> MaterialTheme.shapes.medium.copy(
                bottomStart = ZeroCornerSize,
                bottomEnd = ZeroCornerSize,
            )
            index == count - 1 -> MaterialTheme.shapes.medium.copy(
                topStart = ZeroCornerSize,
                topEnd = ZeroCornerSize,
            )
            else -> RectangleShape
        }
        FlatCard(shape = shape) {
            Column {
                row(index)
                if (index != count - 1) RowDivider(dividerInset)
            }
        }
    }
}

/**
 * The tinted, rounded glyph tile that leads a grouped row (the
 * Settings/Contacts look). Shared so the tile's size, corner radius and
 * glyph size stay one decision rather than five.
 */
@Composable
fun IconTile(
    icon: ImageVector,
    tint: Color = MaterialTheme.colorScheme.secondary,
    contentDescription: String? = null,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(17.dp),
        )
    }
}

/**
 * Empty state: says what a screen is for and, when there is one obvious way
 * to fill it, offers that action. Pass a null [actionLabel] for the states
 * with nothing to offer (a search that matched nothing).
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        if (body != null) {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.padding(top = 24.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(actionLabel)
            }
        }
    }
}

/**
 * The app's search field (iOS `.searchable` parity). Styled after
 * `UISearchBar` — a filled gray capsule with no visible border and a gray
 * leading glyph — rather than Material's outlined/focus-color field, with a
 * clear button once there is something to clear.
 */
@Composable
fun ConchSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        leadingIcon = {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Filled.Clear,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            null
        },
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = modifier,
    )
}

/**
 * The screen's primary "add" action. It lives in the nav bar rather than a
 * floating button, and its primary tint is what makes it read as the primary
 * action rather than one more toolbar glyph — so both live here once.
 */
@Composable
fun TopBarAddButton(contentDescription: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            Icons.Filled.Add,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.primary,
        )
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
