package io.github.fate_grand_automata.ui.more

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.fate_grand_automata.scripts.capture.AccountSummary
import io.github.fate_grand_automata.capture.CaptureAccount
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
        folder = "gains-tracker", name = name, widthDp = 360, heightDp = 1200, locale = "ko",
        beforeCapture = { composeRule.waitForIdle() },
    ) {
        GainsTrackerContent(
            state = state, onEnabled = {}, onStopWithService = {}, onCreateCertificate = {},
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
            enabled = true, certExists = true, certInstalled = true, running = true,
            accounts = listOf(CaptureAccount("7001", "Master", summary, summary.lastUpdateTime)), currentId = "7001",
        ),
    )

    private fun certShot(name: String, state: GainsTrackerUiState, confirm: Boolean = false) = composeRule.shoot(
        folder = "capture-folder", name = name, widthDp = 360, heightDp = 800, locale = "ko",
        beforeCapture = {
            if (confirm) composeRule.onNodeWithText(
                androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
                    .getString(io.github.fate_grand_automata.R.string.capture_cert_create_new),
            ).performClick()
        },
    ) {
        GainsTrackerContent(
            state = state, onEnabled = {}, onStopWithService = {}, onCreateCertificate = {},
            onOpenSecuritySettings = {}, onStart = {}, onStop = {}, onDelete = {},
        )
    }

    @Test fun certificateMissing() = certShot("CertMissing", GainsTrackerUiState(enabled = true))

    @Test fun certificatePresent() = certShot(
        "CertPresent",
        GainsTrackerUiState(enabled = true, certExists = true, certSavedPath = "FGA/capture/fga-capture-ca.crt"),
    )

    @Test fun newCertificateConfirmation() = certShot(
        "NewCertConfirm", GainsTrackerUiState(enabled = true, certExists = true), confirm = true,
    )
}
