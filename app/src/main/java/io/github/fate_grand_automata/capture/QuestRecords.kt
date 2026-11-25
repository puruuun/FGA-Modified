package io.github.fate_grand_automata.capture

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeToSequence
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

/** [complete] is false when the stored text stopped being readable part way; the rows before that point are kept. */
internal data class UserQuestRead(val rows: List<UserQuestRow>, val complete: Boolean)

private val questJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
}

/**
 * Decodes the stored table one row at a time: the table's text is about 0.9 MB for 6,600 quests, and a JsonElement
 * tree of it would cost about ten times that (issue #258), so no tree is built.
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun readUserQuestRows(stream: InputStream): Sequence<UserQuestRow> =
    questJson.decodeToSequence<UserQuestRow>(stream, DecodeSequenceMode.ARRAY_WRAPPED)

internal fun readUserQuest(raw: String): UserQuestRead = raw.byteInputStream().use(::readUserQuest)

internal fun readUserQuest(stream: InputStream): UserQuestRead {
    val rows = ArrayList<UserQuestRow>()
    val iterator = readUserQuestRows(stream).iterator()
    while (true) {
        val row = try {
            if (!iterator.hasNext()) return UserQuestRead(rows, complete = true)
            iterator.next()
        } catch (_: SerializationException) {
            return UserQuestRead(rows, complete = false)
        } catch (_: IllegalArgumentException) {
            return UserQuestRead(rows, complete = false)
        }
        if (row.questId > 0) rows.add(row)
    }
}

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
internal fun questRecords(read: UserQuestRead, wars: Collection<QuestWarSummary>): List<QuestRecord> {
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

/**
 * [Ready]: names from the account's own server list. [Loading]: the list is being fetched. [Waiting]: there is no cached
 * list and a script is running, so nothing is fetched until it ends. [Missing]: the fetch failed; numbers are shown.
 */
enum class QuestNameStatus { Ready, Loading, Waiting, Missing }

/**
 * [records] is null while the stored table is read. [paused] is true when the account changed while a script was
 * running: the list is not rebuilt then and shows the last result. [damaged] is true when the stored table could
 * only be read in part.
 */
data class QuestRecordsState(
    val records: List<QuestRecord>? = null,
    val names: QuestNameStatus = QuestNameStatus.Loading,
    val paused: Boolean = false,
    val damaged: Boolean = false,
)

/** What a rebuild depends on: the account's revision (it changes after every battle) and its server. */
internal data class QuestRecordsKey(val revision: Long?, val server: String)

/**
 * The records of one account, built only while the block collects this flow and dropped with it. They are not part of
 * the dashboard projection, which is rebuilt after every battle. While a script runs nothing is rebuilt and nothing is
 * downloaded; the first build when the block opens still reads the stored table, without a download.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class QuestRecordsLoader(
    private val readTable: suspend (userId: String) -> String?,
    /** The `nice_war` summary of [server]; empty when it is not cached and cannot be fetched. */
    private val questWars: suspend (server: String, allowDownload: Boolean, force: Boolean) -> List<QuestWarSummary>,
    private val scriptRunning: Flow<Boolean>,
    private val isScriptRunning: () -> Boolean,
) {
    fun load(userId: String, keys: Flow<QuestRecordsKey>, retries: Flow<Int>): Flow<QuestRecordsState> {
        var last: QuestRecordsState? = null
        var builtKey: QuestRecordsKey? = null
        var read: UserQuestRead? = null
        var lastRetry: Int? = null
        return combine(keys.distinctUntilChanged(), scriptRunning.distinctUntilChanged(), retries) { key, running, retry -> Triple(key, running, retry) }
            .transformLatest { (key, running, retry) ->
                val previous = last
                if (previous != null && running) {
                    val paused = previous.copy(paused = key != builtKey)
                    if (paused != previous) { last = paused; emit(paused) }
                    return@transformLatest
                }
                val rebuild = previous == null || key != builtKey || read == null
                val retried = lastRetry != null && retry != lastRetry
                lastRetry = retry
                if (!rebuild && !retried && previous.names == QuestNameStatus.Ready) {
                    if (previous.paused) previous.copy(paused = false).also { last = it; emit(it) }
                    return@transformLatest
                }
                if (rebuild) {
                    if (previous == null) emit(QuestRecordsState())
                    val raw = readTable(userId)
                    read = withContext(Dispatchers.Default) { raw?.let(::readUserQuest) ?: UserQuestRead(emptyList(), complete = true) }
                    builtKey = key
                }
                val rows = checkNotNull(read)
                val cached = questWars(key.server, false, false)
                var state = QuestRecordsState(
                    records = withContext(Dispatchers.Default) { questRecords(rows, cached) },
                    names = if (cached.isNotEmpty()) QuestNameStatus.Ready else QuestNameStatus.Loading,
                    damaged = !rows.complete,
                )
                if (isScriptRunning()) {
                    if (cached.isEmpty()) state = state.copy(names = QuestNameStatus.Waiting)
                    last = state
                    emit(state)
                    return@transformLatest
                }
                last = state
                emit(state)
                val fetched = questWars(key.server, true, retried)
                val full = if (fetched.isEmpty()) {
                    state.copy(names = if (cached.isEmpty()) QuestNameStatus.Missing else QuestNameStatus.Ready)
                } else {
                    state.copy(records = withContext(Dispatchers.Default) { questRecords(rows, fetched) }, names = QuestNameStatus.Ready)
                }
                last = full
                if (full != state) emit(full)
            }
    }
}
