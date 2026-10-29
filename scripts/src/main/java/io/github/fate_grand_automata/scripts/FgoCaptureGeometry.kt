package io.github.fate_grand_automata.scripts

import io.github.lib_automata.RealScale
import io.github.lib_automata.Region
import io.github.lib_automata.Size

/** The full display is captured at the same scale used by ScreenshotManager's game-area crop. */
class FgoCaptureGeometry(screenSize: Size, windowRegion: Region) {
    companion object {
        /** Capture stays landscape even when the service is started from portrait settings. */
        fun forLandscape(screenSize: Size, windowRegion: (Size) -> Region): FgoCaptureGeometry {
            val landscape = Size(maxOf(screenSize.width, screenSize.height), minOf(screenSize.width, screenSize.height))
            return FgoCaptureGeometry(landscape, windowRegion(landscape))
        }
    }

    private val area = FgoGameAreaManager(windowRegion.size) { windowRegion.location }
    val scale = RealScale(area).screenToImage ?: 1.0
    val captureSize = screenSize * scale
    val crop = area.gameArea * scale
}
