package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.CommandStartMode
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.GameServers
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.modules.Battle
import io.github.fate_grand_automata.scripts.modules.CommandStartGate
import io.github.fate_grand_automata.scripts.modules.TurnLimitRetreat
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.lib_automata.Location
import io.github.lib_automata.Match
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TurnLimitRetreatTest {
    private class Fixture(
        server: GameServer = GameServer.Kr,
        enabled: Boolean = true,
        turns: Int = 4,
        stopAfterStreak: Boolean = false,
        mode: CommandStartMode = CommandStartMode.Basic
    ) {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val config = mockk<IBattleConfig>(relaxed = true)
        val patterns = mutableMapOf<Images, Pattern>()
        val trace = mutableListOf<String>()

        /** Images that are on screen right now; clicks below change it like the game would. */
        val visible = mutableSetOf<Images>()
        var menuAppearsOnClick = true
        var dialogAppearsOnClick = true
        var closeAppearsOnClick = true

        val menuMatch = Match(Region(10, 10, 50, 50), 0.99)
        val retreatClick = Location(1, 1)
        val okClick = Location(2, 2)
        val menuXClick = Location(3, 3)
        val closeClick = Location(4, 4)
        val menuRegion = Region(100, 0, 10, 10)
        val retreatRegion = Region(200, 0, 10, 10)
        val okRegion = Region(300, 0, 10, 10)
        val closeRegion = Region(400, 0, 10, 10)
        val searchRegion = Region(500, 0, 10, 10)

        val retreat = TurnLimitRetreat(api, config)

        init {
            every { config.turnLimitRetreatEnabled } returns enabled
            every { config.turnLimitRetreatTurns } returns turns
            every { config.turnLimitRetreatStopAfterStreak } returns stopAfterStreak
            every { config.commandStartMode } returns mode
            every { api.prefs.gameServer } returns server
            every { api.locations.battle.master.battleMenuSearchRegion } returns searchRegion
            every { api.locations.turnLimitRetreatRegion } returns retreatRegion
            every { api.locations.turnLimitRetreatOkRegion } returns okRegion
            every { api.locations.ordealCallOutOfPodsRegion } returns closeRegion
            every { api.locations.turnLimitRetreatClick } returns retreatClick
            every { api.locations.withdrawAcceptClick } returns okClick
            every { api.locations.battleMenuCloseClick } returns menuXClick
            every { api.locations.ordealCallOutOfPodsClick } returns closeClick
            every { api.images[any(), any()] } answers { patterns.getOrPut(firstArg()) { mockk() } }
            every { with(api) { any<Region>().find(any<Pattern>(), any()) } } answers {
                val image = patterns.entries.first { it.value == secondArg<Pattern>() }.key
                if (image == Images.BattleMenu && firstArg<Region>() == searchRegion) menuMatch else null
            }
            every { with(api) { any<Region>().exists(any<Pattern>(), any(), any()) } } answers {
                val image = patterns.entries.first { it.value == secondArg<Pattern>() }.key
                val region = firstArg<Region>()
                when (image) {
                    Images.TurnLimitRetreat -> region == retreatRegion && Images.TurnLimitRetreat in visible
                    Images.TurnLimitRetreatConfirm -> region == okRegion && Images.TurnLimitRetreatConfirm in visible
                    Images.Close -> region == closeRegion && Images.Close in visible
                    else -> false
                }
            }
            every { with(api) { any<Region>().click(any()) } } answers {
                trace += "click-menu"
                if (menuAppearsOnClick) visible += Images.TurnLimitRetreat
            }
            every { with(api) { any<Location>().click(any()) } } answers {
                when (firstArg<Location>()) {
                    retreatClick -> { trace += "retreat"; if (dialogAppearsOnClick) visible += Images.TurnLimitRetreatConfirm }
                    okClick -> { trace += "ok"; visible.clear(); if (closeAppearsOnClick) visible += Images.Close }
                    menuXClick -> { trace += "menu-x"; visible.clear() }
                    closeClick -> { trace += "close"; visible.clear() }
                    else -> error("Unexpected click")
                }
            }
            for (duration in listOf(0.5.seconds, 5.seconds)) {
                every { with(api) { duration.wait(any()) } } answers { trace += "wait:$duration" }
            }
        }
    }

    // effective turn rule

    @Test
    fun `limit of four retreats on turn four and not on turn three`() {
        val f = Fixture(turns = 4)
        assertFalse(f.retreat.shouldRetreat(3))
        assertFalse(f.retreat.pending)
        assertTrue(f.retreat.shouldRetreat(4))
        assertTrue(f.retreat.pending)
    }

    private fun battle(f: Fixture, state: BattleState, gate: CommandStartGate) = Battle(
        f.api, mockk(relaxed = true), state, f.config, mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), gate, mockk(relaxed = true), f.retreat
    )

    @Test
    fun `battle retreats as turn four starts without playing it`() {
        val f = Fixture(turns = 4)
        val state = BattleState()
        repeat(3) { state.nextTurn() }
        every { f.api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
        val skills = mockk<io.github.fate_grand_automata.scripts.modules.AutoSkill>(relaxed = true)
        val battle = Battle(
            f.api, mockk(relaxed = true), state, f.config, skills, mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), CommandStartGate(f.config), mockk(relaxed = true), f.retreat
        )
        battle.performBattle()
        assertEquals(4, state.runTotalTurns)
        assertTrue(f.retreat.pending)
        verify(exactly = 0) { skills.execute(any(), any()) }
    }

    @Test
    fun `one card count waiting turn moves the retreat to game turn five`() {
        val f = Fixture(turns = 4, mode = CommandStartMode.CardCount)
        val gate = CommandStartGate(f.config)
        gate.recordWaitingTurn()
        val state = BattleState()
        repeat(3) { state.nextTurn() }
        every { f.api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
        val battle = battle(f, state, gate)
        // Game turn 4 minus one waiting turn is 3: the gate keeps handling it, no retreat.
        assertFalse(f.retreat.shouldRetreat(state.runTotalTurns + 1 - gate.waitingTurns))
        // Game turn 5 minus one waiting turn is 4: retreat.
        repeat(1) { state.nextTurn() }
        battle.performBattle()
        assertEquals(5, state.runTotalTurns)
        assertTrue(f.retreat.pending)
    }

    // not triggered

    @Test
    fun `not triggered when off, in the weekly route or on other servers`() {
        assertFalse(Fixture(enabled = false).retreat.shouldRetreat(9))
        val weekly = Fixture().also { it.retreat.disabled = true }
        assertFalse(weekly.retreat.shouldRetreat(9))
        for (server in GameServers.values.filter { it !is GameServer.Kr && it !is GameServer.Jp }) {
            val f = Fixture(server = server)
            assertFalse(f.retreat.shouldRetreat(9), "$server")
            assertFalse(f.retreat.pending)
        }
        assertTrue(Fixture(server = GameServer.Jp.Original).retreat.shouldRetreat(4))
    }

    @Test
    fun `other servers never load a retreat image`() {
        val f = Fixture(server = GameServer.En.Original)
        f.retreat.shouldRetreat(9)
        verify(exactly = 0) { f.api.images[Images.TurnLimitRetreat, any()] }
        verify(exactly = 0) { f.api.images[Images.TurnLimitRetreatConfirm, any()] }
    }

    // sequence

    @Test
    fun `full sequence taps menu, retreat, ok, close in order with the owner timings`() {
        val f = Fixture()
        f.retreat.execute()
        assertEquals(
            listOf("click-menu", "wait:500ms", "retreat", "wait:500ms", "ok", "wait:500ms", "close", "wait:5s"),
            f.trace
        )
        assertEquals(1, f.retreat.count)
        assertEquals(1, f.retreat.streak)
    }

    @Test
    fun `unverified confirmation never taps ok and closes the menu`() {
        val f = Fixture().also { it.dialogAppearsOnClick = false }
        val e = assertFailsWith<AutoBattle.BattleExitException> { f.retreat.execute() }
        assertEquals(AutoBattle.ExitReason.TurnLimitRetreatFailed, e.reason)
        assertFalse("ok" in f.trace)
        assertEquals("menu-x", f.trace.last())
        assertEquals(0, f.retreat.count)
    }

    @Test
    fun `menu that never opens fails after one retry`() {
        val f = Fixture().also { it.menuAppearsOnClick = false }
        val e = assertFailsWith<AutoBattle.BattleExitException> { f.retreat.execute() }
        assertEquals(AutoBattle.ExitReason.TurnLimitRetreatFailed, e.reason)
        assertEquals(2, f.trace.count { it == "click-menu" })
        assertFalse("retreat" in f.trace)
    }

    @Test
    fun `missing close returns to the loop without throwing`() {
        val f = Fixture().also { it.closeAppearsOnClick = false }
        f.retreat.execute()
        assertFalse("close" in f.trace)
        assertEquals(1, f.retreat.count)
    }

    @Test
    fun `third attempt without the quest list reappearing fails`() {
        val f = Fixture()
        f.retreat.execute(); f.retreat.execute()
        val e = assertFailsWith<AutoBattle.BattleExitException> { f.retreat.execute() }
        assertEquals(AutoBattle.ExitReason.TurnLimitRetreatFailed, e.reason)
        f.retreat.onQuestListSeen()
        f.retreat.execute()
    }

    // accounting

    @Test
    fun `a retreat leaves runs, the run limit and turn statistics unchanged`() {
        val f = Fixture()
        val state = BattleState()
        val gate = CommandStartGate(f.config)
        val battle = battle(f, state, gate)
        state.nextStage(); state.nextTurn(); state.nextTurn()
        battle.abandonRun()
        assertEquals(0, state.runs)
        assertEquals(-1, state.stage)
        assertEquals(0, state.runTotalTurns)
        assertEquals(0, state.maxTurnsPerRun)
        // resetState after a retreat must not count a run or write progress either.
        battle.resetState()
        assertEquals(0, state.runs)
        val prefs = f.api.prefs
        verify(exactly = 0) { prefs.updateCompletedRuns(any()) }
    }

    // streak and the main loop

    private fun entry(f: Fixture, state: BattleState, battle: Battle, questListBetween: Boolean = false): AutoBattle {
        every { f.api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
        every { battle.isIdle() } returns true
        // The real loop shows the quest list again (menu()) before the next battle.
        if (questListBetween) every { battle.abandonRun() } answers { f.retreat.onQuestListSeen() }
        every { battle.performBattle() } answers { f.retreat.shouldRetreat(f.config.turnLimitRetreatTurns); Unit }
        every { f.api.prefs.selectedServerConfigPref.shouldLimitRuns } returns false
        return AutoBattle(
            mockk(relaxed = true), f.api, state, battle, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), f.retreat
        )
    }

    @Test
    fun `three retreats in a row stop when the checkbox is on and leave runs at zero`() {
        val f = Fixture(stopAfterStreak = true)
        val state = BattleState()
        val battle = mockk<Battle>(relaxed = true)
        val exit = assertFailsWith<AutoBattle.ExitException> { entry(f, state, battle, questListBetween = true).script() }
        assertEquals(AutoBattle.ExitReason.TurnLimitRetreatStreak, exit.reason)
        assertEquals(3, exit.state.turnLimitRetreatCount)
        assertEquals(0, exit.state.timesRan)
        assertEquals(0, state.runs)
        verify(exactly = 3) { battle.abandonRun() }
    }

    @Test
    fun `without the checkbox retreats go on until the attempt guard stops the run`() {
        val f = Fixture(stopAfterStreak = false)
        val battle = mockk<Battle>(relaxed = true)
        val exit = assertFailsWith<AutoBattle.ExitException> { entry(f, BattleState(), battle).script() }
        // No quest list is ever seen here, so the per-run guard ends it after two attempts.
        assertEquals(AutoBattle.ExitReason.TurnLimitRetreatFailed, exit.reason)
        assertEquals(2, exit.state.turnLimitRetreatCount)
    }

    @Test
    fun `a cleared run resets the streak`() {
        val f = Fixture(stopAfterStreak = true)
        f.retreat.execute(); f.retreat.execute()
        assertEquals(2, f.retreat.streak)
        f.retreat.onRunCleared()
        assertEquals(0, f.retreat.streak)
        f.retreat.execute(); f.retreat.execute()
        f.retreat.checkStreak()
        f.retreat.onRunCleared()
        f.retreat.onQuestListSeen()
        f.retreat.execute(); f.retreat.execute()
        f.retreat.checkStreak() // two only: no stop
        f.retreat.onQuestListSeen()
        f.retreat.execute()
        assertFailsWith<AutoBattle.BattleExitException> { f.retreat.checkStreak() }
    }

    @Test
    fun `streak without the checkbox never stops`() {
        val f = Fixture(stopAfterStreak = false)
        repeat(5) { f.retreat.onQuestListSeen(); f.retreat.execute(); f.retreat.checkStreak() }
        assertEquals(5, f.retreat.streak)
    }
}
