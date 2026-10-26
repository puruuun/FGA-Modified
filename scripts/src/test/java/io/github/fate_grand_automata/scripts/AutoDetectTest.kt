package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.entrypoints.AutoDetect
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.ScriptModeEnum
import io.github.fate_grand_automata.scripts.modules.SkillLevelScreens
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.mockk.*
import kotlin.test.*

class AutoDetectTest {
    private class Fixture {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val screens = mockk<SkillLevelScreens>(relaxed = true)
        val patterns = mutableMapOf<Images, Pattern>()
        val banner = Region(10, 20, 30, 40)
        val initialBanner = Region(50, 60, 30, 40)
        var visibleBanner: Region? = null
        val detector = AutoDetect(api, screens)

        init {
            every { api.prefs.gameServer } returns GameServer.Kr
            every { api.locations.fp.summonCheck } returns banner
            every { api.locations.fp.initialSummonCheck } returns initialBanner
            every { api.images[any(), any()] } answers { patterns.getOrPut(firstArg()) { mockk() } }
            every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
            every { with(api) { any<Region>().contains(any<Pattern>()) } } answers {
                firstArg<Region>() == visibleBanner && secondArg<Pattern>() == patterns[Images.FriendSummon]
            }
            every { with(api) { any<Region>().exists(any<Pattern>(), any(), any()) } } returns false
            every { screens.detectMode() } returns null
            // The result screen offers Continue, but that signal must never select FP.
            every { api.findImage(any(), Images.FPSummonContinue) } returns true
        }
    }

    @Test fun `FP banner and initial banner still select FP`() {
        for (initial in listOf(false, true)) {
            val f = Fixture()
            f.visibleBanner = if (initial) f.initialBanner else f.banner
            assertEquals(ScriptModeEnum.FP, f.detector.get())
        }
    }

    @Test fun `FP continue result alone falls back to Battle without checking Continue`() {
        val f = Fixture()
        assertEquals(ScriptModeEnum.Battle, f.detector.get())
        verify(exactly = 0) { f.api.findImage(any(), Images.FPSummonContinue) }
    }
}
