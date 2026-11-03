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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Whether [AdaptiveColumns] may use two columns. Defaults to `true` and the app never sets it:
 * the two-column battle config is always on. It exists only so tests can render the legacy
 * single-column path and compare it with the default.
 */
val LocalAdaptiveLayout = compositionLocalOf { true }

/** Widest a two-column screen gets before the content is centred instead of stretched. */
private val TwoColumnMaxWidth = 1280.dp

/** Widest a single-column screen gets before the content is centred instead of stretched. */
private val OneColumnMaxWidth = 840.dp

/**
 * Lays a screen out in one column when the space it has is Compact (below 600 dp) and in two
 * columns from Medium up. [content] gets `true` for two columns. The content is centred and capped
 * at 1280 dp in two columns and at 840 dp in one column (Compact windows are narrower than that, so
 * phones keep the full width). The size class comes from the space the screen is given, which is
 * the window for a full-screen destination, so rotation and window resizing just recompose and
 * keep state held in `remember`/`rememberSaveable`.
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

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = if (twoColumns) TwoColumnMaxWidth else OneColumnMaxWidth)
                    .fillMaxSize()
            ) {
                content(twoColumns)
            }
        }
    }
}
