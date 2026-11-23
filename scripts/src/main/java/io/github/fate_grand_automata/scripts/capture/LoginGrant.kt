package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The first place of one support category. The game sends only the first place of each category, so the top entries
 * of a login never add up to its total.
 */
@Serializable
data class LoginTopServant(val svtId: Long, val friendPoint: Long)

/**
 * What one login granted, kept next to its period so the log can show where the points came from.
 * [at] is Unix seconds like the period; a missing top entry is `null` ("none").
 */
@Serializable
data class LoginBreakdown(
    val at: Long = 0,
    val friend: Long = 0,
    val follow: Long = 0,
    val recommend: Long = 0,
    val normal: LoginTopServant? = null,
    val event: LoginTopServant? = null,
    val grand: LoginTopServant? = null,
) {
    val total get() = friend + follow + recommend
}

/** Friend points the server reports as granted by a login (other players borrowing the owner's supports). */
object LoginGrant {
    /**
     * [responseText] is the login body's small `response` array as kept in [AccountState.rawLoginResponse].
     * The grant is already inside the snapshot's friendPoint, so it is read here instead of diffed.
     * Anything unreadable counts as 0: a log gap is better than a failed capture.
     */
    fun friendPoints(responseText: String): Long = breakdown(responseText)?.total ?: 0

    /** The parts of the grant, stamped with [at]; null when there is no successful login to read. */
    fun breakdown(responseText: String, at: Long = 0): LoginBreakdown? {
        val array = runCatching { Json.parseToJsonElement(responseText) as? JsonArray }.getOrNull() ?: return null
        val success = array.firstNotNullOfOrNull { row ->
            val obj = row as? JsonObject ?: return@firstNotNullOfOrNull null
            // Same rule as AccountState.isLoginSuccess: a failed login grants nothing.
            if (text(obj["nid"]) == "login" && text(obj["resCode"]) == "00") obj["success"] as? JsonObject else null
        } ?: return null
        return LoginBreakdown(
            at = at,
            friend = amount(success["addFriendPoint"]),
            follow = amount(success["addFollowFriendPoint"]),
            recommend = amount(success["addRecommendSupportFriendPoint"]),
            normal = top(success["topAddFriendPointSvt"]),
            event = top(success["topAddFriendPointSvtEQ"]),
            grand = top(success["topAddFriendPointSvtGrand"]),
        )
    }

    // Only plain integers: "5" or 5.5 would be a different, unknown server shape.
    private fun plain(element: JsonElement?): Long? =
        (element as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private fun amount(element: JsonElement?) = plain(element)?.coerceAtLeast(0) ?: 0

    // The server sends svtId 0 and friendPoint 0 for a category nobody earned in.
    private fun top(element: JsonElement?): LoginTopServant? {
        val obj = element as? JsonObject ?: return null
        val svtId = plain(obj["svtId"]) ?: return null
        val points = plain(obj["friendPoint"]) ?: return null
        return if (svtId > 0 && points > 0) LoginTopServant(svtId, points) else null
    }

    private fun text(element: JsonElement?) = (element as? JsonPrimitive)?.contentOrNull
}