package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.GameServers
import io.github.fate_grand_automata.scripts.locations.BattleScreenLocations
import io.github.fate_grand_automata.scripts.locations.IScriptAreaTransforms
import io.github.fate_grand_automata.scripts.locations.Locations
import io.github.fate_grand_automata.scripts.locations.MasterLocations
import io.github.fate_grand_automata.scripts.models.AutoSkillAction
import io.github.fate_grand_automata.scripts.models.OrderChangeMember
import io.github.fate_grand_automata.scripts.models.Skill
import io.github.fate_grand_automata.scripts.modules.Caster
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import io.github.lib_automata.AutomataApi
import io.github.lib_automata.Location
import io.github.lib_automata.Region
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CasterMasterSkillMenuDelayTest {
    private val transforms = object : IScriptAreaTransforms {
        override val scriptArea = Region(0, 0, 2560, 1440)
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

    private inner class Fixture(menuDelay: Duration) {
        val master = MasterLocations(transforms, mockk(relaxed = true), mockk<AutomataApi>(relaxed = true), mockk(relaxed = true))
        val battleLocations = BattleScreenLocations(transforms, master)
        val locations = Locations(
            transforms,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            battleLocations,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        )
        val prefs = mockk<IPreferences>(relaxed = true).apply {
            every { masterSkillMenuDelay } returns menuDelay
        }
        val api = mockk<IFgoAutomataApi>(relaxed = true).apply {
            every { this@apply.locations } returns this@Fixture.locations
            every { this@apply.prefs } returns this@Fixture.prefs
        }
        val caster = Caster(api, mockk(relaxed = true), mockk(relaxed = true))
    }

    private val orderChange = AutoSkillAction.OrderChange(OrderChangeMember.Starting.A, OrderChangeMember.Sub.A)

    @Test
    fun `master skill waits the default 1500 ms after the open tap`() {
        val f = Fixture(1500.milliseconds)

        f.caster.castMasterSkill(Skill.Master.A)

        verifyOrder {
            with(f.api) {
                f.master.masterSkillOpenClick.click()
                1500.milliseconds.wait(true)
            }
        }
    }

    @Test
    fun `master skill waits the configured 300 ms after the open tap`() {
        val f = Fixture(300.milliseconds)

        f.caster.castMasterSkill(Skill.Master.A)

        verifyOrder {
            with(f.api) {
                f.master.masterSkillOpenClick.click()
                300.milliseconds.wait(true)
            }
        }
        verify(exactly = 0) { with(f.api) { 1500.milliseconds.wait(any()) } }
    }

    @Test
    fun `order change waits the configured 300 ms after the open tap`() {
        val f = Fixture(300.milliseconds)

        f.caster.orderChange(orderChange)

        verifyOrder {
            with(f.api) {
                f.master.masterSkillOpenClick.click()
                300.milliseconds.wait(true)
                f.master.locate(Skill.Master.C).click()
            }
        }
        verify(exactly = 0) { with(f.api) { 1500.milliseconds.wait(any()) } }
    }

    @Test
    fun `command spell menu keeps its own 0_5 s wait whatever the master skill delay is`() {
        val f = Fixture(300.milliseconds)

        runCatching { f.caster.castCommandSpell(Skill.CommandSpell.NpCharge, null) }

        verifyOrder {
            with(f.api) {
                f.master.commandSpellClick.click()
                0.5.seconds.wait(true)
            }
        }
        verify(exactly = 0) { with(f.api) { 300.milliseconds.wait(any()) } }
    }
}
