package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.locations.FpSellLocations
import io.github.lib_automata.Match
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import javax.inject.Inject
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

class FpSellReader @Inject constructor(api: IFgoAutomataApi, val loc: FpSellLocations) : IFgoAutomataApi by api {
    companion object {
        /** Labels only pick the layout and the kind. Dimmed labels on locked tiles still reach this. */
        const val LABEL_SIMILARITY = 0.85
        /**
         * Exclusion signals only ever remove a tile from the candidates, so a low threshold is the safe direction
         * (a missed padlock is the dangerous error; a false padlock only skips a tile).
         */
        const val EXCLUSION_SIMILARITY = 0.8
        /** The NEW! tag sits 128 px (720p) above the label of the same tile, i.e. 256 in 1440p. */
        const val NEW_TAG_TO_LABEL = 256
    }

    data class ReadCell(val region: Region, val value: FpSellPolicy.Cell)

    /** Missing assets are reported as null, never guessed: an absent template only narrows what can be checked. */
    private fun template(image: Images): Pattern? = runCatching { images[image] }.getOrNull()

    private val lockIcon by lazy { template(Images.FpSellLock) }
    private val lockTemplate get() = lockIcon
    val hasLockTemplate get() = lockIcon != null

    /** Overlay words that mark a tile as not selectable: 잠금 중 / ロック中, 選択不可, グランドサーヴァント, PARTY. */
    private val exclusionWords by lazy {
        listOf(Images.FpSellLockedText, Images.FpSellUnselectable, Images.FpSellGrandServant, Images.FpSellParty)
            .mapNotNull { template(it) }
    }

    fun onSellHeading() = loc.heading.exists(images[Images.FpSellHeading], similarity = 0.9)
    fun onServantTab() = loc.servantTab.exists(images[Images.FpSellTab], similarity = 0.9)
    fun onSellScreen() = onSellHeading() && onServantTab()

    /** D-11: the warning is a servant-slot warning only when the first line holds the servant word. */
    fun servantFullWarning(): Boolean = useSameSnapIn {
        FpSellPolicy.isServantFullWarning(loc.fullDialogLine.exists(images[Images.FpSellWordServant], similarity = 0.9))
    }

    /**
     * Seven columns establish the small-icon layout: six or more of the seven label columns carry a label on one row.
     * Overlays (PARTY, locks) can hide one label, so one missing column is allowed. A large-icon layout does not reach six.
     */
    fun isSevenColumns(): Boolean = useSameSnapIn {
        val columns = (0..6).map { tileRows(it) }
        columns.flatten().any { row ->
            columns.count { column -> column.any { abs(it - row) <= 12 } } >= 6
        }
    }

    /** Label rows (1440p Y) of one column. A NEW! tag on the tile gives the same row, so a dimmed label is not needed. */
    private fun tileRows(column: Int): List<Int> {
        val labels = labelMatches(column).map { it.region.y }
        val tags = listOf(Images.FpSellNewBright, Images.FpSellNewDark, Images.FpSellNewOrange,
            Images.FpSellNewSelected, Images.FpSellNewSelectedRight, Images.FpSellNewOrangeRight).flatMap {
            loc.newColumn(column).findAll(images[it], similarity = 0.8).map { match -> match.region.y + NEW_TAG_TO_LABEL }
        }
        return labels + tags
    }

    private fun labelMatches(column: Int): List<Match> =
        listOf(Images.FpSellServant, Images.FpSellServantSelected, Images.FpSellServantSelectedBronze, Images.FpSellExp, Images.FpSellExpDark).flatMap {
            loc.labelColumn(column).findAll(images[it], similarity = LABEL_SIMILARITY)
        }.sortedBy { it.region.y }.fold(mutableListOf()) { result, match ->
            if (result.none { abs(it.region.y - match.region.y) < 12 }) result.add(match)
            result
        }

    fun read(): List<ReadCell> = useSameSnapIn {
        if (!onSellScreen() || !isSevenColumns()) return@useSameSnapIn emptyList()
        (0..6).flatMap { column -> labelMatches(column).mapNotNull { label ->
            val card = loc.card(column, label.region.y)
            if (card.y < loc.grid.y + 28 || card.bottom > loc.grid.bottom) null else ReadCell(card, readCard(card))
        } }.sortedWith(compareBy({ it.region.y }, { it.region.x }))
    }

    fun readCard(card: Region): FpSellPolicy.Cell {
        val servant = listOf(Images.FpSellServant, Images.FpSellServantSelected, Images.FpSellServantSelectedBronze).any {
            loc.label(card).exists(images[it], similarity = LABEL_SIMILARITY) }
        val exp = listOf(Images.FpSellExp, Images.FpSellExpDark).any { loc.label(card).exists(images[it], similarity = LABEL_SIMILARITY) }
        val kind = when { servant && !exp -> FpSellPolicy.Kind.Servant; exp && !servant -> FpSellPolicy.Kind.Exp; else -> null }
        val stars = rarity(card, kind)
        val new = newState(card)
        val area = loc.lock(card)
        val padlock = lockTemplate?.let { area.exists(it, similarity = EXCLUSION_SIMILARITY) } == true
        val words = exclusionWords.any { area.exists(it, similarity = EXCLUSION_SIMILARITY) }
        // Unknown only when the padlock template is missing. Without a padlock or a lock word, the tile reads Unlocked.
        val lock = when {
            lockTemplate == null -> FpSellPolicy.Lock.Unknown
            padlock || words -> FpSellPolicy.Lock.Locked
            else -> FpSellPolicy.Lock.Unlocked
        }
        return FpSellPolicy.Cell(kind, stars, new, lock,
            listOf(Images.FpSellSelected, Images.FpSellSelectedLower).any {
                loc.selection(card).exists(images[it], similarity = 0.88) },
            excluded = padlock || words)
    }

    private fun rarity(card: Region, kind: FpSellPolicy.Kind?): Int? {
        val candidates = when (kind) {
            FpSellPolicy.Kind.Servant -> listOf(
                1 to listOf(Images.FpSellRarity1),
                2 to listOf(Images.FpSellRarity2, Images.FpSellRarity2R2c3, Images.FpSellRarity2R2c6),
                3 to listOf(Images.FpSellRarity3, Images.FpSellRarity3R0c5, Images.FpSellRarity3R0c6,
                    Images.FpSellRarity3R1c0, Images.FpSellRarity3R1c1, Images.FpSellRarity3R1c2,
                    Images.FpSellRarity3R1c3, Images.FpSellRarity3R1c4, Images.FpSellRarity3R1c5, Images.FpSellRarity3R1c6),
                4 to listOf(Images.FpSellRarity4, Images.FpSellRarity4R0c1))
            FpSellPolicy.Kind.Exp -> listOf(4 to listOf(Images.FpSellRarityExp4, Images.FpSellRarityExp4Dark),
                5 to listOf(Images.FpSellRarityExp5, Images.FpSellRarityExp5Dark))
            null -> return null
        }
        val scores = candidates.map { (stars, templates) -> stars to templates.maxOf {
            loc.stars(card).findAll(images[it], similarity = 0.8).maxOfOrNull { match -> match.score } ?: 0.0
        } }.sortedByDescending { it.second }
        return scores.first().takeIf { it.second >= 0.9 && it.second - scores[1].second >= 0.08 }?.first
    }

    fun newState(card: Region): Boolean? {
        val score = listOf(Images.FpSellNewBright, Images.FpSellNewDark, Images.FpSellNewOrange,
            Images.FpSellNewSelected, Images.FpSellNewSelectedRight, Images.FpSellNewOrangeRight).maxOf {
            loc.newLabel(card).findAll(images[it], similarity = 0.75).maxOfOrNull { it.score } ?: 0.0
        }
        return when { score >= 0.88 -> true; score >= 0.8 -> null; else -> false }
    }

    /** A borderline NEW read is retried from a fresh frame, once, after its shimmer changes. */
    fun confirmed(card: Region): FpSellPolicy.Cell {
        val first = useSameSnapIn { readCard(card) }
        if (first.new != null) return first
        0.3.seconds.wait()
        return useSameSnapIn { readCard(card) }.let { it.copy(new = it.new == true) }
    }
}
