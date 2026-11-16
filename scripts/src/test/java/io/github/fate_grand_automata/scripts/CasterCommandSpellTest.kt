package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.GameServers
import io.github.fate_grand_automata.scripts.locations.BattleScreenLocations
import io.github.fate_grand_automata.scripts.locations.IScriptAreaTransforms
import io.github.fate_grand_automata.scripts.locations.Locations
import io.github.fate_grand_automata.scripts.locations.MasterLocations
import io.github.fate_grand_automata.scripts.models.ServantTarget
import io.github.fate_grand_automata.scripts.models.Skill
import io.github.fate_grand_automata.scripts.modules.Caster
import io.github.lib_automata.AutomataApi
import io.github.lib_automata.Location
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class CasterCommandSpellTest {
    private fun transforms(width: Int): IScriptAreaTransforms = object : IScriptAreaTransforms {
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
    }

    private inner class Fixture(width: Int) {
        val transforms = transforms(width)
        val automataApi = mockk<AutomataApi>(relaxed = true)
        val imageLoader = mockk<IImageLoader>(relaxed = true)
        val messages = mockk<IScriptMessages>(relaxed = true)
        val master = MasterLocations(transforms, imageLoader, automataApi, messages)
        val battleLocations = BattleScreenLocations(transforms, master)
        val locations = Locations(
            transforms,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            battleLocations,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        val api = mockk<IFgoAutomataApi>(relaxed = true).apply {
            every { this@apply.locations } returns this@Fixture.locations
            every { this@apply.images } returns this@Fixture.imageLoader
        }
        val caster = Caster(api, mockk(relaxed = true), mockk(relaxed = true))
    }

    @Test
    fun `casting command spell taps diamond then NP charge without searching label or battle menu, and throws OutOfCommandSpells when dialog is missing`() {
        for (width in listOf(2560, 3360)) {
            val f = Fixture(width)

            val ex = assertFailsWith<AutoBattle.BattleExitException> {
                f.caster.castCommandSpell(Skill.CommandSpell.NpCharge, ServantTarget.A)
            }
            assertEquals(AutoBattle.ExitReason.OutOfCommandSpells, ex.reason)

            verify(exactly = 0) {
                f.imageLoader[Images.CommandSpell, any()]
                f.imageLoader[Images.BattleMenu, any()]
            }
            verifyOrder {
                with(f.api) {
                    f.master.commandSpellClick.click()
                    f.master.locate(Skill.CommandSpell.NpCharge).click()
                }
            }
            verify(exactly = 0) {
                with(f.api) {
                    f.battleLocations.skillOkClick.click()
                }
            }
        }
    }

    @Test
    fun `casting command spell confirms OK after diamond and NP charge clicks when cancel button appears`() {
        for (width in listOf(2560, 3360)) {
            val f = Fixture(width)
            val cancelPattern = mockk<Pattern>(relaxed = true)
            every { f.imageLoader[Images.Cancel] } returns cancelPattern
            every {
                with(f.api) {
                    f.master.cancelCommandSpellRegion.exists(cancelPattern, 2.seconds)
                }
            } returns true

            f.caster.castCommandSpell(Skill.CommandSpell.NpCharge, ServantTarget.A)

            verify(exactly = 0) {
                f.imageLoader[Images.CommandSpell, any()]
                f.imageLoader[Images.BattleMenu, any()]
            }
            verifyOrder {
                with(f.api) {
                    f.master.commandSpellClick.click()
                    f.master.locate(Skill.CommandSpell.NpCharge).click()
                    f.battleLocations.skillOkClick.click()
                }
            }
        }
    }
}
