package io.github.fate_grand_automata.scripts.events

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.InputStream

@Serializable
data class GameEvent(val id: Long, val type: String, val name: String, val startedAt: Long, val endedAt: Long)

@Serializable
enum class BonusKind { Drop, Point, Bond, Encounter, Other }

@Serializable
data class BonusEquip(val id: Long, val kinds: Set<BonusKind>)

data class RunningEvents(val limited: List<GameEvent> = emptyList(), val campaigns: List<GameEvent> = emptyList()) {
    val visible get() = limited.isNotEmpty() || campaigns.isNotEmpty()
}

enum class RemainingUnit { Days, Hours }

fun remainingParts(seconds: Long): Pair<RemainingUnit, Long> =
    if (seconds >= 86_400) RemainingUnit.Days to seconds / 86_400
    else RemainingUnit.Hours to (seconds / 3600).coerceAtLeast(1)

/** Public Atlas KR data only. Each export row is decoded separately, including the large CE export. */
object GameEvents {
    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_DURATION = 180L * 86_400

    @OptIn(ExperimentalSerializationApi::class)
    fun parse(input: InputStream): List<GameEvent> = json.decodeToSequence<JsonElement>(input).mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val id = row.number("id")?.takeIf { it > 0 } ?: return@mapNotNull null
        val type = row.text("type") ?: return@mapNotNull null
        val name = row.text("name")?.replace(Regex("[\\r\\n]+\\s*"), " ")?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val start = row.number("startedAt")?.takeIf { it >= 0 } ?: return@mapNotNull null
        val end = row.number("endedAt")?.takeIf { it >= start } ?: return@mapNotNull null
        GameEvent(id, type, name, start, end)
    }.toList()

    fun running(events: List<GameEvent>, now: Long): RunningEvents {
        val active = events.filter {
            it.startedAt <= now && now <= it.endedAt && it.endedAt - it.startedAt in 0..MAX_DURATION
        }.sortedWith(compareBy<GameEvent> { it.endedAt }.thenBy { it.id })
        return RunningEvents(active.filter { it.type == "eventQuest" || it.type == "warBoard" },
            active.filter { it.type.endsWith("Campaign") })
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun bonusMap(input: InputStream): Map<Long, List<BonusEquip>> {
        val events = mutableMapOf<Long, MutableMap<Long, MutableSet<BonusKind>>>()
        json.decodeToSequence<JsonElement>(input).forEach { element ->
            val row = element as? JsonObject ?: return@forEach
            val id = row.number("id")?.takeIf { it > 0 } ?: return@forEach
            row.objects("skills").flatMap { it.objects("functions") }.forEach functionLoop@ { function ->
                val kind = kind(function.text("funcType") ?: return@functionLoop)
                function.objects("svals").mapNotNull { it.number("EventId")?.takeIf { event -> event > 0 } }
                    .forEach { event -> events.getOrPut(event) { mutableMapOf() }.getOrPut(id) { mutableSetOf() }.add(kind) }
            }
        }
        return events.mapValues { (_, equips) -> equips.entries.sortedBy { it.key }.map { BonusEquip(it.key, it.value) } }
    }

    private fun kind(type: String?) = when (type) {
        "eventDropUp", "eventDropRateUp", "classDropUp" -> BonusKind.Drop
        "eventPointUp", "eventFortificationPointUp" -> BonusKind.Point
        "servantFriendshipUp" -> BonusKind.Bond
        "enemyEncountRateUp", "enemyEncountCopyRateUp" -> BonusKind.Encounter
        else -> BonusKind.Other
    }

    private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.objects(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
}
