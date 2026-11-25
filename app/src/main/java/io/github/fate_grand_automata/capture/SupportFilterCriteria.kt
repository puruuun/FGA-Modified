package io.github.fate_grand_automata.capture

import android.content.Context
import io.github.fate_grand_automata.prefs.core.SupportPrefsCore
import io.github.fate_grand_automata.scripts.capture.DisplayedAscension
import io.github.fate_grand_automata.scripts.capture.SupportListCriteria
import io.github.fate_grand_automata.scripts.enums.BondCEEffectEnum
import io.github.fate_grand_automata.util.chaldea.BuiltinSupportIds
import io.github.fate_grand_automata.util.portraits.ServantPortraitFolder
import io.github.fate_grand_automata.util.portraits.SupportCatalogKind
import io.github.fate_grand_automata.util.portraits.SupportImageCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import java.io.File

/** One servant or CE of a list: its id, the collection number a downloaded image's folder name carries, its name. */
data class SupportListRow(val id: Long, val number: Int, val name: String, val className: String? = null)

/**
 * Image names to ids, and servants to their class (#685), from what is already on disk and never downloaded here:
 * the built-in images' ids (`Support/builtin-ids.json`), the servant and CE lists that capture keeps for names, and
 * the lists the image download keeps. A list that is not on disk simply maps nothing.
 */
class SupportIdLookup(
    private val builtinServants: Map<String, Long> = emptyMap(),
    private val builtinCes: Map<String, Long> = emptyMap(),
    /** Folder-name region (null for JP, "KR", "NA") to the rows of that region's numbering. */
    private val servantRows: Map<String?, List<SupportListRow>> = emptyMap(),
    private val ceRows: Map<String?, List<SupportListRow>> = emptyMap(),
) {
    private val servantNumbers = servantRows.mapValues { (_, rows) -> rows.associate { it.number to it.id } }
    private val ceNumbers = ceRows.mapValues { (_, rows) -> rows.associate { it.number to it.id } }

    /** svtId to the game's class id; empty when no servant list is on disk. */
    val servantClassIds: Map<Long, Int> = servantRows.values.flatten()
        .mapNotNull { row -> row.className?.let(::classIdOf)?.let { row.id to it } }.toMap()

    fun servantId(entry: String): Long? = idOf(entry, builtinServants, servantNumbers)

    fun ceId(entry: String): Long? = idOf(ServantPortraitFolder.displayName(entry), builtinCes, ceNumbers, entry)

    private fun idOf(name: String, builtins: Map<String, Long>, numbers: Map<String?, Map<Int, Long>>, original: String = name): Long? {
        ID_ENTRY.matchEntire(original)?.let { return it.groupValues[1].toLongOrNull() }
        builtins[original]?.let { return it }
        val number = ServantPortraitFolder.number(name) ?: return null
        return numbers[ServantPortraitFolder.region(name)]?.get(number)
    }

    /** An entry that [servantId] maps back to [id], for the migration of the experiment's ids. */
    fun servantEntry(id: Long): String = entryOf(id, builtinServants, servantRows)

    fun ceEntry(id: Long): String = entryOf(id, builtinCes, ceRows)

    private fun entryOf(id: Long, builtins: Map<String, Long>, rows: Map<String?, List<SupportListRow>>): String {
        builtins.entries.firstOrNull { it.value == id }?.let { return it.key }
        for (region in listOf(null, "KR")) {
            rows[region]?.firstOrNull { it.id == id }?.let { return ServantPortraitFolder.name(it.number, it.name, region) }
        }
        return idEntry(id)
    }

    companion object {
        /** An entry that names the id itself, written by the migration when no image name is known for it. */
        private val ID_ENTRY = Regex("#([1-9][0-9]{0,17})")

        fun idEntry(id: Long) = "#$id"

        /**
         * Atlas `className` (spelled as Atlas spells it, which is how the servant lists store it) to the game's class id,
         * the one `releasedClassIds` of a class board holds. Id and name pairs are from the `SvtClass` table of Atlas
         * Academy's `https://api.atlasacademy.io/export/JP/nice_enums.json`, read by the planner on 2026-11-25; the ids
         * do not follow the table's order (39 is `uOlgaMarieGrand`, 41 `uOlgaMarieStellar`, 43 `beastVII`), so each
         * is looked up, not counted. Each id here was also seen in `releasedClassIds` of a real follower/list: on KR
         * (2026-11-23) boards 1 to 7 hold [1]..[7], board 8 [11, 23] and board 9 [10, 25, 28]; on JP (2026-11-25)
         * board 8 holds [8, 9, 11, 23] and board 9 [10, 25, 28, 33, 38, 40]. Every other class name stays unknown: a
         * chosen servant of one with +62 stops the filter, and any other servant of one does not match the class
         * score condition.
         */
        private val CLASS_IDS = mapOf(
            "saber" to 1, "archer" to 2, "lancer" to 3, "rider" to 4, "caster" to 5, "assassin" to 6, "berserker" to 7,
            "shielder" to 8, "ruler" to 9, "alterEgo" to 10, "avenger" to 11, "moonCancer" to 23, "foreigner" to 25,
            "pretender" to 28, "beast" to 33, "beastEresh" to 38, "unBeastOlgaMarie" to 40,
        )

        fun classIdOf(className: String): Int? = CLASS_IDS[className]

        private val SERVANT_TYPES = setOf("normal", "heroine")
        private const val CE_TYPE = "servantEquip"

        fun load(context: Context): SupportIdLookup = load(
            builtinJson = runCatching {
                context.assets.open("Support/builtin-ids.json").bufferedReader().use { it.readText() }
            }.getOrNull(),
            names = GameNames(File(context.filesDir, "capture/names")),
            catalogs = File(context.cacheDir, "support-catalogs"),
        )

        /** Reads only files that exist; anything unreadable counts as not there. */
        internal fun load(builtinJson: String?, names: GameNames, catalogs: File): SupportIdLookup {
            val builtins = builtinJson?.let { runCatching { BuiltinSupportIds.parse(it) }.getOrNull() }.orEmpty()
            fun builtin(kind: String) = builtins.filter { it.kind == kind && it.id != null }.associate { it.folder to it.id!!.toLong() }

            fun catalog(region: String, kind: SupportCatalogKind): List<SupportListRow> = runCatching {
                val file = File(catalogs, "$region-${kind.endpoint}.json")
                if (!file.isFile) return@runCatching emptyList()
                SupportImageCatalog.parse(file.readText(), kind).map {
                    SupportListRow(it.id.toLong(), it.number, it.originalName.ifBlank { it.name }, it.className)
                }
            }.getOrDefault(emptyList())

            fun captured(list: String, server: String, types: Set<String>): List<SupportListRow> =
                names.cachedRows(list, server).values.mapNotNull { row ->
                    val number = row.collectionNo?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt() ?: return@mapNotNull null
                    if (row.type !in types) return@mapNotNull null
                    SupportListRow(row.id, number, row.name.orEmpty().ifBlank { "#${row.id}" }, row.className)
                }

            // The image download's list is the one the folder number came from; the capture list fills in the rest.
            fun merged(vararg sources: List<SupportListRow>): List<SupportListRow> =
                sources.toList().flatten().distinctBy { it.number }

            return SupportIdLookup(
                builtinServants = builtin("servant"),
                builtinCes = builtin("ce"),
                servantRows = mapOf(
                    null to merged(catalog("JP", SupportCatalogKind.Servant), captured("basic_servant", "JP", SERVANT_TYPES)),
                    "KR" to merged(catalog("KR", SupportCatalogKind.Servant), captured("basic_servant", "KR", SERVANT_TYPES)),
                    "NA" to catalog("NA", SupportCatalogKind.Servant),
                ),
                ceRows = mapOf(
                    null to merged(catalog("JP", SupportCatalogKind.CE), captured("basic_equip", "JP", setOf(CE_TYPE))),
                    "KR" to merged(catalog("KR", SupportCatalogKind.CE), captured("basic_equip", "KR", setOf(CE_TYPE))),
                    "NA" to catalog("NA", SupportCatalogKind.CE),
                ),
            )
        }
    }
}

/** The support conditions of the active filter, a profile or a battle config, read once from its preferences. */
data class SupportFilterSettings(
    val servants: Set<String> = emptySet(),
    val ces: Set<String> = emptySet(),
    val mlb: Boolean = false,
    val maxAscended: Boolean = false,
    val displayedAscensions: Set<String> = emptySet(),
    val skills: List<Boolean> = List(3) { false },
    val appendCheck: Boolean = false,
    val appends: List<Boolean> = List(5) { false },
    val npCheck: Boolean = false,
    val minimumNp: Int = 0,
    val score62: Boolean = false,
    val friendsOnly: Boolean = false,
    val grandServant: Boolean = false,
    val grandScore16: Boolean = false,
    val bondCeEffect: BondCEEffectEnum = BondCEEffectEnum.Ignore,
    val bothCeSlots: Boolean = false,
) {
    companion object {
        /**
         * The stored selection mode is not read: the filter is switched by hand and applies to manual play as much as
         * to a script (#695), so a config copied in with another mode still filters by its conditions.
         */
        fun of(core: SupportPrefsCore) = SupportFilterSettings(
            servants = core.preferredServants.get(),
            ces = core.preferredCEs.get(),
            mlb = core.mlb.get(),
            maxAscended = core.maxAscended.get(),
            displayedAscensions = core.displayedAscensions.get(),
            skills = listOf(core.skill1Max.get(), core.skill2Max.get(), core.skill3Max.get()),
            appendCheck = core.appendSkillCheck.get(),
            appends = core.appendSkillsMax.map { it.get() },
            npCheck = core.npLevelCheck.get(),
            minimumNp = core.minimumNpLevel.get(),
            score62 = core.score62Check.get(),
            friendsOnly = core.friendsOnly.get(),
            grandServant = core.grandServant.get(),
            grandScore16 = core.grandScore16Check.get(),
            bondCeEffect = core.bondCEEffect.get(),
            bothCeSlots = core.requireBothNormalAndRewardMatch.get(),
        )

        /** Emits once at once and again whenever a value [of] reads changes. */
        fun changes(core: SupportPrefsCore): Flow<Unit> = merge(
            core.preferredServants.asFlow(), core.preferredCEs.asFlow(), core.mlb.asFlow(),
            core.maxAscended.asFlow(), core.displayedAscensions.asFlow(), core.skill1Max.asFlow(), core.skill2Max.asFlow(),
            core.skill3Max.asFlow(), core.appendSkillCheck.asFlow(), *core.appendSkillsMax.map { it.asFlow() }.toTypedArray(),
            core.npLevelCheck.asFlow(), core.minimumNpLevel.asFlow(), core.score62Check.asFlow(), core.friendsOnly.asFlow(),
            core.grandServant.asFlow(), core.grandScore16Check.asFlow(), core.bondCEEffect.asFlow(),
            core.requireBothNormalAndRewardMatch.asFlow(),
        ).map { }.onStart { emit(Unit) }
    }
}

/** What a filter's settings give the proxy. */
sealed interface SupportFilterBuild {
    /** The feature switch or the toggle is off, or the conditions could not be read. */
    data object Off : SupportFilterBuild

    data class Ready(val criteria: SupportListCriteria) : SupportFilterBuild

    /**
     * A chosen servant or CE has no known id. The filter does not run: it would hide exactly the followers the user
     * wants (#685).
     */
    data class Unmapped(val servants: List<String>, val ces: List<String>) : SupportFilterBuild

    /** The class score condition is on, but no servant list is on disk to tell a servant's class, so the filter does not run. */
    data object NoClassList : SupportFilterBuild

    /**
     * The class score condition is on and these chosen servants have no known class id, so the filter does not run:
     * it would hide every support of theirs (owner decision, #685).
     */
    data class UnknownClass(val servants: List<String>) : SupportFilterBuild

    /** What the proxy applies: null unless the criteria are ready and set at least one condition. */
    val applied: SupportListCriteria? get() = (this as? Ready)?.criteria?.takeIf { it.hasConditions }
}

/**
 * The criteria for [settings]. Conditions the script applies only together with a preferred servant (final ascension,
 * skills, appends) are applied by the filter on the same terms, so the filter never hides a support the script would
 * take. NP level and the class score are applied as stored; the support screen shows them only with a preferred servant
 * and clears them when the last one is removed. The grand conditions (#704) apply on the script's terms too: "grand
 * servant" only with a preferred servant, "+16" only with it, and the bond CE effect and "both CE slots" only with it and
 * a preferred CE. Friend names, NP images and the class tab are left to the script's screen reading.
 */
fun buildSupportFilter(settings: SupportFilterSettings, lookup: SupportIdLookup): SupportFilterBuild {
    val servantIds = settings.servants.associateWith(lookup::servantId)
    val ceIds = settings.ces.associateWith(lookup::ceId)
    val unmappedServants = servantIds.filterValues { it == null }.keys.sorted()
    val unmappedCes = ceIds.filterValues { it == null }.keys.sorted()
    if (unmappedServants.isNotEmpty() || unmappedCes.isNotEmpty()) {
        return SupportFilterBuild.Unmapped(unmappedServants, unmappedCes)
    }
    val hasServants = servantIds.isNotEmpty()
    val grand = settings.grandServant && hasServants
    val grandScore16 = grand && settings.grandScore16
    val grandCes = grand && ceIds.isNotEmpty()
    // "+16" reads the servant's class like "+62" and stops the filter on the same terms.
    val needsClass = settings.score62 || grandScore16
    if (needsClass && lookup.servantClassIds.isEmpty()) return SupportFilterBuild.NoClassList
    if (needsClass) {
        val unknown = servantIds.filterValues { it !in lookup.servantClassIds }.keys.sorted()
        if (unknown.isNotEmpty()) return SupportFilterBuild.UnknownClass(unknown)
    }
    val ascensions = HashMap<Long, MutableSet<Int>>()
    for (stored in settings.displayedAscensions) {
        val (token, servant) = DisplayedAscension.parse(stored) ?: continue
        val id = servantIds[servant] ?: continue
        val shown = DisplayedAscension.dispLimitCount(token) ?: continue
        ascensions.getOrPut(id) { mutableSetOf() } += shown
    }
    val ces = ceIds.values.filterNotNull().toSet()
    return SupportFilterBuild.Ready(
        SupportListCriteria(
            servantIds = servantIds.values.filterNotNull().toSet(),
            ceIds = ces,
            ceMlb = settings.mlb && ces.isNotEmpty(),
            maxAscended = settings.maxAscended && hasServants,
            displayedAscensions = ascensions,
            skillsMax = if (hasServants) settings.skills else List(3) { false },
            appendsMax = if (hasServants && settings.appendCheck) settings.appends else List(5) { false },
            minimumNpLevel = if (settings.npCheck && settings.minimumNp > 1) settings.minimumNp else 0,
            classScore62 = settings.score62,
            servantClassIds = if (needsClass) lookup.servantClassIds else emptyMap(),
            friendsOnly = settings.friendsOnly,
            grandServant = grand,
            grandScore16 = grandScore16,
            bondCeEffect = if (grandCes) settings.bondCeEffect else BondCEEffectEnum.Ignore,
            bothCeSlots = grandCes && settings.bothCeSlots,
        )
    )
}
