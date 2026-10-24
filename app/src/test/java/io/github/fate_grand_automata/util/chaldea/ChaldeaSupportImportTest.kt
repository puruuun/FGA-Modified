package io.github.fate_grand_automata.util.chaldea

import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.SupportPrefsCore
import io.github.fate_grand_automata.scripts.enums.SupportClass
import io.github.fate_grand_automata.scripts.enums.SupportSelectionModeEnum
import io.github.fate_grand_automata.util.portraits.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

class ChaldeaSupportImportTest {
    private val member = Json.parseToJsonElement("""{"supportType":"friend","svtId":500800,"ceId":9400340,
        "ceLimitBreak":true,"tdLv":3,"skillLvs":[10,9,10],"appendLvs":[0,10,9,10,1]}""").jsonObject
    private fun entry(kind: SupportCatalogKind, id: Int) = SupportCatalogEntry(kind, id,
        if (kind == SupportCatalogKind.Servant) 150 else 34, "name", "caster", 5, null, emptySet())

    private class Images : ChaldeaSupportImages {
        var folders = emptyList<String>()
        var builtin: String? = "Merlin"
        val writes = mutableListOf<SupportCatalogKind>()
        var failed: SupportCatalogKind? = null
        var metadataFailed: SupportCatalogKind? = null
        var cancelled = false
        override suspend fun entry(kind: SupportCatalogKind, id: Int, language: String): SupportCatalogEntry? {
            if (kind == metadataFailed) error("offline")
            return SupportCatalogEntry(kind, id, if (kind == SupportCatalogKind.Servant) 150 else 34,
                "name", "caster", 5, null, emptySet())
        }
        override suspend fun saved(kind: SupportCatalogKind) = folders
        override fun builtin(entry: SupportCatalogEntry) = builtin
        override suspend fun materialize(plan: SupportImagePlan): String {
            if (cancelled) throw CancellationException()
            writes += plan.entry.kind
            if (plan.entry.kind == failed) error("offline")
            return plan.folder ?: plan.entry.folderName
        }
    }

    private fun data(friend: JsonObject = member) = buildJsonObject {
        put("team", buildJsonObject {
            put("onFieldSvts", JsonArray(listOf(friend, JsonNull, JsonNull)))
            put("backupSvts", JsonArray(List(3) { JsonNull }))
        })
    }

    @Test fun `both real links accept one friend and skip zero or multiple friends`() = runBlocking {
        for (file in listOf("user-share-url.txt", "reported-target-share-url.txt")) {
            val original = ChaldeaShare.decode(javaClass.getResource("/chaldea/$file")!!.readText())
            assertEquals(if (file == "user-share-url.txt") 1 else 0,
                if (ChaldeaSupportImport.friend(original) != null) 1 else 0)
            val team = original.obj("team")!!
            for (count in listOf(0, 1, 2)) {
                var index = 0
                val changed = JsonObject(original + ("team" to JsonObject(team.mapValues { (key, value) ->
                    if (key !in setOf("onFieldSvts", "backupSvts")) value else JsonArray(value.jsonArray.map {
                        val m = (it as? JsonObject) ?: member
                        JsonObject(m + ("supportType" to JsonPrimitive(if (index++ < count) "friend" else "none")))
                    })
                })))
                val plan = ChaldeaSupportImport(Images()).prepare(changed, "ko")
                if (count == 1) {
                    assertNotNull(ChaldeaSupportImport.friend(changed))
                    assertNotNull(plan.settings, "$file: ${plan.warnings}")
                } else {
                    assertNull(ChaldeaSupportImport.friend(changed))
                    assertEquals(setOf(SupportImportWarning.FriendCount), plan.warnings)
                }
            }
        }
    }

    @Test fun `saved identity wins then builtin then download and regional numbers stay separate`() = runBlocking {
        val images = Images()
        val importer = ChaldeaSupportImport(images)
        images.folders = listOf("NA.No.150 regional", "No.150 custom")
        val saved = importer.prepare(data(), "ko").servant!!
        assertEquals("No.150 custom", saved.folder)
        assertEquals(SupportImageOrigin.Saved, saved.origin)
        images.folders = listOf("NA.No.150 regional")
        assertEquals(SupportImageOrigin.Builtin, importer.prepare(data(), "ko").servant!!.origin)
        images.builtin = null
        assertEquals(SupportImageOrigin.Download, importer.prepare(data(), "ko").servant!!.origin)
        assertTrue(images.writes.isEmpty())
    }

    @Test fun `all mapped settings replace selections and preserve unrelated preferences`() {
        val store = MemoryPrefs()
        val prefs = SupportPrefsCore(PrefMaker(store))
        prefs.selectionMode.set(SupportSelectionModeEnum.Manual)
        prefs.preferredServants.set(setOf("old1", "old2"))
        prefs.preferredCEs.set(setOf("oldCE"))
        prefs.grandServant.set(true)
        prefs.score62Check.set(true)
        prefs.grandScore16Check.set(true)
        prefs.friendsOnly.set(true)
        prefs.maxAscended.set(true)
        prefs.friendNames.set(setOf("friend"))
        val before = store.data.toMap()
        val settings = ChaldeaSupportImport.settings(member, "caster")
        settings.apply(prefs, "Merlin", "Kaleidoscope.png", false)
        assertEquals(SupportSelectionModeEnum.Preferred, prefs.selectionMode.get())
        assertEquals(setOf("Merlin"), prefs.preferredServants.get())
        assertEquals(setOf("Kaleidoscope.png"), prefs.preferredCEs.get())
        assertTrue(prefs.mlb.get())
        assertTrue(prefs.npLevelCheck.get())
        assertEquals(3, prefs.minimumNpLevel.get())
        assertEquals(listOf(true, false, true), listOf(prefs.skill1Max.get(), prefs.skill2Max.get(), prefs.skill3Max.get()))
        assertTrue(prefs.appendSkillCheck.get())
        assertEquals(listOf(false, true, false, true, false), prefs.appendSkillsMax.map { it.get() })
        assertEquals(SupportClass.Caster, prefs.supportClass.get())
        before.filterKeys { it in setOf("support_grand_servant", "support_score_62_check", "support_grand_score_16_check",
            "support_friends_only", "support_max_ascended", "support_friend_names_list") }.forEach { (key, value) ->
            assertEquals(value, store.data[key], key)
        }
        ChaldeaSupportImport.settings(JsonObject(member + ("appendLvs" to JsonArray(List(5) { JsonPrimitive(9) }))), "unknown")
            .apply(prefs, null, null, false)
        assertEquals(setOf("Merlin"), prefs.preferredServants.get())
        assertTrue(prefs.preferredCEs.get().isEmpty())
        assertFalse(prefs.appendSkillCheck.get())
        assertTrue(prefs.appendSkillsMax.none { it.get() })
        assertEquals(SupportClass.Caster, prefs.supportClass.get())
        assertEquals(SupportClass.Extra, ChaldeaSupportImport.settings(member, "moonCancer").supportClass)
        settings.copy(mlb = false).apply(prefs, null, null, true)
        assertFalse(prefs.mlb.get())
    }

    @Test fun `preview cancellation makes no writes and confirm isolates image failure`() {
      runBlocking {
        val images = Images().apply { builtin = null }
        val importer = ChaldeaSupportImport(images)
        val store = MemoryPrefs()
        val prefs = SupportPrefsCore(PrefMaker(store))
        prefs.preferredServants.set(setOf("old"))
        prefs.preferredCEs.set(setOf("oldCE"))
        val before = store.data.toMap()
        val plan = importer.prepare(data(), "ko") // Discarding the preview is cancel.
        assertEquals(before, store.data)
        assertTrue(images.writes.isEmpty())
        images.failed = SupportCatalogKind.CE
        val result = importer.confirm(plan)
        assertEquals(setOf(SupportImportWarning.CEImage), result.warnings)
        plan.settings!!.apply(prefs, result.servant, result.ce, SupportImportWarning.CEImage in result.warnings)
        assertEquals(setOf("No.150 name"), prefs.preferredServants.get())
        assertEquals(setOf("oldCE"), prefs.preferredCEs.get())
        images.failed = SupportCatalogKind.Servant
        assertNotNull(importer.confirm(plan).ce)
        images.cancelled = true
        assertFailsWith<CancellationException> { importer.confirm(plan) }
      }
    }

    @Test fun `metadata failures skip support or only CE and no equipped CE clears selection`() = runBlocking {
        val images = Images()
        val importer = ChaldeaSupportImport(images)
        images.metadataFailed = SupportCatalogKind.CE
        val plan = importer.prepare(data(), "ko")
        assertNotNull(plan.servant)
        assertEquals(setOf(SupportImportWarning.CEImage), plan.warnings)
        images.metadataFailed = SupportCatalogKind.Servant
        assertEquals(setOf(SupportImportWarning.Metadata), importer.prepare(data(), "ko").warnings)
        images.metadataFailed = null
        val none = importer.prepare(data(JsonObject(member + ("ceId" to JsonPrimitive(0)))), "ko")
        assertNull(none.ce)
        assertTrue(none.warnings.isEmpty())
        assertTrue(images.writes.isEmpty())
    }

    @Test fun `mapping covers every shipped identity including explicitly unmapped entries without duplicates`() {
        val assets = File(checkNotNull(System.getProperty("fga.supportAssets")))
        val rows = BuiltinSupportIds.parse(File(assets, "builtin-ids.json").readText())
        for (kind in listOf("servant", "ce")) {
            val folders = File(assets, kind).listFiles()!!.map { it.name }.toSet()
            assertEquals(folders, rows.filter { it.kind == kind }.map { it.folder }.toSet())
        }
        assertEquals(9, rows.count { it.kind == "servant" && it.id != null })
        assertEquals(23, rows.count { it.kind == "ce" && it.id != null })
        assertEquals(0, rows.count { it.kind == "ce" && it.id == null })
        for (row in rows.filter { it.kind == "servant" && it.id != null }) {
            assertTrue(File(assets, "servant/${row.folder}").listFiles()!!.any {
                Regex("f_${row.id}[0-9]\\.png").matches(it.name)
            })
        }
        val text = File(assets, "builtin-ids.json").readText()
        val array = Json.parseToJsonElement(text).jsonArray
        array.map { it.jsonObject }.filter { it.text("kind") == "ce" }.forEach {
            assertTrue((it.int("id") ?: 0) > 0)
            assertTrue((it.int("number") ?: 0) > 0)
            assertTrue(!it.text("name").isNullOrBlank())
        }
        assertFailsWith<IllegalArgumentException> { BuiltinSupportIds.parse(JsonArray(array + array.first()).toString()) }
    }

    @Test fun `friend in backup is found and omitted default append levels disable checks`() {
        val root = data()
        val team = root.obj("team")!!
        val moved = JsonObject(root + ("team" to JsonObject(team + mapOf(
            "onFieldSvts" to JsonArray(List(3) { JsonNull }),
            "backupSvts" to JsonArray(listOf(JsonNull, JsonNull, member))
        ))))
        assertEquals(member, ChaldeaSupportImport.friend(moved))
        val settings = ChaldeaSupportImport.settings(JsonObject(member - "appendLvs"), "caster")
        assertFalse(settings.appendCheck)
        assertEquals(List(5) { false }, settings.appends)
    }
}
