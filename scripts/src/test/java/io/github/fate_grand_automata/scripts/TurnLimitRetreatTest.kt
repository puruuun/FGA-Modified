package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.CommandStartMode
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.GameServers
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.modules.Battle
import io.github.fate_grand_automata.scripts.modules.CommandStartGate
import io.github.fate_grand_automata.scripts.modules.TurnLimitRetreat
import io.github.fate_grand_automata.scripts.modules.Withdraw
import io.github.lib_automata.ScriptAbortException
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
        streakCount: Int = 3,
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
        var retryOnOk = false
        var ignoredOkTaps = 0

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
        val retryRegion = Region(600, 0, 10, 10)

        // Party wipe screen
        val withdrawRegion = Region(700, 0, 10, 10)
        val wipeMatch = Match(Region(710, 10, 20, 20), 0.99)
        val wipeCloseClick = Location(5, 5)
        val menuScreenRegion = Region(800, 0, 10, 10)
        var wipeVisible = true
        var wipeStaysVisible = true
        private var inWipe = false

        val retreat = TurnLimitRetreat(api, config)
        val withdraw = Withdraw(api, retreat)

        init {
            every { api.locations.withdrawRegion } returns withdrawRegion
            every { api.locations.withdrawCloseClick } returns wipeCloseClick
            every { api.locations.menuScreenRegion } returns menuScreenRegion
            every { with(api) { any<Region>().contains(any<Pattern>()) } } answers {
                val image = patterns.entries.first { it.value == secondArg<Pattern>() }.key
                when (image) {
                    Images.Withdraw -> firstArg<Region>() == withdrawRegion && wipeVisible
                    Images.Menu -> firstArg<Region>() == menuScreenRegion && Images.Menu in visible
                    else -> false
                }
            }
            every { config.turnLimitRetreatEnabled } returns enabled
            every { config.turnLimitRetreatTurns } returns turns
            every { config.turnLimitRetreatStopAfterStreak } returns stopAfterStreak
            every { config.turnLimitRetreatStreakCount } returns streakCount
            every { config.commandStartMode } returns mode
            every { api.prefs.gameServer } returns server
            every { api.locations.battle.master.battleMenuSearchRegion } returns searchRegion
            every { api.locations.turnLimitRetreatRegion } returns retreatRegion
            every { api.locations.turnLimitRetreatOkRegion } returns okRegion
            every { api.locations.ordealCallOutOfPodsRegion } returns closeRegion
            every { api.locations.retryRegion } returns retryRegion
            every { api.locations.turnLimitRetreatClick } returns retreatClick
            every { api.locations.withdrawAcceptClick } returns okClick
            every { api.locations.battleMenuCloseClick } returns menuXClick
            every { api.locations.ordealCallOutOfPodsClick } returns closeClick
            every { api.images[any(), any()] } answers { patterns.getOrPut(firstArg()) { mockk() } }
            every { with(api) { any<Region>().find(any<Pattern>(), any()) } } answers {
                val image = patterns.entries.first { it.value == secondArg<Pattern>() }.key
                when {
                    image == Images.BattleMenu && firstArg<Region>() == searchRegion -> menuMatch
                    image == Images.Withdraw && firstArg<Region>() == withdrawRegion && wipeVisible -> wipeMatch
                    else -> null
                }
            }
            every { with(api) { any<Region>().exists(any<Pattern>(), any(), any()) } } answers {
                val image = patterns.entries.first { it.value == secondArg<Pattern>() }.key
                val region = firstArg<Region>()
                when (image) {
                    Images.TurnLimitRetreat -> region == retreatRegion && Images.TurnLimitRetreat in visible
                    Images.TurnLimitRetreatConfirm -> region == okRegion && Images.TurnLimitRetreatConfirm in visible
                    Images.Close -> region == closeRegion && Images.Close in visible
                    Images.Retry -> region == retryRegion && Images.Retry in visible
                    else -> false
                }
            }
            every { with(api) { any<Region>().click(any()) } } answers {
                if (firstArg<Region>() == wipeMatch.region) {
                    trace += "wipe-withdraw"
                    inWipe = true
                } else {
                    trace += "click-menu"
                    if (menuAppearsOnClick) visible += Images.TurnLimitRetreat
                }
            }
            every { with(api) { any<Location>().click(any()) } } answers {
                when (firstArg<Location>()) {
                    retreatClick -> { trace += "retreat"; if (dialogAppearsOnClick) visible += Images.TurnLimitRetreatConfirm }
                    wipeCloseClick -> {
                        trace += "wipe-close"
                        inWipe = false
                        wipeVisible = wipeStaysVisible
                        if (!wipeStaysVisible) visible += Images.Menu
                    }
                    okClick -> {
                        trace += "ok"
                        if (inWipe) return@answers
                        if (ignoredOkTaps > 0) ignoredOkTaps-- else {
                            visible.clear()
                            if (closeAppearsOnClick) visible += Images.Close
                            if (retryOnOk) visible += Images.Retry
                        }
                    }
                    menuXClick -> { trace += "menu-x"; visible.clear() }
                    closeClick -> { trace += "close"; visible.clear() }
                    else -> error("Unexpected click")
                }
            }
            for (duration in listOf(0.5.seconds, 1.seconds, 6.seconds)) {
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
            listOf("click-menu", "wait:500ms", "retreat", "wait:500ms", "ok", "wait:1s", "close", "wait:6s"),
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
    fun `missing close with the connection retry dialog returns to the loop and counts`() {
        val f = Fixture().also { it.closeAppearsOnClick = false; it.retryOnOk = true }
        f.retreat.execute()
        assertFalse("close" in f.trace)
        assertEquals(1, f.trace.count { it == "ok" })
        assertEquals(1, f.retreat.count)
    }

    @Test
    fun `missing close while the confirmation still shows taps ok once more and then succeeds`() {
        val f = Fixture().also { it.ignoredOkTaps = 1 }
        f.retreat.execute()
        assertEquals(
            listOf("click-menu", "wait:500ms", "retreat", "wait:500ms", "ok", "wait:1s", "ok", "wait:1s", "close", "wait:6s"),
            f.trace
        )
        assertEquals(1, f.retreat.count)
        assertEquals(1, f.retreat.streak)
    }

    @Test
    fun `missing close with nothing else on screen fails before counting`() {
        val f = Fixture().also { it.closeAppearsOnClick = false }
        val e = assertFailsWith<AutoBattle.BattleExitException> { f.retreat.execute() }
        assertEquals(AutoBattle.ExitReason.TurnLimitRetreatFailed, e.reason)
        assertEquals(1, f.trace.count { it == "ok" })
        assertEquals(0, f.retreat.count)
        assertEquals(0, f.retreat.streak)
    }

    @Test
    fun `failed close wait never resets the run state`() {
        val f = Fixture().also { it.closeAppearsOnClick = false }
        val battle = mockk<Battle>(relaxed = true)
        val exit = assertFailsWith<AutoBattle.ExitException> { entry(f, BattleState(), battle).script() }
        assertEquals(AutoBattle.ExitReason.TurnLimitRetreatFailed, exit.reason)
        assertEquals(0, exit.state.turnLimitRetreatCount)
        verify(exactly = 0) { battle.abandonRun() }
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
        assertEquals(AutoBattle.ExitReason.TurnLimitRetreatStreak(3), exit.reason)
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

    @Test
    fun `count one stops on the first retreat`() {
        val f = Fixture(stopAfterStreak = true, streakCount = 1)
        f.retreat.execute()
        val e = assertFailsWith<AutoBattle.BattleExitException> { f.retreat.checkStreak() }
        assertEquals(AutoBattle.ExitReason.TurnLimitRetreatStreak(1), e.reason)
    }

    @Test
    fun `count five lets four retreats continue and stops on the fifth`() {
        val f = Fixture(stopAfterStreak = true, streakCount = 5)
        repeat(4) { f.retreat.onQuestListSeen(); f.retreat.execute(); f.retreat.checkStreak() }
        f.retreat.onQuestListSeen()
        f.retreat.execute()
        val e = assertFailsWith<AutoBattle.BattleExitException> { f.retreat.checkStreak() }
        assertEquals(AutoBattle.ExitReason.TurnLimitRetreatStreak(5), e.reason)
    }

    @Test
    fun `checkbox off never stops however small the count is`() {
        val f = Fixture(stopAfterStreak = false, streakCount = 1)
        repeat(30) { f.retreat.onQuestListSeen(); f.retreat.execute(); f.retreat.checkStreak() }
        assertEquals(30, f.retreat.streak)
    }

    @Test
    fun `a stored count below one is treated as one`() {
        val f = Fixture(stopAfterStreak = true, streakCount = 0)
        f.retreat.execute()
        val e = assertFailsWith<AutoBattle.BattleExitException> { f.retreat.checkStreak() }
        assertEquals(AutoBattle.ExitReason.TurnLimitRetreatStreak(1), e.reason)
    }

    @Test
    fun `a cleared run resets the streak toward a larger count`() {
        val f = Fixture(stopAfterStreak = true, streakCount = 4)
        repeat(3) { f.retreat.onQuestListSeen(); f.retreat.execute(); f.retreat.checkStreak() }
        f.retreat.onRunCleared()
        repeat(3) { f.retreat.onQuestListSeen(); f.retreat.execute(); f.retreat.checkStreak() }
        assertEquals(3, f.retreat.streak)
    }

    // retreat and restart: party wipe

    private val wipeTrace = listOf("wipe-withdraw", "wait:500ms", "ok", "wait:1s", "wipe-close")

    private fun wipeEntry(f: Fixture, state: BattleState, battle: Battle): AutoBattle {
        every { f.api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
        every { battle.isIdle() } returns false
        every { f.api.prefs.selectedServerConfigPref.shouldLimitRuns } returns false
        return AutoBattle(
            mockk(relaxed = true), f.api, state, battle, mockk(relaxed = true), f.withdraw,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), f.retreat
        )
    }

    @Test
    fun `off stops a party wipe with WithdrawDisabled and taps nothing`() {
        val f = Fixture(enabled = false)
        val e = assertFailsWith<AutoBattle.BattleExitException> { f.withdraw.withdraw() }
        assertEquals(AutoBattle.ExitReason.WithdrawDisabled, e.reason)
        assertTrue(f.trace.isEmpty())
        assertFalse(f.retreat.shouldRetreat(9))
    }

    @Test
    fun `zero turns withdraws on a wipe with the old tap sequence and never retreats on a turn`() {
        val f = Fixture(turns = 0)
        assertTrue(f.withdraw.withdraw())
        assertEquals(wipeTrace, f.trace)
        assertEquals(1, f.retreat.count)
        assertEquals(1, f.retreat.wipeCount)
        assertEquals(0, f.retreat.turnLimitCount)
        assertEquals(1, f.retreat.streak)
        for (turn in 1..20) assertFalse(f.retreat.shouldRetreat(turn))
        assertFalse(f.retreat.pending)
    }

    @Test
    fun `a wipe through the main loop abandons the run and counts it as a retreat not a run`() {
        val f = Fixture(turns = 6)
        val state = BattleState()
        val battle = mockk<Battle>(relaxed = true)
        every { battle.abandonRun() } throws ScriptAbortException()
        val exit = assertFailsWith<AutoBattle.ExitException> { wipeEntry(f, state, battle).script() }
        assertEquals(AutoBattle.ExitReason.Abort, exit.reason)
        assertEquals(1, exit.state.wipeWithdrawCount)
        assertEquals(0, exit.state.turnLimitRetreatCount)
        assertEquals(0, exit.state.timesRan)
        assertEquals(0, state.runs)
        verify(exactly = 1) { battle.abandonRun() }
        verify(exactly = 0) { battle.resetState() }
    }

    @Test
    fun `with six turns a wipe is a wipe withdraw and turn six is a turn limit retreat`() {
        val f = Fixture(turns = 6)
        assertFalse(f.retreat.shouldRetreat(4))
        f.withdraw.withdraw()
        assertTrue(f.retreat.shouldRetreat(6))
        f.retreat.execute()
        assertEquals(2, f.retreat.count)
        assertEquals(1, f.retreat.wipeCount)
        assertEquals(1, f.retreat.turnLimitCount)
    }

    @Test
    fun `wipe then retreat then wipe stops on the third with the checkbox on`() {
        val f = Fixture(turns = 6, stopAfterStreak = true)
        f.withdraw.withdraw(); f.retreat.checkStreak()
        f.retreat.onQuestListSeen(); f.retreat.execute(); f.retreat.checkStreak()
        f.retreat.onQuestListSeen(); f.withdraw.withdraw()
        val e = assertFailsWith<AutoBattle.BattleExitException> { f.retreat.checkStreak() }
        assertEquals(AutoBattle.ExitReason.TurnLimitRetreatStreak(3), e.reason)
        assertEquals(2, f.retreat.wipeCount)
        assertEquals(1, f.retreat.turnLimitCount)
    }

    @Test
    fun `three wipes in a row through the main loop end the script with the streak reason`() {
        val f = Fixture(stopAfterStreak = true)
        val exit = assertFailsWith<AutoBattle.ExitException> {
            wipeEntry(f, BattleState(), mockk<Battle>(relaxed = true)).script()
        }
        assertEquals(AutoBattle.ExitReason.TurnLimitRetreatStreak(3), exit.reason)
        assertEquals(3, exit.state.wipeWithdrawCount)
        assertEquals(0, exit.state.timesRan)
    }

    @Test
    fun `a wipe does not reset the streak, a cleared run does`() {
        val f = Fixture(stopAfterStreak = true)
        f.withdraw.withdraw(); f.withdraw.withdraw()
        assertEquals(2, f.retreat.streak)
        // Resetting after a wipe goes through abandonRun, which leaves the streak alone.
        val state = BattleState()
        val battle = battle(f, state, CommandStartGate(f.config))
        state.nextStage()
        battle.abandonRun()
        battle.resetState()
        assertEquals(2, f.retreat.streak)
        f.retreat.onRunCleared()
        assertEquals(0, f.retreat.streak)
    }

    @Test
    fun `other servers treat a stored turn count as zero, load no retreat image and still withdraw on a wipe`() {
        for (server in GameServers.values.filter { it !is GameServer.Kr && it !is GameServer.Jp }) {
            val f = Fixture(server = server, turns = 6)
            assertEquals(0, f.retreat.limitTurns, "$server")
            for (turn in 1..20) assertFalse(f.retreat.shouldRetreat(turn))
            assertTrue(f.withdraw.withdraw(), "$server")
            assertEquals(wipeTrace, f.trace, "$server")
            assertFalse(Images.TurnLimitRetreat in f.patterns.keys, "$server")
            assertFalse(Images.TurnLimitRetreatConfirm in f.patterns.keys, "$server")
            assertEquals(1, f.retreat.wipeCount)
        }
        assertEquals(6, Fixture(server = GameServer.Kr, turns = 6).retreat.limitTurns)
    }

    @Test
    fun `weekly route withdraws on a wipe with the switch on, then the route quest ends`() {
        val f = Fixture().also { it.wipeStaysVisible = false }
        val battle = mockk<Battle>(relaxed = true)
        val exit = assertFailsWith<AutoBattle.ExitException> { wipeEntry(f, BattleState(), battle).runSelectedQuest() }
        assertEquals(AutoBattle.ExitReason.StopAfterThisRun, exit.reason)
        assertEquals(wipeTrace, f.trace.take(wipeTrace.size))
        assertEquals(1, exit.state.wipeWithdrawCount)
    }

    @Test
    fun `weekly route stops on a wipe when the switch is off`() {
        val f = Fixture(enabled = false)
        val battle = mockk<Battle>(relaxed = true)
        val exit = assertFailsWith<AutoBattle.ExitException> { wipeEntry(f, BattleState(), battle).runSelectedQuest() }
        assertEquals(AutoBattle.ExitReason.WithdrawDisabled, exit.reason)
        assertTrue(f.trace.isEmpty())
    }

    @Test
    fun `exit state keeps the wipe and turn limit counts apart`() {
        val f = Fixture(turns = 6)
        f.withdraw.withdraw(); f.withdraw.withdraw()
        f.retreat.execute()
        assertEquals(3, f.retreat.count)
        assertEquals(2, f.retreat.wipeCount)
        assertEquals(1, f.retreat.turnLimitCount)
    }
}
