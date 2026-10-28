package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.entrypoints.AutoCEEnhance
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.modules.CEEnhanceScreens
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.mockk.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class AutoCEEnhanceTest {
    private val api = mockk<IFgoAutomataApi>(relaxed = true)
    private val screens = mockk<CEEnhanceScreens>(relaxed = true)
    private val script = AutoCEEnhance(mockk(relaxed = true), screens, mockk(relaxed = true), api)

    init {
        every { api.prefs.gameServer } returns GameServer.Kr
        every { with(api) { any<Region>().exists(any<Pattern>(), any(), any()) } } returns true
    }

    @Test fun `just executed close is no matching CE even before first refund`() {
        script.execute()
        assertEquals(AutoCEEnhance.ExitReason.NoMatchingCE,
            assertFailsWith<AutoCEEnhance.ExitException> { script.handlePromptDialog() }.reason)
    }

    @Test fun `first other close is refund and second is no materials or QP`() {
        script.handlePromptDialog()
        verify { with(api) { locations.servant.emptyEmberOrQPDialogRegion.click(1) } }
        assertEquals(AutoCEEnhance.ExitReason.NoMaterialsOrQP,
            assertFailsWith<AutoCEEnhance.ExitException> { script.handlePromptDialog() }.reason)
    }

    @Test fun `confirm clears just executed and allows the refund popup`() {
        script.execute()
        script.confirm()
        script.handlePromptDialog()
        assertEquals(AutoCEEnhance.ExitReason.NoMaterialsOrQP,
            assertFailsWith<AutoCEEnhance.ExitException> { script.handlePromptDialog() }.reason)
    }

    @Test fun `active disabled comparison rejects ties and low scores`() {
        assertEquals(CEEnhanceScreens.Recommended.Active, CEEnhanceScreens.recommended(0.886, 0.852))
        assertEquals(CEEnhanceScreens.Recommended.Disabled, CEEnhanceScreens.recommended(0.82, 0.922))
        assertEquals(CEEnhanceScreens.Recommended.Absent, CEEnhanceScreens.recommended(0.9, 0.9))
        assertEquals(CEEnhanceScreens.Recommended.Absent, CEEnhanceScreens.recommended(0.84, 0.83))
    }

    @Test fun `auto fill ON with no confirmation in three seconds stops`() {
        script.execute()
        script.confirm()
        every { with(api) { any<Map<Pattern, Region>>().exists(3.seconds, any(), any()) } } returns false
        assertEquals(AutoCEEnhance.ExitReason.NoMaterialsOrQP,
            assertFailsWith<AutoCEEnhance.ExitException> { script.autoSelect() }.reason)
        verify { with(api) { any<Map<Pattern, Region>>().exists(3.seconds, null, false) } }
    }

    @Test fun `toggle retries at most twice and rechecks each tap`() {
        val toggle = screens.loc.autoFill
        every { with(api) { toggle.exists(any<Pattern>(), any(), any()) } } returns false
        script.execute()
        verify(exactly = 2) { with(api) { toggle.click(1) } }
        verify(exactly = 3) { with(api) { toggle.exists(any<Pattern>(), any(), any()) } }
    }
}
