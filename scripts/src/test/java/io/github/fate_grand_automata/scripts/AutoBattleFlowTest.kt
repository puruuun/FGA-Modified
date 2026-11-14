package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.GameServers
import io.github.fate_grand_automata.scripts.models.BoostItem
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.modules.*
import io.github.lib_automata.*
import io.mockk.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class AutoBattleFlowTest {
    private class Fixture(server: GameServer = GameServer.Kr) {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val state = BattleState()
        val support = spyk(Support(mockk(), mockk(), mockk(relaxed = true)))
        val refill = mockk<Refill>(relaxed = true)
        val party = mockk<PartySelection>(relaxed = true)
        val items = mockk<QuestItems>(relaxed = true)
        val trace = mutableListOf<String>()
        val close = Location(100, 100)
        val quest = Location(200, 200)
        val start = Location(300, 300)
        val popup = Location(1280, 100)
        val continueRegion = Region(1400, 1000, 800, 300)
        val normalRepeatRegion = Region(1500, 1100, 100, 50)
        val boostRepeatRegion = Region(1700, 1100, 100, 50)
        val continueBoost = Location(1260, 1120)
        val boostItem1 = Location(2560, 418)
        val boostSkip = Location(2932, 1304)
        val clock = TestTimeSource()
        val patterns = mutableMapOf<Images, Pattern>()
        var screen = "dialog"
        var podsEmpty = false
        var missionPopupClear = false
        var missionPopupClearDuration = 2.seconds
        var boostMode = -1
        var repeatVisible = false
        var repeatBoostShifted = false
        var disappearAfterPopupTap = false
        var onLoopWait: (() -> Unit)? = null
        val battle: Battle
        val entry: AutoBattle

        init {
            every { api.prefs.gameServer } returns server
            every { api.prefs.boostItemSelectionMode } answers { boostMode }
            every { api.prefs.missionPopupClear } answers { missionPopupClear }
            every { api.prefs.missionPopupClearDuration } answers { missionPopupClearDuration }
            every { api.prefs.selectedServerConfigPref.shouldLimitRuns } returns false
            every { api.locations.scriptArea } returns Region(0, 0, 2560, 1440)
            every { api.locations.continueRegion } returns continueRegion
            every { api.locations.continueBoostClick } returns continueBoost
            every { api.locations.missionPopupClearClick } returns popup
            every { api.locations.locate(BoostItem.Enabled.BoostItem1) } returns boostItem1
            every { api.locations.locate(BoostItem.Enabled.Skip) } returns boostSkip
            every { api.locations.ordealCallOutOfPodsClick } returns close
            every { api.locations.menuSelectQuestClick } returns quest
            every { api.locations.menuStartQuestClick } returns start
            every { api.images[any(), any()] } answers { patterns.getOrPut(firstArg()) { mockk() } }
            every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
            every { with(api) { any<Region>().contains(any<Pattern>()) } } answers {
                patterns[Images.Menu] == secondArg<Pattern>() && screen == "menu"
            }
            every { with(api) { any<Region>().exists(any<Pattern>(), any(), any()) } } answers {
                patterns[Images.SupportScreen] == secondArg<Pattern>() && screen == "support"
            }
            every { with(api) { any<Region>().find(any<Pattern>(), any()) } } answers {
                if (firstArg<Region>() == continueRegion && secondArg<Pattern>() == patterns[Images.Repeat] && screen == "repeat") {
                    trace += "find:repeat"
                    if (!repeatVisible) null
                    else Match(if (repeatBoostShifted) boostRepeatRegion else normalRepeatRegion, 1.0)
                } else null
            }
            every { api.findImage(any(), any()) } answers {
                secondArg<Images>() == Images.Close && screen in listOf("dialog", "out-of-pods")
            }
            every { with(api) { any<Location>().click(any()) } } answers {
                val times = secondArg<Int>()
                when (firstArg<Location>()) {
                    close -> { trace += "close:${state.runs}"; screen = "menu" }
                    quest -> { trace += "quest:${state.runs}"; screen = if (podsEmpty) "out-of-pods" else "selected" }
                    start -> { trace += "start"; screen = "started" }
                    popup -> {
                        trace += "popup:$times"
                        clock += 800.milliseconds
                        if (disappearAfterPopupTap) repeatVisible = false
                    }
                    normalRepeatRegion.center -> { trace += "click:repeat" }
                    boostRepeatRegion.center -> { trace += "click:repeatBoost" }
                    continueBoost -> { trace += "click:continueBoost" }
                    boostItem1 -> { trace += "click:boost1" }
                    boostSkip -> { trace += "click:boostSkip" }
                    else -> error("Unexpected click")
                }
            }
            every { with(api) { any<Region>().click(any()) } } answers {
                when (firstArg<Region>()) {
                    normalRepeatRegion -> trace += "click:repeat"
                    boostRepeatRegion -> trace += "click:repeatBoost"
                    else -> error("Unexpected region click")
                }
            }
            every { refill.refill() } answers { throw AutoBattle.BattleExitException(AutoBattle.ExitReason.Abort) }
            every { support.selectSupport(any()) } answers { trace += "support"; screen = "party" }
            every { party.selectParty() } answers { trace += "party" }
            every { items.prepare() } answers { trace += "items" }
            for (duration in listOf(0.5.seconds, 1.seconds, 2.seconds, 2.5.seconds, 4.seconds, 5.seconds)) {
                every { with(api) { duration.wait(any()) } } answers {
                    trace += "wait:$duration"
                    if (duration == 0.5.seconds) onLoopWait?.invoke()
                    if (duration == 5.seconds) throw AutoBattle.BattleExitException(AutoBattle.ExitReason.Abort)
                }
            }
            // Real resetState, including its stage guard, is what counts the completed run.
            battle = Battle(api, mockk(relaxed = true), state, mockk(relaxed = true),
                mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
                mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
                mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
            entry = AutoBattle(mockk(relaxed = true), api, state, battle, support,
                mockk(relaxed = true), QuestLaunch(api, party, items), mockk(relaxed = true),
                mockk(relaxed = true), refill, mockk(relaxed = true), mockk(relaxed = true),
                mockk(relaxed = true), mockk(relaxed = true),
                missionPopupClear = MissionPopupClear(api, popup, clock))
        }

        fun run() = assertFailsWith<AutoBattle.ExitException> { entry.script() }.also {
            if (it.reason is AutoBattle.ExitReason.Unexpected) throw it.reason.cause!!
        }
    }

    @Test fun `bottom centre dialog closes without exiting and next menu counts the battle exactly once on every server`() {
        for (server in GameServers.values) {
            val f = Fixture(server)
            f.state.nextStage()
            val exit = f.run()
            assertEquals(AutoBattle.ExitReason.Abort, exit.reason)
            assertEquals(listOf("close:0", "wait:500ms", "quest:1", "wait:2.5s"), f.trace)
            assertEquals(1, f.state.runs)
            assertEquals(1, exit.state.timesRan)
            val prefs = f.api.prefs
            verify(exactly = 1) { prefs.updateCompletedRuns(1) }
        }
    }

    @Test fun `out of pods stops at the next quest tap with the completed battle counted once`() {
        val f = Fixture()
        repeat(3) { f.state.nextRun() }
        f.state.nextStage()
        f.podsEmpty = true
        val exit = f.run()
        assertEquals(4, (exit.reason as AutoBattle.ExitReason.LimitRuns).count)
        assertEquals(4, exit.state.timesRan)
        assertEquals(listOf("close:3", "wait:500ms", "quest:4", "wait:2.5s"), f.trace)
        verify(exactly = 0) { f.refill.refill() }
    }

    @Test fun `out of pods at quest start retains the current count and the single check after two point five seconds`() {
        for (runs in listOf(0, 3)) {
            val f = Fixture()
            repeat(runs) { f.state.nextRun() }
            f.screen = "menu"; f.podsEmpty = true
            val exit = f.run()
            assertEquals(runs, (exit.reason as AutoBattle.ExitReason.LimitRuns).count)
            assertEquals(runs, exit.state.timesRan)
            assertEquals(listOf("quest:$runs", "wait:2.5s"), f.trace)
            val region = f.api.locations.ordealCallOutOfPodsRegion
            verify(exactly = 1) { f.api.findImage(region, Images.Close) }
        }
    }

    @Test fun `support to party items and Start has the same fixed waits on every server`() {
        for (server in GameServers.values) {
            val f = Fixture(server); f.screen = "support"
            assertEquals(AutoBattle.ExitReason.Abort, f.run().reason)
            assertEquals(listOf("support", "wait:4s", "party", "wait:1s", "items", "wait:1s", "start", "wait:2s", "wait:5s"), f.trace)
        }
    }

    @Test fun `continuing after support does not select a party or launch again`() {
        val f = Fixture(); f.screen = "support"
        // Enter the same support handler state established by repeatQuest.
        AutoBattle::class.java.getDeclaredField("isContinuing").apply { isAccessible = true }.set(f.entry, true)
        every { with(f.api) { 0.5.seconds.wait() } } answers { throw AutoBattle.BattleExitException(AutoBattle.ExitReason.Abort) }
        assertEquals(AutoBattle.ExitReason.Abort, f.run().reason)
        assertEquals(listOf("support"), f.trace)
        verify(exactly = 0) { f.party.selectParty(); f.items.prepare() }
    }

    @Test fun `repeatQuest clears mission popups before looking up and clicking Repeat when switch is on`() {
        val f = Fixture().apply {
            screen = "repeat"
            repeatVisible = true
            missionPopupClear = true
            missionPopupClearDuration = 2.seconds
        }
        assertEquals(AutoBattle.ExitReason.Abort, f.run().reason)
        assertEquals(
            listOf("find:repeat", "popup:10", "popup:10", "popup:10", "find:repeat", "click:repeat", "wait:2.5s"),
            f.trace,
        )
    }

    @Test fun `repeatQuest skips mission popup taps when switch is off`() {
        val f = Fixture().apply {
            screen = "repeat"
            repeatVisible = true
            missionPopupClear = false
        }
        assertEquals(AutoBattle.ExitReason.Abort, f.run().reason)
        assertEquals(
            listOf("find:repeat", "find:repeat", "click:repeat", "wait:2.5s"),
            f.trace,
        )
    }

    @Test fun `repeatQuest does not click repeat when button vanishes after popup taps and does not re-tap on next loop`() {
        val f = Fixture().apply {
            screen = "repeat"
            repeatVisible = true
            disappearAfterPopupTap = true
            missionPopupClear = true
            missionPopupClearDuration = 2.seconds
            onLoopWait = {
                repeatVisible = true
                disappearAfterPopupTap = false
            }
        }
        assertEquals(AutoBattle.ExitReason.Abort, f.run().reason)
        assertEquals(
            listOf(
                "find:repeat",
                "popup:10",
                "popup:10",
                "popup:10",
                "find:repeat",
                "wait:500ms",
                "find:repeat",
                "find:repeat",
                "click:repeat",
                "wait:2.5s",
            ),
            f.trace,
        )
    }

    @Test fun `repeatQuest runs boost path after clearing mission popups when repeat button is boost-shifted`() {
        val f = Fixture().apply {
            screen = "repeat"
            repeatVisible = true
            repeatBoostShifted = true
            boostMode = 1
            missionPopupClear = true
            missionPopupClearDuration = 2.seconds
        }
        assertEquals(AutoBattle.ExitReason.Abort, f.run().reason)
        assertEquals(
            listOf(
                "find:repeat",
                "popup:10",
                "popup:10",
                "popup:10",
                "find:repeat",
                "click:continueBoost",
                "click:boost1",
                "click:boostSkip",
                "wait:2.5s",
            ),
            f.trace,
        )
    }
}
