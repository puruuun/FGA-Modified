package io.github.fate_grand_automata.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.prefs.core.SupportFilterSource
import io.github.fate_grand_automata.runner.performScriptExitCaptureAndCleanup
import io.github.fate_grand_automata.scripts.capture.SupportListCounts
import io.github.fate_grand_automata.scripts.capture.SupportListCriteria
import io.github.fate_grand_automata.scripts.entrypoints.AutoLottery
import io.github.fate_grand_automata.scripts.enums.BondCEEffectEnum
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.SupportSelectionModeEnum
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
    private val criteria = SupportListCriteria(setOf(1L))
    private val key = PrefsCore.LEGACY_SUPPORT_LIST_FILTER_KEY

    @Before fun reset() {
        SupportListFilterState.resetForTesting()
        SupportListLastResult.resetForTesting()
    }

    @Test fun onlyTheFeatureSwitchAndTheToggleTogetherWithAReadyBuildGiveTheProxyCriteria() {
        val ready = SupportFilterBuild.Ready(criteria)
        for (feature in listOf(false, true)) for (toggle in listOf(false, true)) {
            SupportListFilterState.update(feature, toggle, ready)
            assertEquals(if (feature && toggle) criteria else null, SupportListFilterState.criteria(), "feature=$feature toggle=$toggle")
        }
        for (stopped in listOf(SupportFilterBuild.Off, SupportFilterBuild.NoClassList, SupportFilterBuild.Unmapped(listOf("x"), emptyList()),
            SupportFilterBuild.UnknownClass(listOf("y")), SupportFilterBuild.Ready(SupportListCriteria()))) {
            SupportListFilterState.update(true, true, ready)
            SupportListFilterState.update(true, true, stopped)
            assertNull(SupportListFilterState.criteria(), stopped.toString())
        }
    }

    @Test fun changingWhatAppliesClearsTheLastResult() {
        SupportListFilterState.update(true, true, SupportFilterBuild.Ready(criteria))
        SupportListLastResult.filtered(SupportListCounts(20, 3))
        SupportListFilterState.update(true, true, SupportFilterBuild.Ready(criteria))
        assertNotNull(SupportListLastResult.last.value)
        SupportListFilterState.update(true, false, SupportFilterBuild.Ready(criteria))
        assertNull(SupportListLastResult.last.value)
    }

    @Test fun scriptStartAndScriptEndChangeNothing() {
        val prefs = prefs()
        SupportListFilterState.update(true, true, SupportFilterBuild.Ready(criteria))
        CaptureAutoStart.onScriptStart(context, prefs, GameServer.Kr, battle = true)
        assertEquals(criteria, SupportListFilterState.criteria())
        performScriptExitCaptureAndCleanup(e = AutoLottery.ExitException(AutoLottery.ExitReason.RanOutOfCurrency), cleanup = {})
        assertEquals(criteria, SupportListFilterState.criteria())

        SupportListFilterState.update(true, false, SupportFilterBuild.Ready(criteria))
        CaptureAutoStart.onScriptStart(context, prefs, GameServer.Kr, battle = true)
        performScriptExitCaptureAndCleanup(e = AutoLottery.ExitException(AutoLottery.ExitReason.RanOutOfCurrency), cleanup = {})
        assertNull(SupportListFilterState.criteria())
    }

    private fun prefs(): PrefsCore {
        val store = context.getSharedPreferences("support-filter-state", Context.MODE_PRIVATE)
        store.edit().clear().commit()
        context.getSharedPreferences(PrefsCore.MANUAL_SUPPORT_FILTER_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        for (id in listOf("cfg", "other", "grand")) context.getSharedPreferences(id, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(PrefsCore.SUPPORT_FILTER_PROFILE_FILE_PREFIX + SupportFilterToggleMigration.PROFILE_ID, Context.MODE_PRIVATE)
            .edit().clear().commit()
        return PrefsCore(PrefMaker(store), context)
    }

    private val lookup = SupportIdLookup(
        builtinServants = mapOf("Arcueid" to 2300500L),
        builtinCes = mapOf("Black Grail.png" to 9400480L),
        servantRows = mapOf(null to listOf(SupportListRow(100100, 2, "Servant", "saber"))),
    )

    @Test fun theFeatureSwitchAndTheToggleAreOffByDefault() {
        val prefs = prefs()
        assertFalse(prefs.supportFilterFeature.get())
        assertFalse(prefs.supportFilterOn.get())
        assertEquals(SupportFilterSource.None, prefs.supportFilterSource.get())
    }

    @Test fun theExperimentsIdsMoveIntoTheActiveFilterOnce() {
        val prefs = prefs()
        prefs.legacySupportFilterEnabled.set(true)
        prefs.legacySupportFilterServants.set(setOf("2300500", "100100", "777", "bad"))
        prefs.legacySupportFilterCes.set(setOf("9400480"))
        prefs.activeSupportFilter.preferredServants.set(setOf("Kept"))

        assertTrue(SupportFilterMigration.run(prefs, lookup))
        val active = prefs.activeSupportFilter
        assertEquals(setOf("Kept", "Arcueid", "No.2 Servant", "#777"), active.preferredServants.get())
        assertEquals(setOf("Black Grail.png"), active.preferredCEs.get())
        assertEquals(emptySet(), prefs.legacySupportFilterServants.get())
        assertFalse(prefs.legacySupportFilterEnabled.get())
        assertTrue(prefs.supportFilterMigrated.get())

        // A second start never runs it again, even if old keys came back from somewhere.
        prefs.legacySupportFilterServants.set(setOf("999"))
        assertFalse(SupportFilterMigration.run(prefs, lookup))
        assertFalse("#999" in active.preferredServants.get())
    }

    private fun configs(prefs: PrefsCore, vararg ids: String) = ids.map { prefs.forBattleConfig(it) }.also {
        prefs.battleConfigList.set(ids.toSet())
    }

    @Test fun theToggleMigrationKeepsTheManualFilterAsActiveAndAsAProfile() {
        val prefs = prefs()
        val (on, off) = configs(prefs, "cfg", "other")
        on.sharedPrefs.edit().putBoolean(key, true).commit()
        on.support.preferredServants.set(setOf("Merlin"))
        off.sharedPrefs.edit().putBoolean(key, false).commit()
        off.support.friendsOnly.set(true)
        off.support.preferredCEs.set(setOf("Black Grail.png"))
        prefs.activeSupportFilterFile.edit().putBoolean(key, true).commit()
        prefs.activeSupportFilter.preferredServants.set(setOf("Arcueid"))
        prefs.activeSupportFilter.score62Check.set(true)

        assertTrue(SupportFilterToggleMigration.run(prefs, "My filter"))

        assertEquals(setOf("Arcueid"), prefs.activeSupportFilter.preferredServants.get())
        assertTrue(prefs.activeSupportFilter.score62Check.get())
        val profile = prefs.forSupportFilterProfile(SupportFilterToggleMigration.PROFILE_ID)
        assertEquals(setOf(SupportFilterToggleMigration.PROFILE_ID), prefs.supportFilterProfiles.get())
        assertEquals("My filter", profile.name.get())
        assertEquals(setOf("Arcueid"), profile.support.preferredServants.get())
        assertTrue(profile.support.score62Check.get())
        assertEquals(SupportFilterSource.Profile, prefs.supportFilterSource.get())
        assertEquals("My filter", prefs.supportFilterSourceName.get())
        assertTrue(prefs.supportFilterOn.get())
        assertTrue(prefs.supportFilterFeature.get())
        // The retired key is gone everywhere; every other support setting stays as it was.
        for (file in listOf(on.sharedPrefs, off.sharedPrefs, prefs.activeSupportFilterFile)) assertFalse(file.contains(key))
        assertEquals(setOf("Merlin"), on.support.preferredServants.get())
        assertTrue(off.support.friendsOnly.get())
        assertEquals(setOf("Black Grail.png"), off.support.preferredCEs.get())
        assertTrue(prefs.supportFilterToggleMigrated.get())

        // Never again.
        prefs.supportFilterOn.set(false)
        assertFalse(SupportFilterToggleMigration.run(prefs, "My filter"))
        assertFalse(prefs.supportFilterOn.get())
    }

    @Test fun theFeatureSwitchIsTurnedOnOnlyForSomeoneWhoHadTheFilterOnSomewhere() {
        // A battle config's switch alone: the feature is on, the toggle stays off.
        prefs().let { prefs ->
            configs(prefs, "cfg").single().sharedPrefs.edit().putBoolean(key, true).commit()
            SupportFilterToggleMigration.run(prefs, "My filter")
            assertTrue(prefs.supportFilterFeature.get())
            assertFalse(prefs.supportFilterOn.get())
        }
        // Every switch off: the launcher stays as it was, and an untouched manual filter gives no profile.
        prefs().let { prefs ->
            val config = configs(prefs, "cfg").single()
            config.sharedPrefs.edit().putBoolean(key, false).commit()
            prefs.activeSupportFilterFile.edit().putBoolean(key, false).commit()
            SupportFilterToggleMigration.run(prefs, "My filter")
            assertFalse(prefs.supportFilterFeature.get())
            assertFalse(prefs.supportFilterOn.get())
            assertEquals(emptySet(), prefs.supportFilterProfiles.get())
            assertEquals(SupportFilterSource.None, prefs.supportFilterSource.get())
            assertFalse(config.sharedPrefs.contains(key))
        }
        // Conditions but the switch off: they become a profile, the feature stays off.
        prefs().let { prefs ->
            prefs.activeSupportFilter.friendsOnly.set(true)
            SupportFilterToggleMigration.run(prefs, "My filter")
            assertFalse(prefs.supportFilterFeature.get())
            assertEquals(setOf(SupportFilterToggleMigration.PROFILE_ID), prefs.supportFilterProfiles.get())
        }
    }

    @Test fun anOldExportThatCarriesTheRetiredSwitchImportsWithoutIt() {
        val prefs = prefs()
        val config = prefs.forBattleConfig("cfg")
        config.import(io.github.fate_grand_automata.prefs.BattleConfigFile.decode(
            """{"autoskill_name":"old","support_list_filter":true,"support_friends_only":true}"""
        ))
        assertFalse(config.sharedPrefs.contains(key))
        assertEquals("old", config.name.get())
        assertTrue(config.support.friendsOnly.get())
    }

    @Test fun theActiveFilterAndTheProfilesAreStoredApartFromEveryBattleConfig() {
        val prefs = prefs()
        val config = prefs.forBattleConfig("cfg")
        prefs.activeSupportFilter.preferredServants.set(setOf("Arcueid"))
        val profile = prefs.addSupportFilterProfile("p", "apart")
        profile.support.preferredCEs.set(setOf("Black Grail.png"))
        assertEquals(emptySet(), config.support.preferredServants.get())
        assertEquals(emptySet(), config.support.preferredCEs.get())
        assertFalse(config.export().keys.any { it.startsWith("support_filter") })
        prefs.removeSupportFilterProfile("apart")
        assertEquals(emptySet(), prefs.supportFilterProfiles.get())
        assertEquals(emptySet(), prefs.forSupportFilterProfile("apart").support.preferredCEs.get())
    }

    @Test fun loadingCopiesASnapshotAndNamesTheSource() {
        val prefs = prefs()
        val config = prefs.forBattleConfig("cfg").support
        config.preferredServants.set(setOf("Arcueid"))
        config.friendsOnly.set(true)
        prefs.loadSupportFilter(config, SupportFilterSource.Config, "Daily")
        config.preferredServants.set(setOf("Other"))
        config.friendsOnly.set(false)
        assertEquals(setOf("Arcueid"), prefs.activeSupportFilter.preferredServants.get())
        assertTrue(prefs.activeSupportFilter.friendsOnly.get())
        assertEquals(SupportFilterSource.Config, prefs.supportFilterSource.get())
        assertEquals("Daily", prefs.supportFilterSourceName.get())

        val profile = prefs.addSupportFilterProfile("Farming", "snap")
        profile.support.preferredCEs.set(setOf("Black Grail.png"))
        prefs.loadSupportFilter(profile.support, SupportFilterSource.Profile, "Farming")
        profile.support.preferredCEs.set(emptySet())
        assertEquals(setOf("Black Grail.png"), prefs.activeSupportFilter.preferredCEs.get())
        assertEquals(SupportFilterSource.Profile, prefs.supportFilterSource.get())
        prefs.removeSupportFilterProfile("snap")
    }

    @Test fun theStoredSelectionModeDoesNotStopAConfigsConditions() {
        val prefs = prefs()
        val support = prefs.forBattleConfig("cfg").support
        support.preferredServants.set(setOf("Arcueid"))
        for (mode in SupportSelectionModeEnum.entries) {
            support.selectionMode.set(mode)
            assertEquals(SupportListCriteria(setOf(2300500L)), buildSupportFilter(SupportFilterSettings.of(support), lookup).applied, mode.name)
        }
    }

    private fun settle(): Int {
        var last = -1
        var count = SupportListFilterController.buildsForTesting.get()
        val until = System.currentTimeMillis() + 10_000
        while ((count < 1 || count != last) && System.currentTimeMillis() < until) {
            last = count
            Thread.sleep(700)
            count = SupportListFilterController.buildsForTesting.get()
        }
        return count
    }

    @Test fun initTwiceLeavesOneWatcherAndOneChangeRebuildsOnce() {
        val prefs = prefs()
        SupportListFilterController.init(context, prefs)
        val first = SupportListFilterController.watcherForTesting!!
        SupportListFilterController.init(context, prefs)
        val second = SupportListFilterController.watcherForTesting!!
        assertTrue(first.isCancelled)
        assertTrue(second.isActive)
        val before = settle()
        prefs.activeSupportFilter.friendsOnly.set(true)
        val after = settle()
        assertEquals(1, after - before)
        second.cancel()
    }

    @Test fun theWatcherAppliesTheActiveConditionsOnlyWhileTheFeatureAndTheToggleAreOn() {
        val prefs = prefs()
        prefs.supportFilterToggleMigrated.set(true)
        prefs.activeSupportFilter.friendsOnly.set(true)
        SupportListFilterController.init(context, prefs)
        try {
            settle()
            assertNull(SupportListFilterState.criteria())
            prefs.supportFilterOn.set(true)
            settle()
            assertNull(SupportListFilterState.criteria())
            prefs.supportFilterFeature.set(true)
            settle()
            assertEquals(SupportListCriteria(friendsOnly = true), SupportListFilterState.criteria())
            prefs.activeSupportFilter.friendsOnly.set(false)
            prefs.activeSupportFilter.minimumNpLevel.set(4)
            prefs.activeSupportFilter.npLevelCheck.set(true)
            settle()
            assertEquals(SupportListCriteria(minimumNpLevel = 4), SupportListFilterState.criteria())
            prefs.supportFilterOn.set(false)
            settle()
            assertNull(SupportListFilterState.criteria())
            prefs.supportFilterOn.set(true)
            settle()
            prefs.supportFilterFeature.set(false)
            settle()
            assertNull(SupportListFilterState.criteria())
        } finally {
            SupportListFilterController.watcherForTesting?.cancel()
        }
    }

    @Test fun grandConditionsAreLeftToTheScript() {
        val prefs = prefs()
        val support = prefs.forBattleConfig("grand").support
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
