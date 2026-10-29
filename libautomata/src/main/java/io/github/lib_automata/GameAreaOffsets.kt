package io.github.lib_automata

/** Insets in native screen pixels. Limits are enforced by settings, never by capture. */
data class GameAreaOffsets(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0) {
    fun region(screen: Size, reverseLandscape: Boolean) =
        customGameArea(screen, left, top, right, bottom, reverseLandscape)

    fun maximums(screen: Size): GameAreaOffsets {
        if (screen.width < 1280 || screen.height < 720) return GameAreaOffsets()
        return GameAreaOffsets(
            (screen.width - 1280 - right).coerceAtLeast(0),
            (screen.height - 720 - bottom).coerceAtLeast(0),
            (screen.width - 1280 - left).coerceAtLeast(0),
            (screen.height - 720 - top).coerceAtLeast(0)
        )
    }

    fun clamped(screen: Size): GameAreaOffsets {
        if (screen.width < 1280 || screen.height < 720) return GameAreaOffsets()
        val l = left.coerceIn(0, screen.width - 1280)
        val t = top.coerceIn(0, screen.height - 720)
        return GameAreaOffsets(l, t,
            right.coerceIn(0, screen.width - 1280 - l),
            bottom.coerceIn(0, screen.height - 720 - t))
    }
}
