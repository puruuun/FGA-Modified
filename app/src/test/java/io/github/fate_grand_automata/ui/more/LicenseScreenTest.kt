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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

private const val LATCH_TIMEOUT_SECONDS = 10L

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

    private fun awaitOrFail(latch: CountDownLatch, message: String) {
        if (!latch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw AssertionError("Timed out after ${LATCH_TIMEOUT_SECONDS}s: $message")
    }

    /**
     * Leaving the screen cancels the load while an asset is being read. The caller is cancelled from the
     * test thread, as `produceState` does on leaving, and the read is released afterwards.
     */
    private fun cancelWhileReading(names: List<String>, onLoaded: (List<String>) -> Unit): List<String> {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = CopyOnWriteArrayList<String>()
        runBlocking {
            val job = launch(Dispatchers.Default) {
                onLoaded(loadNoticesOrEmpty {
                    loadNoticeAssets(names) {
                        reads += it
                        reading.countDown()
                        awaitOrFail(release, "the test never released the read")
                        "paragraph of $it"
                    }
                })
            }
            awaitOrFail(reading, "no asset was read: the load never started or never called the reader")
            job.cancel()
            release.countDown()
            job.join()
        }
        return reads
    }

    @Test fun `cancelling the caller while an asset is read stops the remaining reads`() {
        val reads = cancelWhileReading(NOTICE_ASSETS) {}
        assertEquals(listOf(NOTICE_ASSETS.first()), reads)
    }

    @Test fun `cancelling the caller while an asset is read delivers no result`() {
        var delivered: List<String>? = null
        cancelWhileReading(listOf(ANDROID_NOTICES_ASSET)) { delivered = it }
        assertEquals(null, delivered)
    }

    @Test fun `a failed load shows no paragraphs`() {
        runBlocking {
            assertEquals(emptyList(), loadNoticesOrEmpty { throw java.io.IOException("unreadable") })
        }
    }

    @Test fun `paragraphs split at blank lines and keep their lines`() {
        assertEquals(listOf("a\nb", "c", "d"), noticeParagraphs("a\nb\n\nc\n \n\n\nd\n"))
    }
}
