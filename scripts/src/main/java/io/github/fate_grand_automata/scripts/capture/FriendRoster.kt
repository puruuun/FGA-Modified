package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** One support servant slot of a friend. Append values are skill levels, null while locked. */
@Serializable
data class FriendSlot(
    val deckId: Long,
    val classId: Int,
    val svtId: Long,
    val lv: Int = 0,
    val np: Int = 0,
    val skills: List<Int> = emptyList(),
    val appends: List<Int?> = List(5) { null },
    val ceId: Long = 0,
    val ceLimitCount: Int = 0,
    val ceLv: Int = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class Friend(
    val userId: String,
    val name: String = "",
    val lv: Int = 0,
    val friendCode: String? = null,
    val mainDeckIds: List<Long> = emptyList(),
    val eventDeckIds: List<Long> = emptyList(),
    val lastLogin: Long = 0,
    val slots: List<FriendSlot> = emptyList(),
) {
    fun isEvent(slot: FriendSlot) = slot.deckId in eventDeckIds && slot.deckId !in mainDeckIds
}

/**
 * The friends of one account, from the owner's own friend list. Never holds profile messages.
 * Times are Unix seconds; [updatedAt] is the server time of the last friend/top response.
 */
@Serializable
data class FriendRoster(val updatedAt: Long = 0, val friends: Map<String, Friend> = emptyMap()) {
    fun toJson(): String = Json.encodeToString(this)

    companion object {
        private val APPEND_SLOTS = mapOf(3001000L to 1, 3002000L to 2, 3033000L to 4, 3034000L to 5)

        fun fromJson(value: String): FriendRoster = Json.decodeFromString(value)

        /** Append slot 1..5 of a passive skill id; class attack-up skills are all slot 3. */
        fun appendSlot(skillId: Long): Int = APPEND_SLOTS[skillId] ?: 3

        /**
         * Applies one game response. Anything unreadable leaves [previous] as it is, because a roster must
         * never be half replaced. Support lists only update a roster that friend/top already created.
         */
        fun update(previous: FriendRoster?, path: String, body: JsonObject, now: Long): FriendRoster? = runCatching {
            when (path) {
                "friend/top" -> fromFriendTop(body, now) ?: previous
                "follower/list", "battle/result" -> previous?.let { mergeFollowers(it, body) }
                else -> previous
            }
        }.getOrDefault(previous)

        /** Replaces the whole roster; friend requests (tblFriend status 1 or 2) are not friends. Null if unreadable. */
        fun fromFriendTop(body: JsonObject, now: Long = 0): FriendRoster? {
            val cache = body["cache"] as? JsonObject ?: return null
            val rows = (cache["updated"] as? JsonObject)?.get("otherUserGame") as? JsonArray ?: return null
            val requests = ((cache["replaced"] as? JsonObject)?.get("tblFriend") as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .filter { it.long("status") in setOf(1L, 2L) }
                .mapNotNull { it.string("friendId") }.toSet()
            // A non-empty list without a single readable user is a broken response, not an empty friend list.
            if (rows.isNotEmpty() && rows.none { ((it as? JsonObject)?.string("userId")) != null }) return null
            val friends = rows.mapNotNull { it as? JsonObject }.mapNotNull { row ->
                val id = row.string("userId") ?: return@mapNotNull null
                if (id in requests) return@mapNotNull null
                val all = row.slotObjects("userSvtLeaderHash") + row.slotObjects("eventUserSvtLeaderHash")
                Friend(
                    userId = id,
                    name = row.string("userName").orEmpty(),
                    lv = row.long("userLv")?.toInt() ?: 0,
                    friendCode = row.string("friendCode")?.takeIf { it.isNotBlank() },
                    mainDeckIds = row.longs("mainSupportDeckIds"),
                    eventDeckIds = row.longs("eventSupportDeckIds"),
                    lastLogin = all.maxOfOrNull { it.long("updatedAt") ?: 0L }?.coerceAtLeast(0) ?: 0,
                    slots = all.mapNotNull(::slot).distinctBy { it.deckId to it.classId },
                )
            }.associateBy { it.userId }
            return FriendRoster(cache.long("serverTime") ?: now, friends)
        }

        /** Updates or adds the friends (type 1) found in quest support lists. Others are ignored. */
        fun mergeFollowers(roster: FriendRoster, body: JsonObject): FriendRoster {
            val cache = body["cache"] as? JsonObject ?: return roster
            val followers = (cache["updated"] as? JsonObject)?.get("userFollower") as? JsonArray ?: return roster
            val infos = followers.mapNotNull { it as? JsonObject }
                .flatMap { (it["followerInfo"] as? JsonArray).orEmpty().mapNotNull { info -> info as? JsonObject } }
                .filter { it.long("type") == 1L }
            if (infos.isEmpty()) return roster
            val friends = roster.friends.toMutableMap()
            for ((id, group) in infos.groupBy { it.string("userId") }) {
                if (id == null) continue
                val known = friends[id]
                var friend = known ?: Friend(userId = id)
                group.first().let { info ->
                    info.string("userName")?.let { friend = friend.copy(name = it) }
                    info.long("userLv")?.let { friend = friend.copy(lv = it.toInt()) }
                    // Support lists carry no friend code: keep the one friend/top gave.
                    info.string("friendCode")?.takeIf { it.isNotBlank() }?.let { friend = friend.copy(friendCode = it) }
                }
                val slots = friend.slots.toMutableList()
                var main = friend.mainDeckIds
                var lastLogin = friend.lastLogin
                for (info in group) {
                    val new = slot(info) ?: continue
                    val index = slots.indexOfFirst { it.deckId == new.deckId && it.classId == new.classId }
                    if (index < 0) slots += new else slots[index] = new
                    // A friend first met through a support list has no deck lists; unknown decks count as normal.
                    if (known == null && new.deckId !in main) main = main + new.deckId
                    lastLogin = maxOf(lastLogin, new.updatedAt)
                }
                friends[id] = friend.copy(slots = slots, mainDeckIds = main, lastLogin = lastLogin)
            }
            return roster.copy(friends = friends)
        }

        /** The message field is never read. Empty slots (svtId 0) are dropped. */
        private fun slot(row: JsonObject): FriendSlot? {
            val svtId = row.long("svtId") ?: return null
            if (svtId == 0L) return null
            val appends = MutableList<Int?>(5) { null }
            (row["appendPassiveSkill"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.forEach { skill ->
                val id = skill.long("skillId") ?: return@forEach
                appends[appendSlot(id) - 1] = skill.long("skillLv")?.toInt()
            }
            val ce = row["equipTarget1"] as? JsonObject
            return FriendSlot(
                deckId = row.long("supportDeckId") ?: 0,
                classId = row.long("classId")?.toInt() ?: 0,
                svtId = svtId,
                lv = row.long("lv")?.toInt() ?: 0,
                np = row.long("treasureDeviceLv")?.toInt() ?: 0,
                skills = (1..3).map { row.long("skillLv$it")?.toInt() ?: 0 },
                appends = appends,
                ceId = ce?.long("svtId") ?: 0,
                ceLimitCount = ce?.long("limitCount")?.toInt() ?: 0,
                ceLv = ce?.long("lv")?.toInt() ?: 0,
                updatedAt = row.long("updatedAt") ?: 0,
            )
        }

        private fun JsonObject.slotObjects(key: String): List<JsonObject> = when (val value = this[key]) {
            is JsonArray -> value.mapNotNull { it as? JsonObject }
            is JsonObject -> value.values.mapNotNull { it as? JsonObject }
            else -> emptyList()
        }

        private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
        private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
        private fun JsonObject.longs(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.longOrNull }
    }
}
