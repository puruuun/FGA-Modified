package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.entrypoints.*
import io.github.fate_grand_automata.scripts.modules.WeeklyRouteDriver
import io.github.fate_grand_automata.scripts.prefs.IPerServerConfigPrefs
import io.github.fate_grand_automata.scripts.weekly.*
import io.github.lib_automata.ExitManager
import io.mockk.*
import java.io.File
import kotlin.test.*

class WeeklyBattleLifecycleTest {
    @Test fun `full route finalizes cumulative resources exactly once and restores the saved run limit`() {
        val config=mockk<IPerServerConfigPrefs>(relaxed=true)
        var limit=17;var enabled=false
        every { config.limitRuns } answers { limit };every { config.limitRuns=any() } answers { limit=firstArg() }
        every { config.shouldLimitRuns } answers { enabled };every { config.shouldLimitRuns=any() } answers { enabled=firstArg() }
        val api=mockk<IFgoAutomataApi>(relaxed=true);every { api.prefs.selectedServerConfigPref } returns config
        val battle=mockk<AutoBattle>(relaxed=true);val driver=mockk<WeeklyRouteDriver>(relaxed=true)
        every { driver.screen() } returns RouteScreen.Home
        every { driver.openList(any()) } returns true;every { driver.openChapter(any()) } returns true
        every { driver.prepareMap(any()) } returns true;every { driver.closePanel() } returns true
        every { driver.openSpot(any()) } returns SpotOutcome.Started
        every { driver.battle(any(),any()) } answers { limit=99;enabled=true;BattleOutcome(secondArg(),true) }
        val items=(1..2).map { RouteItem(RouteQuest(it,100,100,"chapter","spot","name",5,"free","repeatLast"),2) }
        val session=object:IWeeklyRouteSession { override var run:WeeklyRouteRun?=WeeklyRouteRun("JP",items,emptyList(),emptyMap(),File("unused")) }
        val script=AutoWeeklyRoute(mockk<ExitManager>(),api,session,driver,battle)
        val exit=assertFailsWith<AutoWeeklyRoute.ExitException> { script.script() }
        assertTrue((exit.reason as AutoWeeklyRoute.ExitReason.Finished).results.all { it.done==2 && it.status==StopStatus.Done })
        verify(exactly=1) { battle.finishRun(false) };verify(exactly=2) { driver.battle(any(),2) }
        assertEquals(17,limit);assertFalse(enabled);assertNull(session.run)
    }
}
