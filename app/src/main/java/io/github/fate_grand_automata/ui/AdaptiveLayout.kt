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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Test seam for rendering the uncapped, single-column baseline. Defaults to `true` and the app
 * never overrides it: width caps and the two-column battle config are always on, with no setting.
 */
val LocalAdaptiveLayout = compositionLocalOf { true }

internal const val CappedContentTag = "capped_content"

/** Centres content within [maxWidth], leaving the window background at its full width. */
@Composable
fun CappedWidth(
    modifier: Modifier = Modifier,
    maxWidth: Dp = 1280.dp,
    content: @Composable BoxScope.() -> Unit
) {
    if (!LocalAdaptiveLayout.current) {
        Box(modifier = modifier.fillMaxSize(), content = content)
        return
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .fillMaxSize()
                .testTag(CappedContentTag),
            content = content
        )
    }
}

/**
 * Lays a screen out in one column when the space it has is Compact (below 600 dp) and in two
 * columns from Medium up. [content] gets `true` for two columns. The content is centred and capped
 * at 1280 dp; phones keep the full width. The size class comes from the space the screen is given,
 * which is the window for a full-screen destination, so rotation and window resizing just
 * recompose and keep state held in `remember`/`rememberSaveable`.
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

        CappedWidth {
            content(twoColumns)
        }
    }
}
