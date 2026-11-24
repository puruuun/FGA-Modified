package io.github.fate_grand_automata.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.runner.performScriptExitCaptureAndCleanup
import io.github.fate_grand_automata.scripts.capture.SupportListCounts
import io.github.fate_grand_automata.scripts.capture.SupportListCriteria
import io.github.fate_grand_automata.scripts.entrypoints.AutoLottery
import io.github.fate_grand_automata.scripts.enums.BondCEEffectEnum
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class SupportListFilterStateTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val manual = SupportListCriteria(setOf(1L))
    private val script = SupportListCriteria(setOf(2L))

    @Before fun reset() {
        SupportListFilterState.resetForTesting()
        SupportListLastResult.resetForTesting()
    }

    @Test fun withoutAScriptTheManualFilterApplies() {
        assertNull(SupportListFilterState.criteria())
        SupportListFilterState.setManual(SupportFilterBuild.Ready(manual))
        assertEquals(manual, SupportListFilterState.criteria())
        SupportListFilterState.setManual(SupportFilterBuild.Off)
        assertNull(SupportListFilterState.criteria())
    }

    @Test fun whileAScriptRunsOnlyItsConfigDecidesAndTheManualFilterReturnsAfterIt() {
        SupportListFilterState.setManual(SupportFilterBuild.Ready(manual))
        val token = SupportListFilterState.beginScript()
        // The config's build is not there yet, and the manual filter is not applied meanwhile.
        assertNull(SupportListFilterState.criteria())
        SupportListFilterState.setScript(token, SupportFilterBuild.Ready(script))
        assertEquals(script, SupportListFilterState.criteria())
        // Editing the manual filter during the run changes nothing for the run.
        SupportListFilterState.setManual(SupportFilterBuild.Ready(SupportListCriteria(setOf(3L))))
        assertEquals(script, SupportListFilterState.criteria())
        SupportListFilterState.endScript()
        assertEquals(SupportListCriteria(setOf(3L)), SupportListFilterState.criteria())
    }

    @Test fun aConfigWithItsSwitchOffFiltersNothingEvenWithTheManualFilterOn() {
        SupportListFilterState.setManual(SupportFilterBuild.Ready(manual))
        val token = SupportListFilterState.beginScript()
        SupportListFilterState.setScript(token, SupportFilterBuild.Off)
        assertNull(SupportListFilterState.criteria())
        SupportListFilterState.setScript(token, SupportFilterBuild.Unmapped(listOf("x"), emptyList()))
        assertNull(SupportListFilterState.criteria())
    }

    @Test fun aBuildThatArrivesAfterItsScriptEndedIsDropped() {
        val first = SupportListFilterState.beginScript()
        SupportListFilterState.endScript()
        SupportListFilterState.setScript(first, SupportFilterBuild.Ready(script))
        assertNull(SupportListFilterState.criteria())
        val second = SupportListFilterState.beginScript()
        SupportListFilterState.setScript(first, SupportFilterBuild.Ready(script))
        assertNull(SupportListFilterState.criteria())
        SupportListFilterState.setScript(second, SupportFilterBuild.Ready(script))
        assertEquals(script, SupportListFilterState.criteria())
    }

    @Test fun changingWhatAppliesClearsTheLastResult() {
        SupportListFilterState.setManual(SupportFilterBuild.Ready(manual))
        SupportListLastResult.filtered(SupportListCounts(20, 3))
        SupportListFilterState.setManual(SupportFilterBuild.Ready(manual))
        assertNotNull(SupportListLastResult.last.value)
        SupportListFilterState.beginScript()
        assertNull(SupportListLastResult.last.value)
    }

    @Test fun theScriptExitPathEndsTheScriptFilter() {
        SupportListFilterState.setManual(SupportFilterBuild.Ready(manual))
        val token = SupportListFilterState.beginScript()
        SupportListFilterState.setScript(token, SupportFilterBuild.Ready(script))
        performScriptExitCaptureAndCleanup(
            e = AutoLottery.ExitException(AutoLottery.ExitReason.RanOutOfCurrency),
            onScriptEnd = {}, discardRun = {}, cleanup = {},
        )
        assertEquals(manual, SupportListFilterState.criteria())
    }

    private fun prefs(): PrefsCore {
        val store = context.getSharedPreferences("support-filter-state", Context.MODE_PRIVATE)
        store.edit().clear().commit()
        context.getSharedPreferences(PrefsCore.MANUAL_SUPPORT_FILTER_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        for (id in listOf("cfg", "grand")) context.getSharedPreferences(id, Context.MODE_PRIVATE).edit().clear().commit()
        return PrefsCore(PrefMaker(store), context)
    }

    private val lookup = SupportIdLookup(
        builtinServants = mapOf("Arcueid" to 2300500L),
        builtinCes = mapOf("Black Grail.png" to 9400480L),
        servantRows = mapOf(null to listOf(SupportListRow(100100, 2, "Servant", "saber"))),
    )

    @Test fun theExperimentsIdsMoveIntoTheManualFilterOnce() {
        val prefs = prefs()
        prefs.legacySupportFilterEnabled.set(true)
        prefs.legacySupportFilterServants.set(setOf("2300500", "100100", "777", "bad"))
        prefs.legacySupportFilterCes.set(setOf("9400480"))
        prefs.manualSupportFilter.preferredServants.set(setOf("Kept"))

        assertTrue(SupportFilterMigration.run(prefs, lookup))
        val manual = prefs.manualSupportFilter
        assertEquals(setOf("Kept", "Arcueid", "No.2 Servant", "#777"), manual.preferredServants.get())
        assertEquals(setOf("Black Grail.png"), manual.preferredCEs.get())
        assertFalse(manual.supportListFilter.get())
        assertEquals(emptySet(), prefs.legacySupportFilterServants.get())
        assertFalse(prefs.legacySupportFilterEnabled.get())
        assertTrue(prefs.supportFilterMigrated.get())

        // A second start never runs it again, even if old keys came back from somewhere.
        prefs.legacySupportFilterServants.set(setOf("999"))
        assertFalse(SupportFilterMigration.run(prefs, lookup))
        assertFalse("#999" in manual.preferredServants.get())
    }

    @Test fun theManualFilterIsStoredApartFromEveryBattleConfig() {
        val prefs = prefs()
        val config = prefs.forBattleConfig("cfg")
        prefs.manualSupportFilter.preferredServants.set(setOf("Arcueid"))
        prefs.manualSupportFilter.supportListFilter.set(true)
        assertEquals(emptySet(), config.support.preferredServants.get())
        assertFalse(config.support.supportListFilter.get())
        assertFalse("support_list_filter" in config.export())
    }

    @Test fun grandConditionsAreLeftToTheScript() {
        val prefs = prefs()
        val support = prefs.forBattleConfig("grand").support
        support.supportListFilter.set(true)
        support.preferredServants.set(setOf("Arcueid"))
        val plain = buildSupportFilter(SupportFilterSettings.of(support), lookup)
        support.grandServant.set(true)
        support.grandScore16Check.set(true)
        support.bondCEEffect.set(BondCEEffectEnum.NP)
        support.requireBothNormalAndRewardMatch.set(true)
        assertEquals(plain, buildSupportFilter(SupportFilterSettings.of(support), lookup))
        assertEquals(SupportListCriteria(setOf(2300500L)), plain.applied)
    }
}
