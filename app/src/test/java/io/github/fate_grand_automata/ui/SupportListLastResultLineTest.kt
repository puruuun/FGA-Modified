package io.github.fate_grand_automata.ui

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.SupportListLastResult
import io.github.fate_grand_automata.scripts.capture.SupportListCounts
import io.github.fate_grand_automata.ui.more.SupportListLastResultLine
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
}
