package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.SupportSelectionModeEnum
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.lib_automata.Location
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.github.lib_automata.ScriptAbortException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class AutoBattleFriendRequestTest {
    private class Fixture(
        option: Boolean = true,
        mode: SupportSelectionModeEnum = SupportSelectionModeEnum.Preferred,
        friendsOnly: Boolean = false,
        screenRemains: Boolean = false,
        iterations: Int = 1
    ) {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val trace = mutableListOf<String>()
        val logs = mutableListOf<ScriptLog.FriendRequest>()
        private val patterns = mutableMapOf<Images, Pattern>()
        private val send = Location(1960, 1200)
        private val reject = Location(600, 1200)
        private var screenVisible = true
        private var snapshotVisible = false
        private var loops = 0
        val entry: AutoBattle

        init {
            every { api.prefs.selectedBattleConfig.support.selectionMode } returns mode
            every { api.prefs.selectedBattleConfig.support.autoFriendRequest } returns option
            every { api.prefs.selectedBattleConfig.support.friendsOnly } returns friendsOnly
            every { api.locations.resultFriendRequestSendClick } returns send
            every { api.locations.resultFriendRequestRejectClick } returns reject
            every { api.images[any(), any()] } answers { patterns.getOrPut(firstArg()) { mockk() } }
            every { api.useSameSnapIn<Any?>(any()) } answers {
                // Freeze each frame: a cached detection frame would incorrectly trigger the fallback.
                snapshotVisible = screenVisible
                firstArg<() -> Any?>().invoke()
            }
            every { with(api) { any<Region>().contains(any<Pattern>()) } } answers {
                if (secondArg<Pattern>() == patterns[Images.SupportExtra]) {
                    trace += "detect:$snapshotVisible"
                    snapshotVisible
                } else false
            }
            every { with(api) { any<Region>().exists(any<Pattern>(), any(), any()) } } returns false
            every { with(api) { any<Region>().find(any<Pattern>(), any()) } } returns null
            every { with(api) { any<Location>().click(any()) } } answers {
                trace += when (firstArg<Location>()) {
                    send -> "send"
                    reject -> "reject"
                    else -> error("Unexpected click")
                }
            }
            every { with(api) { 1.seconds.wait(any()) } } answers {
                trace += "wait:1s"
                screenVisible = screenRemains
            }
            every { with(api) { 0.5.seconds.wait(any()) } } answers {
                if (++loops >= iterations) throw ScriptAbortException()
                screenVisible = true
            }
            every { api.messages.log(any()) } answers { logs += firstArg<ScriptLog.FriendRequest>() }
            entry = AutoBattle(
                mockk(relaxed = true), api, BattleState(), mockk(relaxed = true),
                mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
                mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
                mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)
            )
        }

        fun run() {
            val exit = assertFailsWith<AutoBattle.ExitException> { entry.script() }
            if (exit.reason is AutoBattle.ExitReason.Unexpected) throw exit.reason.cause!!
            assertThat(exit.reason).isEqualTo(AutoBattle.ExitReason.Abort)
        }

        fun assertLog(sent: Boolean, fallbackReject: Boolean) {
            assertThat(logs.map { it.sent to it.fallbackReject }).isEqualTo(listOf(sent to fallbackReject))
        }
    }

    @Test
    fun `disabled option rejects without waiting or rechecking`() {
        val f = Fixture(option = false)
        f.run()
        assertThat(f.trace).isEqualTo(listOf("detect:true", "reject"))
        f.assertLog(sent = false, fallbackReject = false)
    }

    @Test
    fun `every non Preferred mode rejects even when the option is on`() {
        for (mode in SupportSelectionModeEnum.entries.filter { it != SupportSelectionModeEnum.Preferred }) {
            val f = Fixture(mode = mode)
            f.run()
            assertThat(f.trace).isEqualTo(listOf("detect:true", "reject"))
            f.assertLog(sent = false, fallbackReject = false)
        }
    }

    @Test
    fun `friends only rejects even when imported preferences enable requests`() {
        val f = Fixture(friendsOnly = true)
        f.run()
        assertThat(f.trace).isEqualTo(listOf("detect:true", "reject"))
        f.assertLog(sent = false, fallbackReject = false)
    }

    @Test
    fun `send waits then uses a fresh frame and does not reject when the screen is gone`() {
        val f = Fixture()
        f.run()
        assertThat(f.trace).isEqualTo(listOf("detect:true", "send", "wait:1s", "detect:false"))
        f.assertLog(sent = true, fallbackReject = false)
        verify(exactly = 2) { f.api.useSameSnapIn<Any?>(any()) }
    }

    @Test
    fun `send rejects after one second when the screen remains`() {
        val f = Fixture(screenRemains = true)
        f.run()
        assertThat(f.trace).isEqualTo(listOf("detect:true", "send", "wait:1s", "detect:true", "reject"))
        f.assertLog(sent = true, fallbackReject = true)
    }

    @Test
    fun `Preferred also sends for either support fallback mode`() {
        for (fallback in listOf(SupportSelectionModeEnum.First, SupportSelectionModeEnum.Manual)) {
            val f = Fixture()
            every { f.api.prefs.selectedBattleConfig.support.fallbackTo } returns fallback
            f.run()
            f.assertLog(sent = true, fallbackReject = false)
        }
    }

    @Test
    fun `later detections can send again without a once per screen guard`() {
        val f = Fixture(screenRemains = true, iterations = 2)
        f.run()
        val attempt = listOf("detect:true", "send", "wait:1s", "detect:true", "reject")
        assertThat(f.trace).isEqualTo(attempt + attempt)
        assertThat(f.logs.map { it.sent to it.fallbackReject }).isEqualTo(listOf(true to true, true to true))
    }
}
