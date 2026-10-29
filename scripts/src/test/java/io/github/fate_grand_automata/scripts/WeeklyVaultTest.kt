package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.modules.*
import io.github.fate_grand_automata.scripts.weekly.*
import io.github.lib_automata.MeanColor
import io.github.lib_automata.OcrText
import io.github.lib_automata.Location
import io.github.fate_grand_automata.scripts.locations.WeeklyVaultAnchor
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.mockk.*
import kotlin.test.*

class WeeklyVaultTest {
    @Test fun `product driver probe accepts vault without tapping and route taps only the name line`() {
        for (probe in listOf(false, true)) {
            val api = mockk<IFgoAutomataApi>(relaxed = true)
            every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>()() }
            every { api.useColor<Any?>(any()) } answers { firstArg<() -> Any?>()() }
            val reader = mockk<WeeklyVaultReader>(relaxed = true)
            // OCR can be blank or report a different difficulty; neither name nor AP vetoes yellow.
            val panel = VaultPanel(WeeklyVaultAnchor(Location(2194, 700)), VaultColor(43.5, 0.99), null, emptyList(), null, true)
            val later = panel.copy(anchor = WeeklyVaultAnchor(Location(2194, 1000)), name = "中級", ap = 40)
            every { reader.read() } returns listOf(panel, later)
            val driver = spyk(WeeklyRouteDriver(api, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
                mockk(relaxed = true), mockk(relaxed = true), BattleState(), mockk(relaxed = true), mockk(relaxed = true),
                mockk(relaxed = true), reader))
            every { driver.screen() } returns RouteScreen.List
            every { driver.dailyList() } returns true
            every { driver.listScrollbar() } returns ListScrollbar.State(true, false)
            val quest = item(1002).quest
            assertEquals(SpotOutcome.Started, if (probe) driver.probeSpot(quest) else driver.openSpot(quest))
            verify(exactly = if (probe) 0 else 1) { with(api) { panel.anchor.tap.click(1) } }
            verify(exactly = 0) { with(api) { later.anchor.tap.click(any()) } }
            if (probe) verify(exactly = 0) { with(api) { any<Location>().click(any()) } }
            assertTrue(driver.closePanel())
        }
    }

    private fun driverWith(panels: List<VaultPanel>, bars: ArrayDeque<ListScrollbar.State>, api: IFgoAutomataApi): WeeklyRouteDriver {
        val reader = mockk<WeeklyVaultReader>(relaxed = true)
        every { reader.read() } returns panels
        val driver = spyk(WeeklyRouteDriver(api, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), BattleState(), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), reader))
        every { driver.screen() } returns RouteScreen.List
        every { driver.dailyList() } returns true
        every { driver.listScrollbar() } answers { if (bars.size > 1) bars.removeFirst() else bars.first() }
        return driver
    }

    @Test fun `list starting mid-scroll scrolls up first then taps the first yellow below the top`() {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>()() }
        every { api.useColor<Any?>(any()) } answers { firstArg<() -> Any?>()() }
        val panel = VaultPanel(WeeklyVaultAnchor(Location(2194, 700)), VaultColor(43.5, 0.99), null, emptyList(), null, true)
        val bars = ArrayDeque(listOf(ListScrollbar.State(false, false), ListScrollbar.State(false, false), ListScrollbar.State(true, false)))
        val driver = driverWith(listOf(panel), bars, api)
        assertEquals(SpotOutcome.Started, driver.openSpot(item(1002).quest))
        assertEquals(1, bars.size) // two bar reads were spent scrolling up before the top showed
        verify(exactly = 1) { with(api) { panel.anchor.tap.click(1) } }
    }

    @Test fun `top never confirmed means no tap`() {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>()() }
        every { api.useColor<Any?>(any()) } answers { firstArg<() -> Any?>()() }
        val panel = VaultPanel(WeeklyVaultAnchor(Location(2194, 700)), VaultColor(43.5, 0.99), null, emptyList(), null, true)
        val driver = driverWith(listOf(panel), ArrayDeque(listOf(ListScrollbar.State(false, false))), api)
        assertTrue(driver.openSpot(item(1002).quest) is SpotOutcome.NotFound)
        verify(exactly = 0) { with(api) { any<Location>().click(any()) } }
    }

    @Test fun `toTop scrolls up until the top end and gives up at the cap`() {
        val calls = mutableListOf<Int>()
        var n = 0
        assertTrue(WeeklyListScan.toTop(14, { ListScrollbar.State(n >= 3, false) }, { d, i -> assertEquals(ListSearchDirection.Up, d); n = i; calls += i }))
        assertEquals(listOf(1, 2, 3), calls)
        assertFalse(WeeklyListScan.toTop(4, { ListScrollbar.State(false, false) }, { _, _ -> }))
    }

    private fun export(id: Int = 94061636, name: String = "宝物庫の扉を開け 初級", war: Int = 1002,
                       consume: String = "ap", after: String = "repeatLast", opened: Int = 0, closed: Int = 999) = """
        [{"id":$war,"eventId":1,"longName":"daily","banner":"daily.png","maps":[],"spots":[{"id":99002,"quests":[
        {"id":$id,"name":"$name","type":"event","consumeType":"$consume","consume":10,"afterClear":"$after",
        "openedAt":$opened,"closedAt":$closed,"phases":[1]}]}]}]
    """.trimIndent()

    @Test fun `only allowlisted open AP repeat vault in war 1002 is selected and retains event type`() {
        val q = AtlasWeekly.quests(export(), 100).single()
        assertEquals(94061636, q.id); assertEquals("event", q.type)
        assertTrue(q.repeatable(emptySet()))
        assertTrue(AtlasWeekly.quests(export(name = "보물창고의 문을 열어라 《초급》"), 100).isNotEmpty())
        for (raw in listOf(export(id = 94061637), export(id = 94061601), export(id = 94006801), export(war = 1003),
            export(consume = "item"), export(after = "close"), export(opened = 101), export(closed = 100))) {
            assertTrue(AtlasWeekly.quests(raw, 100).isEmpty())
        }
        assertFalse(q.copy(id = 94061637).repeatable(emptySet()))
        assertFalse(q.copy(ap = 0).repeatable(emptySet()))
        assertFalse(q.repeatable(setOf(1002)))
        assertFalse(AtlasWeekly.mainStoryWar(1002))
    }

    @Test fun `failed name check logs the drop and mapless daily war data is retained`() {
        val logs = mutableListOf<String>()
        assertTrue(AtlasWeekly.quests(export(name = "種火集め 初級"), 100, logs::add).isEmpty())
        assertTrue(logs.single().contains("vaultNameMismatch"))
        val war = AtlasWeekly.wars(export(), 100).wars.getValue(1002)
        assertEquals("daily.png", war.banner); assertTrue(war.maps.isEmpty())
    }

    @Test fun `vault is enabled by default in candidates and probe and covers caster missions`() {
        val q = AtlasWeekly.quests(export(), 100).single().copy(enemyDataAvailable = true,
            enemies = List(9) { RouteEnemy(1, 5, emptySet()) })
        assertEquals(listOf(q), WeeklyCandidateSettings.candidates("KR", listOf(q), emptySet()))
        assertEquals(listOf(q.id), WeeklyProbe.select("KR", listOf(q), ProbeScope.All, emptySet(), emptySet()).map { it.quest.id })
        val disabled = setOf(WeeklyCandidateSettings.chapterKey("KR", 1002))
        assertTrue(WeeklyProbe.select("KR", listOf(q), ProbeScope.Enabled, emptySet(), disabled).isEmpty())
        val plan = WeeklyRouteSolver.solve(listOf(RouteMission(1, "Caster", 15, MissionKind.EnemyClass, setOf(5))), listOf(q), emptySet())
        assertEquals(2, plan.items.single().count); assertEquals(20L, plan.totalAp)
        assertEquals(ProbeStatus.NoPanel, ProbeStatus.classify("vault panel not found"))
        assertEquals(ProbeStatus.Mismatch, ProbeStatus.classify("vault name mismatch"))
    }

    @Test fun `name consensus remains diagnostic only`() {
        assertNull(WeeklyVaultPolicy.consensus(listOf(OcrText("宝物庫 初級", 90))))
        assertEquals("宝物庫初級", WeeklyVaultPolicy.consensus(listOf(OcrText("宝物庫 初級", 90), OcrText("宝物庫《初級》", 85))))
        assertNull(WeeklyVaultPolicy.consensus(listOf(OcrText("宝物庫 初級", 90), OcrText("宝物庫 超級", 90))))
    }

    @Test fun `measured yellow green and red backgrounds only accept yellow`() {
        val yellow = WeeklyVaultPolicy.color(MeanColor(218.69, 159.04, 1.20))
        assertTrue(yellow.yellow); assertTrue(yellow.hue in 43.0..44.0)
        assertFalse(WeeklyVaultPolicy.color(MeanColor(120.02, 144.02, 25.88)).yellow)
        assertFalse(WeeklyVaultPolicy.color(MeanColor(170.05, 51.20, 37.83)).yellow)
        assertFalse(WeeklyVaultPolicy.color(MeanColor(220.0, 220.0, 220.0)).yellow)
        assertTrue(VaultColor(38.0, 0.80).yellow)
        assertTrue(VaultColor(52.0, 1.0).yellow)
        assertFalse(VaultColor(37.99, 1.0).yellow)
        assertFalse(VaultColor(52.01, 1.0).yellow)
        assertFalse(VaultColor(44.0, 0.799).yellow)
    }

    @Test fun `only top-left scores classify Home Map List and ambiguous frames stay unknown`() {
        assertEquals(RouteScreen.Home, WeeklyRouteScreens.classify(0.95, 0.2, 0.3))
        assertEquals(RouteScreen.Map, WeeklyRouteScreens.classify(0.2, 0.95, 0.3))
        assertEquals(RouteScreen.List, WeeklyRouteScreens.classify(0.2, 0.3, 0.95))
        assertEquals(RouteScreen.Unknown, WeeklyRouteScreens.classify(0.9, 0.9, 0.2))
        assertEquals(RouteScreen.Unknown, WeeklyRouteScreens.classify(0.2, 0.3, 0.87))
        assertEquals(RouteScreen.Unknown, WeeklyRouteScreens.classify(0.1, 0.2, 0.3))
    }

    private fun item(war: Int) = RouteItem(RouteQuest(if (war == 1002) 94061636 else war, war, war, "chapter", "spot", "name", 10,
        if (war == 1002) "event" else "free", "repeatLast"), 1)

    private class Fake : RouteDriver {
        var shown = RouteScreen.Home
        var war = 0
        var failVault = false
        val actions = mutableListOf<String>()
        override fun log(message: String) = Unit
        override fun screen() = shown.also { actions += "screen:$it" }
        override fun openList(list: ChapterList) = true.also { actions += "list:$list"; shown = if (list == ChapterList.ChaldeaGate) RouteScreen.List else RouteScreen.List }
        override fun openChapter(warId: Int): Boolean {
            actions += "chapter:$warId"; war = warId
            if (failVault && warId == 1002) return false
            shown = if (warId == 1002) RouteScreen.List else RouteScreen.Map
            return true
        }
        override fun prepareMap(warId: Int) = true
        override fun openSpot(quest: RouteQuest) = SpotOutcome.Started.also { actions += "panel:${quest.warId}" }
        override fun probeSpot(quest: RouteQuest) = SpotOutcome.Started.also { actions += "probe" }
        override fun battle(quest: RouteQuest, count: Int) = BattleOutcome(count, true).also { actions += "battle:$war" }
        override fun closePanel() = true.also { if (war != 1002) shown = RouteScreen.Map }
        override fun terminal() = true.also { actions += "terminal:$shown"; shown = if (war == 1002) RouteScreen.List else RouteScreen.List }
        override fun closeList() = true.also { actions += "close:$shown"; shown = RouteScreen.Home }
    }

    @Test fun `vault visits last and route and probe end on daily list without a map`() {
        for (server in listOf("JP", "KR")) {
            val visits = WeeklyVisitOrder.order(server, listOf(item(1002), item(307), item(100), item(301)))
            assertEquals(1002, visits.last().warId)
            assertEquals(ChapterList.ChaldeaGate, visits.last().list)
            for (probe in listOf(false, true)) {
                val fake = Fake()
                val result = WeeklyRouteMachine(fake, server, probe).run(visits)
                assertTrue(result.all { it.status == StopStatus.Done })
                assertEquals(RouteScreen.List, fake.shown)
                val gate = fake.actions.indexOf("list:ChaldeaGate")
                assertEquals("screen:Home", fake.actions[gate - 1])
                assertTrue(gate < fake.actions.indexOf("chapter:1002"))
                if (!probe) assertTrue(fake.actions.indexOf("chapter:1002") < fake.actions.indexOf("panel:1002"))
                assertEquals(probe, "probe" in fake.actions)
                if (probe) assertTrue(fake.actions.none { it.startsWith("battle:") })
            }
        }
    }

    @Test fun `leaving vault closes daily to gate to home and gate failure keeps earlier completed stops`() {
        val fake = Fake()
        val visits = listOf(ChapterVisit(1002, ChapterList.ChaldeaGate, listOf(item(1002))), ChapterVisit(100, ChapterList.MainRecord1, listOf(item(100))))
        WeeklyRouteMachine(fake, "JP").run(visits)
        assertTrue("terminal:List" in fake.actions); assertTrue("close:List" in fake.actions)
        val failed = Fake().apply { failVault = true }
        val result = WeeklyRouteMachine(failed, "JP").run(WeeklyVisitOrder.order("JP", listOf(item(100), item(1002))))
        assertEquals(listOf(StopStatus.Done, StopStatus.NotFound), result.map { it.status })
    }
}
