package io.github.fate_grand_automata.util.migration

import io.github.fate_grand_automata.prefs.BattleConfigFile
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OriginalFgaImporterTest {
    @Test fun `one original storage root supports each selected import mode`() {
        for (selection in ImportSelection.entries) {
            val source = dir("original-${selection.name}")
            val dest = dir("modified-${selection.name}")
            script(source, "scripts/nested/exported.fga")
            image(source, "support/ce/Custom CE.png")
            val imported = mutableListOf<Map<String, *>>()
            val storage = object : ImportedScriptStore {
                override fun existing() = imported.toList()
                override fun add(values: Map<String, *>) { imported += values }
            }
            val report = OriginalFgaImporter(storage).run(
                selection, LocalDocument(source), LocalDocument(source), LocalDocument(dest)
            )
            assertEquals(if (selection.scripts) 1 else 0, report.scripts.imported)
            assertEquals(if (selection.images) 1 else 0, report.images.imported)
            assertEquals(selection.images, File(dest, "support/ce/Custom CE.png").exists())
            assertEquals(if (selection.scripts) 1 else 0, imported.size)
            assertEquals(0, report.issueCount)
        }
    }

    @TempDir lateinit var temp: Path
    private val records = mutableListOf<Map<String, *>>()
    private val store = object : ImportedScriptStore {
        override fun existing() = records.toList()
        override fun add(values: Map<String, *>) { records += values }
    }
    private fun dir(path: String) = File(temp.toFile(), path).apply { mkdirs() }
    private fun script(folder: File, name: String = "test.fga", values: Map<String, *> = values()) {
        File(folder, name).apply { requireNotNull(parentFile).mkdirs(); writeText(BattleConfigFile.encode(values)) }
    }
    private fun values() = mapOf(
        "autoskill_name" to "한국어・日本語",
        "autoskill_cmd" to "ghi1x31ih3gt2acbdefj654",
        "autoskill_party" to "2",
        "shuffle_cards_wave" to 3,
        "support_friends_only" to true,
        "support_pref_servant_list" to setOf("Oberon"),
        "support_pref_ce_list" to setOf("Custom CE.png"),
        "support_friend_names_list" to setOf("친구.png")
    )
    private fun image(folder: File, path: String, content: String = path) = File(folder, path).apply {
        requireNotNull(parentFile).mkdirs(); writeBytes(content.toByteArray())
    }

    @Test fun `script only imports recursively without touching images or destination`() {
        val source = dir("scripts")
        script(source, "nested/test.FGA")
        File(source, "other.json").writeText("{}"); File(source, "photo.png").writeText("ignore")
        val report = OriginalFgaImporter(store).run(ImportSelection.Scripts, LocalDocument(source), null, null)
        assertEquals(ImportCounts(imported = 1), report.scripts)
        assertEquals(ImportCounts(), report.images)
        assertEquals(0, report.issueCount)
        assertEquals(values()["autoskill_cmd"], records.single()["autoskill_cmd"])
        assertEquals(3, records.single()["shuffle_cards_wave"])
        assertEquals("2", records.single()["autoskill_party"])
        assertEquals(true, records.single()["support_friends_only"])
    }

    @Test fun `images only copies every category and servant variants with names intact`() {
        val source = dir("original")
        val dest = dir("modified")
        val paths = listOf("servant/Oberon/variant.png", "ce/Custom CE.png", "friend/친구.png")
        paths.forEach { image(source, "support/$it") }
        image(source, "support/.nomedia"); image(source, "support/ce/readme.txt")
        val forbiddenStore = object : ImportedScriptStore {
            override fun existing(): List<Map<String, *>> = error("Scripts must not be read")
            override fun add(values: Map<String, *>) = error("Scripts must not be written")
        }
        val report = OriginalFgaImporter(forbiddenStore).run(
            ImportSelection.Images, null, LocalDocument(source), LocalDocument(dest)
        )
        assertEquals(ImportCounts(imported = 3), report.images)
        assertEquals(ImportCounts(), report.scripts)
        paths.forEach { assertContentEquals(File(source, "support/$it").readBytes(), File(dest, "support/$it").readBytes()) }
        assertFalse(File(dest, "support/ce/readme.txt").exists())
    }

    @Test fun `both accepts support folder itself and preserves script image references`() {
        val scripts = dir("exported")
        val images = dir("support")
        val dest = dir("modified")
        script(scripts)
        image(images, "servant/Oberon/1.png")
        image(images, "ce/Custom CE.png")
        image(images, "friend/친구.png")
        val progress = mutableListOf<OriginalImportProgress>()
        val report = OriginalFgaImporter(store).run(
            ImportSelection.Both, LocalDocument(scripts), LocalDocument(images), LocalDocument(dest), onProgress = progress::add
        )
        assertEquals(1, report.scripts.imported)
        assertEquals(3, report.images.imported)
        assertEquals(setOf("Oberon"), (records.single()["support_pref_servant_list"] as Collection<*>).toSet())
        assertTrue(File(dest, "support/servant/Oberon/1.png").exists())
        assertEquals(setOf(ImportPart.Scripts, ImportPart.Images), progress.map { it.part }.toSet())
    }

    @Test fun `legacy Gson and current exports are accepted without a build number`() {
        val source = dir("scripts")
        // Source-derived flat JSON layout used by v2481 and v2996's Gson exporter.
        File(source, "legacy.fga").writeText("""{"autoskill_name":"Legacy","autoskill_cmd":"a1b2c3","autoskill_party":"-1","shuffle_cards_wave":2,"support_friends_only":true,"support_pref_servant_list":["Oberon"],"future_option":"retained"}""")
        script(source, "modern.fga")
        val report = OriginalFgaImporter(store).run(ImportSelection.Scripts, LocalDocument(source), null, null)
        assertEquals(2, report.scripts.imported)
        val legacy = records.first { it["autoskill_name"] == "Legacy" }
        assertEquals(2, legacy["shuffle_cards_wave"])
        assertEquals("-1", legacy["autoskill_party"])
        assertEquals("retained", legacy["future_option"])
    }

    @Test fun `identical scripts are skipped across set order and repeated imports`() {
        val source = dir("scripts")
        val original = values() + ("battle_config_mat" to linkedSetOf("Proof", "Bone"))
        records += original
        script(source, "a.fga", values() + ("battle_config_mat" to listOf("Bone", "Proof")))
        script(source, "b.fga", values() + ("autoskill_name" to "Other"))
        val importer = OriginalFgaImporter(store)
        assertEquals(ImportCounts(imported = 1, skipped = 1), importer.run(ImportSelection.Scripts, LocalDocument(source), null, null).scripts)
        assertEquals(ImportCounts(skipped = 2), importer.run(ImportSelection.Scripts, LocalDocument(source), null, null).scripts)
        assertEquals(2, records.size)
        assertEquals(original, records.first())
    }

    @Test fun `damaged unrelated and unsupported scripts fail individually without empty entries`() {
        val source = dir("scripts")
        File(source, "bad.fga").writeText("not json")
        File(source, "unrelated.fga").writeText("""{"setting":true}""")
        File(source, "wrong-type.fga").writeText("""{"autoskill_cmd":123}""")
        script(source, "future.fga", values() + ("autoskill_cmd" to "z"))
        script(source, "valid.fga")
        val report = OriginalFgaImporter(store).run(ImportSelection.Scripts, LocalDocument(source), null, null)
        assertEquals(ImportCounts(imported = 1, failed = 4), report.scripts)
        assertEquals(1, records.size)
        assertEquals(ImportProblem.UnsupportedCommand, report.issues.first { it.path == "future.fga" }.problem)
    }

    @Test fun `existing image is preserved unless overwrite is selected`() {
        val source = dir("original")
        val dest = dir("modified")
        image(source, "support/ce/a.png", "new")
        val target = image(dest, "support/ce/a.png", "old")
        val importer = OriginalFgaImporter(store)
        fun run(overwrite: Boolean) = importer.run(ImportSelection.Images, null, LocalDocument(source), LocalDocument(dest), overwrite)
        assertEquals(ImportCounts(skipped = 1), run(false).images)
        assertEquals("old", target.readText())
        assertEquals(ImportCounts(imported = 1), run(true).images)
        assertEquals("new", target.readText())
        assertEquals(ImportCounts(skipped = 1), run(true).images)
        assertEquals("new", File(source, "support/ce/a.png").readText())
    }

    @Test fun `same source and destination never truncates an image even when overwriting`() {
        val root = dir("shared")
        val target = image(root, "support/ce/a.png", "keep")
        val report = OriginalFgaImporter(store).run(ImportSelection.Images, null, LocalDocument(root), LocalDocument(root), true)
        assertEquals(ImportCounts(skipped = 1), report.images)
        assertEquals("keep", target.readText())
    }

    @Test fun `failed overwrite restores old image and continues with other files`() {
        val source = dir("original")
        val dest = dir("modified")
        image(source, "support/ce/a.png", "new")
        image(source, "support/ce/b.png", "second")
        val target = image(dest, "support/ce/a.png", "old")
        val failures = mutableSetOf(target.canonicalPath)
        val report = OriginalFgaImporter(store).run(
            ImportSelection.Images, null, LocalDocument(source), LocalDocument(dest, failures), true
        )
        assertEquals(ImportCounts(imported = 1, failed = 1), report.images)
        assertEquals("old", target.readText())
        assertEquals("second", File(dest, "support/ce/b.png").readText())
    }

    @Test fun `a failed new image is removed`() {
        val source = dir("original")
        val dest = dir("modified")
        image(source, "support/ce/a.png")
        val target = File(dest, "support/ce/a.png")
        val report = OriginalFgaImporter(store).run(
            ImportSelection.Images, null, LocalDocument(source), LocalDocument(dest, mutableSetOf(target.canonicalPath))
        )
        assertEquals(1, report.images.failed)
        assertFalse(target.exists())
    }

    @Test fun `empty or incorrect source folders are reported without silent success`() {
        val empty = dir("empty")
        val report = OriginalFgaImporter(store).run(ImportSelection.Both, LocalDocument(empty), LocalDocument(empty), LocalDocument(dir("dest")))
        assertEquals(2, report.issueCount)
        assertTrue(report.issues.any { it.part == ImportPart.Scripts && it.problem == ImportProblem.NoFiles })
        assertTrue(report.issues.any { it.part == ImportPart.Images && it.problem == ImportProblem.Folder })
    }

    @Test fun `nested destination is rejected before copying source images into itself`() {
        val source = dir("original")
        image(source, "support/ce/a.png")
        val dest = dir("original/support/ce/modified")
        val report = OriginalFgaImporter(store).run(ImportSelection.Images, null, LocalDocument(source), LocalDocument(dest))
        assertEquals(0, report.images.imported)
        assertTrue(report.issues.any { it.problem == ImportProblem.OverlappingFolders })
        assertFalse(File(dest, "support").exists())
    }

    @Test fun `directory at an image path is reported as a failure instead of a skipped image`() {
        val source = dir("original")
        val dest = dir("modified")
        image(source, "support/ce/a.png")
        dir("modified/support/ce/a.png")
        val report = OriginalFgaImporter(store).run(ImportSelection.Images, null, LocalDocument(source), LocalDocument(dest))
        assertEquals(ImportCounts(failed = 1), report.images)
    }

    @Test fun `one corrupt import does not prevent the other selected category from importing`() {
        val scripts = dir("scripts")
        val images = dir("original")
        val dest = dir("modified")
        File(scripts, "bad.fga").writeText("broken")
        image(images, "support/ce/a.png")
        val report = OriginalFgaImporter(store).run(ImportSelection.Both, LocalDocument(scripts), LocalDocument(images), LocalDocument(dest))
        assertEquals(ImportCounts(failed = 1), report.scripts)
        assertEquals(ImportCounts(imported = 1), report.images)
        assertTrue(records.isEmpty())
    }

    @Test fun `image failure does not remove successfully imported scripts`() {
        val scripts = dir("scripts")
        script(scripts)
        val report = OriginalFgaImporter(store).run(ImportSelection.Both, LocalDocument(scripts), LocalDocument(dir("wrong")), null)
        assertEquals(ImportCounts(imported = 1), report.scripts)
        assertEquals(ImportCounts(failed = 1), report.images)
        assertEquals(1, records.size)
    }

    @Test fun `a provider changing an image name fails instead of breaking the script reference`() {
        val source = dir("original")
        val dest = dir("modified")
        val support = dir("modified/support")
        val ce = dir("modified/support/ce")
        image(source, "support/ce/a.png")
        val renamedCe = object : ImportDocument by LocalDocument(ce) {
            override fun createFile(name: String) = LocalDocument(File(ce, "$name.png").apply { createNewFile() })
        }
        val destination = object : ImportDocument by LocalDocument(dest) {
            override fun children() = listOf(object : ImportDocument by LocalDocument(support) {
                override fun children() = listOf(renamedCe)
            })
        }
        val report = OriginalFgaImporter(store).run(ImportSelection.Images, null, LocalDocument(source), destination)
        assertEquals(ImportCounts(failed = 1), report.images)
        assertFalse(File(ce, "a.png.png").exists())
    }

    @Test fun `read failure leaves destination untouched and other images still import`() {
        val source = dir("original")
        val dest = dir("modified")
        image(source, "support/ce/a.png")
        image(source, "support/ce/b.png")
        val sourceDoc = object : ImportDocument by LocalDocument(source) {
            override fun children(): List<ImportDocument> = listOf(object : ImportDocument by LocalDocument(File(source, "support")) {
                override fun children(): List<ImportDocument> = listOf(object : ImportDocument by LocalDocument(File(source, "support/ce")) {
                    override fun children(): List<ImportDocument> = listOf(
                        object : ImportDocument by LocalDocument(File(source, "support/ce/a.png")) {
                            override fun openInput(): ByteArrayInputStream = throw IOException("read denied")
                        }, LocalDocument(File(source, "support/ce/b.png"))
                    )
                })
            })
        }
        val report = OriginalFgaImporter(store).run(ImportSelection.Images, null, sourceDoc, LocalDocument(dest))
        assertEquals(ImportCounts(imported = 1, failed = 1), report.images)
        assertFalse(File(dest, "support/ce/a.png").exists())
    }

    private class LocalDocument(val file: File, val failWrites: MutableSet<String> = mutableSetOf()) : ImportDocument {
        override val name get() = file.name
        override val identity get() = file.canonicalPath
        override val isDirectory get() = file.isDirectory
        override fun children() = file.listFiles()?.map { LocalDocument(it, failWrites) } ?: throw IOException("Not a folder")
        override fun openInput() = file.inputStream()
        override fun openOutput(): OutputStream {
            if (failWrites.remove(identity)) {
                file.writeText("partial")
                throw IOException("Storage full")
            }
            return file.outputStream()
        }
        override fun createDirectory(name: String) = LocalDocument(File(file, name).apply { check(mkdir()) }, failWrites)
        override fun createFile(name: String) = LocalDocument(File(file, name).apply { check(createNewFile()) }, failWrites)
        override fun delete() { check(file.delete()) }
    }
}
