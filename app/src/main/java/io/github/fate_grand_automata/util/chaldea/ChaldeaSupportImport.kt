package io.github.fate_grand_automata.util.chaldea

import io.github.fate_grand_automata.prefs.core.SupportPrefsCore
import io.github.fate_grand_automata.scripts.enums.SupportClass
import io.github.fate_grand_automata.scripts.enums.SupportSelectionModeEnum
import io.github.fate_grand_automata.util.portraits.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

enum class SupportImageOrigin { Saved, Builtin, Download }
enum class SupportImportWarning { FriendCount, Metadata, ServantImage, CEImage }
data class SupportImagePlan(val entry: SupportCatalogEntry, val folder: String?, val origin: SupportImageOrigin)
data class ChaldeaSupportSettings(
    val mlb: Boolean, val npLevel: Int, val skills: List<Boolean>, val appends: List<Boolean>,
    val supportClass: SupportClass?
) {
    val appendCheck get() = appends.any { it }

    fun apply(prefs: SupportPrefsCore, servant: String?, ce: String?, ceFailed: Boolean) {
        prefs.selectionMode.set(SupportSelectionModeEnum.Preferred)
        if (servant != null) prefs.preferredServants.set(setOf(servant))
        if (!ceFailed) prefs.preferredCEs.set(ce?.let(::setOf) ?: emptySet())
        prefs.mlb.set(mlb)
        if (npLevel > 1) prefs.minimumNpLevel.set(npLevel)
        prefs.npLevelCheck.set(npLevel > 1)
        prefs.skill1Max.set(skills[0])
        prefs.skill2Max.set(skills[1])
        prefs.skill3Max.set(skills[2])
        prefs.appendSkillCheck.set(appendCheck)
        prefs.appendSkillsMax.zip(appends).forEach { (pref, max) -> pref.set(max) }
        supportClass?.let { prefs.supportClass.set(it) }
    }
}
data class ChaldeaSupportPlan(
    val servant: SupportImagePlan?, val ce: SupportImagePlan?, val settings: ChaldeaSupportSettings?,
    val warnings: Set<SupportImportWarning> = emptySet()
)
data class ChaldeaSupportResult(val servant: String?, val ce: String?, val warnings: Set<SupportImportWarning>)

/** Preparation is read-only; only confirm may copy/download images. No preferences are held here. */
interface ChaldeaSupportImages {
    suspend fun entry(kind: SupportCatalogKind, id: Int, language: String): SupportCatalogEntry?
    suspend fun saved(kind: SupportCatalogKind): List<String>
    fun builtin(entry: SupportCatalogEntry): String?
    suspend fun materialize(plan: SupportImagePlan): String
}

class ChaldeaSupportImport(private val images: ChaldeaSupportImages) {
    companion object {
        fun friend(data: JsonObject): JsonObject? {
            val team = data.obj("team") ?: return null
            return (team.array("onFieldSvts").orEmpty() + team.array("backupSvts").orEmpty())
                .filterIsInstance<JsonObject>().filter { it.text("supportType") == "friend" }.singleOrNull()
        }

        fun settings(member: JsonObject, className: String): ChaldeaSupportSettings {
            fun levels(key: String, size: Int): List<Int> {
                if (key == "appendLvs" && member[key] == null) return List(size) { 0 }
                val values = member.array(key)?.map { (it as? JsonPrimitive)?.intOrNull ?: fail(ChaldeaImportError.DATA) }
                    ?: fail(ChaldeaImportError.DATA)
                if (values.size != size || values.any { it !in 0..10 }) fail(ChaldeaImportError.DATA)
                return values
            }
            val np = member.int("tdLv")?.takeIf { it in 1..5 } ?: fail(ChaldeaImportError.DATA)
            return ChaldeaSupportSettings(member.bool("ceLimitBreak") ?: false, np,
                levels("skillLvs", 3).map { it == 10 }, levels("appendLvs", 5).map { it == 10 },
                SupportClass.entries.firstOrNull { it.name.equals(className, ignoreCase = true) }
                    ?: if (className in setOf("shielder", "ruler", "avenger", "moonCancer", "alterEgo", "foreigner", "pretender", "beast")) SupportClass.Extra else null)
        }
    }

    suspend fun prepare(data: JsonObject, language: String): ChaldeaSupportPlan {
        val member = friend(data) ?: return ChaldeaSupportPlan(null, null, null, setOf(SupportImportWarning.FriendCount))
        try {
            val servant = images.entry(SupportCatalogKind.Servant, member.int("svtId") ?: fail(ChaldeaImportError.DATA), language)
                ?: return ChaldeaSupportPlan(null, null, null, setOf(SupportImportWarning.Metadata))
            val settings = settings(member, servant.className)
            val ceId = member.int("ceId") ?: 0
            val ce = if (ceId > 0) try { images.entry(SupportCatalogKind.CE, ceId, language) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null } else null
            val warnings = mutableSetOf<SupportImportWarning>()
            suspend fun image(entry: SupportCatalogEntry?, warning: SupportImportWarning): SupportImagePlan? = try {
                entry?.let { resolve(it) }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { warnings += warning; null }
            if (ceId > 0 && ce == null) warnings += SupportImportWarning.CEImage
            return ChaldeaSupportPlan(image(servant, SupportImportWarning.ServantImage),
                image(ce, SupportImportWarning.CEImage), settings, warnings.toSet())
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) {
            return ChaldeaSupportPlan(null, null, null, setOf(SupportImportWarning.Metadata))
        }
    }

    private suspend fun resolve(entry: SupportCatalogEntry): SupportImagePlan {
        val saved = ServantPortraitFolder.resolve(entry.folderName, images.saved(entry.kind))
        if (saved != null) return SupportImagePlan(entry, saved, SupportImageOrigin.Saved)
        val builtin = images.builtin(entry)
        return SupportImagePlan(entry, builtin, if (builtin != null) SupportImageOrigin.Builtin else SupportImageOrigin.Download)
    }

    suspend fun confirm(plan: ChaldeaSupportPlan): ChaldeaSupportResult {
        val warnings = plan.warnings.toMutableSet()
        suspend fun image(item: SupportImagePlan?, warning: SupportImportWarning): String? {
            if (item == null) return null
            return try { images.materialize(item) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { warnings += warning; null }
        }
        return ChaldeaSupportResult(image(plan.servant, SupportImportWarning.ServantImage),
            image(plan.ce, SupportImportWarning.CEImage), warnings)
    }
}

data class BuiltinSupportId(val kind: String, val folder: String, val id: Int?, val number: Int?, val region: String?)
object BuiltinSupportIds {
    fun parse(text: String): List<BuiltinSupportId> = Json.parseToJsonElement(text).jsonArray.map {
        val row = it.jsonObject
        BuiltinSupportId(row.getValue("kind").jsonPrimitive.content, row.getValue("folder").jsonPrimitive.content,
            row["id"]?.jsonPrimitive?.intOrNull, row["number"]?.jsonPrimitive?.intOrNull,
            row["region"]?.jsonPrimitive?.contentOrNull)
    }.also { rows ->
        require(rows.all { it.kind in setOf("servant", "ce") && '/' !in it.folder && '\\' !in it.folder })
        require(rows.distinctBy { it.kind to it.folder }.size == rows.size)
        require(rows.filter { it.id != null }.distinctBy { it.kind to it.id }.size == rows.count { it.id != null })
        require(rows.all { (it.id == null) == (it.number == null) })
    }
}
