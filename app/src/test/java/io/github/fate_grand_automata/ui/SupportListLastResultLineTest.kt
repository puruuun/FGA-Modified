package io.github.fate_grand_automata.ui

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.SupportListLastResult
import io.github.fate_grand_automata.scripts.capture.SupportListCounts
import io.github.fate_grand_automata.ui.more.SupportListFilterSettings
import io.github.fate_grand_automata.ui.more.SupportListLastResultLine
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.prefs.core.PrefMaker
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "ko-w360dp-h800dp-hdpi")
class SupportListLastResultLineTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun showsNothingBeforeTheFirstList() {
        SupportListLastResult.resetForTesting()
        compose.setContent { FGATheme { SupportListLastResultLine() } }
        compose.onNodeWithText("마지막 목록", substring = true).assertDoesNotExist()
    }

    @Test fun showsTheCountsAfterAFilteredList() {
        SupportListLastResult.resetForTesting()
        SupportListLastResult.filtered(SupportListCounts(20, 12, 498, 15))
        compose.setContent { FGATheme { SupportListLastResultLine() } }
        compose.onNodeWithText("마지막 목록: 20명 중 12명, 서번트 칸 498개 중 15개").assertExists()
        compose.onNodeWithText(context.getString(R.string.capture_support_filter_last, 20, 12, 498, 15)).assertExists()
    }

    @Test fun showsNotChangedAfterAPassThrough() {
        SupportListLastResult.resetForTesting()
        SupportListLastResult.filtered(SupportListCounts(20, 12, 498, 15))
        SupportListLastResult.unchanged()
        compose.setContent { FGATheme { SupportListLastResultLine() } }
        compose.onNodeWithText("마지막 목록: 바꾸지 않음").assertExists()
    }

    private fun prefs(enabled: Boolean): PrefsCore {
        val store = context.getSharedPreferences("support-filter-line", Context.MODE_PRIVATE)
        store.edit().clear().commit()
        return PrefsCore(PrefMaker(store), context).also { it.captureSupportFilterEnabled.set(enabled) }
    }

    private val lastText = "마지막 목록: 20명 중 12명, 서번트 칸 498개 중 15개"

    @Test fun lineIsShownInTheSettingsWhileTheSwitchIsOn() {
        SupportListLastResult.resetForTesting()
        SupportListLastResult.filtered(SupportListCounts(20, 12, 498, 15))
        compose.setContent { FGATheme { SupportListFilterSettings(prefs(true)) } }
        compose.onNodeWithText(lastText).assertExists()
    }

    @Test fun lineIsHiddenWithTheSwitchOffEvenWhenAResultIsHeld() {
        SupportListLastResult.resetForTesting()
        SupportListLastResult.filtered(SupportListCounts(20, 12, 498, 15))
        compose.setContent { FGATheme { SupportListFilterSettings(prefs(false)) } }
        compose.waitForIdle()
        compose.onNodeWithText(lastText).assertDoesNotExist()
        assertNotNull(SupportListLastResult.last.value)
    }

    @Test fun turningTheSwitchOffClearsTheResult() {
        SupportListLastResult.resetForTesting()
        SupportListLastResult.filtered(SupportListCounts(20, 12, 498, 15))
        compose.setContent { FGATheme { SupportListFilterSettings(prefs(true)) } }
        compose.onNodeWithText(lastText).assertExists()
        compose.onNodeWithText(context.getString(R.string.capture_support_filter)).performClick()
        compose.onNodeWithText(lastText).assertDoesNotExist()
        assertNull(SupportListLastResult.last.value)
    }
}
