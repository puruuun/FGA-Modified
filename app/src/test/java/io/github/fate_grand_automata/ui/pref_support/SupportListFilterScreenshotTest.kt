package io.github.fate_grand_automata.ui.pref_support

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.CaptureLauncherFixture
import io.github.fate_grand_automata.capture.SupportFilterBuild
import io.github.fate_grand_automata.capture.SupportListLastResult
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.prefs.core.SupportFilterSource
import io.github.fate_grand_automata.prefs.core.SupportPrefsCore
import io.github.fate_grand_automata.scripts.capture.SupportListCounts
import io.github.fate_grand_automata.scripts.capture.SupportListCriteria
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.ScriptModeEnum
import io.github.fate_grand_automata.ui.launcher.LauncherPanel
import io.github.fate_grand_automata.ui.launcher.ScriptLauncher
import io.github.fate_grand_automata.ui.more.ImportFromBattleConfig
import io.github.fate_grand_automata.ui.more.MoreOptionsContent
import io.github.fate_grand_automata.ui.more.SupportFilterProfile
import io.github.fate_grand_automata.ui.more.SupportListFilterScreen
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import io.github.fate_grand_automata.util.StorageProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The support list filter of #695, light and dark, Korean: the launcher (feature off, filter panel, weekly panel) at
 * the width the overlay window takes with a side panel on a 16:9 screen 720dp high, and the More and profile screens
 * at 360dp. Images land in `build/outputs/roborazzi/support695/`. Recorded by `:app:recordRoborazziCi`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SupportListFilterScreenshotTest(private val dark: Boolean) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))

        // The overlay window on 1280x720dp: about 65% of the width alone, 1.4 times that with the filter panel, and the
        // whole width less a margin with the weekly panel.
        const val LAUNCHER_WIDTH = 832
        const val LAUNCHER_FILTER = 1164
        const val LAUNCHER_WIDE = 1256
        const val LAUNCHER_HEIGHT = 640
    }

    private val compose = createComposeRule()

    @get:Rule
    val rules = screenshotRules(compose)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun prefs(): PrefsCore {
        val shared = context.getSharedPreferences("support695", Context.MODE_PRIVATE)
        shared.edit().clear().commit()
        context.getSharedPreferences(PrefsCore.MANUAL_SUPPORT_FILTER_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("t", Context.MODE_PRIVATE).edit().clear().commit()
        return PrefsCore(PrefMaker(shared), context).also { it.captureTrackerEnabled.set(true) }
    }

    private fun shoot(name: String, width: Int, height: Int, locale: String = "ko", beforeCapture: () -> Unit = {}, content: @Composable () -> Unit) =
        compose.shoot("support695", name, width, height, dark = dark, locale = locale, beforeCapture = beforeCapture) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
        }

    private fun SupportPrefsCore.longConditions() = apply {
        preferredServants.set(setOf("Arcueid", "Merlin", "Skadi", "Altria Caster", "No.351 Somebody with a long name"))
        displayedAscensions.set(setOf("s4|Arcueid", "c1|Arcueid", "s1|Merlin", "s2|Merlin", "s3|Merlin", "c2|Skadi"))
        preferredCEs.set(setOf("Black Grail.png", "Kaleidoscope.png", "No.48 Some event CE.png"))
        mlb.set(true)
        maxAscended.set(true)
        skill1Max.set(true)
        skill3Max.set(true)
        appendSkillCheck.set(true)
        appendSkillsMax[1].set(true)
        minimumNpLevel.set(3)
        npLevelCheck.set(true)
        score62Check.set(true)
        friendsOnly.set(true)
    }

    private fun launcherFixture(feature: Boolean): CaptureLauncherFixture {
        context.getSharedPreferences(PrefsCore.MANUAL_SUPPORT_FILTER_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        return CaptureLauncherFixture(context, GameServer.Kr).also {
            it.core.captureTrackerEnabled.set(true)
            it.core.supportFilterFeature.set(feature)
        }
    }

    @Test fun launcherFeatureOff() {
        val fixture = launcherFixture(feature = false)
        shoot("LauncherFeatureOff", LAUNCHER_WIDTH, LAUNCHER_HEIGHT) {
            ScriptLauncher(ScriptModeEnum.Battle, {}, fixture.prefs, fixture.core)
        }
    }

    @Test fun launcherFilterPanel() = filterPanel("ko")

    // The buttons' labels are longest in Japanese.
    @Test fun launcherFilterPanelJa() = filterPanel("ja")

    private fun filterPanel(locale: String) {
        val fixture = launcherFixture(feature = true)
        fixture.core.supportFilterOn.set(true)
        fixture.core.activeSupportFilter.longConditions()
        fixture.core.supportFilterSource.set(SupportFilterSource.Profile)
        fixture.core.supportFilterSourceName.set("주회용 필터")
        SupportListLastResult.resetForTesting()
        fixture.core.addSupportFilterProfile("주회용 필터", "a")
        shoot("LauncherFilterPanel", LAUNCHER_FILTER, LAUNCHER_HEIGHT, locale, beforeCapture = {
            SupportListLastResult.filtered(SupportListCounts(20, 6, 498, 7))
            compose.waitForIdle()
        }) {
            ScriptLauncher(ScriptModeEnum.Battle, {}, fixture.prefs, fixture.core, initialPanel = LauncherPanel.SupportFilter)
        }
    }

    @Test fun launcherWeeklyPanel() {
        val fixture = launcherFixture(feature = true)
        shoot("LauncherWeeklyPanel", LAUNCHER_WIDE, LAUNCHER_HEIGHT) {
            ScriptLauncher(ScriptModeEnum.Battle, {}, fixture.prefs, fixture.core, initialPanel = LauncherPanel.Weekly)
        }
    }

    @Test fun moreScreenWithTwoProfiles() {
        val prefs = prefs()
        prefs.supportFilterFeature.set(true)
        prefs.addSupportFilterProfile("주회용 필터", "a").support.longConditions()
        prefs.addSupportFilterProfile("고난도", "b").support.apply {
            preferredServants.set(setOf("Skadi"))
            friendsOnly.set(true)
        }
        shoot("MoreSupportListFilter", 360, 800) { SupportListFilterScreen(prefs, editProfile = {}) }
    }

    @Test fun profileScreen() {
        val prefs = prefs()
        val profile = prefs.addSupportFilterProfile("주회용 필터", "a")
        profile.support.preferredServants.set(setOf("Arcueid"))
        val vm = SupportViewModel(StorageProvider(prefs, context))
        shoot("ProfileScreen", 360, 1400) {
            SupportFilterProfile(
                prefs, profile, vm,
                filter = SupportListFilterUi(
                    R.string.support_list_filter_summary_profile,
                    build = { SupportFilterBuild.Ready(SupportListCriteria(setOf(2300500L))) },
                    extra = { ImportFromBattleConfig(prefs, profile) },
                    script = false,
                ),
                captureAvailable = true,
            )
        }
    }

    @Test fun battleConfigSupportScreenUnmappedNotice() {
        val prefs = prefs()
        val support = prefs.forBattleConfig("t").support.apply {
            preferredServants.set(setOf("Arcueid"))
            friendsOnly.set(true)
        }
        val vm = SupportViewModel(StorageProvider(prefs, context))
        shoot("BattleConfigSupportUnmapped", 360, 800) {
            PreferredSupport(
                support, vm,
                filter = SupportListFilterUi(
                    R.string.support_list_filter_summary_config,
                    build = { SupportFilterBuild.Unmapped(listOf("내가 만든 이미지"), emptyList()) },
                ),
                captureAvailable = true,
            )
        }
    }

    @Test fun moreScreenAdditionalFeatures() {
        val prefs = prefs()
        shoot(
            "MoreAdditionalFeatures", 360, 800,
            beforeCapture = {
                compose.onNodeWithText(context.getString(R.string.p_additional_features)).performClick()
                compose.waitForIdle()
            },
        ) {
            MoreOptionsContent(
                prefs = prefs, storageSummary = "FGA", extractSummary = "", extractSupportImages = {},
                additionalFeatures = {}, goToFineTune = {}, goToAddedFeatureTuning = {},
                goToLicense = {}, goToWeeklySettings = {}, pickDirectory = {},
            )
        }
    }
}
