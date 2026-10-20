package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.weekly.*
import io.github.fate_grand_automata.scripts.modules.*
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.lib_automata.*
import kotlin.test.*

class WeeklyGeometryTest {
    private fun points(sx: Double=.667,sy: Double=.675) = (0 until 60).map { i ->
        val x=200.0+(i%10)*100;val y=100.0+(i/10)*120
        ImageFeatureMatch(x,y,-60+sx*x+(i%3-1)*.15,30+sy*y+(i%5-2)*.1)
    }
    @Test fun `localization independently solves both scales and rejects outliers`() {
        val noise=(0 until 30).map { ImageFeatureMatch(it*53.0,it*97.0,1200-it*29.0,it*17.0) }
        val fit=assertNotNull(MapLocalizer.fit(points()+noise))
        assertTrue(MapLocalizer.accepts(fit));assertEquals(.667,fit.scaleX,.001);assertEquals(.675,fit.scaleY,.001)
        assertEquals(-60+.667*1234,fit.screenX(1234.0),1.0);assertEquals(30+.675*567,fit.screenY(567.0),1.0)
        assertEquals(1234.0,fit.mapX(fit.screenX(1234.0)),.0001)
    }
    @Test fun `no evidence degenerate support low agreement and invalid scales fail closed`() {
        assertNull(MapLocalizer.fit(emptyList()));assertNull(MapLocalizer.fit(points().take(10)))
        assertNull(MapLocalizer.fit(points().map { it.copy(mapX=1.0) }))
        assertNull(MapLocalizer.fit(points(-.667,.675)))
        assertNull(MapLocalizer.fit(points(2.0,1.0)))
        assertNull(MapLocalizer.fit(points().map { it.copy(screenX=it.screenX*.01,screenY=it.screenY*.01) }))
    }
    @Test fun `wrong zoom range cannot make a usable fit`() { assertNull(MapLocalizer.fit(points(1.25,1.25),.45..0.9)) }
    @Test fun `UI covered and offscreen spots require bounded nonfling drags`() {
        assertFalse(MapView.isTappable(100.0,40.0,1280,720));assertFalse(MapView.isTappable(1200.0,650.0,1280,720))
        assertTrue(MapView.isTappable(640.0,360.0,1280,720))
        val drag=assertNotNull(MapView.dragToward(3000.0,-1000.0,1280,720));assertTrue(kotlin.math.abs(drag.x)<=640 && kotlin.math.abs(drag.y)<=324)
        val path=MapView.dragPath(drag,1280,720);assertTrue(path.first.x in 0.0..1280.0 && path.second.y in 0.0..720.0)
    }
    @Test fun `pinch fingers start together symmetrically inside bounds and converge`() {
        val bounds=Region(400,300,1760,800);val plan=PinchGesture.zoomOut(bounds)
        assertEquals(700L,plan.durationMs);assertTrue(plan.startSpan>plan.endSpan)
        assertEquals(plan.startA.y,plan.startB.y);assertEquals(plan.endA.y,plan.endB.y)
        assertEquals(bounds.center.x*2,plan.startA.x+plan.startB.x)
        for(p in listOf(plan.startA,plan.startB,plan.endA,plan.endB)) assertTrue(p.x>=bounds.x && p.x<bounds.right && p.y>=bounds.y && p.y<bounds.bottom)
        assertTrue(PinchGesture.plan(bounds.center,10000,10000,bounds).startSpan<=bounds.width)
    }
    @Test fun `invalid pinch geometry and duration are rejected before dispatch`() {
        val b=Region(0,0,100,100)
        assertFailsWith<IllegalArgumentException> { PinchGesture.plan(Location(50,150),50,10,b) }
        assertFailsWith<IllegalArgumentException> { PinchGesture.plan(b.center,1,1,b) }
        assertFailsWith<IllegalArgumentException> { PinchGesture.plan(b.center,50,10,b,0) }
    }
    @Test fun `same width different quest name can never pass strict production guard`() {
        val panel=QuestPanel(true,5,7.0,128,"別の同長教会")
        assertNotEquals(PanelVerdict.Match,QuestPanelMatch.strictVerdict(panel,5,"骸彷徨う教会",GameServer.Jp.Original))
        assertNotEquals(PanelVerdict.Match,QuestPanelMatch.strictVerdict(panel.copy(nameText=null),5,"骸彷徨う教会",GameServer.Jp.Original))
    }
    @Test fun `name OCR consensus requires two agreeing confident full reads`() {
        assertEquals("피가끓어오르는광연",QuestPanelMatch.nameConsensus(listOf(OcrText("피가 끓어오르는 광연",90),OcrText("피가끓어오르는광연",80))))
        assertNull(QuestPanelMatch.nameConsensus(listOf(OcrText("name",90))))
        assertNull(QuestPanelMatch.nameConsensus(listOf(OcrText("name",90),OcrText("other",90))))
    }
    @Test fun `whole-catalogue correction needs a unique margin and two readings and never favours the target`() {
        val reads=listOf(OcrText("骸入径う教会",48),OcrText("骸徳径う教会",49))
        assertEquals("骸彷徨う教会",QuestPanelMatch.canonicalName(reads,listOf("骸彷徨う教会","別の神殿","他の原野")))
        // If the observed spelling is itself a legitimate quest, it is never silently changed to the planned quest.
        assertNull(QuestPanelMatch.canonicalName(reads,listOf("骸彷徨う教会","骸入径う教会")))
        assertNull(QuestPanelMatch.canonicalName(reads.take(1),listOf("骸彷徨う教会")))
        assertNull(QuestPanelMatch.canonicalName(reads.map { it.copy(confidence=44) },listOf("骸彷徨う教会")))
        assertNull(QuestPanelMatch.canonicalName(listOf(OcrText("ABC",90),OcrText("ABC",90)),listOf("ABD","ABE")))
    }

    @Test fun `both drag directions start and end inside the map safe area`() {
        for(width in listOf(960,1280,1680)) for(x in listOf(-2000.0,4000.0)) for(y in listOf(-2000.0,3000.0)) {
            val drag=assertNotNull(MapView.dragToward(x,y,width,720))
            val path=MapView.dragPath(drag,width,720)
            assertTrue(MapView.isTappable(path.first.x,path.first.y,width,720),"start=$path width=$width")
            assertTrue(MapView.isTappable(path.second.x,path.second.y,width,720),"end=$path width=$width")
        }
    }

}
