package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.locations.FpSellLocations
import io.github.lib_automata.Match
import io.github.lib_automata.MeanColor
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
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
        /**
         * NEW! tag score bands. Real tags score 0.858-0.95 (the tag sits on the card row above, whose art varies;
         * lower rows read lower), tiles without a tag stay under the 0.75 search floor, so 0.85 keeps a gap on both sides.
         */
        const val NEW_PRESENT = 0.85
        const val NEW_ABSENT = 0.8
        /** The NEW! tag sits 128 px (720p) above the label of the same tile, i.e. 256 in 1440p. */
        const val NEW_TAG_TO_LABEL = 256

        /**
         * D-19: the selection frame is teal (about RGB 92/245/190) on every server. Unselected frames are gold, silver,
         * bronze or gray, at least 50 RGB units away on KR 18/20/27/28, JP 29 and JP 45. A strip of frame within
         * [SELECTED_MAX_DISTANCE] is selected, one beyond [UNSELECTED_MIN_DISTANCE] is not, and anything between is unknown.
         */
        val SELECTION_TEAL = MeanColor(92.0, 245.0, 190.0)
        const val SELECTED_MAX_DISTANCE = 25.0
        const val UNSELECTED_MIN_DISTANCE = 45.0
        /** Chromatic ratios keep the dimmed owner stop frame readable without accepting gray as teal. */
        private fun normalized(color: MeanColor): MeanColor? = if (color.green >= 20.0)
            MeanColor(color.red * 245.0 / color.green, 245.0, color.blue * 245.0 / color.green) else null

        fun frameTier(colors: List<MeanColor?>): FpSellPolicy.Tier? {
            val tiers = colors.mapNotNull { color ->
                val c = color?.let { normalized(it) } ?: return@mapNotNull null
                when {
                    // Silver needs a bright neutral frame: dark gray and near-silver artwork are not a tier.
                    color.green >= 155 && abs(c.red - 245) <= 16 && abs(c.blue - 245) <= 16 -> FpSellPolicy.Tier.Silver
                    abs(c.red - 278) <= 30 && abs(c.blue - 55) <= 45 -> FpSellPolicy.Tier.Gold
                    abs(c.red - 278) <= 28 && abs(c.blue - 215) <= 22 -> FpSellPolicy.Tier.Bronze
                    else -> null
                }
            }.distinct()
            return tiers.singleOrNull()
        }

        /** Smallest RGB distance to the teal frame colour over the strips, or null when any strip carries no colour. */
        fun selectionDistance(strips: List<MeanColor?>): Double? {
            if (strips.isEmpty() || strips.any { it == null }) return null
            // FGA's pure-blue debug marker can cover one band. It is not a frame hue;
            // require a usable independent band rather than treating that overlay as a negative selection.
            val colors = strips.filterNotNull().mapNotNull { normalized(it) }
            if (colors.isEmpty()) return null
            return colors.minOf {
                sqrt((it.red - SELECTION_TEAL.red).pow(2) + (it.green - SELECTION_TEAL.green).pow(2) +
                    (it.blue - SELECTION_TEAL.blue).pow(2))
            }
        }

        /** True when clearly teal, false when clearly another frame colour, null when borderline or unreadable. */
        fun selectionState(distance: Double?): Boolean? = when {
            distance == null -> null
            distance <= SELECTED_MAX_DISTANCE -> true
            distance >= UNSELECTED_MIN_DISTANCE -> false
            else -> null
        }
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

    /** One label match on a page. [skip] names the geometry filter that dropped it, or is null for an evaluated tile. */
    data class PageTile(
        val column: Int,
        val region: Region,
        val skip: String?,
        val cell: FpSellPolicy.Cell?,
        val stars: List<Pair<Int, Double>>,
        val newScore: Double,
        val slots: List<Double> = emptyList(),
    )

    /** Every tile found on the current page, including the ones dropped by the grid filters (for diagnosis and logs). */
    fun readPage(): List<PageTile> = useSameSnapIn {
        if (!onSellScreen() || !isSevenColumns()) return@useSameSnapIn emptyList()
        (0..6).flatMap { column -> labelMatches(column).map { label ->
            val card = loc.card(label.region)
            when {
                card.y < loc.grid.y + 28 -> PageTile(column, card, "gridTop", null, emptyList(), 0.0)
                !loc.labelInsideGrid(label.region) -> PageTile(column, card, "gridBottom", null, emptyList(), 0.0)
                else -> {
                    val cell = readCard(card)
                    PageTile(column, card, null, cell, rarityScores(card, cell.kind), newScore(card),
                        if (cell.kind == FpSellPolicy.Kind.Servant) starSlotScores(card) else emptyList())
                }
            }
        } }.sortedWith(compareBy({ it.region.y }, { it.region.x }))
    }

    fun read(): List<ReadCell> = readPage().filter { it.skip == null }.map { ReadCell(it.region, it.cell!!) }

    /**
     * Distance of the card's frame to the teal selection colour, read from a fresh colour frame.
     * Call it outside [useSameSnapIn]: the strips need a colour capture, and a cached gray frame carries no hue.
     */
    fun frameColors(card: Region): List<MeanColor?> = useColor {
        useSameSnapIn {
            loc.frameBands.map { band ->
                val samples = loc.frameOffsets.map { offset ->
                    loc.frameStrip(card, offset, band).getPattern().use { it.meanColor() }
                }
                if (samples.any { it == null }) null else {
                    val colors = samples.filterNotNull()
                    // Find the leading outside->frame brightness rise, then use the next pixel inside the frame.
                    // The search covers only the narrow edge neighborhood, including accumulated pitch drift.
                    val edge = (1 until colors.size).maxByOrNull { i ->
                        val a = colors[i - 1]; val b = colors[i]
                        maxOf(0.0, b.red - a.red) + maxOf(0.0, b.green - a.green) + maxOf(0.0, b.blue - a.blue)
                    } ?: 0
                    colors[minOf(edge + 1, colors.lastIndex)]
                }
            }
        }
    }

    fun selectionDistance(card: Region): Double? = selectionDistance(frameColors(card))

    fun tier(card: Region): FpSellPolicy.Tier? {
        val colors = frameColors(card)
        if (selectionState(selectionDistance(colors)) != false) return null
        return frameTier(colors)
    }

    fun tierStars(card: Region): Int? = FpSellPolicy.starsFromTier(tier(card), useSameSnapIn { starSlotScores(card) })

    /** Selected state of the card from a fresh frame; null when borderline (D-19). Never inside [useSameSnapIn]. */
    fun selection(card: Region): Boolean? = selectionState(selectionDistance(card))

    /**
     * Gray diagnostics only: selected state stays null and legacy strip rarity is retained for existing reports.
     * Every candidate decision must use [confirmed], which replaces servant rarity with D-24 frame-tier rarity.
     */
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
        return FpSellPolicy.Cell(kind, stars, new, lock, selected = null, excluded = padlock || words)
    }

    /** Best template score per star count, strongest first; empty when the kind is unknown. */
    fun rarityScores(card: Region, kind: FpSellPolicy.Kind?): List<Pair<Int, Double>> {
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
            null -> return emptyList()
        }
        return candidates.map { (stars, templates) -> stars to templates.maxOf {
            loc.stars(card).findAll(images[it], similarity = 0.8).maxOfOrNull { match -> match.score } ?: 0.0
        } }.sortedByDescending { it.second }
    }

    /**
     * Best score of one gold star per slot, rightmost first (five slots; a servant has at most four stars).
     * Unlike the strip templates, a single star carries almost no card art, so it matches on any tile (JP 1-star, frame 46).
     */
    fun starSlotScores(card: Region): List<Double> {
        val star = images[Images.FpSellRarityStar]
        return (0 until FpSellPolicy.STAR_SLOTS).map { slot ->
            loc.starSlot(card, slot).findAll(star, similarity = 0.3).maxOfOrNull { it.score } ?: 0.0
        }
    }

    private fun rarity(card: Region, kind: FpSellPolicy.Kind?): Int? {
        val scores = rarityScores(card, kind)
        if (scores.size < 2) return null
        val stripStars = scores.first().takeIf { it.second >= 0.9 && it.second - scores[1].second >= 0.08 }?.first
        // The strip templates are cut from KR card art and never reach 0.9 on other art. Only a servant falls back to
        // the slot-wise count; an uncertain count stays null, so the tile is not selected.
        return stripStars ?: if (kind == FpSellPolicy.Kind.Servant) FpSellPolicy.starsFromSlots(starSlotScores(card)) else null
    }

    /** Best NEW! tag score in the band above the card. */
    fun newScore(card: Region): Double = listOf(Images.FpSellNewBright, Images.FpSellNewDark, Images.FpSellNewOrange,
            Images.FpSellNewSelected, Images.FpSellNewSelectedRight, Images.FpSellNewOrangeRight).maxOf {
            loc.newLabel(card).findAll(images[it], similarity = 0.75).maxOfOrNull { it.score } ?: 0.0
        }

    fun newState(card: Region): Boolean? {
        val score = newScore(card)
        return when { score >= NEW_PRESENT -> true; score >= NEW_ABSENT -> null; else -> false }
    }

    /** A borderline NEW read is retried from a fresh frame, once, after its shimmer changes. */
    fun confirmed(card: Region): FpSellPolicy.Cell {
        val first = useSameSnapIn { readCard(card) }
        val cell = if (first.new != null) first else {
            0.3.seconds.wait()
            useSameSnapIn { readCard(card) }.let { it.copy(new = it.new == true) }
        }
        val selected = selection(card)
        // Teal tiles are already selected; no tier is needed and they are never tapped again.
        return cell.copy(selected = selected, stars = when {
            cell.kind != FpSellPolicy.Kind.Servant -> cell.stars
            selected == true -> null
            selected == false -> tierStars(card)
            else -> null
        })
    }
}
