package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.modules.FpSellButtons
import io.github.lib_automata.*
import io.mockk.*
import kotlin.test.*
import kotlin.time.TestTimeSource
import kotlin.time.Duration.Companion.milliseconds

class FpSellButtonsTest {
    private class Missing : RuntimeException()
    private val api = mockk<IFgoAutomataApi>(relaxed = true)
    private val region = Region(10,20,30,40)
    private val buttons = FpSellButtons(api)
    init {
        every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
        every { with(api) { region.find(any<Pattern>(),any()) } } returns Match(region,1.0)
    }

    @Test fun `button waits the full two second window and uses fresh frames`() {
        var reads=0
        every { with(api) { region.find(any<Pattern>(),any()) } } answers {
            reads++
            if (reads==21) Match(region,1.0) else null
        }
        buttons.click(region,Images.FpSellMenu) { throw Missing() }
        assertEquals(21,reads)
        verify(exactly=20) { with(api) { FpSellButtons.POLL.wait() } }
        verify(exactly=1) { with(api) { region.click(1) } }
    }

    @Test fun `missing button stops after two seconds without any tap`() {
        every { with(api) { region.find(any<Pattern>(),any()) } } returns null
        assertFailsWith<Missing> { buttons.click(region,Images.FpSellConfirm) { throw Missing() } }
        verify(exactly=21) { with(api) { region.find(any<Pattern>(),any()) } }
        verify(exactly=0) { with(api) { region.click(any()) } }
    }

    @Test fun `menu waits three seconds then retries once and verifies next screen again`() {
        for (image in listOf(Images.FpSellMenu,Images.FpSellSummonMenu)) {
            clearMocks(api,answers=false)
            var signals=0
            buttons.menu(region,image,{ ++signals==32 },{ throw Missing() })
            assertEquals(32,signals)
            verify(exactly=30) { with(api) { FpSellButtons.POLL.wait() } }
            verify(exactly=2) { with(api) { region.click(1) } }
        }
    }

    @Test fun `menu next screen timeout permits only two taps in total`() {
        var signals=0
        assertFailsWith<Missing> {
            buttons.menu(region,Images.FpSellMenu,{ signals++;false },{ throw Missing() })
        }
        assertEquals(62,signals)
        verify(exactly=60) { with(api) { FpSellButtons.POLL.wait() } }
        verify(exactly=2) { with(api) { region.click(1) } }
    }

    @Test fun `successful menu transition has no retry and retry requires another template match`() {
        buttons.menu(region,Images.FpSellMenu,{ true },{ throw Missing() })
        verify(exactly=1) { with(api) { region.click(1) } }
        clearMocks(api,answers=false)
        var reads=0
        every { with(api) { region.find(any<Pattern>(),any()) } } answers {
            if (++reads==1) Match(region,1.0) else null
        }
        assertFailsWith<Missing> { buttons.menu(region,Images.FpSellMenu,{ false },{ throw Missing() }) }
        verify(exactly=1) { with(api) { region.click(1) } }
    }

    @Test fun `FP summon confirm cancel and convert cannot enter menu retry and single click never repeats`() {
        for (image in listOf(Images.FpSellSummonButton,Images.FpSellConfirm,Images.FpSellCancel,Images.FpSellConvert)) {
            clearMocks(api,answers=false)
            assertFailsWith<IllegalArgumentException> { buttons.menu(region,image,{ false },{ throw Missing() }) }
            verify(exactly=0) { with(api) { region.click(any()) } }
            buttons.click(region,image) { throw Missing() }
            verify(exactly=1) { with(api) { region.click(1) } }
        }
    }
    @Test fun `capture and matching latency count toward the two second budget`() {
        val clock=TestTimeSource()
        val timed=FpSellButtons(api,clock)
        var reads=0
        assertFalse(timed.awaitSignal(FpSellButtons.BUTTON_POLLS) {
            reads++
            clock += 700.milliseconds
            false
        })
        assertEquals(3,reads)
        verify(exactly=2) { with(api) { FpSellButtons.POLL.wait() } }
    }
}
