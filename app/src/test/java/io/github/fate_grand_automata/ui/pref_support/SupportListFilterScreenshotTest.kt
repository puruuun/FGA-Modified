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
import io.github.fate_grand_automata.capture.SupportFilterBuild
import io.github.fate_grand_automata.capture.SupportListLastResult
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.capture.SupportListCounts
import io.github.fate_grand_automata.scripts.capture.SupportListCriteria
import io.github.fate_grand_automata.ui.more.ImportFromBattleConfig
import io.github.fate_grand_automata.ui.more.ManualSupportFilter
import io.github.fate_grand_automata.ui.more.MoreOptionsContent
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
 * The support list filter screens of #685 at 360x1400dp (support screens) and 360x800dp (More), light and dark,
 * Korean. Images land in `build/outputs/roborazzi/support685/`. Recorded by `:app:recordRoborazziCi`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SupportListFilterScreenshotTest(private val dark: Boolean) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }

    private val compose = createComposeRule()

    @get:Rule
    val rules = screenshotRules(compose)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun prefs(): PrefsCore {
        val shared = context.getSharedPreferences("support685", Context.MODE_PRIVATE)
        shared.edit().clear().commit()
        context.getSharedPreferences(PrefsCore.MANUAL_SUPPORT_FILTER_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("t", Context.MODE_PRIVATE).edit().clear().commit()
        return PrefsCore(PrefMaker(shared), context).also { it.captureTrackerEnabled.set(true) }
    }

    private fun shoot(name: String, height: Int, beforeCapture: () -> Unit = {}, content: @Composable () -> Unit) =
        compose.shoot("support685", name, 360, height, dark = dark, locale = "ko", beforeCapture = beforeCapture) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
        }

    private fun SupportPrefsCoreFixture(prefs: PrefsCore) = prefs.forBattleConfig("t").support.apply {
        supportListFilter.set(true)
        preferredServants.set(setOf("Arcueid"))
        preferredCEs.set(setOf("Black Grail.png"))
        maxAscended.set(true)
        friendsOnly.set(true)
    }

    @Test fun battleConfigSupportScreenFilterOn() {
        val prefs = prefs()
        val support = SupportPrefsCoreFixture(prefs)
        SupportListLastResult.resetForTesting()
        val vm = SupportViewModel(StorageProvider(prefs, context))
        shoot("BattleConfigSupportFilterOn", 1400, beforeCapture = {
            SupportListLastResult.filtered(SupportListCounts(20, 6, 498, 7))
            compose.waitForIdle()
        }) {
            PreferredSupport(
                support, vm,
                filter = SupportListFilterUi(
                    R.string.support_list_filter_summary_config,
                    build = { SupportFilterBuild.Ready(SupportListCriteria(setOf(2300500L))) },
                ),
                captureAvailable = true,
            )
        }
    }

    @Test fun battleConfigSupportScreenUnmappedNotice() {
        val prefs = prefs()
        val support = SupportPrefsCoreFixture(prefs)
        SupportListLastResult.resetForTesting()
        val vm = SupportViewModel(StorageProvider(prefs, context))
        shoot("BattleConfigSupportUnmapped", 800) {
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

    @Test fun manualFilterScreen() {
        val prefs = prefs()
        prefs.manualSupportFilter.supportListFilter.set(true)
        prefs.manualSupportFilter.preferredServants.set(setOf("Arcueid"))
        SupportListLastResult.resetForTesting()
        val vm = SupportViewModel(StorageProvider(prefs, context))
        shoot("ManualSupportFilter", 1400) {
            ManualSupportFilter(
                prefs, vm,
                filter = SupportListFilterUi(
                    R.string.support_list_filter_summary_manual,
                    build = { SupportFilterBuild.Ready(SupportListCriteria(setOf(2300500L))) },
                    extra = { ImportFromBattleConfig(prefs) },
                ),
                captureAvailable = true,
            )
        }
    }

    @Test fun moreScreenAdditionalFeatures() {
        val prefs = prefs()
        shoot(
            "MoreAdditionalFeatures", 800,
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
