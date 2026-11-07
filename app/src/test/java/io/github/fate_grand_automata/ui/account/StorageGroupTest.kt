package io.github.fate_grand_automata.ui.account

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.ui.LocalNavigateBack
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "ko-w360dp-h800dp-hdpi")
class StorageGroupTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val fixture by lazy { DashboardUiFixture() }

    private fun openStorage() {
        compose.onNodeWithText(fixture.context.getString(R.string.p_storage)).performClick()
        compose.waitForIdle()
    }

    @Test fun downloadItemSitsBelowTheExtractionAndOnlyItStartsTheDownload() {
        var extracted = 0
        var downloaded = 0
        compose.setContent { CompositionLocalProvider(LocalNavigateBack provides {}) {
            io.github.fate_grand_automata.ui.FGATheme { fixture.More(extract = { extracted++ }, download = { downloaded++ }) } } }
        openStorage()
        val extract = fixture.context.getString(R.string.support_menu_extract_default_support_images)
        val download = "로그 아이콘 데이터 다운로드"
        compose.onNodeWithText(extract).assertExists()
        val extractY = compose.onNodeWithText(extract).fetchSemanticsNode().positionInRoot.y
        val downloadY = compose.onNodeWithText(download).fetchSemanticsNode().positionInRoot.y
        assert(downloadY > extractY)
        compose.onNodeWithText(extract).performClick()
        assertEquals(1 to 0, extracted to downloaded)
        compose.onNodeWithText(download).performClick()
        assertEquals(1 to 1, extracted to downloaded)
    }

    @Test fun summariesAreIndependentAndDownloadHasNoDescriptionLine() {
        compose.setContent { CompositionLocalProvider(LocalNavigateBack provides {}) {
            io.github.fate_grand_automata.ui.FGATheme {
                fixture.More(extractSummary = "서포트 이미지가 성공적으로 추출되었습니다", downloadSummary = "아이콘 받는 중 3/7") } } }
        openStorage()
        compose.onNodeWithText("서포트 이미지가 성공적으로 추출되었습니다").assertExists()
        compose.onNodeWithText("아이콘 받는 중 3/7").assertExists()
        compose.onAllNodesWithText("Wi-Fi", substring = true).assertCountEquals(0)
    }

    @Test fun storageDownloadInProgress360Light() = compose.shoot("more-storage", "DownloadInProgress", 360, 800, locale = "ko",
        beforeCapture = { openStorage() }) {
        CompositionLocalProvider(LocalNavigateBack provides {}) {
            fixture.More(extractSummary = "서포트 이미지가 성공적으로 추출되었습니다", downloadSummary = "아이콘 받는 중 123/1340")
        }
    }
}
