package io.github.fate_grand_automata.capture

import kotlinx.serialization.Serializable
import java.io.InputStream
import java.util.Locale

/** One war of the public `nice_war` export, reduced to what the quest records block shows. Never holds user state. */
@Serializable
data class QuestWarSummary(
    val id: Long,
    val name: String? = null,
    /** The war's long name on one line; Atlas separates its two parts with a line break. */
    val longName: String? = null,
    val eventId: Long = 0,
    val eventName: String? = null,
    /**
     * Atlas's `parentWarId` (0 for a war without a parent). Null only in a summary cached before the field existed: that
     * cannot say which wars are Grand Duel wars, so [GameNames.questWars] rebuilds it instead of reading it as "none".
     */
    val parentWarId: Long? = null,
    val quests: List<QuestNameSummary> = emptyList(),
)

/**
 * [lv] is Atlas's `recommendLv` text ("90+", "90++", "80", ...) and [noBattle] its `noBattle` flag. The block reads
 * neither since the owner removed the level and story chips (2026-11-26); they stay so that cached summaries stay valid.
 */
@Serializable
data class QuestNameSummary(
    val id: Long,
    val name: String? = null,
    val spot: String? = null,
    val type: String? = null,
    val lv: String? = null,
    val raid: Boolean = false,
    val noBattle: Boolean = false,
)

/** The fields of a stored `userQuest` row that the block reads; every other field is skipped by the decoder. */
@Serializable
internal data class UserQuestRow(
    val questId: Long = 0,
    val questPhase: Long = 0,
    val clearNum: Long = 0,
    val challengeNum: Long = 0,
    val lastStartedAt: Long = 0,
)

/** The stored `userQuest` table, read one row at a time without a tree (see [readTableRows]). */
internal fun readUserQuestRows(stream: InputStream): Sequence<UserQuestRow> = readTableRows(stream, UserQuestRow.serializer())

internal fun readUserQuest(raw: String): TableRead<UserQuestRow> = raw.byteInputStream().use(::readUserQuest)

internal fun readUserQuest(stream: InputStream): TableRead<UserQuestRow> = readTable(stream, UserQuestRow.serializer()) { it.questId > 0 }

/** Single choice. [All] lists every quest, story quests included (owner, 2026-11-26). */
enum class QuestFilter { All, Free, Event, Daily, Raid, GrandDuel }
enum class QuestSort { Clears, LastRun, Name }

/**
 * One quest of the account. [name], [war] and [spot] are null when the account's own server list does not know the
 * quest; names are never taken from the other server's list (owner, #730).
 */
data class QuestRecord(
    val id: Long,
    val name: String?,
    val war: String?,
    val spot: String?,
    val clears: Long,
    val challenges: Long,
    /** Epoch seconds of the last start; null when the row has none. */
    val lastStartedAt: Long?,
    val kinds: Set<QuestFilter>,
    internal val searchText: String,
)

data class QuestView(val totalClears: Long, val clearedQuests: Int, val rows: List<QuestRecord>)

/** Atlas groups the daily quests in their own war, not by quest type (they are `event` quests in war 1002). */
internal const val DAILY_WAR = 1002L

/**
 * JP's 冠位戴冠戦 (Grand Duel) is a war without quests of its own whose child wars (one per class, the extra wars and the
 * 冠位名代戦) name it as `parentWarId`. Owner decision (2026-11-26): a quest of such a war shows under this chip only, and
 * under All; never under Free, Event, Daily or Raid, even when its war is an event war or the quest has the raid flag
 * (the 冠位名代戦 does). KR's list has no such war yet, so the chip is empty there.
 */
internal const val GRAND_DUEL_WAR = 8395L

/**
 * One record per quest: the game keeps one `userQuest` row per quest, with `questPhase` as the furthest phase reached and
 * `clearNum` counting clears of the whole quest. A second row for the same quest (not seen) is not added up, which would
 * count the clears twice; the row with the furthest phase, then the most clears, is kept.
 */
internal fun questRecords(read: TableRead<UserQuestRow>, wars: Collection<QuestWarSummary>): List<QuestRecord> {
    val byId = HashMap<Long, UserQuestRow>(read.rows.size * 2)
    for (row in read.rows) {
        val old = byId[row.questId]
        if (old == null || row.questPhase > old.questPhase || (row.questPhase == old.questPhase && row.clearNum > old.clearNum)) {
            byId[row.questId] = row
        }
    }
    class Known(val war: QuestWarSummary, val quest: QuestNameSummary)
    val known = HashMap<Long, Known>(byId.size * 2)
    for (war in wars) for (quest in war.quests) {
        if (quest.id in byId && quest.id !in known) known[quest.id] = Known(war, quest)
    }
    return byId.values.map { row ->
        val hit = known[row.questId]
        val quest = hit?.quest
        val war = hit?.war
        val warLabel = war?.let { w -> w.eventName?.takeIf { w.eventId != 0L && it.isNotBlank() } ?: w.longName?.takeIf { it.isNotBlank() } ?: w.name }
        val kinds = buildSet {
            if (quest != null && war != null) {
                if (war.parentWarId == GRAND_DUEL_WAR) {
                    add(QuestFilter.GrandDuel)
                } else {
                    if (quest.type == "free") add(QuestFilter.Free)
                    if (war.eventId != 0L) add(QuestFilter.Event)
                    if (war.id == DAILY_WAR) add(QuestFilter.Daily)
                    if (quest.raid) add(QuestFilter.Raid)
                }
            }
        }
        val search = listOfNotNull("#${row.questId}", quest?.name, quest?.spot, war?.name, war?.longName, war?.eventName)
            .joinToString("\u0000").lowercase(Locale.ROOT)
        QuestRecord(
            id = row.questId,
            name = quest?.name?.takeIf { it.isNotBlank() },
            war = warLabel,
            spot = quest?.spot?.takeIf { it.isNotBlank() },
            clears = row.clearNum,
            challenges = row.challengeNum,
            lastStartedAt = row.lastStartedAt.takeIf { it > 0 },
            kinds = kinds,
            searchText = search,
        )
    }
}

internal fun questView(records: List<QuestRecord>, filter: QuestFilter, query: String, sort: QuestSort): QuestView {
    val needle = query.trim().lowercase(Locale.ROOT)
    val shown = records.filter { record ->
        val inFilter = filter == QuestFilter.All || filter in record.kinds
        inFilter && (needle.isEmpty() || needle in record.searchText)
    }
    val comparator = when (sort) {
        QuestSort.Clears -> compareByDescending<QuestRecord> { it.clears }.thenByDescending { it.lastStartedAt ?: 0 }.thenBy { it.id }
        QuestSort.LastRun -> compareByDescending<QuestRecord> { it.lastStartedAt ?: 0 }.thenByDescending { it.clears }.thenBy { it.id }
        // A quest without a name sorts after the named ones, by its number.
        QuestSort.Name -> compareBy<QuestRecord> { it.name == null }.thenBy { it.name.orEmpty() }.thenBy { it.id }
    }
    return QuestView(shown.sumOf { it.clears }, shown.count { it.clears > 0 }, shown.sortedWith(comparator))
}

/** The quest records block's state: one record per quest, or null while the stored table is read. */
typealias QuestRecordsState = RecordsState<List<QuestRecord>>
