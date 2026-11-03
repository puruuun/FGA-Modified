package io.github.fate_grand_automata.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Whether [CappedWidth] and [AdaptiveColumns] adapt to the window. The layout always adapts, so
 * the default is `true` and the app never sets this. It exists only so tests can render the
 * legacy full-width single-column path with `false` and check that a Compact window looks the
 * same either way.
 */
val LocalAdaptiveLayout = compositionLocalOf { true }

/** Widest a single-column screen gets before the content is centred instead of stretched. */
val ContentMaxWidth = 840.dp

/** Widest a two-column screen gets. */
val TwoColumnMaxWidth = 1280.dp

/**
 * Centres [content] horizontally and limits it to [maxWidth]. Windows narrower than that, phones
 * included, get the full width, so they look the same as without this wrapper.
 */
@Composable
fun CappedWidth(
    modifier: Modifier = Modifier,
    maxWidth: Dp = ContentMaxWidth,
    content: @Composable BoxScope.() -> Unit
) {
    if (!LocalAdaptiveLayout.current) {
        Box(modifier = modifier.fillMaxSize(), content = content)
        return
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .fillMaxSize(),
            content = content
        )
    }
}

/**
 * Lays a screen out in one column when the space it has is Compact (below 600 dp) and in two
 * columns from Medium up. [content] gets `true` for two columns. The width is capped at
 * [TwoColumnMaxWidth] then, and at [ContentMaxWidth] in one column. The size class comes from
 * the space the screen is given, which is the window for a full-screen destination, so rotation
 * and window resizing just recompose and keep state held in `remember`/`rememberSaveable`.
 */
@Composable
fun AdaptiveColumns(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(twoColumns: Boolean) -> Unit
) {
    val enabled = LocalAdaptiveLayout.current

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val sizeClass = WindowSizeClass.calculateFromSize(DpSize(maxWidth, maxHeight))
        val twoColumns = enabled && sizeClass.widthSizeClass != WindowWidthSizeClass.Compact
        CappedWidth(maxWidth = if (twoColumns) TwoColumnMaxWidth else ContentMaxWidth) {
            content(twoColumns)
        }
    }
}
