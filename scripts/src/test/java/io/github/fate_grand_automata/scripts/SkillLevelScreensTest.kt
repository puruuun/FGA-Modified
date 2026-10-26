package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.GameServers
import io.github.fate_grand_automata.scripts.locations.IScriptAreaTransforms
import io.github.fate_grand_automata.scripts.locations.SkillLevelLocations
import io.github.fate_grand_automata.scripts.modules.SkillLevelMode
import io.github.fate_grand_automata.scripts.modules.SkillLevelScores
import io.github.fate_grand_automata.scripts.modules.SkillLevelScreens
import io.github.lib_automata.Location
import io.github.lib_automata.Match
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #33: the skill / append screens are told apart by the centre-left material-list label. */
class SkillLevelScreensTest {
    private val labelRegion = Region(720, 730, 1240, 140)
    private val reselectRegion = Region(340, 0, 340, 180)
    private val appendLabel = mockk<Pattern>()
    private val skillLabel = mockk<Pattern>()
    private val reselect = mockk<Pattern>()
    private val api = mockk<IFgoAutomataApi>(relaxed = true)
    private val loc = mockk<SkillLevelLocations>(relaxed = true)
    private val screens = SkillLevelScreens(api, loc)

    init {
        every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
        every { loc.label } returns labelRegion
        every { loc.reselect } returns reselectRegion
        every { api.images[Images.AppendLevelLabel] } returns appendLabel
        every { api.images[Images.SkillLevelLabel] } returns skillLabel
        every { api.images[Images.SkillLevelReselect] } returns reselect
    }

    private fun seen(pattern: Pattern, region: Region = labelRegion, onScreen: Boolean) =
        with(api) { every { region.exists(pattern, any(), any()) } returns onScreen }

    @Test fun `append label is checked before the skill label`() {
        seen(appendLabel, onScreen = true)
        seen(skillLabel, onScreen = true)
        assertEquals(SkillLevelMode.Append, screens.detectMode())
    }

    @Test fun `skill label alone is the skill screen and no label is no screen`() {
        seen(appendLabel, onScreen = false)
        seen(skillLabel, onScreen = true)
        assertEquals(SkillLevelMode.Skill, screens.detectMode())
        seen(skillLabel, onScreen = false)
        assertNull(screens.detectMode())
    }

    @Test fun `labels are matched at 0 90 and onLabel follows the requested mode`() {
        assertEquals(0.90, SkillLevelScreens.LABEL_SIMILARITY)
        seen(appendLabel, onScreen = false)
        seen(skillLabel, onScreen = true)
        assertTrue(screens.onLabel(SkillLevelMode.Skill))
        assertTrue(!screens.onLabel(SkillLevelMode.Append))
        with(api) { verify { labelRegion.exists(skillLabel, any(), 0.90) } }
        with(api) { verify { labelRegion.exists(appendLabel, any(), 0.90) } }
    }

    @Test fun `a template missing on this server never counts as a match`() {
        every { api.images[Images.AppendLevelLabel] } throws IllegalStateException("missing")
        every { api.images[Images.SkillLevelLabel] } throws IllegalStateException("missing")
        assertNull(screens.detectMode())
    }

    @Test fun `best scores take the highest match above the floor and null below it`() {
        with(api) {
            every { labelRegion.findAll(appendLabel, SkillLevelScreens.SCORE_FLOOR) } returns
                listOf(Match(Region(0, 0, 1, 1), 0.41), Match(Region(5, 0, 1, 1), 0.998))
            every { labelRegion.findAll(skillLabel, SkillLevelScreens.SCORE_FLOOR) } returns emptyList()
            every { reselectRegion.findAll(reselect, SkillLevelScreens.SCORE_FLOOR) } returns
                listOf(Match(Region(0, 0, 1, 1), 1.0))
        }
        val scores = screens.bestScores()
        assertEquals(SkillLevelScores(0.998, null, 1.0), scores)
        assertEquals(
            "scores label-append=0.998 label-skill=<0.30 reselect=1.000 detected=Append",
            scores.describe(SkillLevelMode.Append)
        )
        assertEquals("scores label-append=0.998 label-skill=<0.30 reselect=1.000 detected=none", scores.describe(null))
    }

    // ---- the search region against the template boxes measured on the owner's 20 captures ----

    private fun locations(width: Int): SkillLevelLocations = SkillLevelLocations(object : IScriptAreaTransforms {
        override val scriptArea = Region(0, 0, width, 1440)
        override val isWide = scriptArea.size.isWide()
        override val gameServer: GameServer = GameServers.default
        override val canLongSwipe = false
        override fun Location.xFromCenter() = this + Location(scriptArea.center.x, 0)
        override fun Region.xFromCenter() = this + Location(scriptArea.center.x, 0)
        override fun Location.xFromRight() = this + Location(scriptArea.right, 0)
        override fun Region.xFromRight() = this + Location(scriptArea.right, 0)
        override fun Location.yFromBottom() = this + Location(0, scriptArea.bottom)
        override fun Region.yFromBottom() = this + Location(0, scriptArea.bottom)
    })

    /** (left, width) at 720p from the game-area centre-based capture, top y 383 (16:9) / 358 (21:9), height 34. */
    private val templates = listOf(390 to 278, 385 to 418, 390 to 277, 385 to 362)

    @Test fun `label region holds every template with margin on 16 9 and 21 9`() {
        for ((width, top720) in listOf(2560 to 383, 3360 to 358)) {
            val region = locations(width).label
            val center = width / 2
            for ((left, w) in templates) {
                // 720p x is measured on a 1280 wide game area: its centre is 640.
                val x1440 = center + (left - 640) * 2
                assertTrue(x1440 - region.x >= 40, "left margin $left on $width")
                assertTrue(region.right - (x1440 + w * 2) >= 40, "right margin $left+$w on $width")
                assertTrue(top720 * 2 - region.y >= 30, "top margin on $width")
                assertTrue(region.bottom - (top720 + 34) * 2 >= 30, "bottom margin on $width")
            }
        }
    }
}
