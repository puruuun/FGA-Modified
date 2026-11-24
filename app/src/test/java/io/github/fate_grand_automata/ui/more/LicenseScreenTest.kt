package io.github.fate_grand_automata.ui.more

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.BuildConfig
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.screenshotRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [32], qualifiers = "w400dp-h800dp")
class LicenseScreenTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules = screenshotRules(composeRule)

    @Test fun `with the notices file the screen offers the entry and shows the notices`() {
        composeRule.setContent {
            FGATheme { LicenseScreenContent("MIT License text") { listOf("Notice one", "tract-core 0.23.10") } }
        }
        composeRule.onAllNodesWithText("Open-source notices").assertCountEquals(1)
        composeRule.onNodeWithText("Open-source notices").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("tract-core 0.23.10").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodesWithText("Notice one").assertCountEquals(1)
        composeRule.onAllNodesWithText("MIT License text").assertCountEquals(0)
    }

    @Test fun `without the notices file the screen has no entry`() {
        composeRule.setContent {
            FGATheme { LicenseScreenContent("MIT License text", null) }
        }
        composeRule.onAllNodesWithText("MIT License text").assertCountEquals(1)
        composeRule.onAllNodesWithText("Open-source notices").assertCountEquals(0)
    }

    @Test fun `every build packages the Android notices and the engine notices exactly with the engine`() {
        val assets = ApplicationProvider.getApplicationContext<Application>().assets
        val expected = if (BuildConfig.NATIVE_READER) NOTICE_ASSETS else listOf(ANDROID_NOTICES_ASSET)
        assertEquals(expected, packagedNoticeAssets(assets))
    }

    @Test fun `the real screen offers the entry and shows the packaged Android notices`() {
        composeRule.setContent { FGATheme { LicenseScreen() } }
        composeRule.onAllNodesWithText("Open-source notices").assertCountEquals(1)
        composeRule.onNodeWithText("Open-source notices").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Third-party notices of this app (libraries and data)").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun `both assets are shown in order and an unreadable one adds nothing`() {
        runBlocking {
            val both = loadNoticeAssets(NOTICE_ASSETS) { if (it == ANDROID_NOTICES_ASSET) "android one\n\nandroid two" else "engine one" }
            assertEquals(listOf("android one", "android two", "engine one"), both)
            val engineOnly = loadNoticeAssets(NOTICE_ASSETS) {
                if (it == ANDROID_NOTICES_ASSET) throw java.io.IOException("unreadable") else "engine one"
            }
            assertEquals(listOf("engine one"), engineOnly)
            assertEquals(emptyList(), loadNoticeAssets(NOTICE_ASSETS) { throw java.io.FileNotFoundException(it) })
        }
    }

    @Test fun `a cancellation while reading an asset is not swallowed`() {
        runBlocking {
            assertFailsWith<CancellationException> {
                loadNoticeAssets(NOTICE_ASSETS) { throw CancellationException("left the screen") }
            }
        }
    }

    @Test fun `a failed load shows no paragraphs and a cancelled one stays cancelled`() {
        runBlocking {
            assertEquals(emptyList(), loadNoticesOrEmpty { throw java.io.IOException("unreadable") })
            assertFailsWith<CancellationException> { loadNoticesOrEmpty { throw CancellationException("left the screen") } }
        }
    }

    @Test fun `paragraphs split at blank lines and keep their lines`() {
        assertEquals(listOf("a\nb", "c", "d"), noticeParagraphs("a\nb\n\nc\n \n\n\nd\n"))
    }
}
