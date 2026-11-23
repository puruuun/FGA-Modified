package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Local diagnostics of one `follower/list` response (issue #669): its shape (types and lengths per key path) and small
 * value histograms, so the support list filter can be designed from a real KR or JP response.
 *
 * Privacy rests on the output being assembled from an allow-list: a value reaches the output only as a count under a key
 * that this file names, or under a number below [SMALL] of a field that is on a list here. Nothing is copied from the
 * response and removed afterwards. Strings of the response never appear; key names appear only when they look like
 * identifiers and not like a person's data.
 */
object SupportListShape {
    private const val SMALL = 1000L
    private const val MAX_VALUES = 40
    private const val MAX_NODES = 3000
    private const val MAX_FIELDS = 1500
    private const val MAX_DEPTH = 24

    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]{0,47}")

    /** Object keys under a slot whose small numbers are ids or personal data and so never get a histogram. */
    private val DENIED_KEY_PARTS = listOf(
        "userid", "svtid", "skillid", "tdid", "npid", "graph", "name", "message", "code", "friend", "token", "time", "date",
    )

    private val SLOT_HASHES = listOf("userSvtLeaderHash", "eventUserSvtLeaderHash", "userSvtGrandHash")

    /** Slot fields whose small values are written as a histogram. */
    private val SLOT_NUMBERS = listOf(
        "classId", "limitCount", "dispLimitCount", "imageLimitCount", "commandCardLimitCount", "iconLimitCount",
        "portraitLimitCount", "lv", "skillLv1", "skillLv2", "skillLv3", "treasureDeviceLv", "exceedCount", "grandSvt",
        "battleVoice", "eventSvtPoint", "transformVal",
    )
    private val EQUIP_TARGETS = listOf("equipTarget1", "equipTarget2", "equipTarget3")
    private val SLOT_LENGTHS = listOf("commandCode", "classPassive")

    /** Own handling above; the generic "numbers and booleans in an object" pass must leave these alone. */
    private val SLOT_HANDLED_OBJECTS = EQUIP_TARGETS.toSet() + "afterTransformSvtInfo"

    private fun safeKey(key: String) =
        IDENTIFIER.matches(key) && DENIED_KEY_PARTS.none { key.lowercase().contains(it) }

    /** A diagnostics object for [root], or null when it is not a support list response. Never throws. */
    fun build(root: JsonObject, server: String, time: Long): JsonObject? = try {
        buildUnsafe(root, server, time)
    } catch (_: Exception) {
        null
    } catch (_: StackOverflowError) {
        null
    }

    private class Node {
        var count = 0
        val types = sortedSetOf<String>()
        var minLen = Int.MAX_VALUE
        var maxLen = -1
    }

    private class Hist {
        private val values = HashMap<Long, Int>()
        private val labels = HashMap<String, Int>()
        private var large = 0
        var total = 0
            private set

        fun add(value: JsonElement?) {
            total++
            if (value == null || value is JsonNull) return label("<null>")
            if (value !is JsonPrimitive) return label("<structure>")
            if (value.isString) return label("<string>")
            value.booleanOrNull?.let { return label(it.toString()) }
            val number = value.longOrNull ?: return label("<number>")
            if (number > -SMALL && number < SMALL) values.merge(number, 1, Int::plus) else large++
        }

        fun addNumber(value: Int) = add(JsonPrimitive(value))
        fun addBoolean(value: Boolean) = add(JsonPrimitive(value))

        private fun label(name: String) {
            labels.merge(name, 1, Int::plus)
        }

        fun toJson() = buildJsonObject {
            put("n", total)
            if (values.size + labels.size <= MAX_VALUES) {
                putJsonObject("values") {
                    values.toSortedMap().forEach { (v, c) -> put(v.toString(), c) }
                    labels.toSortedMap().forEach { (v, c) -> put(v, c) }
                }
            } else {
                values.keys.minOrNull()?.let { put("min", it) }
                values.keys.maxOrNull()?.let { put("max", it) }
                put("distinct", values.size)
                put("other", labels.values.sum())
            }
            if (large > 0) put("large", large)
        }
    }

    private class Collector {
        val nodes = HashMap<String, Node>()
        var nodesDropped = 0
        val histograms = HashMap<String, HashMap<String, Hist>>()
        var fieldsDropped = 0
        var followers = 0
        val filled = HashMap<String, Int>()

        fun hist(group: String, field: String): Hist? {
            val map = histograms.getOrPut(group) { HashMap() }
            map[field]?.let { return it }
            if (map.size >= MAX_FIELDS) {
                fieldsDropped++
                return null
            }
            return Hist().also { map[field] = it }
        }

        fun add(group: String, field: String, value: JsonElement?) = hist(group, field)?.add(value)
        fun addLength(group: String, field: String, length: Int) = hist(group, field)?.addNumber(length)
        fun addFlag(group: String, field: String, flag: Boolean) = hist(group, field)?.addBoolean(flag)

        fun node(path: String): Node? {
            nodes[path]?.let { return it }
            if (nodes.size >= MAX_NODES) {
                nodesDropped++
                return null
            }
            return Node().also { nodes[path] = it }
        }
    }

    private fun isMap(obj: JsonObject) = obj.isNotEmpty() && obj.keys.all { key -> key.isNotEmpty() && key.all { it in '0'..'9' } }

    private fun keySegment(key: String) = if (IDENTIFIER.matches(key)) key else "{?}"

    /** One pass; array elements and id-keyed map values are merged into one node per path. */
    private fun walk(element: JsonElement, path: String, depth: Int, c: Collector) {
        val node = c.node(path) ?: return
        node.count++
        when (element) {
            is JsonNull -> node.types += "null"
            is JsonPrimitive -> node.types += when {
                element.isString -> "string"
                element.booleanOrNull != null -> "boolean"
                element.longOrNull != null -> "int"
                else -> "number"
            }
            is JsonArray -> {
                node.types += "array"
                node.minLen = minOf(node.minLen, element.size)
                node.maxLen = maxOf(node.maxLen, element.size)
                if (depth < MAX_DEPTH) element.forEach { walk(it, "$path[]", depth + 1, c) }
            }
            is JsonObject -> {
                if (isMap(element)) {
                    node.types += "map"
                    node.minLen = minOf(node.minLen, element.size)
                    node.maxLen = maxOf(node.maxLen, element.size)
                    if (depth < MAX_DEPTH) element.values.forEach { walk(it, "$path{*}", depth + 1, c) }
                } else {
                    node.types += "object"
                    if (depth < MAX_DEPTH) element.forEach { (key, value) -> walk(value, "$path.${keySegment(key)}", depth + 1, c) }
                }
            }
        }
    }

    /** The elements of an array, or the values of an id-keyed map; anything else has none. */
    private fun members(value: JsonElement?): List<JsonElement> = when (value) {
        is JsonArray -> value
        is JsonObject -> value.values.toList()
        else -> emptyList()
    }

    private fun buildUnsafe(root: JsonObject, server: String, time: Long): JsonObject? {
        val cache = root["cache"] as? JsonObject ?: return null
        val updated = cache["updated"] as? JsonObject ?: return null
        val userFollower = updated["userFollower"] as? JsonArray ?: return null
        val c = Collector()
        walk(root, "$", 0, c)
        val infos = userFollower.flatMap { entry ->
            (((entry as? JsonObject)?.get("followerInfo")) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        }
        c.followers = infos.size
        infos.forEach { follower(it, c) }
        return buildJsonObject {
            put("server", server)
            put("time", time)
            putJsonArray("topKeys") { root.keys.filter(IDENTIFIER::matches).sorted().forEach { add(JsonPrimitive(it)) } }
            putJsonArray("cacheKeys") { cache.keys.filter(IDENTIFIER::matches).sorted().forEach { add(JsonPrimitive(it)) } }
            putJsonObject("counts") {
                put("followerInfo", c.followers)
                putJsonObject("filledSlots") { SLOT_HASHES.forEach { put(it, c.filled[it] ?: 0) } }
            }
            putJsonObject("shape") {
                c.nodes.toSortedMap().forEach { (path, node) ->
                    putJsonObject(path) {
                        put("n", node.count)
                        putJsonArray("types") { node.types.forEach { add(JsonPrimitive(it)) } }
                        if (node.maxLen >= 0) putJsonObject("len") {
                            put("min", node.minLen)
                            put("max", node.maxLen)
                        }
                    }
                }
            }
            putJsonObject("histograms") {
                c.histograms.toSortedMap().forEach { (group, fields) ->
                    putJsonObject(group) { fields.toSortedMap().forEach { (field, hist) -> put(field, hist.toJson()) } }
                }
            }
            if (c.nodesDropped > 0 || c.fieldsDropped > 0) {
                putJsonObject("truncated") {
                    put("nodes", c.nodesDropped)
                    put("fields", c.fieldsDropped)
                }
            }
        }
    }

    private fun follower(row: JsonObject, c: Collector) {
        c.add("follower", "type", row["type"])
        for ((key, value) in row) {
            // The slot hashes are counted below, whether they are arrays or id-keyed maps.
            if (value is JsonArray && key !in SLOT_HASHES && IDENTIFIER.matches(key)) c.addLength("follower", "$key.len", value.size)
        }
        for (key in SLOT_HASHES) {
            val hash = row[key]
            if (hash is JsonArray || (hash is JsonObject && isMap(hash))) {
                c.addLength("follower", "$key.len", members(hash).size)
            }
            for (slot in members(hash)) (slot as? JsonObject)?.let { slot(key, it, c) }
        }
        classBoards(row["userClassBoardInfo"], c)
    }

    private fun slot(group: String, slot: JsonObject, c: Collector) {
        val svtId = (slot["svtId"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        if (svtId != null) {
            c.addFlag(group, "svtId.isZero", svtId == 0L)
            if (svtId > 0L) c.filled.merge(group, 1, Int::plus)
        }
        SLOT_NUMBERS.forEach { if (it in slot) c.add(group, it, slot[it]) }
        if ("afterTransformSvtInfo" in slot) c.addFlag(group, "afterTransformSvtInfo.isNull", slot["afterTransformSvtInfo"] is JsonNull)
        (slot["appendPassiveSkill"] as? JsonArray)?.let { append ->
            c.addLength(group, "appendPassiveSkill.len", append.size)
            append.forEach { entry -> (entry as? JsonObject)?.get("skillLv")?.let { c.add(group, "appendPassiveSkill[].skillLv", it) } }
        }
        SLOT_LENGTHS.forEach { key -> (slot[key] as? JsonArray)?.let { c.addLength(group, "$key.len", it.size) } }
        for (key in EQUIP_TARGETS) {
            when (val target = slot[key]) {
                is JsonNull -> c.addFlag(group, "$key.isNull", true)
                is JsonObject -> {
                    c.addFlag(group, "$key.isNull", false)
                    val id = (target["svtId"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
                    if (id != null) c.addFlag(group, "$key.svtId.isZero", id == 0L)
                    listOf("limitCount", "lv").forEach { if (it in target) c.add(group, "$key.$it", target[it]) }
                }
                else -> {}
            }
        }
        for ((name, value) in slot) {
            if (value !is JsonObject || name in SLOT_HANDLED_OBJECTS || !safeKey(name)) continue
            for ((key, inner) in value) {
                if (!safeKey(key)) continue
                when {
                    inner is JsonPrimitive && !inner.isString && inner !is JsonNull -> c.add(group, "$name.$key", inner)
                    inner is JsonArray -> {
                        c.addLength(group, "$name.$key.len", inner.size)
                        inner.forEach { e ->
                            if (e is JsonPrimitive && !e.isString && e.longOrNull != null) c.add(group, "$name.$key[]", e)
                        }
                    }
                }
            }
        }
    }

    private fun classBoards(value: JsonElement?, c: Collector) {
        val boards = value as? JsonArray ?: return
        c.addLength("classBoards", "perFollower", boards.size)
        for (board in boards.filterIsInstance<JsonObject>()) {
            val base = (board["classBoardBaseId"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            c.add("classBoards", "classBoardBaseId", board["classBoardBaseId"])
            // Past the small range the id is not a key of its own, so nothing large can name a field.
            val tag = if (base != null && base > -SMALL && base < SMALL) base.toString() else "large"
            (board["squareIds"] as? JsonArray)?.let { c.addLength("classBoards", "squareIds.len@$tag", it.size) }
            (board["releasedClassIds"] as? JsonArray)?.forEach { c.add("classBoards", "releasedClassIds@$tag", it) }
        }
    }
}
