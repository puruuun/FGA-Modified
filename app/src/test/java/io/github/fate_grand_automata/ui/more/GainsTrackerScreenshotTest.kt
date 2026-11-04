package io.github.fate_grand_automata.ui.more

import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.scripts.capture.AccountSummary
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 360 dp review images of the gains tracker screen, recorded locally with :app:recordRoborazziCi. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class GainsTrackerScreenshotTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules = screenshotRules(composeRule)

    private val summary = AccountSummary(
        masterName = "Master", level = 120, ap = 85, apMax = 142, carryOverActPoint = 20,
        stone = 312, freeStone = 200, chargeStone = 112, fragments = 45, goldenApples = 12,
        silverApples = 30, bronzeApples = 8, copperApples = 150, bronzeSaplings = 3, tickets = 7,
        manaPrisms = 9000, rarePrisms = 120, qp = 123_456_789,
        snapshotTime = 1_762_000_000, lastUpdateTime = 1_762_003_600,
    )

    private fun shoot(name: String, state: GainsTrackerUiState) = composeRule.shoot(
        folder = "gains-tracker", name = name, widthDp = 360, heightDp = 1200,
        beforeCapture = { composeRule.waitForIdle() },
    ) {
        GainsTrackerContent(
            state = state, onEnabled = {}, onStopWithScript = {}, onCreateCertificate = {},
            onOpenSecuritySettings = {}, onStart = {}, onStop = {}, onDelete = {},
        )
    }

    @Test
    fun withoutSnapshot() = shoot(
        "NoSnapshot",
        GainsTrackerUiState(enabled = true, certExists = true, certInstalled = true, running = true),
    )

    @Test
    fun withSnapshot() = shoot(
        "Snapshot",
        GainsTrackerUiState(
            enabled = true, certExists = true, certInstalled = true, running = true, summary = summary,
        ),
    )
}
