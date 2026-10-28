package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.locations.FpSellLocations
import io.github.lib_automata.MeanColor
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import javax.inject.Inject
import kotlin.math.pow
import kotlin.math.sqrt

class FpSellReader @Inject constructor(api: IFgoAutomataApi, val loc: FpSellLocations) : IFgoAutomataApi by api {
    companion object {
        const val LOCK_SIMILARITY = 0.8
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

    /** Missing assets are reported as null, never guessed: an absent template only narrows what can be checked. */
    private fun template(image: Images): Pattern? = runCatching { images[image] }.getOrNull()

    private val lockIcon by lazy { template(Images.FpSellLock) }
    private val lockTemplate get() = lockIcon
    val hasLockTemplate get() = lockIcon != null

    fun onServantTab() = loc.servantTab.exists(images[Images.FpSellTab], similarity = 0.9)

    /** Wait for frame edges at any supported size, including an initially open CE tab. */
    fun gridReadable(): Boolean = loc.grid.getPattern().use { image ->
        val pixels = image.grayPixels() ?: return@use false
        (5..7).any { columns ->
            FpSellGrid.read(pixels, image.width, image.height, loc.layoutColumns(columns), loc.grid.y / 2,
                loc.layoutPitch(columns), loc.layoutCardHeight(columns))?.readable == true
        }
    }

    /** D-11: the warning is a servant-slot warning only when the first line holds the servant word. */
    fun servantFullWarning(): Boolean = useSameSnapIn {
        FpSellPolicy.isServantFullWarning(loc.fullDialogLine.exists(images[Images.FpSellWordServant], similarity = 0.9))
    }

    data class PageTile(val column: Int, val region: Region, val skip: String?)

    /** D-30: fit one row phase from the seven frame edges, independent of all label text. */
    private fun grid(): FpSellGrid.Result? = loc.grid.getPattern().use { image ->
        val pixels = image.grayPixels() ?: return@use null
        FpSellGrid.read(pixels, image.width, image.height,
            (0..6).map { (loc.columnX(it) - loc.grid.x) / 2 }, loc.grid.y / 2, loc.rowPitch / 2, loc.cardHeight / 2)
    }

    fun isSevenColumns(): Boolean = useSameSnapIn { grid()?.seven == true }

    fun readPage(): List<PageTile> = useSameSnapIn {
        val grid = grid() ?: return@useSameSnapIn emptyList()
        if (!grid.seven) return@useSameSnapIn emptyList()
        grid.tops.flatMap { top -> (0..6).map { column ->
            val card = loc.card(column, top * 2)
            PageTile(column, card, when {
                card.y < loc.grid.y -> "gridTop"
                card.bottom > loc.grid.bottom -> "gridBottom"
                else -> null
            })
        } }
    }

    /** Card presence uses both side edges over two long independent bands, never a kind label. */
    fun present(card: Region): Boolean = useSameSnapIn {
        val area = loc.presence(card)
        area.getPattern().use { image ->
            image.grayPixels()?.let { FpSellGrid.present(it, image.width, image.height, 8, loc.cardWidth / 2) } == true
        }
    }

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

    /** Fresh colour capture, outside the cached grayscale safety read. */
    fun selection(card: Region): Boolean? = selectionState(selectionDistance(card))

    fun readCard(card: Region): FpSellPolicy.Cell {
        val present = present(card)
        if (!present) return FpSellPolicy.Cell(false, FpSellPolicy.Lock.Unknown, null)
        val gray = useSameSnapIn {
            val area = loc.lock(card)
            val padlock = lockTemplate?.let { area.exists(it, similarity = LOCK_SIMILARITY) } == true
            val lock = when {
                padlock -> FpSellPolicy.Lock.Locked
                lockTemplate == null -> FpSellPolicy.Lock.Unknown
                else -> FpSellPolicy.Lock.Unlocked
            }
            FpSellPolicy.Cell(true, lock, null)
        }
        return gray.copy(selected = selection(card))
    }
}
