package io.github.fate_grand_automata.capture

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.ContentResolver
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.ui.more.CertSaveRequest
import io.github.fate_grand_automata.ui.more.GainsTrackerViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException

/** Synthetic provider: no game captures, external files, accounts or devices. */
class ExportTestProvider : DocumentsProvider() {
    val files = linkedMapOf<String, File>()
    var stuck = setOf<String>()
    var pathAvailable = true
    var accessible = true
    var missingQueryThrows = false
    fun uri(id: String) = DocumentsContract.buildDocumentUriUsingTree(
        DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root"), id,
    )
    fun add(id: String): Uri {
        files[id] = File(context!!.cacheDir, "synthetic-$id").apply { writeText("old-$id") }
        return uri(id)
    }
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?) = MatrixCursor(arrayOf("root_id"))
    override fun queryDocument(documentId: String, projection: Array<out String>?): MatrixCursor {
        if (!accessible) throw SecurityException("revoked")
        if (missingQueryThrows && documentId !in files) throw FileNotFoundException(documentId)
        val columns = projection ?: arrayOf("document_id", "_display_name", "mime_type", "flags")
        return MatrixCursor(columns).apply {
            if (documentId in files || documentId in listOf("root", "parent")) addRow(columns.map {
                when (it) {
                    "document_id" -> documentId
                    "_display_name" -> when (documentId) { "root" -> "Chosen tree"; "parent" -> "Exports"; else -> "$documentId.json" }
                    "mime_type" -> if (documentId in listOf("root", "parent")) DocumentsContract.Document.MIME_TYPE_DIR else "application/json"
                    "flags" -> DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                    else -> null
                }
            })
        }
    }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?) =
        MatrixCursor(projection ?: arrayOf("document_id"))
    override fun isChildDocument(parentDocumentId: String, documentId: String) = true
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(files.getValue(documentId), ParcelFileDescriptor.parseMode(mode))
    override fun deleteDocument(documentId: String) {
        if (documentId in stuck || !accessible) throw FileNotFoundException("permission gone")
        if (documentId !in files) throw FileNotFoundException(documentId)
        check(files.remove(documentId)?.delete() != false)
    }
    override fun findDocumentPath(parentDocumentId: String?, childDocumentId: String): DocumentsContract.Path {
        if (!pathAvailable) throw UnsupportedOperationException()
        return DocumentsContract.Path(if (parentDocumentId == null) "root" else null, listOf("root", "parent", childDocumentId))
    }
    companion object { const val AUTHORITY = "capture.export.test" }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ko")
class CaptureExportRecoveryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var provider: ExportTestProvider
    private fun prefs() = PrefsCore(PrefMaker(context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)), context)

    @Before fun setup() {
        CertificateSaveGuard.finish()
        CertificateSaveGuard.endVpn()
        CaptureTracker.setRunning(false)
        context.getSharedPreferences("capture-picked-exports", Context.MODE_PRIVATE).edit().clear().commit()
        prefs().dirRoot.set("")
        provider = Robolectric.buildContentProvider(ExportTestProvider::class.java).create(ProviderInfo().apply {
                authority = ExportTestProvider.AUTHORITY
                exported = true
                grantUriPermissions = true
                readPermission = Manifest.permission.MANAGE_DOCUMENTS
                writePermission = Manifest.permission.MANAGE_DOCUMENTS
            }).get()
        ShadowContentResolver.registerProviderInternal(ExportTestProvider.AUTHORITY, provider)
        CaptureTracker.init(context)
        CaptureTracker.delete()
    }

    @After fun cleanup() {
        CertificateSaveGuard.finish()
        CertificateSaveGuard.endVpn()
        CaptureTracker.setRunning(false)
        CaptureTracker.delete()
    }

    @Test fun newCertificateAndVpnStartupAreMutuallyExclusiveAndBothSavesRejectDoubleTaps() {
        assertFalse(CertificateSaveGuard.begin(true, running = true))
        assertTrue(CertificateSaveGuard.beginVpn())
        assertFalse(CertificateSaveGuard.begin(true, running = false))
        assertTrue(CertificateSaveGuard.begin(false, running = true))
        assertFalse(CertificateSaveGuard.begin(false, running = true))
        assertFalse(CertificateSaveGuard.begin(true, running = true))
        CertificateSaveGuard.finish()
        CertificateSaveGuard.endVpn()
        assertTrue(CertificateSaveGuard.begin(true, running = false))
        assertFalse(CertificateSaveGuard.begin(false, running = false))
        assertFalse(CertificateSaveGuard.begin(true, running = false))
        assertFalse(CertificateSaveGuard.beginVpn())
    }

    private fun valid(leaf: File, ca: File) = leaf.isFile && ca.isFile && leaf.readText() == ca.readText()

    @Test fun failureBetweenTemporaryWritesOrRenamesLeavesTheOldPair() {
        for (failAt in listOf("ca.cer.tmp", "rename-ca")) {
            val dir = kotlin.io.path.createTempDirectory("cert-transaction").toFile()
            try {
                CertificatePairFiles(dir, ::valid).commit("old".toByteArray(), "old".toByteArray())
                var failed = false
                val pair = CertificatePairFiles(dir, ::valid, write = { file, bytes ->
                    if (!failed && file.name == failAt) { failed = true; error("injected write failure") }
                    file.writeBytes(bytes)
                }, rename = { from, to ->
                    if (!failed && failAt == "rename-ca" && to.name == "ca.cer") { failed = true; error("injected rename failure") }
                    java.nio.file.Files.move(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    Unit
                })
                assertThrows(IllegalStateException::class.java) { pair.commit("new".toByteArray(), "new".toByteArray()) }
                assertTrue(failed)
                assertEquals("old", File(dir, "leaf.p12").readText())
                assertEquals("old", File(dir, "ca.cer").readText())
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun leftoverTemporaryFileAndMixedPairRestoreLastCompletePairOnLoad() {
        val dir = kotlin.io.path.createTempDirectory("cert-recovery").toFile()
        try {
            File(dir, "leaf.p12.bak").writeText("old")
            File(dir, "ca.cer.bak").writeText("old")
            File(dir, "leaf.p12").writeText("new")
            File(dir, "ca.cer").writeText("old")
            File(dir, "ca.cer.tmp").writeText("new")
            assertTrue(CertificatePairFiles(dir, ::valid).recover())
            assertEquals("old", File(dir, "leaf.p12").readText())
            assertFalse(File(dir, "ca.cer.tmp").exists())
            File(dir, "leaf.p12").writeText("mixed")
            assertTrue(CertificatePairFiles(dir, ::valid).recover())
            assertEquals("old", File(dir, "leaf.p12").readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun unmatchedCertificateIsNeverExposedWithoutACompleteBackup() {
        val dir = CertStore.directory(context)
        val one = CertStore.prepare()
        val two = CertStore.prepare()
        listOf("leaf.p12.bak", "ca.cer.bak", "leaf.p12.tmp", "ca.cer.tmp").forEach { File(dir, it).delete() }
        File(dir, "leaf.p12").writeBytes(one.leaf)
        File(dir, "ca.cer").writeBytes(two.caDer)
        assertFalse(CertStore.exists(context))
        assertNull(CertStore.caBytes(context))
        assertThrows(IllegalStateException::class.java) { CertStore.serverContext(context) }
    }

    @Test fun failedRollbackIsSurfacedWithOriginalFailure() {
        val dir = kotlin.io.path.createTempDirectory("cert-rollback").toFile()
        try {
            CertificatePairFiles(dir, ::valid).commit("old".toByteArray(), "old".toByteArray())
            val pair = CertificatePairFiles(dir, ::valid, rename = { _, _ -> error("cannot rename") })
            val failure = assertThrows(IllegalStateException::class.java) { pair.commit("new".toByteArray(), "new".toByteArray()) }
            assertEquals("Certificate rollback failed", failure.message)
            assertEquals("cannot rename", failure.cause?.message)
            assertEquals(1, failure.suppressed.size)
        } finally { dir.deleteRecursively() }
    }

    private fun request(id: String) = ChaldeaExportStore.Result.NeedsSaveDialog("requested.json", "new-$id".toByteArray(), id)

    private fun tracked(id: String) = context.getSharedPreferences("capture-picked-exports", Context.MODE_PRIVATE)
        .getStringSet(id, emptySet()).orEmpty().toSet()

    private fun removeOutsideFga(uri: Uri) {
        assertTrue(provider.files.remove(DocumentsContract.getDocumentId(uri))!!.delete())
    }

    private fun snapshot() {
        CaptureTracker.onGameResponse("https://yfgo-game.netmarble.com/login/top", """{"response":[{"nid":"login","resCode":"00","success":{}}],"cache":{"replaced":{"userGame":[{"userId":7001,"name":"Synthetic"}]}}}""".toByteArray(), null)
        assertNotNull(CaptureTracker.snapshot("7001"))
    }

    @Test fun externallyDeletedPickedFileDoesNotWarnOnNextFolderExport() = runBlocking {
        snapshot()
        val old = provider.add("old")
        ChaldeaExportStore.picked(context, old, request("7001"))
        removeOutsideFga(old)
        val saved = ChaldeaExportStore.export(context, "7001", providerFolder()) as ChaldeaExportStore.Result.Written
        assertFalse(saved.cleanupFailed)
        assertTrue(tracked("7001").isEmpty())
    }

    @Test fun externallyDeletedPickedFileDoesNotWarnOnNextPickedExport() = runBlocking {
        val old = provider.add("old")
        ChaldeaExportStore.picked(context, old, request("7001"))
        removeOutsideFga(old)
        provider.missingQueryThrows = true
        val latest = provider.add("latest")
        val saved = ChaldeaExportStore.picked(context, latest, request("7001")) as ChaldeaExportStore.Result.Written
        assertFalse(saved.cleanupFailed)
        assertEquals(setOf(latest.toString()), tracked("7001"))
    }

    @Test fun externallyDeletedPickedFileDoesNotFailAccountDeletion() = runBlocking {
        val old = provider.add("old")
        ChaldeaExportStore.picked(context, old, request("7001"))
        val other = provider.add("other")
        ChaldeaExportStore.picked(context, other, request("7002"))
        removeOutsideFga(old)
        assertTrue(ChaldeaExportStore.deleteAccount(context, "7001"))
        assertTrue(tracked("7001").isEmpty())
        assertEquals(setOf(other.toString()), tracked("7002"))
        assertTrue(provider.files.getValue("other").exists())
    }

    @Test fun externallyDeletedPickedFilesDoNotFailDeleteAll() = runBlocking {
        for (id in listOf("7001", "7002")) {
            val uri = provider.add(id)
            ChaldeaExportStore.picked(context, uri, request(id))
            removeOutsideFga(uri)
        }
        provider.missingQueryThrows = true
        assertTrue(ChaldeaExportStore.deleteTrackerData(context))
        assertTrue(tracked("7001").isEmpty())
        assertTrue(tracked("7002").isEmpty())
    }

    @Test fun falseDeleteResultForMissingDocumentClearsTracking() {
        val uri = provider.add("old")
        PickedChaldeaFiles(context).remember("7001", uri)
        removeOutsideFga(uri)
        assertTrue(PickedChaldeaFiles(context, remove = { false }).delete("7001"))
        assertTrue(tracked("7001").isEmpty())
    }

    @Test fun falseDeleteResultForExistingDocumentKeepsTracking() {
        val uri = provider.add("old")
        PickedChaldeaFiles(context).remember("7001", uri)
        assertFalse(PickedChaldeaFiles(context, remove = { false }).delete("7001"))
        assertEquals(setOf(uri.toString()), tracked("7001"))
        assertTrue(provider.files.getValue("old").exists())
    }

    @Test fun revokedPickedPermissionKeepsTrackingAndReportsBothDeleteActions() = runBlocking {
        val uri = provider.add("old")
        PickedChaldeaFiles(context).remember("7001", uri)
        provider.accessible = false
        assertFalse(ChaldeaExportStore.deleteAccount(context, "7001"))
        assertFalse(ChaldeaExportStore.deleteTrackerData(context))
        assertEquals(setOf(uri.toString()), tracked("7001"))
        assertTrue(provider.files.getValue("old").exists())
    }

    @Test fun refusedPersistablePermissionStillSavesAndTracksPickedExport() = runBlocking {
        val uri = provider.add("latest")
        val resolver = object : ContentResolver(context) {
            var attempts = 0
            override fun takePersistableUriPermission(uri: Uri, modeFlags: Int) {
                attempts++
                throw SecurityException("not persistable")
            }
        }
        val noPersist = object : ContextWrapper(context) {
            override fun getContentResolver() = resolver
        }
        shadowOf(resolver).registerOutputStream(uri, provider.files.getValue("latest").outputStream())
        val saved = ChaldeaExportStore.picked(noPersist, uri, request("7001")) as ChaldeaExportStore.Result.Written
        assertFalse(saved.cleanupFailed)
        assertEquals(3, resolver.attempts)
        assertEquals("new-7001", provider.files.getValue("latest").readText())
        assertEquals(setOf(uri.toString()), tracked("7001"))
    }

    private fun providerFolder() = object : CaptureFolder {
        override val label = "Chosen tree/capture"
        override fun names() = provider.files.keys.map { "$it.json" }
        override fun documentUri(name: String) = DocumentsContract.buildDocumentUri(ExportTestProvider.AUTHORITY, name.removeSuffix(".json"))
        override fun create(name: String, mime: String): String {
            provider.add(name.removeSuffix(".json"))
            return name
        }
        override fun open(name: String) = provider.files.getValue(name.removeSuffix(".json")).outputStream()
        override fun delete(name: String): Boolean { provider.deleteDocument(name.removeSuffix(".json")); return true }
        override fun rename(from: String, to: String): Boolean {
            val file = provider.files.remove(from.removeSuffix(".json")) ?: return false
            provider.files[to.removeSuffix(".json")] = file
            return true
        }
    }

    @Test fun pickedFileInsideCaptureFolderIsKeptWhenOlderFolderExportsAreRemoved() = runBlocking {
        provider.add("chaldea-account-7001-20261110-0100")
        val newest = provider.add("chaldea-account-7001-20261111-0100")
        val result = ChaldeaExportStore.picked(context, newest, request("7001"), providerFolder()) as ChaldeaExportStore.Result.Written
        assertFalse(result.cleanupFailed)
        assertEquals(setOf("chaldea-account-7001-20261111-0100"), provider.files.keys)
        assertEquals("new-7001", provider.files.values.single().readText())
        val single = DocumentsContract.buildDocumentUri(ExportTestProvider.AUTHORITY, DocumentsContract.getDocumentId(newest))
        assertTrue(PickedChaldeaFiles(context).delete("7001", keep = single))
        assertEquals(1, provider.files.size)
        assertTrue(ChaldeaExportStore.deleteAccount(context, "7001"))
        assertTrue(provider.files.isEmpty())
    }

    @Test fun pickedDocumentsAreReplacedPerAccountAndBothDeleteActionsIncludeThem() = runBlocking {
        val first = provider.add("first")
        val other = provider.add("other")
        val latest = provider.add("latest")
        assertTrue(ChaldeaExportStore.picked(context, first, request("7001")) is ChaldeaExportStore.Result.Written)
        ChaldeaExportStore.picked(context, other, request("7002"))
        val saved = ChaldeaExportStore.picked(context, latest, request("7001")) as ChaldeaExportStore.Result.Written
        assertFalse(saved.cleanupFailed)
        assertEquals(setOf("other", "latest"), provider.files.keys)
        assertEquals("new-7001", provider.files.getValue("latest").readText())
        assertTrue(ChaldeaExportStore.deleteAccount(context, "7001"))
        assertEquals(setOf("other"), provider.files.keys)
        assertTrue(ChaldeaExportStore.deleteTrackerData(context))
        assertTrue(provider.files.isEmpty())
    }

    @Test fun unremovablePickedDocumentIsReportedAndRemainsTrackedForRetry() = runBlocking {
        ChaldeaExportStore.picked(context, provider.add("old"), request("7001"))
        provider.stuck = setOf("old")
        val saved = ChaldeaExportStore.picked(context, provider.add("new"), request("7001")) as ChaldeaExportStore.Result.Written
        assertTrue(saved.cleanupFailed)
        assertEquals(setOf("old", "new"), provider.files.keys)
        assertFalse(ChaldeaExportStore.deleteAccount(context, "7001"))
        assertEquals(setOf("old"), provider.files.keys)
        // A new store instance re-reads the persisted URI even after private account deletion.
        provider.stuck = emptySet()
        assertTrue(PickedChaldeaFiles(context).delete("7001"))
        assertTrue(provider.files.isEmpty())
    }

    @Test fun revokedFolderPermissionReportsFailureButStillDeletesPrivateData() = runBlocking {
        CaptureTracker.onGameResponse("https://yfgo-game.netmarble.com/login/top", """{"response":[{"nid":"login","resCode":"00","success":{}}],"cache":{"replaced":{"userGame":[{"userId":7001,"name":"Synthetic"}]}}}""".toByteArray(), null)
        assertNotNull(CaptureTracker.snapshot("7001"))
        prefs().dirRoot.set(DocumentsContract.buildTreeDocumentUri(ExportTestProvider.AUTHORITY, "root").toString())
        provider.accessible = false
        assertFalse(ChaldeaExportStore.deleteAccount(context, "7001"))
        assertNull(CaptureTracker.snapshot("7001"))
        assertFalse(ChaldeaExportStore.deleteTrackerData(context))
    }

    @Test fun pickedLocationUsesParentThenTreeThenClearFallbackWithRealName() = runBlocking {
        val uri = provider.add("actual")
        context.contentResolver.query(uri, arrayOf("_display_name"), android.os.Bundle.EMPTY, null)!!.use {
            assertTrue(it.moveToFirst())
            assertEquals("actual.json", it.getString(0))
        }
        assertNotNull(DocumentsContract.findDocumentPath(context.contentResolver, uri))
        val saved = CaptureFileSaver.writePicked(context, uri, byteArrayOf(1))!!
        assertEquals("Exports/actual.json", saved.path)
        assertEquals("Exports/actual.json", (ChaldeaExportStore.picked(context, uri, request("7001")) as ChaldeaExportStore.Result.Written).path)
        provider.pathAvailable = false
        assertEquals("Chosen tree/actual.json", CaptureFileSaver.pickedLocation(context, uri).path)
        val single = DocumentsContract.buildDocumentUri(ExportTestProvider.AUTHORITY, "actual")
        assertEquals("선택한 위치/actual.json", CaptureFileSaver.pickedLocation(context, single).path)
    }

    @Test fun cancelledDialogsLeaveCertificatePairAndExistingExportsUntouched() = runBlocking {
        val original = CertStore.prepare()
        CertStore.commit(context, original)
        val replacement = CertStore.prepare()
        val uri = provider.add("keep")
        PickedChaldeaFiles(context).remember("7001", uri)
        val before = provider.files.getValue("keep").readBytes()
        val vm = GainsTrackerViewModel(context, prefs())
        vm.saveRequest.value = CertSaveRequest("ca.crt", replacement.caDer, replacement)
        vm.certificatePicked(null)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(CertificateSaveGuard.saving.value)
        assertArrayEquals(original.caDer, CertStore.caBytes(context))
        assertArrayEquals(original.leaf, File(CertStore.directory(context), "leaf.p12").readBytes())
        assertNull(ChaldeaExportStore.picked(context, null, request("7001")))
        assertArrayEquals(before, provider.files.getValue("keep").readBytes())
        assertEquals(setOf("keep"), provider.files.keys)
        assertTrue(PickedChaldeaFiles(context).delete("7001"))
        assertTrue(provider.files.isEmpty())
    }
}
