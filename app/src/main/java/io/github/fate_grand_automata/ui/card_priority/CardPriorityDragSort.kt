package io.github.fate_grand_automata.ui.card_priority

import androidx.annotation.ColorRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.colorResource
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.scripts.enums.CardAffinityEnum
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.scripts.models.CardScore
import io.github.fate_grand_automata.scripts.models.ServantCardKey
import io.github.fate_grand_automata.ui.drag_sort.DragSort
import io.github.fate_grand_automata.ui.drag_sort.DragSortItemStyle

@Composable
fun CardPriorityDragSort(
    scores: SnapshotStateList<CardScore>
) {
    DragSort(
        items = scores,
        key = { "${it.type}-${it.affinity}" },
        style = {
            val colors = cardTileColors(it.type, it.affinity)
            DragSortItemStyle(
                backgroundColor = colors.background,
                contentColor = colors.content,
                text = it.toString()
            )
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun ServantCardDragSort(
    cards: SnapshotStateList<ServantCardKey>
) {
    DragSort(
        items = cards,
        key = { it.toString() },
        style = {
            val colors = cardTileColors(it.type, CardAffinityEnum.Normal)
            DragSortItemStyle(
                backgroundColor = colors.background,
                contentColor = colors.content,
                text = it.toString()
            )
        },
        modifier = Modifier.fillMaxWidth()
    )
}

/** Tile colours on the row panel, in the current theme. */
@Composable
private fun cardTileColors(type: CardTypeEnum, affinity: CardAffinityEnum): CardTileColors =
    cardTileColors(
        type,
        affinity,
        panel = MaterialTheme.colorScheme.surfaceContainer,
        dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    ) ?: CardTileColors(colorResource(R.color.colorPrimaryDark), Color.White)

@ColorRes
fun CardScore.getColorRes(): Int {
    return when (type) {
        CardTypeEnum.Buster -> when (affinity) {
            CardAffinityEnum.Weak -> R.color.colorBusterWeak
            CardAffinityEnum.Normal -> R.color.colorBuster
            CardAffinityEnum.Resist -> R.color.colorBusterResist
        }
        CardTypeEnum.Arts -> when (affinity) {
            CardAffinityEnum.Weak -> R.color.colorArtsWeak
            CardAffinityEnum.Normal -> R.color.colorArts
            CardAffinityEnum.Resist -> R.color.colorArtsResist
        }
        CardTypeEnum.Quick -> when (affinity) {
            CardAffinityEnum.Weak -> R.color.colorQuickWeak
            CardAffinityEnum.Normal -> R.color.colorQuick
            CardAffinityEnum.Resist -> R.color.colorQuickResist
        }
        CardTypeEnum.Unknown -> R.color.colorPrimaryDark
    }
}
