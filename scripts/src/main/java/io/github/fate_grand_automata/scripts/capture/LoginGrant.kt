package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Friend points the server reports as granted by a login (other players borrowing the owner's supports). */
object LoginGrant {
    private val fields = listOf("addFriendPoint", "addFollowFriendPoint", "addRecommendSupportFriendPoint")

    /**
     * [responseText] is the login body's small `response` array as kept in [AccountState.rawLoginResponse].
     * The grant is already inside the snapshot's friendPoint, so it is read here instead of diffed.
     * Anything unreadable counts as 0: a log gap is better than a failed capture.
     */
    fun friendPoints(responseText: String): Long {
        val array = runCatching { Json.parseToJsonElement(responseText) as? JsonArray }.getOrNull() ?: return 0
        val success = array.firstNotNullOfOrNull { row ->
            val obj = row as? JsonObject ?: return@firstNotNullOfOrNull null
            // Same rule as AccountState.isLoginSuccess: a failed login grants nothing.
            if (text(obj["nid"]) == "login" && text(obj["resCode"]) == "00") obj["success"] as? JsonObject else null
        } ?: return 0
        return fields.sumOf { name ->
            val primitive = success[name] as? JsonPrimitive
            // Only plain integers: "5" or 5.5 would be a different, unknown server shape.
            primitive?.takeIf { !it.isString }?.longOrNull?.coerceAtLeast(0) ?: 0
        }
    }

    private fun text(element: kotlinx.serialization.json.JsonElement?) = (element as? JsonPrimitive)?.contentOrNull
}
