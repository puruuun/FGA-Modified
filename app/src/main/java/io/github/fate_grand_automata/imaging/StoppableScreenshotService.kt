package io.github.fate_grand_automata.imaging

import io.github.lib_automata.ScreenshotService

/**
 * [ScreenshotService] whose underlying capture session can stop asynchronously
 * (for example, when Android terminates a [android.media.projection.MediaProjection]).
 */
interface StoppableScreenshotService : ScreenshotService {
    val isStopped: Boolean
}
