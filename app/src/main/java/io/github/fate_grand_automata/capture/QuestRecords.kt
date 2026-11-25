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
    val quests: List<QuestNameSummary> = emptyList(),
)

/** [lv] is Atlas's `recommendLv` text ("90+", "90++", "80", ...). */
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

/** Single choice. [All] and every chip but [Story] leave story quests out (owner, #730). */
enum class QuestFilter { All, Free, Event, HighLevel, InterludeRankUp, Daily, Raid, Story }
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
    /** "90+" or "90++"; other recommended levels are not shown. */
    val level: String?,
    val clears: Long,
    val challenges: Long,
    /** Epoch seconds of the last start; null when the row has none. */
    val lastStartedAt: Long?,
    val kinds: Set<QuestFilter>,
    val story: Boolean,
    internal val searchText: String,
)

data class QuestView(val totalClears: Long, val clearedQuests: Int, val rows: List<QuestRecord>)

/** Atlas groups these in their own wars, not by quest type (they are `event` quests in war 1001 and 1002). */
internal const val RANK_UP_WAR = 1001L
internal const val DAILY_WAR = 1002L
internal const val INTERLUDE_WAR = 1003L
private val HIGH_LEVELS = setOf("90+", "90++")

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
                if (quest.type == "free") add(QuestFilter.Free)
                if (war.eventId != 0L) add(QuestFilter.Event)
                if (quest.lv in HIGH_LEVELS) add(QuestFilter.HighLevel)
                if (war.id == RANK_UP_WAR || war.id == INTERLUDE_WAR) add(QuestFilter.InterludeRankUp)
                if (war.id == DAILY_WAR) add(QuestFilter.Daily)
                if (quest.raid) add(QuestFilter.Raid)
            }
        }
        val story = quest != null && (quest.type == "main" || quest.noBattle)
        val search = listOfNotNull("#${row.questId}", quest?.name, quest?.spot, war?.name, war?.longName, war?.eventName)
            .joinToString("\u0000").lowercase(Locale.ROOT)
        QuestRecord(
            id = row.questId,
            name = quest?.name?.takeIf { it.isNotBlank() },
            war = warLabel,
            spot = quest?.spot?.takeIf { it.isNotBlank() },
            level = quest?.lv?.takeIf { it in HIGH_LEVELS },
            clears = row.clearNum,
            challenges = row.challengeNum,
            lastStartedAt = row.lastStartedAt.takeIf { it > 0 },
            kinds = kinds,
            story = story,
            searchText = search,
        )
    }
}

internal fun questView(records: List<QuestRecord>, filter: QuestFilter, query: String, sort: QuestSort): QuestView {
    val needle = query.trim().lowercase(Locale.ROOT)
    val shown = records.filter { record ->
        val inFilter = when (filter) {
            QuestFilter.Story -> record.story
            QuestFilter.All -> !record.story
            else -> !record.story && filter in record.kinds
        }
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
