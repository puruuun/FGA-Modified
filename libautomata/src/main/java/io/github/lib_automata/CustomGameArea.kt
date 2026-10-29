package io.github.lib_automata

/** Native screen pixels, using the right offset as the origin in reverse landscape. */
fun customGameArea(
    screenSize: Size,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    reverseLandscape: Boolean
): Region = Region(
    if (reverseLandscape) right else left,
    top,
    screenSize.width - left - right,
    screenSize.height - top - bottom
)
