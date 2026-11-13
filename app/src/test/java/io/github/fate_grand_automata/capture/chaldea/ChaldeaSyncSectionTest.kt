package io.github.fate_grand_automata.capture.chaldea

import android.content.Context
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaAccess
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaServer
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncNowOutcome
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncStatus
import io.github.fate_grand_automata.scripts.capture.chaldea.RootState
import io.github.fate_grand_automata.scripts.capture.chaldea.ShizukuState
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.more.ChaldeaSyncUi
import io.github.fate_grand_automata.ui.more.chaldeaSyncItems
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "en-w360dp-h1000dp-hdpi")
class ChaldeaSyncSectionTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun render(ui: ChaldeaSyncUi, onSyncNow: () -> Unit = {}) {
        compose.setContent {
            FGATheme {
                LazyColumn {
                    chaldeaSyncItems(
                        ui = ui,
                        onEnabled = {},
                        onCloseMinutes = {},
                        syncNow = {
                            onSyncNow()
                            ChaldeaSyncNowOutcome.Done
                        },
                    )
                }
            }
        }
    }

    private fun syncNowButton() = compose.onNodeWithText(context.getString(R.string.chaldea_sync_now))

    // ---- F6: sync now enabled when root is not yet Granted ----

    @Test
    fun syncNowIsEnabledWhenRootIsNotGrantedAndShizukuIsMissing() {
        var clicks = 0
        render(
            ChaldeaSyncUi(
                enabled = true,
                loggingOn = true,
                access = ChaldeaAccess(RootState.Unavailable, ShizukuState.NotInstalled),
            ),
            onSyncNow = { clicks++ },
        )
        syncNowButton().assertIsEnabled()
        syncNowButton().performClick()
        compose.waitForIdle()
        assertEquals(1, clicks)
    }

    @Test
    fun syncNowIsEnabledWhenRootIsUncheckedAndShizukuIsNotRunning() {
        render(
            ChaldeaSyncUi(
                enabled = true,
                loggingOn = true,
                access = ChaldeaAccess(RootState.Unchecked, ShizukuState.NotRunning),
            ),
        )
        syncNowButton().assertIsEnabled()
    }

    @Test
    fun syncNowRemainsDisabledWhileScriptRunsOrOffOrWithoutLogging() {
        render(
            ChaldeaSyncUi(
                enabled = true,
                loggingOn = true,
                access = ChaldeaAccess(RootState.Unavailable, ShizukuState.NotInstalled),
                scriptRunning = true,
            ),
        )
        syncNowButton().assertIsNotEnabled()
    }

    @Test
    fun syncNowRemainsDisabledWhenOffOrLoggingOff() {
        render(
            ChaldeaSyncUi(
                enabled = false,
                loggingOn = true,
                access = ChaldeaAccess(RootState.Unavailable, ShizukuState.NotInstalled),
            ),
        )
        syncNowButton().assertIsNotEnabled()
    }

    @Test
    fun syncNowRemainsDisabledWithoutLogging() {
        render(
            ChaldeaSyncUi(
                enabled = true,
                loggingOn = false,
                access = ChaldeaAccess(RootState.Unavailable, ShizukuState.NotInstalled),
            ),
        )
        syncNowButton().assertIsNotEnabled()
    }

    // ---- F4: cannot_bind wording is mode-neutral across en, ko, ja ----

    @Test
    fun cannotBindTextIsNeutralInEnglish() {
        val text = context.getString(R.string.chaldea_sync_status_cannot_bind)
        assertEquals("Cannot reach the Chaldea sync service. Will try again.", text)
        assertFalse(text.contains("Shizuku", ignoreCase = true))
    }

    @Test
    @Config(qualifiers = "ko")
    fun cannotBindTextIsNeutralInKorean() {
        val text = context.getString(R.string.chaldea_sync_status_cannot_bind)
        assertEquals("Chaldea 동기화 서비스에 연결할 수 없습니다. 다시 시도합니다.", text)
        assertFalse(text.contains("Shizuku", ignoreCase = true))
    }

    @Test
    @Config(qualifiers = "ja")
    fun cannotBindTextIsNeutralInJapanese() {
        val text = context.getString(R.string.chaldea_sync_status_cannot_bind)
        assertEquals("Chaldea同期サービスに接続できません。再試行します。", text)
        assertFalse(text.contains("Shizuku", ignoreCase = true))
    }

    @Test
    fun syncedStatusShowsStage2CountsAndUnmappedOnlyWhenPositive() {
        val synced = ChaldeaSyncStatus.Synced(
            timeMs = 1_700_000_000_000L,
            items = 10,
            mysticCodes = 3,
            boards = 2,
            coins = 4,
            craftEssences = 5,
            commandCodes = 6,
            unmapped = 0,
            created = false,
            regionMismatch = false,
            packageName = null,
            bothPackages = false,
        )
        render(
            ChaldeaSyncUi(
                enabled = true,
                loggingOn = true,
                access = ChaldeaAccess(RootState.Granted, ShizukuState.NotRunning),
                results = mapOf(ChaldeaServer.Kr to synced),
            ),
        )
        compose.onNodeWithText("4 servant coins, 5 craft essences, 6 command codes.", substring = true).assertExists()
        compose.onNodeWithText("collection number is not known yet", substring = true).assertDoesNotExist()
    }

    @Test
    fun syncedStatusAppendsUnmappedMessageWhenUnmappedPositive() {
        val synced = ChaldeaSyncStatus.Synced(
            timeMs = 1_700_000_000_000L,
            items = 1,
            mysticCodes = 2,
            boards = 3,
            coins = 4,
            craftEssences = 5,
            commandCodes = 6,
            unmapped = 7,
            created = false,
            regionMismatch = false,
            packageName = null,
            bothPackages = false,
        )
        render(
            ChaldeaSyncUi(
                enabled = true,
                loggingOn = true,
                access = ChaldeaAccess(RootState.Granted, ShizukuState.NotRunning),
                results = mapOf(ChaldeaServer.Kr to synced),
            ),
        )
        val unmappedText = context.getString(R.string.chaldea_sync_status_unmapped, 7)
        assertEquals("7 command codes skipped because their collection number is not known yet (retried on the next sync).", unmappedText)
        compose.onNodeWithText(unmappedText, substring = true).assertExists()
    }

    @Test
    @Config(qualifiers = "ko")
    fun syncedStatusFormatsKoreanCountsAndUnmapped() {
        val formatted = context.getString(R.string.chaldea_sync_status_synced, "T", 1, 2, 3, 4, 5, 6)
        assertEquals("마지막 반영 T: 아이템 1개, 마술예장 2개, 클래스 보드 3개, 서번트 코인 4개, 개념예장 5개, 커맨드 코드 6개.", formatted)
        assertEquals(
            "도감 번호를 몰라 건너뛴 커맨드 코드 8개 (다음 반영 때 다시 시도합니다).",
            context.getString(R.string.chaldea_sync_status_unmapped, 8),
        )
    }

    @Test
    @Config(qualifiers = "ja")
    fun syncedStatusFormatsJapaneseCountsAndUnmappedPreservingStyle() {
        val formatted = context.getString(R.string.chaldea_sync_status_synced, "T", 1, 2, 3, 4, 5, 6)
        assertEquals("最終反映 T: アイテム1件、魔術礼装2件、クラスボード3件、サーヴァントコイン4件、概念礼装5件、コマンドコード6件。", formatted)
        assertEquals(
            "図鑑番号が不明なためコマンドコード8件を飛ばしました（次回の反映で再試行します）。",
            context.getString(R.string.chaldea_sync_status_unmapped, 8),
        )
    }
}
