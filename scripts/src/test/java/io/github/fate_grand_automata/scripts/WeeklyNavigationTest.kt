package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.weekly.*
import io.github.lib_automata.*
import kotlin.test.*

class WeeklyNavigationTest {
    private fun item(id: Int,war: Int=100,count: Int=2) = RouteItem(RouteQuest(id,war,war,"chapter","spot","name",5,"free","repeatLast",spotId=id,mapId=war),count)
    private class Fake: RouteDriver {
        val actions=mutableListOf<String>()
        var missingWar: Int?=null;var missingSpot: Int?=null;var badMap=false;var zoomOk=true;var battleOk=true;var terminalOk=true
        override fun log(message: String) {}
        override fun screen()=RouteScreen.Home
        override fun openList(list: ChapterList)=true.also { actions+="list:$list" }
        override fun openChapter(warId: Int)=(warId!=missingWar).also { actions+="chapter:$warId" }
        override fun closeList()=true.also { actions+="home" }
        override fun terminal()=terminalOk.also { actions+="terminal" }
        override fun closePanel()=true.also { actions+="closePanel" }
        override fun prepareMap(warId: Int)=zoomOk.also { actions+="zoom:$warId" }
        override fun openSpot(quest: RouteQuest): SpotOutcome {
            actions+="spot:${quest.id}"
            return if(quest.id==missingSpot) SpotOutcome.NotFound("not recognized",skipChapter=badMap) else SpotOutcome.Started
        }
        override fun battle(quest: RouteQuest,count: Int)=BattleOutcome(if(battleOk) count else 1,battleOk).also { actions+="battle:${quest.id}" }
    }
    private fun run(f: Fake,items: List<RouteItem>,server: String="JP")=WeeklyRouteMachine(f,server).run(WeeklyVisitOrder.order(server,items))

    @Test fun `server lists and visit order follow main record order including KR direct chapters`() {
        assertEquals(ChapterList.MainRecord2,ChapterLists.of("JP",301))
        assertEquals(ChapterList.Home,ChapterLists.of("KR",301))
        for(server in listOf("JP","KR")) {
            val visits=WeeklyVisitOrder.order(server,listOf(item(4,301),item(3,201),item(2,101),item(1,100),item(5,100,0)))
            assertEquals(listOf(100,101,201,301),visits.map { it.warId })
        }
    }
    @Test fun `same chapter prepares once and closes each panel before the next spot`() {
        val f=Fake();val result=run(f,listOf(item(2),item(1)))
        assertEquals(listOf(1,2),result.map { it.item.quest.id })
        assertEquals(1,f.actions.count { it=="chapter:100" });assertEquals(1,f.actions.count { it=="zoom:100" })
        assertEquals(2,f.actions.count { it=="closePanel" });assertFalse("terminal" in f.actions)
        assertTrue(result.all { it.status==StopStatus.Done && it.done==2 })
    }
    @Test fun `other chapter in same list uses terminal without reopening the list`() {
        val f=Fake();run(f,listOf(item(1,100),item(2,101)))
        assertEquals(1,f.actions.count { it.startsWith("list:") });assertEquals(1,f.actions.count { it=="terminal" })
        assertFalse("home" in f.actions)
    }
    @Test fun `other list returns through terminal then close to home`() {
        val f=Fake();run(f,listOf(item(1,100),item(2,201)))
        assertEquals(listOf("terminal","home","list:MainRecord15"),f.actions.dropWhile { it!="terminal" }.take(3))
    }
    @Test fun `KR part two opens directly from home after leaving main record list`() {
        val f=Fake();run(f,listOf(item(1,100),item(2,301)),"KR")
        assertFalse("list:Home" in f.actions);assertTrue("home" in f.actions)
        assertEquals(StopStatus.Done,run(Fake(),listOf(item(1,301)),"KR").single().status)
    }
    @Test fun `missing chapter skips its quests and continues to the next chapter`() {
        val f=Fake().apply { missingWar=100 };val result=run(f,listOf(item(1),item(2),item(3,101)))
        assertEquals(listOf(StopStatus.NotFound,StopStatus.NotFound,StopStatus.Done),result.map { it.status })
        assertFalse(f.actions.any { it=="spot:1" || it=="spot:2" })
    }
    @Test fun `failed pinch cannot be followed by any spot tap`() {
        val f=Fake().apply { zoomOk=false };assertEquals(StopStatus.NotFound,run(f,listOf(item(1))).single().status)
        assertFalse(f.actions.any { it.startsWith("spot:") })
    }
    @Test fun `unrecognized spot is skipped without a battle and the next spot can run`() {
        val f=Fake().apply { missingSpot=1 };val result=run(f,listOf(item(1),item(2)))
        assertEquals(listOf(StopStatus.NotFound,StopStatus.Done),result.map { it.status });assertFalse("battle:1" in f.actions)
    }
    @Test fun `battle stop preserves actual count and aborts remaining route`() {
        val f=Fake().apply { battleOk=false };val result=run(f,listOf(item(1),item(2),item(3,101)))
        assertEquals(1,result.first().done);assertEquals(listOf(StopStatus.Aborted,StopStatus.NotRun,StopStatus.NotRun),result.map { it.status })
        assertFalse("chapter:101" in f.actions)
    }
    @Test fun `lost navigation never continues with another chapter tap`() {
        val f=Fake().apply { terminalOk=false };val result=run(f,listOf(item(1),item(2,101),item(3,102)))
        assertFalse("chapter:101" in f.actions);assertFalse("chapter:102" in f.actions)
        assertEquals(listOf(StopStatus.Done,StopStatus.NotFound,StopStatus.NotRun),result.map { it.status })
    }
    @Test fun `manual stop during navigation records the active quest as aborted`() {
        val f=object: RouteDriver by Fake() { override fun openSpot(quest: RouteQuest): SpotOutcome=throw ScriptAbortException() }
        val m=WeeklyRouteMachine(f,"JP")
        assertFailsWith<ScriptAbortException> { m.run(WeeklyVisitOrder.order("JP",listOf(item(1)))) }
        m.interrupt("user stopped");assertEquals(StopStatus.Aborted,m.results.single().status)
    }
    @Test fun `an unrecognized map skips the rest of its chapter without another spot tap`() {
        val f=Fake().apply { missingSpot=1;badMap=true }
        val result=run(f,listOf(item(1),item(2),item(3,101)))
        assertEquals(listOf(StopStatus.NotFound,StopStatus.NotFound,StopStatus.Done),result.map { it.status })
        assertFalse("spot:2" in f.actions)
    }

}
