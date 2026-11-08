package io.github.fate_grand_automata.capture

import android.content.Context
import android.content.pm.PackageInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.more.GainsTrackerContent
import io.github.fate_grand_automata.ui.more.GainsTrackerUiState
import io.github.fate_grand_automata.ui.screenshotRules
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "ko-w360dp-h1000dp-hdpi")
class JpRootSettingsUiTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs by lazy {
        PrefsCore(PrefMaker(context.getSharedPreferences("jp-test", 0).also { it.edit().clear().commit() }), context)
    }
    private val fake = FakeJpCaInstaller()
    private val settings by lazy { JpRootSettings(fake, prefs.captureJpRoot.get()) { prefs.captureJpRoot.set(it) } }

    private fun show(installed: Boolean = true) {
        if (installed) shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = CaptureVpnService.JP_GAME_PACKAGE
        })
        compose.setContent {
            val state by settings.state.collectAsState()
            val scope = rememberCoroutineScope()
            FGATheme {
                GainsTrackerContent(GainsTrackerUiState(enabled = true), {}, {}, {}, {}, {}, {}, {},
                    jpState = state, jpInstalled = jpGameInstalled(context),
                    onJpRoot = { scope.launch { settings.setEnabled(it) } })
            }
        }
    }
    private fun message(res: Int) = compose.onNodeWithText(context.getString(res))

    @Test @Config(sdk = [28]) fun hiddenBeforeAndroid10() {
        show()
        message(R.string.capture_jp_root).assertDoesNotExist()
    }
    @Test fun hiddenWithoutOfficialJpPackage() {
        show(installed = false)
        message(R.string.capture_jp_root).assertDoesNotExist()
    }
    @Test fun serverSectionsShowKrHeadingStatusAndButtonsThenJpHeadingAndSwitchThenVpn() {
        show()
        val kr = message(R.string.capture_cert_header).fetchSemanticsNode().boundsInRoot
        val status = message(R.string.capture_cert_missing).fetchSemanticsNode().boundsInRoot
        val create = message(R.string.capture_cert_create).fetchSemanticsNode().boundsInRoot
        val guide = message(R.string.capture_guide_button).fetchSemanticsNode().boundsInRoot
        val jpHeading = message(R.string.capture_jp_root).fetchSemanticsNode().boundsInRoot
        val jpLabel = message(R.string.capture_jp_root_summary).fetchSemanticsNode().boundsInRoot
        val jp = compose.onNodeWithTag("jp-root-switch").fetchSemanticsNode().boundsInRoot
        val vpn = message(R.string.capture_vpn_header).fetchSemanticsNode().boundsInRoot
        assertTrue("KR status must follow its heading", status.top >= kr.bottom)
        assertTrue("Both KR buttons must follow the status", minOf(create.top, guide.top) >= status.bottom)
        assertTrue("JP heading must follow both KR buttons", jpHeading.top >= maxOf(create.bottom, guide.bottom))
        assertTrue("JP label and switch must follow their heading", minOf(jpLabel.top, jp.top) >= jpHeading.bottom)
        assertTrue("JP switch must precede the VPN section", jp.bottom <= vpn.top)
        compose.onAllNodesWithText(context.getString(R.string.capture_jp_root)).assertCountEquals(1)
    }
    @Test fun krSectionIsShownWithoutKrGameInstalled() {
        shadowOf(context.packageManager).removePackage(CaptureVpnService.GAME_PACKAGE)
        assertThrows(android.content.pm.PackageManager.NameNotFoundException::class.java) {
            context.packageManager.getPackageInfo(CaptureVpnService.GAME_PACKAGE, 0)
        }
        show()
        message(R.string.capture_cert_header).assertIsDisplayed()
        message(R.string.capture_cert_missing).assertIsDisplayed()
        message(R.string.capture_cert_create).assertIsDisplayed()
        message(R.string.capture_guide_button).assertIsDisplayed()
    }
    @Test fun noRootKeepsSwitchOffAndShowsMessage() {
        fake.root = false
        show()
        compose.onNodeWithTag("jp-root-switch").performClick()
        compose.waitUntil { settings.state.value.status == JpCaStatus.NoRoot }
        compose.onNodeWithTag("jp-root-switch").assertIsOff()
        message(R.string.capture_jp_no_root).assertIsDisplayed()
        assertFalse(prefs.captureJpRoot.get())
        assertEquals(0, fake.certificates)
        assertEquals(0, fake.injections)
    }
    @Test fun successShowsInstalledAndPersistsOnlyAfterRoot() {
        assertFalse(prefs.captureJpRoot.get())
        show()
        compose.onNodeWithTag("jp-root-switch").performClick()
        compose.waitUntil { settings.state.value.status == JpCaStatus.Installed }
        compose.onNodeWithTag("jp-root-switch").assertIsOn()
        compose.onNodeWithText("JP 인증서: 설치됨").assertIsDisplayed()
        assertTrue(prefs.captureJpRoot.get())
        assertEquals(1, fake.certificates)
        assertEquals(1, fake.injections)
    }
    @Test fun deniedInjectionKeepsSwitchOff() {
        fake.result = RootCaResult.Denied
        show()
        compose.onNodeWithTag("jp-root-switch").performClick()
        compose.waitUntil { settings.state.value.status == JpCaStatus.NoRoot }
        compose.onNodeWithTag("jp-root-switch").assertIsOff()
        assertFalse(prefs.captureJpRoot.get())
    }
    @Test fun turningOffRemovesAndShowsRebootAdvice() {
        runBlocking { settings.setEnabled(true) }
        show()
        compose.onNodeWithTag("jp-root-switch").performClick()
        compose.waitUntil { settings.state.value.status == JpCaStatus.Removed }
        message(R.string.capture_jp_reboot).assertIsDisplayed()
        assertFalse(prefs.captureJpRoot.get())
        assertEquals(1, fake.removals)
    }
    @Test fun refreshReadsCurrentPresenceAfterResume() = runBlocking {
        settings.setEnabled(true)
        fake.present = false
        settings.refresh()
        assertEquals(JpCaStatus.Absent, settings.state.value.status)
        fake.present = true
        settings.refresh()
        assertEquals(JpCaStatus.Installed, settings.state.value.status)
    }
    @Test fun rootProbeHasFiveSecondLimitAndRequiresUidZero() {
        val injector = RootCaInjector(context, RootShell { cmd, _, timeout ->
            assertEquals(listOf("su", "-c", "id"), cmd)
            assertEquals(5L, timeout)
            RootShellOutput(0, listOf("uid=0(root) gid=0(root)"))
        })
        assertTrue(injector.hasRoot())
        assertFalse(RootCaInjector(context, RootShell { _, _, _ ->
            RootShellOutput(0, listOf("uid=2000(shell)")) }).hasRoot())
        assertFalse(RootCaInjector(context, RootShell { _, _, _ ->
            RootShellOutput(-1, emptyList(), true) }).hasRoot())
    }
}
