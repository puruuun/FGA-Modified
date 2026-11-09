package io.github.fate_grand_automata.ui.pref_support

import androidx.compose.ui.test.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.util.portraits.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "ko-w360dp-h800dp-hdpi")
class PickerDownloadTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)

    @Test fun directImportDialogRenders() {
        compose.setContent { FGATheme { Wide {
            io.github.fate_grand_automata.ui.more.ServantPortraitImportDialog(
                io.github.fate_grand_automata.ui.more.PortraitImportState(open = true), "test", {}, {}, {}, {}, {}, {}, {})
        } } }
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
    }
    @Test @Config(qualifiers = "en-w360dp-h800dp-hdpi") fun directEnglishImportDialogRenders() = directImportDialogRenders()
    private fun open(f: PickerDownloadFixture, download: Boolean = true) {
        compose.setContent { FGATheme { Wide { f.Picker(download) } } }
        compose.onNodeWithText(f.title).performClick()
    }
    private fun save(f: PickerDownloadFixture) {
        compose.onNodeWithText("이미지 받기").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("90")
        compose.onNodeWithText(f.context.getString(R.string.portrait_import_search)).performClick()
        compose.onNodeWithText(f.context.getString(R.string.portrait_import_start)).performClick()
        compose.waitForIdle()
    }
    private fun closeImport(f: PickerDownloadFixture) {
        compose.onNodeWithText(f.context.getString(R.string.portrait_import_close)).performClick()
    }
    private fun confirm(f: PickerDownloadFixture) {
        compose.onNodeWithText(f.context.getString(android.R.string.ok).uppercase()).performClick()
    }

    @Test fun servantPreset() = preset(SupportCatalogKind.Servant)
    @Test fun cePreset() = preset(SupportCatalogKind.CE)
    private fun preset(kind: SupportCatalogKind) {
        val f = PickerDownloadFixture(kind)
        open(f)
        compose.onNodeWithText("이미지 받기").performClick()
        compose.runOnIdle { assertEquals(kind, f.controller.portraitImport.value.kind) }
        compose.onNodeWithText(f.context.getString(R.string.portrait_import_title)).assertIsDisplayed()
    }
    @Test fun otherPickerHasNoButton() {
        open(PickerDownloadFixture(), download = false)
        compose.onNodeWithText("이미지 받기").assertDoesNotExist()
    }
    @Test fun servantSaveKeepsPendingTicksUntilOk() = saveAndConfirm(SupportCatalogKind.Servant)
    @Test fun ceSaveKeepsPendingTicksUntilOk() = saveAndConfirm(SupportCatalogKind.CE)
    private fun saveAndConfirm(kind: SupportCatalogKind) {
        val f = PickerDownloadFixture(kind)
        open(f)
        compose.onNodeWithText("추가 선택").performClick()
        save(f)
        closeImport(f)
        compose.onNodeWithText(f.entry.folderName).assertIsDisplayed()
        compose.onAllNodesWithContentDescription("check").assertCountEquals(3)
        compose.runOnIdle { assertEquals(setOf("기존 선택"), f.preference.get()); assertEquals(1, f.reloads) }
        confirm(f)
        compose.runOnIdle { assertEquals(setOf("기존 선택", "추가 선택", f.entry.folderName), f.preference.get()) }
    }
    @Test fun cancelDiscardsPendingTicksButKeepsImages() {
        val f = PickerDownloadFixture()
        open(f)
        compose.onNodeWithText("추가 선택").performClick()
        save(f); closeImport(f)
        compose.onNodeWithText(f.context.getString(android.R.string.cancel).uppercase()).performClick()
        compose.runOnIdle { assertEquals(setOf("기존 선택"), f.preference.get()) }
        compose.onNodeWithText(f.title).performClick()
        compose.onNodeWithText(f.entry.folderName).assertIsDisplayed()
        compose.onAllNodesWithContentDescription("check").assertCountEquals(1)
    }
    @Test fun failedImportDoesNotReloadOrSelect() = failed(PortraitImportError.Network, 1)
    @Test fun zeroSavedDoesNotReloadOrSelect() = failed(null, 0)
    private fun failed(error: PortraitImportError?, saved: Int) {
        val f = PickerDownloadFixture().apply { failure = error; this.saved = saved }
        open(f); save(f); closeImport(f)
        compose.onNodeWithText(f.entry.folderName).assertDoesNotExist()
        confirm(f)
        compose.runOnIdle { assertEquals(0, f.reloads); assertEquals(setOf("기존 선택"), f.preference.get()) }
    }
    @Test fun closedImportKeepsEarlierPendingTicks() {
        val f = PickerDownloadFixture()
        open(f)
        compose.onNodeWithText("추가 선택").performClick()
        compose.onNodeWithText("이미지 받기").performClick(); closeImport(f)
        confirm(f)
        compose.runOnIdle { assertEquals(0, f.saves); assertEquals(0, f.reloads); assertEquals(setOf("기존 선택", "추가 선택"), f.preference.get()) }
    }
    @Test fun missingStorageCannotSave() {
        val f = PickerDownloadFixture(storageReady = false)
        open(f)
        compose.onNodeWithText("이미지 받기").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("90")
        compose.onNodeWithText(f.context.getString(R.string.portrait_import_search)).performClick()
        compose.onNodeWithText(f.context.getString(R.string.portrait_import_start)).assertIsNotEnabled()
        compose.runOnIdle { f.controller.importPortraits(); assertEquals(0, f.saves); assertEquals(0, f.reloads) }
        closeImport(f); confirm(f)
        compose.runOnIdle { assertEquals(setOf("기존 선택"), f.preference.get()) }
    }
    @Test fun additionalToolsStillOpensSharedImportDialog() {
        val f = PickerDownloadFixture()
        compose.setContent { FGATheme { Wide { f.Tools() } } }
        compose.onNodeWithText(f.context.getString(R.string.portrait_import_title)).performClick()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.runOnIdle { assertTrue(f.controller.portraitImport.value.open); assertEquals(SupportCatalogKind.Servant, f.controller.portraitImport.value.kind) }
    }
    @Test fun switchingKindSavesWithoutTickingWrongPicker() {
        val f = PickerDownloadFixture()
        open(f)
        compose.onNodeWithText("이미지 받기").performClick()
        compose.onNodeWithText(f.context.getString(R.string.portrait_import_ce)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("90")
        compose.onNodeWithText(f.context.getString(R.string.portrait_import_search)).performClick()
        compose.onNodeWithText(f.context.getString(R.string.portrait_import_start)).performClick()
        closeImport(f); confirm(f)
        compose.runOnIdle { assertEquals(1, f.reloads); assertEquals(setOf("기존 선택"), f.preference.get()) }
    }
    @Test fun additionalToolsKeepsExistingFolderRenameAndPreferenceMigration() {
        val f = PickerDownloadFixture()
        val old = "No.90 예전 이름"
        java.io.File(f.root, "support/servant/$old").mkdirs()
        f.prefs.battleConfigList.set(setOf("test"))
        f.preference.set(setOf(old))
        val folder = f.storage.prepareSupportPortraitFolder(f.kind.storage, 90, "새 이름")
        assertEquals("No.90 새 이름", folder)
        assertEquals(setOf(folder), f.preference.get())
    }
    @Test fun pickerDownloadReusesExistingFolderWithoutChangingStoredPreferences() {
        val f = PickerDownloadFixture()
        val old = "No.90 예전 이름"
        java.io.File(f.root, "support/servant/$old").mkdirs()
        f.prefs.battleConfigList.set(setOf("test"))
        f.preference.set(setOf(old))
        val folder = f.storage.prepareSupportPortraitFolder(f.kind.storage, 90, "새 이름", updateSelections = false)
        assertEquals(old, folder)
        assertEquals(setOf(old), f.preference.get())
    }
}