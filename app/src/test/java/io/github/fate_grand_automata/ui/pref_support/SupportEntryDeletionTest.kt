package io.github.fate_grand_automata.ui.pref_support

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.SupportImageKind
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.util.StorageProvider
import io.github.fate_grand_automata.util.SupportThumbnailIndex
import io.github.fate_grand_automata.util.deleteSupportEntries
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** #627: deleting support images goes through the real storage layer on a temporary folder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class SupportEntryDeletionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(context.cacheDir, "deletion-${System.nanoTime()}").apply { mkdirs() }
    private val prefs = PrefsCore(PrefMaker(context.getSharedPreferences("del-${System.nanoTime()}", Context.MODE_PRIVATE)), context)
    private val storage = StorageProvider(prefs, context)
        .also { StorageProvider::class.java.getDeclaredField("dirRoot").apply { isAccessible = true }.set(it, DocumentFile.fromFile(root)) }

    private fun file(path: String) = File(root, path).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }

    private fun layout() {
        file("support/servant/Alpha/a.png"); file("support/servant/Alpha/b.png"); file("support/servant/Beta/a.png")
        file("support/ce/Grail.png"); file("support/ce/Other.png"); file("support/ce/Folder CE/x.png")
        // Same names in places a delete must never reach.
        file("support/friend/Alpha/a.png"); file("support/np/Alpha/a.png"); file("support/servant/Alpha.keep"); file("drops/Alpha"); file("Alpha/a.png")
        file("support/ce/Alpha/a.png")
    }

    @Test
    fun `a servant entry removes its whole folder and nothing else`() {
        layout()
        assertEquals(setOf("Alpha"), storage.deleteSupportEntries(SupportImageKind.Servant, setOf("Alpha")))
        assertFalse(File(root, "support/servant/Alpha").exists())
        assertTrue(File(root, "support/servant/Beta/a.png").exists())
        assertTrue(File(root, "support/servant/Alpha.keep").exists())
        for (outside in listOf("support/friend/Alpha/a.png", "support/np/Alpha/a.png", "drops/Alpha", "Alpha/a.png", "support/ce/Alpha/a.png", "support/ce/Grail.png")) {
            assertTrue("$outside was deleted", File(root, outside).exists())
        }
    }

    @Test
    fun `a CE entry removes its image file or its folder`() {
        layout()
        assertEquals(setOf("Grail.png", "Folder CE"), storage.deleteSupportEntries(SupportImageKind.CE, setOf("Grail.png", "Folder CE")))
        assertFalse(File(root, "support/ce/Grail.png").exists())
        assertFalse(File(root, "support/ce/Folder CE").exists())
        assertTrue(File(root, "support/ce/Other.png").exists())
        assertTrue(File(root, "support/servant/Alpha/a.png").exists())
    }

    @Test
    fun `a name that is not a plain entry name never reaches outside the kind folder`() {
        layout()
        val result = storage.deleteSupportEntries(SupportImageKind.Servant, setOf("../ce/Grail.png", "../../drops/Alpha", "..", ".", "", "Alpha/a.png", "a\b"))
        assertEquals(emptySet<String>(), result)
        assertTrue(File(root, "support/ce/Grail.png").exists())
        assertTrue(File(root, "drops/Alpha").exists())
        assertTrue(File(root, "support/servant/Alpha/a.png").exists())
    }

    @Test
    fun `a missing entry is not reported as deleted and the support folder is not created`() {
        file("support/servant/Beta/a.png")
        assertEquals(emptySet<String>(), storage.deleteSupportEntries(SupportImageKind.Servant, setOf("Nobody")))
        assertTrue(File(root, "support/servant/Beta/a.png").exists())
        assertFalse(File(root, "support/ce").exists())
        assertEquals(emptySet<String>(), storage.deleteSupportEntries(SupportImageKind.CE, setOf("Grail.png")))
        assertFalse(File(root, "support/ce").exists())
    }

    @Test
    fun `an entry that fails to delete stays and is left out of the result`() {
        layout()
        val folder = DocumentFile.fromFile(File(root, "support/servant"))
        val removed = deleteSupportEntries(folder, setOf("Alpha", "Beta")) { entry ->
            if (entry.name == "Alpha") false else entry.delete()
        }
        assertEquals(setOf("Beta"), removed)
        assertTrue(File(root, "support/servant/Alpha/a.png").exists())
        assertFalse(File(root, "support/servant/Beta").exists())

        val thrown = deleteSupportEntries(folder, setOf("Alpha")) { throw SecurityException("denied") }
        assertEquals(emptySet<String>(), thrown)
        assertTrue(File(root, "support/servant/Alpha/a.png").exists())
    }

    @Test
    fun `the picker delete never removes a built-in entry even when asked to`() {
        file("support/servant/Arcueid/a.png"); file("support/servant/Mine/a.png")
        var refreshed = 0
        val delete = supportImageDelete(storage, SupportImageKind.Servant, context.assets, storage.supportThumbnails(SupportImageKind.Servant)) { refreshed++ }
        assertTrue(delete.isProtected("Arcueid"))
        assertFalse(delete.isProtected("Mine"))
        assertEquals(setOf("Mine"), runBlocking { delete.delete(setOf("Arcueid", "Mine")) })
        assertTrue(File(root, "support/servant/Arcueid/a.png").exists())
        assertFalse(File(root, "support/servant/Mine").exists())
        assertEquals(1, refreshed)
    }

    @Test
    fun `every battle config loses exactly the servants that were really deleted`() {
        layout()
        prefs.battleConfigList.set(setOf("c1", "c2"))
        for (id in listOf("c1", "c2")) {
            val support = prefs.forBattleConfig(id).support
            support.preferredServants.set(setOf("Alpha", "Beta", "Keep"))
            support.preferredCEs.set(setOf("Grail.png", "Alpha"))
            support.friendNames.set(setOf("Alpha"))
        }
        prefs.forBattleConfig("c2").support.preferredServants.set(setOf("Beta"))

        // Beta stays because its delete fails; Alpha is removed everywhere it was listed.
        val removed = storage.deleteSupportEntries(SupportImageKind.Servant, setOf("Alpha", "Beta")) { it.name != "Beta" && it.delete() }
        assertEquals(setOf("Alpha"), removed)
        assertEquals(setOf("Beta", "Keep"), prefs.forBattleConfig("c1").support.preferredServants.get())
        assertEquals(setOf("Beta"), prefs.forBattleConfig("c2").support.preferredServants.get())
        for (id in listOf("c1", "c2")) {
            val support = prefs.forBattleConfig(id).support
            assertEquals(setOf("Grail.png", "Alpha"), support.preferredCEs.get())
            assertEquals(setOf("Alpha"), support.friendNames.get())
        }
    }

    @Test
    fun `a deleted CE leaves the preferred CEs of every config and not the preferred servants`() {
        layout()
        prefs.battleConfigList.set(setOf("c1", "c2"))
        for (id in listOf("c1", "c2")) {
            val support = prefs.forBattleConfig(id).support
            support.preferredCEs.set(setOf("Grail.png", "Other.png"))
            support.preferredServants.set(setOf("Grail.png"))
        }
        assertEquals(setOf("Grail.png"), storage.deleteSupportEntries(SupportImageKind.CE, setOf("Grail.png")))
        for (id in listOf("c1", "c2")) {
            val support = prefs.forBattleConfig(id).support
            assertEquals(setOf("Other.png"), support.preferredCEs.get())
            assertEquals(setOf("Grail.png"), support.preferredServants.get())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `friend and noble phantasm images are never deleted from the picker`() {
        storage.deleteSupportEntries(SupportImageKind.Friend, setOf("Alpha"))
    }

    @Test
    fun `the thumbnail index stops showing a deleted entry once it forgets it`() {
        layout()
        val index = storage.supportThumbnails(SupportImageKind.Servant)
        runBlocking { assertNotNull(index.thumbnail("Alpha")) }
        storage.deleteSupportEntries(SupportImageKind.Servant, setOf("Alpha"))
        index.forget(setOf("Alpha"))
        runBlocking {
            assertNull(index.thumbnail("Alpha"))
            assertNotNull(index.thumbnail("Beta"))
        }
        assertEquals("servant", SupportThumbnailIndex.folderName(SupportImageKind.Servant))
    }
}
