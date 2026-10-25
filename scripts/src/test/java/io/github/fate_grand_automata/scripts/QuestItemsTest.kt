package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.locations.QuestItemLocations
import io.github.fate_grand_automata.scripts.modules.*
import io.github.fate_grand_automata.scripts.modules.QuestItemReader.Kind
import io.github.fate_grand_automata.scripts.modules.QuestItemReader.View
import io.github.lib_automata.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class QuestItemsTest {
    @Test
    fun `every item tap including dialog close gets an extra fixed 100 milliseconds`() {
        for (dialog in listOf(true, false)) {
            val f = Fixture(dialogMode = dialog)
            f.wanted(true, true)
            val settledTaps = mutableListOf<String>()
            every { with(f.api) { 100.milliseconds.wait(false) } } answers {
                settledTaps += f.trace.last()
            }
            f.launch.start()
            assertThat(settledTaps).isEqualTo(f.trace.filter { it in listOf("main", "storm", "tea", "close") })
            assertTrue(settledTaps.isNotEmpty())
            assertThat(settledTaps.last()).isEqualTo(if (dialog) "close" else "main")
        }
    }

    private class Fixture(val dialogMode: Boolean = true, val kind: Kind = Kind.Teapot, val server: GameServer = GameServer.Jp.Original) {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val party = mockk<PartySelection>()
        val loc = mockk<QuestItemLocations>()
        val main = Region(1420, 1268, 520, 158)
        val left = Region(1030, 730, 200, 164)
        val right = Region(1530, 730, 200, 164)
        val heading = Region(760, 160, 1040, 160)
        val close = Region(970, 1040, 620, 174)
        val quest = Location(2400, 1350)
        val patterns = mutableMapOf<Images, Pattern>()
        val trace = mutableListOf<String>()
        var storm = false
        var tea = false
        var modal = false
        var nativeHeadingOnly = false
        var snapshot = false
        var absent = false
        var hideState = false
        var hideIcon = false
        var ambiguousState = false
        var ambiguousIcon = false
        var ignoreTaps = false
        var ignoreRestore = false
        var ignoreClose = false
        var partialDialog = false
        var wrongAfterClose = false
        var pending: (() -> Unit)? = null
        var framesUntilChange = 0
        var settleFrames = 0
        val reader: QuestItemReader
        val items: QuestItems
        val launch: QuestLaunch

        private fun state(region: Region): Boolean? = when {
            region == main && !absent && !modal && !hideState -> if (kind == Kind.StormPod) storm else tea
            region == left && modal -> storm
            region == right && modal -> tea
            else -> null
        }
        private fun match(region: Region) = Match(Region(region.x + 40, region.y + 20, 60, 40), 0.98)
        private fun change(action: () -> Unit) {
            if (settleFrames == 0) action() else { pending = action; framesUntilChange = settleFrames }
        }
        init {
            every { api.prefs.gameServer } returns server
            every { api.prefs.ignoreQuestItemUsage } returns false
            every { api.prefs.selectedServerConfigPref.useStormPod } returns false
            every { api.prefs.selectedServerConfigPref.useTeapot } returns false
            every { api.locations.questItems } returns loc
            every { loc.isWide } returns false
            every { loc.mainControl } returns main
            every { loc.stormPodControl } returns left
            every { loc.teapotControl } returns right
            every { loc.dialogHeading } returns heading
            every { loc.dialogClose } returns close
            every { api.locations.menuStartQuestClick } returns quest
            every { api.images[any(), any()] } answers { patterns.getOrPut(firstArg()) { mockk<Pattern>() } }
            every { api.useSameSnapIn<Any?>(any()) } answers {
                assertFalse(snapshot)
                if (pending != null && framesUntilChange-- <= 0) { pending!!.invoke(); pending = null }
                snapshot = true
                try { firstArg<() -> Any?>().invoke() } finally { snapshot = false }
            }
            every { with(api) { any<Region>().exists(any<Pattern>(), any(), any()) } } answers {
                assertTrue(snapshot, "All state evidence must come from one fresh frame")
                val region = firstArg<Region>()
                val image = patterns.entries.single { it.value == secondArg<Pattern>() }.key
                when (image) {
                    Images.QuestItemDialog -> region == heading && modal && !nativeHeadingOnly
                    Images.QuestItemDialogKrNative -> region == heading && modal && nativeHeadingOnly
                    Images.QuestItemDialogClose -> region == close && modal && !partialDialog
                    Images.QuestItemTeapot -> region == main && !absent && !modal && !hideIcon && (kind == Kind.Teapot || ambiguousIcon)
                    Images.QuestItemStormPod -> region == main && !absent && !modal && !hideIcon && (kind == Kind.StormPod || ambiguousIcon)
                    else -> false
                }
            }
            every { with(api) { any<Region>().findAll(any<Pattern>(), any()) } } answers {
                assertTrue(snapshot)
                val region = firstArg<Region>()
                val image = patterns.entries.single { it.value == secondArg<Pattern>() }.key
                val on = state(region)
                val found = when (image) {
                    Images.QuestItemDialogClose -> region == close && modal && !partialDialog
                    Images.QuestItemOn, Images.QuestItemOnMain -> on == true || (on != null && ambiguousState)
                    Images.QuestItemOff, Images.QuestItemOffMain -> on == false || (on != null && ambiguousState)
                    else -> false
                }
                if (found) listOf(match(region)) else emptyList()
            }
            every { party.selectParty() } answers { trace.add("party") }
            every { with(api) { any<Location>().click(any()) } } answers {
                assertFalse(snapshot, "Never click under a cached screenshot")
                when (firstArg<Location>()) {
                    match(main).region.center -> {
                        trace.add("main")
                        if (!ignoreTaps && !(ignoreRestore && trace.count { it == "main" } > 1)) change { if (dialogMode) modal = true else if (kind == Kind.StormPod) storm = !storm else tea = !tea }
                    }
                    match(left).region.center -> { trace.add("storm"); if (!ignoreTaps) change { storm = !storm } }
                    match(right).region.center -> { trace.add("tea"); if (!ignoreTaps) change { tea = !tea } }
                    match(close).region.center -> {
                        trace.add("close")
                        if (!ignoreClose) change { modal = false; if (wrongAfterClose) tea = !tea }
                    }
                    quest -> {
                        trace.add("quest"); assertFalse(modal)
                    }
                    else -> error("Unexpected click")
                }
            }
            reader = QuestItemReader(api)
            items = QuestItems(api, reader)
            launch = QuestLaunch(api, party, items)
        }
        fun wanted(storm: Boolean, tea: Boolean) {
            every { api.prefs.selectedServerConfigPref.useStormPod } returns storm
            every { api.prefs.selectedServerConfigPref.useTeapot } returns tea
        }
        fun failsBeforeQuest() {
            val error = assertFailsWith<AutoBattle.BattleExitException> { if (modal) items.prepare() else launch.start() }
            assertThat(error.reason).isEqualTo(AutoBattle.ExitReason.QuestItemSettingFailed)
            assertFalse("quest" in trace)
        }
    }

    @Test
    fun `all four requested combinations are applied independently before closing and starting`() {
        for (server in listOf(GameServer.Kr, GameServer.Jp.Original))
            for (initialStorm in listOf(false, true)) for (initialTea in listOf(false, true))
            for (wantStorm in listOf(false, true)) for (wantTea in listOf(false, true)) {
                val f = Fixture(server = server)
                f.storm = initialStorm; f.tea = initialTea; f.wanted(wantStorm, wantTea)
                f.launch.start()
                assertThat(f.storm).isEqualTo(wantStorm)
                assertThat(f.tea).isEqualTo(wantTea)
                assertThat(f.trace).isEqualTo(buildList {
                    add("party"); add("main")
                    if (initialStorm != wantStorm) add("storm")
                    if (initialTea != wantTea) add("tea")
                    add("close"); add("quest")
                })
            }
    }

    @Test
    fun `direct controls verify the probe flip and restore only when needed for either item`() {
        for (kind in Kind.entries) for (initial in listOf(false, true)) for (wanted in listOf(false, true)) {
            val f = Fixture(dialogMode = false, kind = kind)
            f.storm = initial; f.tea = initial; f.wanted(wanted, wanted)
            f.launch.start()
            assertThat(if (kind == Kind.StormPod) f.storm else f.tea).isEqualTo(wanted)
            assertThat(f.trace).isEqualTo(if (initial == wanted) listOf("party", "main", "main", "quest") else listOf("party", "main", "quest"))
        }
    }

    @Test
    fun `delayed dialog changes use fresh frames without repeating the click`() {
        val f = Fixture(); f.settleFrames = 3; f.wanted(true, true)
        f.launch.start()
        assertThat(f.trace).isEqualTo(listOf("party", "main", "storm", "tea", "close", "quest"))
    }

    @Test
    fun `delayed direct change is confirmed before deciding whether to restore`() {
        val f = Fixture(false); f.settleFrames = 3; f.launch.start()
        assertThat(f.trace).isEqualTo(listOf("party", "main", "main", "quest"))
    }

    @Test
    fun `already open dialog is configured without pressing the main control`() {
        val f = Fixture(); f.modal = true; f.wanted(false, true); f.items.prepare()
        assertThat(f.trace).isEqualTo(listOf("tea", "close"))
    }

    @Test
    fun `bypass and unsupported servers perform no item capture or template load`() {
        for (bypass in listOf(false, true)) {
            val f = Fixture()
            every { f.api.prefs.ignoreQuestItemUsage } returns bypass
            if (!bypass) every { f.api.prefs.gameServer } returns GameServer.En.Original
            f.items.prepare()
            assertThat(f.trace).isEqualTo(emptyList())
            verify(exactly = 0) { f.api.useSameSnapIn<Any?>(any()) }
            verify(exactly = 0) { f.api.images[any(), any()] }
        }
    }

    @Test
    fun `quest with no item control starts without an item click`() {
        val f = Fixture(); f.absent = true; f.launch.start()
        assertThat(f.trace).isEqualTo(listOf("party", "quest"))
    }

    @Test
    fun `initial unreadable or ambiguous controls are skipped without item clicks`() {
        for (failure in 0..3) {
            val f = Fixture(); f.hideState = failure == 0; f.ambiguousState = failure == 1; f.ambiguousIcon = failure == 2; f.hideIcon = failure == 3
            f.launch.start()
            assertThat(f.trace).isEqualTo(listOf("party", "quest"))
        }
    }

    @Test
    fun `failed direct restoration cannot start with the probe state`() {
        val f = Fixture(false); f.ignoreRestore = true; f.failsBeforeQuest()
        assertThat(f.trace).isEqualTo(listOf("party", "main", "main"))
    }

    @Test
    fun `non-party frame without item evidence is unavailable and never tapped`() {
        val f = Fixture(); f.absent = true
        assertThat(f.reader.read()).isEqualTo(View.Unavailable)
        f.items.prepare()
        assertTrue(f.trace.isEmpty())
    }

    @Test
    fun `a main control which does not respond stops after one probe`() {
        for (dialog in listOf(false, true)) {
            val f = Fixture(dialog); f.ignoreTaps = true; f.failsBeforeQuest()
            assertThat(f.trace).isEqualTo(listOf("party", "main"))
        }
    }

    @Test
    fun `failed item toggle or failed dialog close prevents Quest Start`() {
        val toggle = Fixture(); toggle.modal = true; toggle.ignoreTaps = true; toggle.wanted(true, false)
        toggle.failsBeforeQuest()
        assertThat(toggle.trace).isEqualTo(listOf("storm"))
        val close = Fixture(); close.ignoreClose = true; close.failsBeforeQuest()
        assertThat(close.trace).isEqualTo(listOf("party", "main", "close"))
    }

    @Test
    fun `partial dialog and changed state after closing both stop before starting`() {
        val partial = Fixture(); partial.modal = true; partial.partialDialog = true; partial.items.prepare()
        assertTrue(partial.trace.isEmpty())
        val wrong = Fixture(); wrong.wrongAfterClose = true; wrong.failsBeforeQuest()
        assertThat(wrong.trace).isEqualTo(listOf("party", "main", "close"))
    }
    @Test
    fun `quest launch needs no bond party capture after item settings are settled`() {
        val f = Fixture()
        f.launch.start()
        assertThat(f.trace).isEqualTo(listOf("party", "main", "close", "quest"))
    }

    @Test
    fun `KR native modal heading works without loosening control confirmation`() {
        val f = Fixture(server = GameServer.Kr); f.nativeHeadingOnly = true
        f.launch.start()
        assertThat(f.trace).isEqualTo(listOf("party", "main", "close", "quest"))
        val partial = Fixture(server = GameServer.Kr)
        partial.nativeHeadingOnly = true; partial.modal = true; partial.partialDialog = true
        partial.items.prepare()
        assertTrue(partial.trace.isEmpty())
        val jp = Fixture(); jp.nativeHeadingOnly = true; jp.modal = true
        jp.items.prepare()
        assertTrue(jp.trace.isEmpty())
        val loader = jp.api.images
        verify(exactly = 0) { loader[Images.QuestItemDialogKrNative, any()] }
    }

    @Test
    fun `launch uses one second either side of items and two seconds after Start on every server`() {
        for (server in listOf(GameServer.Kr, GameServer.Jp.Original, GameServer.En.Original, GameServer.Cn, GameServer.Tw)) {
            val f = Fixture(server = server)
            every { with(f.api) { 1.seconds.wait() } } answers { f.trace.add("wait1") }
            every { with(f.api) { 2.seconds.wait() } } answers { f.trace.add("wait2") }
            f.launch.start()
            val itemTrace = if (server is GameServer.Kr || server is GameServer.Jp) listOf("main", "close") else emptyList()
            assertThat(f.trace).isEqualTo(listOf("party", "wait1") + itemTrace + listOf("wait1", "quest", "wait2"))
        }
    }
}
