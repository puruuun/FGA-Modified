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

    @Test fun extractionSummaryShowsOnlyTheExtractionState() {
        assertEquals("", extractionSummary(context, ExtractionResult.Idle))
        assertEquals(context.getString(R.string.support_imgs_extracting), extractionSummary(context, ExtractionResult.Extracting))
        assertEquals(context.getString(R.string.support_imgs_extracted), extractionSummary(context, ExtractionResult.Success))
        assertEquals(context.getString(R.string.support_imgs_extract_failed), extractionSummary(context, ExtractionResult.Failure))
    }

    @Test fun downloadSummaryIsEmptyWhenIdleAndHasNoDescriptionLine() {
        assertEquals("", iconDownloadSummary(context, null))
        for (progress in listOf(IconPrefetchProgress.LoadingLists, IconPrefetchProgress.Downloading(3, 7),
            IconPrefetchProgress.Ready(7, 0), IconPrefetchProgress.ListsFailed)) {
            assertEquals(1, iconDownloadSummary(context, progress).lines().size)
        }
    }

    @Test fun downloadSummaryShowsProgressAndResult() {
        assertEquals("아이콘 목록 받는 중…", iconDownloadSummary(context, IconPrefetchProgress.LoadingLists))
        assertEquals("아이콘 받는 중 3/7", iconDownloadSummary(context, IconPrefetchProgress.Downloading(3, 7, 1)))
        assertEquals("아이콘 7개 준비됨", iconDownloadSummary(context, IconPrefetchProgress.Ready(7, 0)))
        assertEquals("아이콘 5개 준비됨, 2개 실패", iconDownloadSummary(context, IconPrefetchProgress.Ready(5, 2)))
        assertEquals("아이콘 목록을 받지 못했습니다", iconDownloadSummary(context, IconPrefetchProgress.ListsFailed))
    }

    @Test fun newMessagesHaveEnglishKoreanAndJapaneseResources() {
        val expected = mapOf("en" to "Downloading icons 3/7", "ko" to "아이콘 받는 중 3/7", "ja" to "アイコンをダウンロード中 3/7")
        for ((language, progress) in expected) {
            val config = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(language)) }
            val localized = context.createConfigurationContext(config)
            assertEquals(progress, iconDownloadSummary(localized, IconPrefetchProgress.Downloading(3, 7)))
            for (key in listOf(R.string.support_imgs_extracting, R.string.icon_prefetch_loading_lists, R.string.icon_prefetch_ready,
                R.string.icon_prefetch_failed, R.string.icon_prefetch_lists_failed, R.string.icon_prefetch_title)) {
                assertTrue(localized.getString(key, 3, 7).isNotBlank())
            }
        }
    }
}
