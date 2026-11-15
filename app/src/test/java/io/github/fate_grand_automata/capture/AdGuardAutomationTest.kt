package io.github.fate_grand_automata.capture

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class AdGuardAutomationTest {
    private val sampleInput = "sample-token-42"

    @Test
    fun `component target uses AdGuard AutomationReceiver`() {
        assertThat(AdGuardAutomation.PACKAGE_NAME).isEqualTo("com.adguard.android")
        assertThat(AdGuardAutomation.AUTOMATION_RECEIVER)
            .isEqualTo("com.adguard.android.receiver.AutomationReceiver")
    }

    @Test
    fun `proxy_default extras use SOCKS5 127_0_0_1 port quiet true`() {
        val cmd = AdGuardCommand.ProxyDefault(8899)
        assertThat(cmd.action).isEqualTo("proxy_default")
        assertThat(AdGuardAutomation.buildExtras(cmd, sampleInput)).isEqualTo(
            mapOf(
                "password" to sampleInput,
                "quiet" to "true",
                "server" to "type=SOCKS5&host=127.0.0.1&port=8899",
            )
        )
    }

    @Test
    fun `proxy_default extras reflect custom port`() {
        val cmd = AdGuardCommand.ProxyDefault(9123)
        assertThat(AdGuardAutomation.buildExtras(cmd, sampleInput)).isEqualTo(
            mapOf(
                "password" to sampleInput,
                "quiet" to "true",
                "server" to "type=SOCKS5&host=127.0.0.1&port=9123",
            )
        )
    }

    @Test
    fun `proxy_state extras set enable true and false as strings`() {
        val onCmd = AdGuardCommand.ProxyState(enable = true)
        assertThat(onCmd.action).isEqualTo("proxy_state")
        assertThat(AdGuardAutomation.buildExtras(onCmd, sampleInput)).isEqualTo(
            mapOf(
                "password" to sampleInput,
                "quiet" to "true",
                "enable" to "true",
            )
        )

        val offCmd = AdGuardCommand.ProxyState(enable = false)
        assertThat(offCmd.action).isEqualTo("proxy_state")
        assertThat(AdGuardAutomation.buildExtras(offCmd, sampleInput)).isEqualTo(
            mapOf(
                "password" to sampleInput,
                "quiet" to "true",
                "enable" to "false",
            )
        )
    }

    @Test
    fun `auto switch is effective only when enabled and non-blank`() {
        assertThat(AdGuardAutomation.isAutoSwitchEffective(enabled = true, password = sampleInput)).isTrue()
        assertThat(AdGuardAutomation.isAutoSwitchEffective(enabled = false, password = sampleInput)).isFalse()
        assertThat(AdGuardAutomation.isAutoSwitchEffective(enabled = true, password = "")).isFalse()
        assertThat(AdGuardAutomation.isAutoSwitchEffective(enabled = true, password = "   ")).isFalse()
    }

    @Test
    fun `log string helper omits password value`() {
        val commands = listOf(
            AdGuardCommand.ProxyDefault(8899),
            AdGuardCommand.ProxyState(enable = true),
            AdGuardCommand.ProxyState(enable = false),
        )
        for (command in commands) {
            val formatted = AdGuardAutomation.formatCommandForLog(command)
            assertThat(formatted).doesNotContain(sampleInput)
        }
        assertThat(AdGuardAutomation.formatCommandForLog(AdGuardCommand.ProxyDefault(8899)))
            .isEqualTo("action=proxy_default port=8899")
        assertThat(AdGuardAutomation.formatCommandForLog(AdGuardCommand.ProxyState(enable = true)))
            .isEqualTo("action=proxy_state enable=true")
        assertThat(AdGuardAutomation.formatCommandForLog(AdGuardCommand.ProxyState(enable = false)))
            .isEqualTo("action=proxy_state enable=false")
    }

    @Test
    fun `reading on returns proxy_default then proxy_state enable true`() {
        val commands = AdGuardAutomation.commandsFor(
            event = AdGuardSwitchEvent.ReadingOn,
            autoSwitchEffective = true,
            readingActive = false,
            startupDisableSent = false,
            port = 8899,
        )
        assertThat(commands).containsExactly(
            AdGuardCommand.ProxyDefault(8899),
            AdGuardCommand.ProxyState(enable = true),
        )
    }

    @Test
    fun `reading off returns proxy_state enable false`() {
        val commands = AdGuardAutomation.commandsFor(
            event = AdGuardSwitchEvent.ReadingOff,
            autoSwitchEffective = true,
            readingActive = true,
            startupDisableSent = true,
            port = 8899,
        )
        assertThat(commands).containsExactly(
            AdGuardCommand.ProxyState(enable = false),
        )
    }

    @Test
    fun `service start with reading off returns proxy_state enable false at most once`() {
        val first = AdGuardAutomation.commandsFor(
            event = AdGuardSwitchEvent.ServiceStartEnsureOrSticky,
            autoSwitchEffective = true,
            readingActive = false,
            startupDisableSent = false,
            port = 8899,
        )
        assertThat(first).containsExactly(AdGuardCommand.ProxyState(enable = false))

        val subsequent = AdGuardAutomation.commandsFor(
            event = AdGuardSwitchEvent.ServiceStartEnsureOrSticky,
            autoSwitchEffective = true,
            readingActive = false,
            startupDisableSent = true,
            port = 8899,
        )
        assertThat(subsequent).isEmpty()

        val whileReading = AdGuardAutomation.commandsFor(
            event = AdGuardSwitchEvent.ServiceStartEnsureOrSticky,
            autoSwitchEffective = true,
            readingActive = true,
            startupDisableSent = false,
            port = 8899,
        )
        assertThat(whileReading).isEmpty()
    }

    @Test
    fun `leaving or shutdown returns proxy_state enable false`() {
        val commands = AdGuardAutomation.commandsFor(
            event = AdGuardSwitchEvent.LeavingOrShutdown,
            autoSwitchEffective = true,
            readingActive = false,
            startupDisableSent = true,
            port = 8899,
        )
        assertThat(commands).containsExactly(AdGuardCommand.ProxyState(enable = false))
    }

    @Test
    fun `ineffective auto switch emits no commands for any event`() {
        for (event in AdGuardSwitchEvent.entries) {
            val commands = AdGuardAutomation.commandsFor(
                event = event,
                autoSwitchEffective = false,
                readingActive = false,
                startupDisableSent = false,
                port = 8899,
            )
            assertThat(commands).isEmpty()
        }
    }
}
