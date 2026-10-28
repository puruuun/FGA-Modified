package io.github.fate_grand_automata.scripts.weekly

import java.io.InputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

/** Strict adapter for official Atlas JSON. Unknown condition combinations stay manual. */
object AtlasWeekly {
    const val CHALDEA_GATE_DAILY_WAR = 1002
    val TREASURE_VAULT_QUESTS = setOf(94061636)
    private fun JsonObject.text(k: String) = this[k]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.number(k: String) = this[k]?.jsonPrimitive?.intOrNull ?: 0
    private fun JsonObject.array(k: String) = this[k] as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.ids(k: String) = array(k).mapNotNull { (it as? JsonPrimitive)?.intOrNull }.toSet()
    private fun JsonObject.traits(k: String) = array(k).map { it.jsonObject.number("id") }.toSet()
    data class Week(val id: Int, val start: Long, val end: Long, val missions: List<RouteMission>)

    fun activeWeek(json: String, now: Long): Week {
        val sets = Json.parseToJsonElement(json).jsonArray.map { it.jsonObject }.filter { set ->
            val start = set["startedAt"]!!.jsonPrimitive.long
            val end = set["endedAt"]!!.jsonPrimitive.long
            now in start until end && set.array("missions").any { it.jsonObject.text("type") == "weekly" }
        }
        require(sets.size == 1) { "no unique active weekly mission set" }
        val set = sets.single()
        return Week(set.number("id"),set["startedAt"]!!.jsonPrimitive.long,set["endedAt"]!!.jsonPrimitive.long,
            set.array("missions").map { mission(it.jsonObject) })
    }

    private fun mission(m: JsonObject): RouteMission {
        val clear = m.array("conds").map { it.jsonObject }.filter { it.text("missionProgressType") == "clear" }
        val c = clear.singleOrNull()
        val d = c?.array("details")?.singleOrNull()?.jsonObject
        val plain = c?.text("condType") == "questClear"
        val simple = d != null && d.number("logicType") == 1 && d.array("addTargetIds").isEmpty() &&
            d.array("targetEventIds").isEmpty() && d.text("conditionLinkType") == "masterMissionStart"
        val kind = when {
            plain -> MissionKind.QuestClear
            !simple || c?.text("condType") != "missionConditionDetail" -> MissionKind.Unsupported
            else -> when(d!!.number("missionCondType")) {
                1 -> MissionKind.EnemyId
                2 -> MissionKind.EnemyTraitAny
                13 -> MissionKind.EnemyTraitAll
                14 -> MissionKind.EnemyClass
                15 -> MissionKind.ServantClass
                24, 31 -> MissionKind.QuestClear
                else -> MissionKind.Unsupported
            }
        }
        return RouteMission(m.number("id"), m.text("name"), c?.number("targetNum") ?: 1, kind,
            (if (plain) c else d)?.ids("targetIds").orEmpty(),
            questTraits = d?.traits("targetQuestIndividualities").orEmpty(),
            meta = c?.text("condType") == "eventMissionClear")
    }

    /** Only main-record 1 / 1.5 / 2, not events, daily gates, strengthening, or part 3. */
    fun mainStoryWar(id: Int) = id in 100..108 || id in 201..204 || id in 300..407

    // Typed decoding of the war export: it is about 25 MB, so no JSON tree is built for it.
    @Serializable private class WarDto(
        val id: Int = 0, val eventId: Int = 0, val priority: Int = 0, val longName: String = "", val banner: String = "",
        val maps: List<MapDto> = emptyList(), val spots: List<SpotDto> = emptyList()
    )
    @Serializable private class MapDto(val id: Int = 0, val mapImage: String = "", val mapImageW: Int = 0, val mapImageH: Int = 0)
    @Serializable private class SpotDto(
        val id: Int = 0, val name: String = "", val x: Double = 0.0, val y: Double = 0.0, val mapId: Int = 0,
        val quests: List<QuestDto> = emptyList()
    )
    @Serializable private class QuestDto(
        val id: Int = 0, val name: String = "", val type: String = "", val consumeType: String = "",
        val consume: Int = 0, val afterClear: String = "", val openedAt: Long = 0, val closedAt: Long = Long.MAX_VALUE,
        val phases: List<Int> = emptyList()
    )
    private val lenient = Json { ignoreUnknownKeys = true }

    fun quests(json: String, now: Long, log: (String) -> Unit = {}): List<RouteQuest> = select(lenient.decodeFromString<List<WarDto>>(json), now, log)
    @OptIn(ExperimentalSerializationApi::class)
    fun quests(stream: InputStream, now: Long, log: (String) -> Unit = {}): List<RouteQuest> = select(lenient.decodeFromStream<List<WarDto>>(stream), now, log)

    /** The route quests and the chapter data of their wars, read from the same war export. */
    class Wars(val quests: List<RouteQuest>, val wars: Map<Int, WarInfo>)

    fun wars(json: String, now: Long, log: (String) -> Unit = {}): Wars = withWars(lenient.decodeFromString<List<WarDto>>(json), now, log)
    @OptIn(ExperimentalSerializationApi::class)
    fun wars(stream: InputStream, now: Long, log: (String) -> Unit = {}): Wars = withWars(lenient.decodeFromStream<List<WarDto>>(stream), now, log)

    private fun withWars(wars: List<WarDto>, now: Long, log: (String) -> Unit): Wars {
        val quests = select(wars, now, log)
        val used = quests.map { it.warId }.toSet()
        return Wars(quests, wars.filter { it.id in used }.associate { w ->
            w.id to WarInfo(w.id, w.longName, w.banner, w.maps.map { WarMap(it.id, it.mapImage, it.mapImageW, it.mapImageH) }, w.spots.associate { it.id to it.name },
                w.spots.mapNotNull { spot -> spot.quests.filter { it.type == "free" }.map { it.name }.takeIf { it.isNotEmpty() }?.let { spot.id to it } }.toMap())
        })
    }

    /** Repeatable main-story free quests: AP cost, still open, and not closed by an `afterClear` such as Fuyuki's danger zone. */
    private fun select(wars: List<WarDto>, now: Long, log: (String) -> Unit): List<RouteQuest> = wars.flatMap { w ->
        val daily = w.id == CHALDEA_GATE_DAILY_WAR
        if (!daily && (!mainStoryWar(w.id) || w.eventId != 0)) emptyList() else
            w.spots.flatMap { spot -> spot.quests.mapNotNull { q ->
                val eligible = if (daily) q.id in TREASURE_VAULT_QUESTS else q.type == "free"
                if (daily && eligible && !q.name.contains("宝物庫") && !q.name.contains("보물창고")) {
                    log("drop quest=${q.id} reason=vaultNameMismatch name=${q.name}")
                    null
                } else if (!eligible || q.consumeType != "ap" || q.afterClear != "repeatLast" || now < q.openedAt || now >= q.closedAt) null
                else RouteQuest(q.id, w.id, w.priority, w.longName, spot.name, q.name, q.consume, q.type, q.afterClear,
                    enemyDataAvailable = false, repeatPhase = q.phases.maxOrNull() ?: 0,
                    spotId = spot.id, spotX = spot.x, spotY = spot.y, mapId = spot.mapId,
                    spotFreeQuests = spot.quests.count { it.type == "free" })
            } }
    }.distinctBy { it.id }

    fun phase(quest: RouteQuest, json: String): RouteQuest {
        val q = Json.parseToJsonElement(json).jsonObject
        require(q.number("id") == quest.id && q.number("warId") == quest.warId &&
            (q["consume"] == null || q.number("consume") == quest.ap)) { "phase identity mismatch" }
        val enemies = q.array("stages").flatMap { stage -> stage.jsonObject.array("enemies").mapNotNull { raw ->
            val e = raw.jsonObject
            if (e.text("deck") != "enemy") null else {
                val svt = e["svt"]!!.jsonObject
                RouteEnemy(svt.number("id"),svt.number("classId"),e.traits("traits"))
            }
        } }
        return quest.copy(traits=q.traits("individuality") + q.traits("phaseIndividuality"),enemies=enemies,
            enemyDataAvailable=enemies.isNotEmpty())
    }
}
