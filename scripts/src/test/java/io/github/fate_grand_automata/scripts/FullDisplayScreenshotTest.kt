package io.github.fate_grand_automata.scripts

import io.github.lib_automata.*
import io.mockk.*
import kotlin.test.*

class FullDisplayScreenshotTest {
    @Test fun `root calibration gets an owned native frame and leaves normal resize unchanged`() {
        val original = mockk<ScreenshotService>(relaxed = true)
        val native = mockk<Pattern>(relaxed = true)
        val owned = mockk<Pattern>(relaxed = true)
        val target = mockk<Pattern>(relaxed = true)
        val platform = mockk<PlatformImpl>()
        every { native.width } returns 3040
        every { native.height } returns 1904
        every { original.takeScreenshot() } returns native
        every { native.copy() } returns owned
        every { platform.getResizableBlankPattern() } returns target
        val provider = ResizedScreenshotProvider(original, 0.5, platform)
        assertSame(owned, provider.takeFullDisplayScreenshot())
        owned.close()
        verify(exactly = 0) { native.resize(any<Pattern>(), any<Size>()) }
        verify(exactly = 0) { native.close() }
        assertSame(target, provider.takeScreenshot())
        verify(exactly = 1) { native.resize(target, Size(1520, 952)) }
        provider.close()
        verify(exactly = 1) { original.close() }
        verify(exactly = 1) { target.close() }
    }
}
