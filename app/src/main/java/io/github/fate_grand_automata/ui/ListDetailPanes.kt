package io.github.fate_grand_automata.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.ui.main.ListPaneWidth

/** True inside the list pane of [ListDetailPanes], where the list is a column and not a screen. */
val LocalInListPane = compositionLocalOf { false }

private const val DetailFadeMillis = 120

private class DetailPane(val key: Any?, val content: @Composable () -> Unit) {
    override fun equals(other: Any?) = other is DetailPane && other.key == key
    override fun hashCode() = key.hashCode()
}

/**
 * The list on the left in a [ListPaneWidth] column, a divider, and [detail] filling the rest. With
 * no [detail] the right pane shows a hint to pick an item. [detailKey] identifies what [detail]
 * shows: when it changes, only the right pane crossfades, and the list is not touched.
 */
@Composable
fun ListDetailPanes(
    list: @Composable () -> Unit,
    detail: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    detailKey: Any? = null
) {
    Row(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .width(ListPaneWidth)
                .fillMaxHeight()
        ) {
            CompositionLocalProvider(LocalInListPane provides true) {
                list()
            }
        }

        VerticalDivider()

        val pane = DetailPane(
            key = if (detail == null) null else detailKey ?: Unit,
            content = detail ?: { PickHint() }
        )

        AnimatedContent(
            targetState = pane,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            transitionSpec = {
                fadeIn(tween(DetailFadeMillis)) togetherWith fadeOut(tween(DetailFadeMillis))
            },
            label = "ListDetailPanes"
        ) {
            // the pane that is fading out keeps its own content; the current one uses the latest
            if (it == pane) pane.content() else it.content()
        }
    }
}

@Composable
private fun PickHint() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.battle_config_list_pick_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp)
        )
    }
}
