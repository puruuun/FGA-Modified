package io.github.fate_grand_automata.scripts.locations

import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.lib_automata.Location
import io.github.lib_automata.Region

/**
 * Wide screens (wider than 18:9) keep screen-edge UI inside a safe area, 1440p. Measured on owner 21:9 captures
 * (docs/design/0012-widescreen-audit.md): back/close buttons, titles, MENU and left-side button columns sit
 * 85-86 px (720p) further in than on 16:9, which is the 171 that [SupportScreenLocations] already used.
 * The inset is the same on both sides and, by the original FGA assumption, does not depend on the aspect ratio.
 */
const val WIDE_EDGE_INSET = 171

/** Bottom-right action buttons (enhance, unlock) sit closer to the edge than the header UI: 57-58 px at 720p. */
const val WIDE_ACTION_INSET = 115

interface IScriptAreaTransforms {
    val scriptArea: Region
    val isWide: Boolean
    val gameServer: GameServer
    val canLongSwipe: Boolean
    fun Location.xFromCenter(): Location
    fun Region.xFromCenter(): Region
    fun Location.xFromRight(): Location
    fun Region.xFromRight(): Region
    fun Location.yFromBottom(): Location
    fun Region.yFromBottom(): Region

    /** [WIDE_EDGE_INSET] on wide screens, 0 on 16:9. */
    val edgeInset: Int get() = if (isWide) WIDE_EDGE_INSET else 0

    /**
     * Left-edge UI. The coordinates are measured from the game area's left edge as on 16:9; wide screens add the
     * safe-area inset. At 16:9 this is the same as the plain left edge.
     */
    fun Location.xFromLeftEdge(): Location = this + Location(edgeInset, 0)
    fun Region.xFromLeftEdge(): Region = this + Location(edgeInset, 0)

    /** Right-edge UI. The coordinates are negative offsets from the right edge as on 16:9; wide screens move them inward. */
    fun Location.xFromRightEdge(): Location = this + Location(scriptArea.right - edgeInset, 0)
    fun Region.xFromRightEdge(): Region = this + Location(scriptArea.right - edgeInset, 0)
}
