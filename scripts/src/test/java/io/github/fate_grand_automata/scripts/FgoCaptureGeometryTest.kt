package io.github.fate_grand_automata.scripts

import io.github.lib_automata.GameAreaOffsets
import io.github.lib_automata.Location
import io.github.lib_automata.Pattern
import io.github.lib_automata.PlatformImpl
import io.github.lib_automata.RealScale
import io.github.lib_automata.Region
import io.github.lib_automata.ResizedScreenshotProvider
import io.github.lib_automata.ScreenshotManager
import io.github.lib_automata.ScreenshotService
import io.github.lib_automata.Size
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FgoCaptureGeometryTest {
    private fun assertDefault(size: Size, expectedCapture: Size, expectedScale: Double) {
        val original = RealScale(FgoGameAreaManager(size) { Location() }).screenToImage ?: 1.0
        val geometry = FgoCaptureGeometry(size, Region(Location(), size))
        assertEquals(original, geometry.scale)
        assertEquals(expectedScale, geometry.scale)
        assertEquals(expectedCapture, geometry.captureSize)
        assertTrue(geometry.crop in Region(Location(), geometry.captureSize))
    }

    @Test
    fun `default 16 by 9 phone keeps original capture scale`() =
        assertDefault(Size(1920, 1080), Size(1280, 720), 2.0 / 3)

    @Test
    fun `default wide phone keeps original capture scale`() =
        assertDefault(Size(2400, 1080), Size(1600, 720), 2.0 / 3)

    @Test
    fun `default BlueStacks keeps original capture scale`() =
        assertDefault(Size(2560, 1440), Size(1280, 720), 0.5)

    @Test
    fun `native 720p retains unscaled geometry`() =
        assertDefault(Size(1280, 720), Size(1280, 720), 1.0)

    @Test
    fun `starting service from portrait settings preserves default phone scale`() {
        val geometry = FgoCaptureGeometry.forLandscape(Size(1080, 2400)) { Region(Location(), it) }
        assertEquals(2.0 / 3, geometry.scale)
        assertEquals(Size(1600, 720), geometry.captureSize)
    }

    @Test
    fun `starting service in portrait resolves saved tablet offsets against landscape capture`() {
        val geometry = FgoCaptureGeometry.forLandscape(Size(1904, 3040)) {
            GameAreaOffsets(1037, 97, 0, 11).region(it, false)
        }
        assertEquals(Size(1943, 1217), geometry.captureSize)
        assertEquals(Region(663, 276, 1280, 720), geometry.crop)
    }

    @Test
    fun `tablet split screen produces exactly 1280 by 720 within capture and ScreenshotManager uses it`() {
        val screen = Size(3040, 1904)
        val window = GameAreaOffsets(1037, 97, 0, 11).region(screen, false)
        val geometry = FgoCaptureGeometry(screen, window)
        assertEquals(1280.0 / 2003, geometry.scale)
        assertEquals(Size(1943, 1217), geometry.captureSize)
        assertEquals(Region(663, 276, 1280, 720), geometry.crop)
        assertTrue(geometry.crop in Region(Location(), geometry.captureSize))
        val area = FgoGameAreaManager(window.size) { window.location }
        val screenshot = mockk<Pattern>(relaxed = true)
        val service = mockk<ScreenshotService>()
        every { service.takeScreenshot() } returns screenshot
        ScreenshotManager(area, service, RealScale(area)).getScreenshot()
        verify(exactly = 1) { screenshot.crop(geometry.crop) }
    }

    @Test
    fun `reverse landscape tablet crop fits capture`() {
        val screen = Size(3040, 1904)
        val window = GameAreaOffsets(1037, 97, 0, 11).region(screen, true)
        val geometry = FgoCaptureGeometry(screen, window)
        assertEquals(Size(1280, 720), geometry.crop.size)
        assertEquals(0, geometry.crop.x)
        assertTrue(geometry.crop in Region(Location(), geometry.captureSize))
    }

    @Test
    fun `root resize factor changes on the existing provider`() {
        val original = mockk<ScreenshotService>()
        val shot = mockk<Pattern>(relaxed = true)
        every { shot.width } returns 3040
        every { shot.height } returns 1904
        every { original.takeScreenshot() } returns shot
        val target = mockk<Pattern>(relaxed = true)
        val platform = mockk<PlatformImpl>()
        every { platform.getResizableBlankPattern() } returns target
        val provider = ResizedScreenshotProvider(original, 1280.0 / 3040, platform)
        provider.takeScreenshot()
        verify { shot.resize(target, Size(1280, 802)) }
        provider.resize(1280.0 / 2003)
        provider.takeScreenshot()
        verify { shot.resize(target, Size(1943, 1217)) }
    }

    @Test
    fun `offset maximums account for the opposite edges and include the minimum limit`() {
        val screen = Size(3040, 1904)
        val maximums = GameAreaOffsets(1037, 97, 100, 11).maximums(screen)
        assertEquals(GameAreaOffsets(1660, 1173, 723, 1087), maximums)
        assertEquals(1280, GameAreaOffsets(maximums.left, 97, 100, 11).region(screen, false).width)
        assertEquals(720, GameAreaOffsets(1037, maximums.top, 100, 11).region(screen, false).height)
    }

    @Test
    fun `typed offsets clamp negatives and excessive values to a 1280 by 720 region`() {
        val screen = Size(3040, 1904)
        val offsets = GameAreaOffsets(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE).clamped(screen)
        assertEquals(GameAreaOffsets(1760, 1184, 0, 0), offsets)
        assertEquals(Size(1280, 720), offsets.region(screen, false).size)
        assertEquals(offsets, offsets.maximums(screen))
        assertEquals(GameAreaOffsets(), GameAreaOffsets(-1, -2, -3, -4).clamped(screen))
    }

    @Test
    fun `screens below either minimum dimension and native 720p only allow zero offsets`() {
        val offsets = GameAreaOffsets(100, 100, 100, 100)
        for (screen in listOf(Size(1279, 1000), Size(2000, 719), Size(1280, 720))) {
            assertEquals(GameAreaOffsets(), offsets.clamped(screen))
            assertEquals(GameAreaOffsets(), offsets.maximums(screen))
        }
    }
}
