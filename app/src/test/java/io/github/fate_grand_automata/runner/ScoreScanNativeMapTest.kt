package io.github.fate_grand_automata.runner

import io.github.fate_grand_automata.scripts.FgoCaptureGeometry
import io.github.fate_grand_automata.scripts.FgoGameAreaManager
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanNative
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanNativeCrop
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanNativeMap
import io.github.lib_automata.GameAreaOffsets
import io.github.lib_automata.Location
import io.github.lib_automata.RealScale
import io.github.lib_automata.RealTransformer
import io.github.lib_automata.Region
import io.github.lib_automata.Size
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #600: the support score scan maps its 720p fields into a full-display frame with the geometry
 * ScreenshotServiceHolder captures with (the rust build's HolderScoreScanNativeFrames calls the same
 * [ScoreScanNativeMap.of]). Checked against the script's own transform to screen pixels, which is a
 * separate path through the game area.
 */
class ScoreScanNativeMapTest {
    private class Case(val display: Size, val offsets: GameAreaOffsets?) {
        val window: (Size) -> Region = { size -> offsets?.region(size, false) ?: Region(Location(), size) }
        val landscape = Size(maxOf(display.width, display.height), minOf(display.width, display.height))
        private val region = window(landscape)
        val area = FgoGameAreaManager(region.size) { region.location }
        val scale = RealScale(area)
        val transformer = RealTransformer(area, scale)
        val geometry = FgoCaptureGeometry.forLandscape(display, window)
        val map = ScoreScanNativeMap.of(display, window, landscape)
    }

    private val cases = listOf(
        Case(Size(2560, 1440), null),
        Case(Size(1920, 1080), null),
        Case(Size(1280, 720), null),
        Case(Size(3040, 1904), null),
        // The owner's tablet in split screen, the geometry tests' saved offsets.
        Case(Size(3040, 1904), GameAreaOffsets(1037, 97, 0, 11)),
        // A wide phone with its camera cutout on the left, and the same started from portrait.
        Case(Size(2400, 1080), GameAreaOffsets(left = 80)),
        Case(Size(1080, 2400), GameAreaOffsets(left = 80)),
        Case(Size(2400, 1080), null),
    )

    /** Score fields of the first and the last row a support list can show, in 1440p script coordinates. */
    private val fields = listOf(Region(2036, 510, 120, 60), Region(2036, 1310, 120, 60))

    @Test
    fun `the map starts from the crop each 720p screenshot is cut at`() {
        for (case in cases) {
            val scriptCrop = case.area.gameArea * (case.scale.screenToImage ?: 1.0)
            assertEquals(scriptCrop, case.geometry.crop, "${case.display} ${case.offsets}")
            assertEquals(case.geometry.crop.location, case.map.origin)
            assertEquals(case.geometry.captureSize, case.map.capture)
        }
    }

    @Test
    fun `a field lands on the native pixels the script means, within the two half-pixel roundings of the 720p capture`() {
        for (case in cases) for (field in fields) {
            val native = case.map.toNative(case.transformer.toImage(field))
            val screen = case.transformer.toScreen(field)
            // The crop corner and the field are each rounded to whole 720p pixels.
            val tolerance = ceil(maxOf(case.map.scaleX, case.map.scaleY))
            val label = "${case.display} ${case.offsets} $field: native $native, screen $screen"
            assertTrue(abs(native.x - screen.x) <= tolerance, label)
            assertTrue(abs(native.y - screen.y) <= tolerance, label)
            assertTrue(abs(native.x + native.width - screen.x - screen.width) <= tolerance, label)
            assertTrue(abs(native.y + native.height - screen.y - screen.height) <= tolerance, label)
            assertTrue(native in Region(Location(), case.landscape), label)
        }
    }

    @Test
    fun `a 16 by 9 display at twice 720p maps exactly`() {
        val case = cases[0]
        assertEquals(2.0, case.map.scaleX)
        assertEquals(2.0, case.map.scaleY)
        val image = case.transformer.toImage(fields[0])
        assertEquals(Region(1018, 255, 60, 30), image)
        assertEquals(Region(2036, 510, 120, 60), case.map.toNative(image))
        assertEquals(
            ScoreScanNativeCrop(Region(1996, 470, 200, 140), Region(40, 40, 120, 60), emptyList()),
            ScoreScanNative.crop(case.map, image),
        )
    }

    @Test
    fun `the tablet in split screen scales each axis by its own ratio`() {
        val case = cases[4]
        assertEquals(Size(1943, 1217), case.map.capture)
        assertEquals(Location(663, 276), case.map.origin)
        assertEquals(3040.0 / 1943, case.map.scaleX)
        assertEquals(1904.0 / 1217, case.map.scaleY)
        val crop = ScoreScanNative.crop(case.map, case.transformer.toImage(fields[0]))!!
        assertEquals(emptyList(), crop.clamped)
        // 100x70 at 720p scale is about 156x110 native pixels on this display.
        assertTrue(crop.region.width in 155..158, "${crop.region}")
        assertTrue(crop.region.height in 108..111, "${crop.region}")
    }

    @Test
    fun `the margin is cut at the frame edges and the cut sides are named`() {
        val map = cases[0].map
        assertEquals(
            ScoreScanNativeCrop(Region(0, 0, 170, 110), Region(10, 10, 120, 60), listOf("left", "top")),
            ScoreScanNative.crop(map, Region(5, 5, 60, 30)),
        )
        assertEquals(
            ScoreScanNativeCrop(Region(2390, 1330, 170, 110), Region(40, 40, 120, 60), listOf("right", "bottom")),
            ScoreScanNative.crop(map, Region(1215, 685, 60, 30)),
        )
        assertNull(ScoreScanNative.crop(map, Region(1250, 0, 60, 30)))
    }
}
