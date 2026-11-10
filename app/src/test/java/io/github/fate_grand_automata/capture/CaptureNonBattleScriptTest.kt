package io.github.fate_grand_automata.capture

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.capture.AccountDelta
import io.github.fate_grand_automata.scripts.capture.ChangeSource
import io.github.fate_grand_automata.scripts.enums.GameServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** Issue #311: only battle scripts pause the outside log; other FGA scripts are recorded like manual play. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class CaptureNonBattleScriptTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var prefs: PrefsCore
    private val host = "https://yfgo-game.netmarble.com/"

    @Before fun setup() {
        val store = context.getSharedPreferences("non-battle-test", Context.MODE_PRIVATE)
        store.edit().clear().commit()
        prefs = PrefsCore(PrefMaker(store), context).apply {
            captureTrackerEnabled.set(true)
            captureConsentVersion.set(CaptureConsent.VERSION)
        }
        CaptureTracker.init(context)
        CaptureTracker.discardRun()
        CaptureTracker.setRunning(false)
        CaptureTracker.delete()
        feed("login/top", outsideLogin().toString())
        CaptureTracker.setRunning(true)
    }

    @After fun clean() {
        CaptureTracker.discardRun()
        CaptureTracker.setRunning(false)
        CaptureTracker.delete()
    }

    private fun feed(path: String, body: String) = CaptureTracker.onGameResponse(host + path, body.toByteArray(), null)
    private fun fruit(num: Long) = feed("item/recover", outsideFruit(num = num).toString())
    private fun summon(friendPoint: Long) = feed("gacha/draw", outsideFriendPointSummon(friendPoint, includeCards = false).toString())
    private fun start(battle: Boolean) = CaptureAutoStart.onScriptStart(context, prefs, GameServer.Kr, battle)
    private val open get() = CaptureTracker.openOutsideEntries.value
    private val battleResult = """{"cache":{"updated":{"userGame":[{"userId":"fictionalA","exp":250}]}},
        "response":[{"success":{"phaseClearQp":1000}}]}"""

    @Test fun nonBattleStartKeepsPeriodOpenAndRecordsSummonWithoutBattleLog() {
        fruit(8)
        val id = open.single().id
        start(battle = false)
        assertEquals(id, open.single().id)
        summon(9800)
        val period = open.single().period
        assertEquals(id, open.single().id)
        assertEquals(-200, period.total.friendPoint)
        assertTrue(period.sources.any { it.source == ChangeSource(ChangeSource.Group.Summon, script = true) })
        CaptureAutoStart.onScriptEnd()
        summon(9600)
        assertEquals(-200L, open.single().period.sources.single { !it.source.script && it.source.group == ChangeSource.Group.Summon }.delta.friendPoint)
        assertEquals(-200L, open.single().period.sources.single { it.source.script }.delta.friendPoint)
        assertNull(CaptureTracker.finishRun())
        assertEquals(id, open.single().id)
    }

    @Test @Config(qualifiers = "ja") fun officialJpNonBattleMarksOnlyScriptTimeChanges() {
        CaptureAutoStart.onScriptStart(context, prefs, GameServer.Jp.Original, battle = false)
        summon(9800)
        assertTrue(open.single().period.sources.single().source.script)
        CaptureAutoStart.onScriptEnd()
        summon(9600)
        val sources = open.single().period.sources
        assertEquals(listOf(true, false), sources.map { it.source.script })
        assertEquals(listOf(-200L, -200L), sources.map { it.delta.friendPoint })
    }

    @Test fun nonBattleStartWithoutPeriodOpensOneOnChange() {
        assertTrue(open.isEmpty())
        start(battle = false)
        fruit(8)
        assertEquals(AccountDelta(items = mapOf(100L to -2L)), open.single().period.total)
    }

    @Test fun battleStartClosesAndPausesAndStillRecordsBattleLog() {
        fruit(8)
        start(battle = true)
        assertTrue(open.isEmpty())
        fruit(6)
        feed("battle/result", battleResult)
        assertTrue(open.isEmpty())
        CaptureAutoStart.onScriptEnd()
        assertEquals(1, CaptureTracker.finishRun()?.capturedBattles)
        // The run ended: later changes are outside again.
        fruit(5)
        assertEquals(1, open.size)
    }

    @Test fun nonBattleEndThenBattleStartClosesPeriodAtBattleStart() {
        start(battle = false)
        fruit(8)
        CaptureAutoStart.onScriptEnd()
        assertEquals(1, open.size)
        start(battle = true)
        assertTrue(open.isEmpty())
    }

    @Test fun vpnAutoStartIsStillAttemptedForNonBattleScript() {
        CaptureTracker.setRunning(false)
        ShadowToast.reset()
        start(battle = false)
        shadowOf(Looper.getMainLooper()).idle()
        // Not running, so the start path reaches the consent/certificate step and reports through a toast.
        assertNotNull(ShadowToast.getTextOfLatestToast())
    }
}
