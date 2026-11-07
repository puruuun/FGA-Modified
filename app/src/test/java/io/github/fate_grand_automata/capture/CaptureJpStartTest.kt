package io.github.fate_grand_automata.capture

import android.content.Context
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.enums.GameServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class CaptureJpStartTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var prefs: PrefsCore

    @Before fun setup() {
        val store = context.getSharedPreferences("jp-start-test", Context.MODE_PRIVATE)
        store.edit().clear().commit()
        prefs = PrefsCore(PrefMaker(store), context).apply {
            captureTrackerEnabled.set(true)
            captureConsentVersion.set(CaptureConsent.VERSION)
            captureJpRoot.set(true)
        }
        CaptureTracker.init(context)
        CaptureTracker.discardRun()
        CaptureTracker.setRunning(false)
        CaptureTracker.delete()
    }

    @After fun clean() {
        CaptureTracker.discardRun()
        CaptureTracker.setRunning(false)
        CaptureTracker.delete()
    }

    private fun install(name: String) = shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = name })
    // Existence checks only. These synthetic files contain no certificate or private key.
    private fun certificate(jp: Boolean) {
        val dir = CertStore.directory(context)
        File(dir, if (jp) "leaf-jp.p12" else "leaf.p12").writeText("synthetic")
        File(dir, if (jp) "ca-jp.cer" else "ca.cer").writeText("synthetic")
    }

    @Test fun jpOnlyServiceStartRequestsVpnConsentWithoutKrCertificate() {
        install(CaptureVpnService.JP_GAME_PACKAGE)
        certificate(true)
        assertFalse(CertStore.exists(context))
        var consent = false
        CaptureServiceLifecycle.onStart(context, prefs) { consent = true }
        assertTrue(consent)
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test fun krOnlyServiceStartStillRequestsConsent() {
        install(CaptureVpnService.GAME_PACKAGE)
        certificate(false)
        var consent = false
        CaptureServiceLifecycle.onStart(context, prefs) { consent = true }
        assertTrue(consent)
    }

    @Test fun neitherReadyShowsExistingCertificateMessage() {
        install(CaptureVpnService.JP_GAME_PACKAGE)
        CaptureServiceLifecycle.onStart(context, prefs) { fail("Missing certificates must not request VPN consent") }
        assertEquals(context.getString(R.string.capture_service_no_certificate), ShadowToast.getTextOfLatestToast())
    }

    private fun script(server: GameServer, ready: Boolean): Int? {
        install(CaptureVpnService.JP_GAME_PACKAGE)
        if (ready) certificate(true)
        CaptureTracker.setRunning(true)
        CaptureAutoStart.onScriptStart(context, prefs, server)
        CaptureTracker.onGameResponse("https://game.fate-go.jp/battle/result", """{
            "cache":{"updated":{"userGame":[{"userId":7002}]}},
            "response":[{"success":{"phaseClearQp":1000}}]}""".toByteArray(), null)
        return CaptureTracker.finishRun()?.capturedBattles
    }

    @Test fun originalJpReadyStartsRun() = assertEquals(1, script(GameServer.Jp.Original, true))
    @Test fun originalJpUnreadyDoesNotStartRun() = assertNull(script(GameServer.Jp.Original, false))
    @Test fun betterFgoDoesNotStartRun() = assertNull(script(GameServer.Jp.BetterFGO, true))
    @Test fun otherServersDoNotStartRun() = assertNull(script(GameServer.En.Original, true))
    @Test fun krStillStartsRun() = assertEquals(1, script(GameServer.Kr, false))
}
