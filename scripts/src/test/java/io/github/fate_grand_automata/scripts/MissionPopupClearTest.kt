package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.fate_grand_automata.scripts.modules.MissionPopupClear
import io.github.lib_automata.Location
import io.github.lib_automata.ScriptAbortException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class MissionPopupClearTest {
    private val api = mockk<IFgoAutomataApi>(relaxed = true)
    private val target = Location(1280, 100)

    @Test
    fun `taps batches of 10 until duration has passed and never captures`() {
        val clock = TestTimeSource()
        var batches = 0
        every { with(api) { target.click(10) } } answers {
            batches++
            clock += 800.milliseconds
        }

        MissionPopupClear(api, target, clock).clear(10.seconds)

        assertThat(batches).isEqualTo(13)
        verify(exactly = 13) { with(api) { target.click(10) } }
        verify(exactly = 0) {
            with(api) {
                any<Duration>().wait(any())
                api.useSameSnapIn<Any?>(any())
            }
        }
    }

    @Test
    fun `always taps at least one batch even when first batch has zero or large elapsed step`() {
        val clockZeroThenLarge = TestTimeSource()
        var batches = 0
        every { with(api) { target.click(10) } } answers {
            batches++
            if (batches > 1) clockZeroThenLarge += 15.seconds
        }
        MissionPopupClear(api, target, clockZeroThenLarge).clear(10.seconds)
        assertThat(batches).isEqualTo(2)

        val clockZeroDuration = TestTimeSource()
        var zeroDurationBatches = 0
        every { with(api) { target.click(10) } } answers { zeroDurationBatches++ }
        MissionPopupClear(api, target, clockZeroDuration).clear(Duration.ZERO)
        assertThat(zeroDurationBatches).isEqualTo(1)
    }

    @Test
    fun `stop request during clicking stops immediately and propagates`() {
        val clock = TestTimeSource()
        var batches = 0
        every { with(api) { target.click(10) } } answers {
            batches++
            clock += 800.milliseconds
            if (batches == 3) throw ScriptAbortException()
        }

        assertFailsWith<ScriptAbortException> {
            MissionPopupClear(api, target, clock).clear(10.seconds)
        }
        assertThat(batches).isEqualTo(3)
    }

    @Test
    fun `clampSeconds keeps values within 1 to 60`() {
        assertThat(MissionPopupClear.clampSeconds(-5)).isEqualTo(1)
        assertThat(MissionPopupClear.clampSeconds(0)).isEqualTo(1)
        assertThat(MissionPopupClear.clampSeconds(1)).isEqualTo(1)
        assertThat(MissionPopupClear.clampSeconds(10)).isEqualTo(10)
        assertThat(MissionPopupClear.clampSeconds(60)).isEqualTo(60)
        assertThat(MissionPopupClear.clampSeconds(61)).isEqualTo(60)
        assertThat(MissionPopupClear.clampSeconds(999)).isEqualTo(60)
    }
}
