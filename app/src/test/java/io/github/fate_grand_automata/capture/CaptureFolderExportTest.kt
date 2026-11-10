package io.github.fate_grand_automata.capture

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.more.GainsTrackerContent
import io.github.fate_grand_automata.ui.more.GainsTrackerUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.LocalDateTime

/** In-memory capture folder whose failures can be switched on one by one. */
private class FakeFolder : CaptureFolder {
    override val label = "FGA/capture"
    val files = linkedMapOf<String, ByteArray>()
    var stuck = setOf<String>()
    var refuseCreate = false
    var renameOnCreate: ((String) -> String)? = null
    var failWrite = false
    override fun names() = files.keys.toList()
    override fun delete(name: String): Boolean {
        if (name in stuck) return false
        files.remove(name)
        return true
    }
    override fun create(name: String, mime: String): String? {
        if (refuseCreate) return null
        val actual = renameOnCreate?.invoke(name) ?: name
        files[actual] = ByteArray(0)
        return actual
    }
    override fun open(name: String): OutputStream? {
        if (failWrite) return null
        return object : ByteArrayOutputStream() {
            override fun close() { super.close(); files[name] = toByteArray() }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ko-w360dp-h800dp-hdpi")
class CaptureFolderExportTest {
    private val compose = createComposeRule()
    @get:Rule val rules = io.github.fate_grand_automata.ui.screenshotRules(compose)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val now = LocalDateTime.of(2026, 11, 11, 2, 34, 5)

    private fun leaf() = File(CertStore.directory(context), "leaf.p12")
    private fun clear() { leaf().delete(); File(CertStore.directory(context), "ca.cer").delete() }

    // ---- layers of the save ----

    @Test fun fixedNameIsReplacedSoTwoExportsLeaveOneFile() {
        val folder = FakeFolder()
        val first = CaptureFileSaver.save(folder, "a.crt", "a-1.crt", "x/y", byteArrayOf(1))
        val second = CaptureFileSaver.save(folder, "a.crt", "a-1.crt", "x/y", byteArrayOf(2, 2))
        assertEquals(SaveOutcome.Saved("a.crt", "FGA/capture", false), first)
        assertEquals(SaveOutcome.Saved("a.crt", "FGA/capture", false), second)
        assertEquals(listOf("a.crt"), folder.names())
        assertArrayEquals(byteArrayOf(2, 2), folder.files["a.crt"])
    }

    @Test fun realDocumentFolderKeepsOneFileAfterTwoExports() {
        val root = kotlin.io.path.createTempDirectory("capture").toFile()
        try {
            val dir = DocumentFile.fromFile(root)
            val folder = DocumentCaptureFolder(dir, { FileOutputStream(File(it.uri.path!!)) }, "FGA")
            val der = byteArrayOf(9, 8, 7)
            // Raw file documents append an extension for a known MIME type, so use an unmapped one here.
            val a = CaptureFileSaver.save(folder, "fga-capture-ca.crt", "fga-capture-ca-1.crt", "x/y", der)
            val b = CaptureFileSaver.save(folder, "fga-capture-ca.crt", "fga-capture-ca-1.crt", "x/y", der)
            assertTrue(a is SaveOutcome.Saved && b is SaveOutcome.Saved)
            assertEquals(listOf("fga-capture-ca.crt"), root.list()!!.toList())
            assertArrayEquals(der, File(root, "fga-capture-ca.crt").readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun fallsBackToTimestampedNameWhenFixedNameCannotBeReplaced() {
        val folder = FakeFolder().apply { files["fga-capture-ca.crt"] = byteArrayOf(0); stuck = setOf("fga-capture-ca.crt") }
        val outcome = CertStore.save(folder, byteArrayOf(5), now)
        assertEquals(SaveOutcome.Saved("fga-capture-ca-20261111-0234.crt", "FGA/capture", true), outcome)
        assertArrayEquals(byteArrayOf(5), folder.files["fga-capture-ca-20261111-0234.crt"])
    }

    @Test fun providerRenameIsDetectedAndNotLeftBehind() {
        val folder = FakeFolder().apply { renameOnCreate = { if (it.endsWith(".crt")) "$it.crt" else it } }
        val outcome = CaptureFileSaver.save(folder, "a.crt", "a-1.crt", "x/y", byteArrayOf(1))
        assertTrue(outcome is SaveOutcome.NeedsDialog)
        assertTrue(folder.names().isEmpty())
    }

    @Test fun refusalOnBothNamesOpensTheSaveDialog() {
        val folder = FakeFolder().apply { refuseCreate = true }
        val outcome = CertStore.save(folder, byteArrayOf(1, 2), now)
        assertTrue(outcome is SaveOutcome.NeedsDialog)
        outcome as SaveOutcome.NeedsDialog
        assertEquals("fga-capture-ca.crt", outcome.name)
        assertArrayEquals(byteArrayOf(1, 2), outcome.bytes)
    }

    @Test fun failedWriteLeavesNoPartialFile() {
        val folder = FakeFolder().apply { failWrite = true }
        assertTrue(CertStore.save(folder, byteArrayOf(1), now) is SaveOutcome.NeedsDialog)
        assertTrue(folder.names().isEmpty())
    }

    @Test fun unsetOrUnusableFolderOpensTheSaveDialog() {
        assertTrue(CertStore.save(null, byteArrayOf(1), now) is SaveOutcome.NeedsDialog)
        // No folder was picked in onboarding in a fresh test app.
        assertNull(CaptureFolders.open(context))
        assertNull(CaptureFolders.open(context, null))
    }

    @Test fun timestampedKeepsExtension() {
        assertEquals("fga-capture-ca-20261111-0234.crt", CaptureFileSaver.timestamped("fga-capture-ca.crt", now))
    }

    // ---- certificate lifecycle ----

    @Test fun reExportKeepsTheSameCaBytes() {
        clear()
        val folder = FakeFolder()
        val (_, created) = CertStore.createAndExport(context, folder)
        assertTrue(created is SaveOutcome.Saved)
        val ca = CertStore.caBytes(context)!!
        val leafBytes = leaf().readBytes()
        val again = CertStore.export(context, folder)
        assertTrue(again is SaveOutcome.Saved)
        assertArrayEquals(ca, CertStore.caBytes(context))
        assertArrayEquals(leafBytes, leaf().readBytes())
        assertArrayEquals(ca, folder.files["fga-capture-ca.crt"])
        assertEquals(1, folder.names().size)
    }

    @Test fun reExportWithoutCaDoesNothing() {
        clear()
        assertNull(CertStore.export(context, FakeFolder()))
    }

    @Test fun failedExportLeavesOldCaAndLeafUnchanged() {
        clear()
        CertStore.createAndExport(context, FakeFolder())
        val ca = CertStore.caBytes(context)!!
        val leafBytes = leaf().readBytes()
        val (pending, outcome) = CertStore.createAndExport(context, FakeFolder().apply { refuseCreate = true })
        assertTrue(outcome is SaveOutcome.NeedsDialog)
        assertNotNull(pending)
        assertArrayEquals(ca, CertStore.caBytes(context))
        assertArrayEquals(leafBytes, leaf().readBytes())
        // Cancelling the dialog simply drops the pending pair; saving it commits the new pair.
        CertStore.commit(context, pending)
        assertFalse(ca.contentEquals(CertStore.caBytes(context)))
        assertArrayEquals(pending.caDer, CertStore.caBytes(context))
    }

    @Test fun successfulCreateCommitsNewPairAfterTheExport() {
        clear()
        val folder = FakeFolder()
        val (pending, outcome) = CertStore.createAndExport(context, folder)
        assertTrue(outcome is SaveOutcome.Saved)
        assertArrayEquals(pending.caDer, CertStore.caBytes(context))
        assertArrayEquals(pending.caDer, folder.files["fga-capture-ca.crt"])
        assertTrue(CertStore.exists(context))
    }

    // ---- Chaldea export ----

    @Test fun chaldeaExportLandsInCaptureFolderAndKeepsNewestPerAccount() {
        val folder = FakeFolder()
        folder.files["chaldea-account-7001-20261111-0100.json"] = byteArrayOf(1)
        folder.files["chaldea-account-7002-20261111-0100.json"] = byteArrayOf(1)
        folder.files["notes.txt"] = byteArrayOf(1)
        val result = ChaldeaExportStore.saveExport(folder, "7001", byteArrayOf(7), now)
        assertEquals(ChaldeaExportStore.Result.Written("chaldea-account-7001-20261111-0234.json", "FGA/capture"), result)
        assertEquals(
            setOf("chaldea-account-7001-20261111-0234.json", "chaldea-account-7002-20261111-0100.json", "notes.txt"),
            folder.names().toSet(),
        )
    }

    @Test fun chaldeaSameMinuteExportReplacesTheFile() {
        val folder = FakeFolder()
        ChaldeaExportStore.saveExport(folder, "7001", byteArrayOf(1), now)
        ChaldeaExportStore.saveExport(folder, "7001", byteArrayOf(2), now)
        assertEquals(1, folder.names().size)
        assertArrayEquals(byteArrayOf(2), folder.files.values.single())
    }

    @Test fun chaldeaFallsBackToSecondStampedNameThenDialog() {
        val blocked = FakeFolder().apply {
            files["chaldea-account-7001-20261111-0234.json"] = byteArrayOf(0)
            stuck = setOf("chaldea-account-7001-20261111-0234.json")
        }
        val result = ChaldeaExportStore.saveExport(blocked, "7001", byteArrayOf(1), now)
        // The fixed file cannot be replaced; the second-stamped file is written, then the older one cannot be
        // cleaned up, so the export is undone rather than leaving two account files.
        assertEquals(ChaldeaExportStore.Result.Failed, result)
        assertEquals(listOf("chaldea-account-7001-20261111-0234.json"), blocked.names())

        val none = ChaldeaExportStore.saveExport(null, "7001", byteArrayOf(1), now)
        assertTrue(none is ChaldeaExportStore.Result.NeedsSaveDialog)
        assertEquals("chaldea-account-7001-20261111-0234.json", (none as ChaldeaExportStore.Result.NeedsSaveDialog).name)
    }

    @Test fun deleteDataRemovesOnlyOwnExportsFromCaptureFolder() = runBlocking {
        val folder = FakeFolder()
        folder.files["chaldea-account-7001-20261111-0100.json"] = byteArrayOf(1)
        folder.files["fga-capture-ca.crt"] = byteArrayOf(1)
        assertTrue(ChaldeaExportStore.deleteTrackerData(context, folder))
        assertEquals(listOf("fga-capture-ca.crt"), folder.names())
        folder.files["chaldea-account-7002-20261111-0100.json"] = byteArrayOf(1)
        folder.files["chaldea-account-7003-20261111-0100.json"] = byteArrayOf(1)
        assertTrue(ChaldeaExportStore.deleteAccount(context, "7002", folder))
        assertEquals(setOf("fga-capture-ca.crt", "chaldea-account-7003-20261111-0100.json"), folder.names().toSet())
    }

    // ---- certificate section ----

    private fun content(state: GainsTrackerUiState, onNew: () -> Unit, onExport: () -> Unit) = compose.setContent {
        FGATheme {
            GainsTrackerContent(
                state = state, onEnabled = {}, onStopWithService = {}, onCreateCertificate = onNew,
                onOpenSecuritySettings = {}, onStart = {}, onStop = {}, onDelete = {}, onExportCertificate = onExport,
            )
        }
    }

    @Test fun existingCaButtonReExportsAndNeverCreates() {
        var created = 0; var exported = 0
        content(GainsTrackerUiState(certExists = true), { created++ }, { exported++ })
        compose.onNodeWithText(context.getString(R.string.capture_cert_export)).performClick()
        assertEquals(0, created)
        assertEquals(1, exported)
    }

    @Test fun newCaNeedsConfirmationWithReinstallWarning() {
        var created = 0
        content(GainsTrackerUiState(certExists = true), { created++ }, {})
        compose.onNodeWithText(context.getString(R.string.capture_cert_create_new)).performClick()
        assertEquals(0, created)
        compose.onNodeWithText(context.getString(R.string.capture_cert_recreate_message)).assertExists()
        compose.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        assertEquals(0, created)
        compose.onNodeWithText(context.getString(R.string.capture_cert_create_new)).performClick()
        compose.onAllNodesWithText(context.getString(R.string.capture_cert_create_new)).assertCountEquals(2)
        compose.onAllNodesWithText(context.getString(R.string.capture_cert_create_new))[1].performClick()
        assertEquals(1, created)
    }

    @Test fun missingCaCreatesDirectly() {
        var created = 0
        content(GainsTrackerUiState(certExists = false), { created++ }, {})
        compose.onNodeWithText(context.getString(R.string.capture_cert_create)).performClick()
        assertEquals(1, created)
    }

    @Test fun savedPathIsShown() {
        content(GainsTrackerUiState(certExists = true, certSavedPath = "FGA/capture/fga-capture-ca.crt"), {}, {})
        compose.onNodeWithText("FGA/capture/fga-capture-ca.crt", substring = true).assertExists()
    }
}
