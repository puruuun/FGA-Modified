package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.modules.*
import io.github.lib_automata.*
import io.mockk.*
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class FpSummonTimingTest {
    private class Done : RuntimeException()

    @Test fun `summon loop keeps the original FGA waits with auto sell on and off`() {
        for (enabled in listOf(true,false)) {
            val api=mockk<IFgoAutomataApi>(relaxed=true)
            every { api.prefs.fpAutoSell } returns enabled
            every { api.prefs.gameServer } returns GameServer.Kr
            every { api.prefs.shouldLimitFP } returns false
            every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
            val summon=Region(20,20,10,10)
            val first=Location(100,100)
            val ok=Location(200,200)
            every { api.locations.fp.summonCheck } returns summon
            every { api.locations.fp.first10SummonClick } returns first
            every { api.locations.fp.okClick } returns ok
            var stage=0
            every { with(api) { any<Region>().contains(any<Pattern>()) } } answers {
                firstArg<Region>()==summon && stage==0
            }
            every { with(api) { first.click(any()) } } answers { stage=1 }
            every { with(api) { ok.click(any()) } } answers { stage=2 }
            every { api.findImage(any(),Images.FPSummonContinue) } answers { stage==2 }
            val reader=mockk<FpSellReader>(relaxed=true)
            every { reader.servantFullWarning() } returns false
            val waits=mutableListOf<Duration>()
            val animation=3.seconds
            for (delay in listOf(0.3.seconds,0.5.seconds,0.8.seconds,1.seconds,3.seconds,3.5.seconds)) {
                every { with(api) { delay.wait(any()) } } answers {
                    waits.add(delay)
                    if(delay==animation) throw Done()
                }
            }
            assertFailsWith<Done> {
                AutoFriendGacha(mockk(),api,mockk(relaxed=true),mockk(relaxed=true),reader).script()
            }
            val expected=listOf(0.3.seconds,0.5.seconds,0.3.seconds,3.seconds)
            assertEquals(expected,waits)
            verify(exactly=4) { with(api) { any<Duration>().wait(any()) } }
        }
    }
}
