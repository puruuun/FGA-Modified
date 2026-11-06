package io.github.fate_grand_automata.ui.more

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ko")
class SupportExtractionSummaryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun summary(progress: IconPrefetchProgress?, extraction: ExtractionResult = ExtractionResult.Success) =
        supportExtractionSummary(context, SupportExtractionState(extraction, progress))

    @Test fun showsExtractionThenProgressThenStaticDownloadNote() {
        assertEquals(listOf(context.getString(R.string.support_imgs_extracted), "아이콘 받는 중 3/7",
            "첫 다운로드는 약 15 MB입니다. Wi-Fi를 권장합니다."), summary(IconPrefetchProgress.Downloading(3, 7, 1)).lines())
    }

    @Test fun showsReadyCountAndOptionalFailuresWithoutChangingExtractionResult() {
        assertTrue(summary(IconPrefetchProgress.Ready(7, 0)).contains("아이콘 7개 준비됨\n"))
        val failed = summary(IconPrefetchProgress.Ready(5, 2), ExtractionResult.Failure).lines()
        assertEquals(context.getString(R.string.support_imgs_extract_failed), failed[0])
        assertEquals("아이콘 5개 준비됨, 2개 실패", failed[1])
    }

    @Test fun showsListLoadingAndFailureAfterExtractionAndNoteBeforeFirstRun() {
        assertTrue(summary(IconPrefetchProgress.LoadingLists).contains("아이콘 목록 받는 중…"))
        assertEquals("아이콘 목록을 받지 못했습니다", summary(IconPrefetchProgress.ListsFailed).lines()[1])
        assertEquals(context.getString(R.string.icon_prefetch_note), supportExtractionSummary(context, SupportExtractionState()))
        assertEquals(context.getString(R.string.support_imgs_extracting), summary(null, ExtractionResult.Extracting).lines()[0])
    }

    @Test fun allNewMessagesHaveEnglishKoreanAndJapaneseResources() {
        val expected = mapOf("en" to "Downloading icons 3/7", "ko" to "아이콘 받는 중 3/7", "ja" to "アイコンをダウンロード中 3/7")
        for ((language, progress) in expected) {
            val config = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(language)) }
            val localized = context.createConfigurationContext(config)
            assertEquals(progress, supportExtractionSummary(localized,
                SupportExtractionState(ExtractionResult.Success, IconPrefetchProgress.Downloading(3, 7))).lines()[1])
            for (key in listOf(R.string.support_imgs_extracting, R.string.icon_prefetch_loading_lists, R.string.icon_prefetch_ready,
                R.string.icon_prefetch_failed, R.string.icon_prefetch_lists_failed, R.string.icon_prefetch_note)) {
                assertTrue(localized.getString(key, 3, 7).isNotBlank())
            }
        }
    }
}
