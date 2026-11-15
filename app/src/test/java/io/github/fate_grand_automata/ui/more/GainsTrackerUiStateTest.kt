package io.github.fate_grand_automata.ui.more

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class GainsTrackerUiStateTest {
    private val sampleInput = "sample-token-42"

    @Test
    fun `adGuardAutoSwitchEffective requires AdGuard installed`() {
        val installedState = GainsTrackerUiState(
            adGuardInstalled = true,
            adGuardAutoSwitch = true,
            adGuardAutomationPassword = sampleInput,
        )
        assertThat(installedState.adGuardAutoSwitchEffective).isTrue()

        val uninstalledState = installedState.copy(adGuardInstalled = false)
        assertThat(uninstalledState.adGuardAutoSwitchEffective).isFalse()
    }

    @Test
    fun `adGuardAutoSwitchEffective stays false when switch off or password blank even if installed`() {
        assertThat(
            GainsTrackerUiState(
                adGuardInstalled = true,
                adGuardAutoSwitch = false,
                adGuardAutomationPassword = sampleInput,
            ).adGuardAutoSwitchEffective
        ).isFalse()

        assertThat(
            GainsTrackerUiState(
                adGuardInstalled = true,
                adGuardAutoSwitch = true,
                adGuardAutomationPassword = "   ",
            ).adGuardAutoSwitchEffective
        ).isFalse()
    }
}
