package io.github.fate_grand_automata.scripts.capture.chaldea

import kotlinx.serialization.json.*

/** Decides which Chaldea user an account is written to. Pure: it never changes the users. */
object ChaldeaUserMatcher {
    sealed interface Match {
        /** A user whose `lastImportId` equals the friend code (the first one, as Chaldea does). */
        data class Existing(val index: Int, val regionMismatch: Boolean) : Match

        /** The single untouched default user; the caller sets its identity fields. */
        data class TakeOver(val index: Int) : Match

        /** No user fits: a new one has to be appended. */
        object Create : Match
    }

    const val DEFAULT_REGION = "jp"
    const val DEFAULT_NAME = "Gudako"

    private val emptyCollections = listOf(
        "items", "servants", "craftEssences", "cmdCodes", "mysticCodes", "classBoards", "summons",
        "myRoomMusic", "dupServantMapping", "eventItemCalc", "luckyBagSvtScores",
    )

    fun match(users: JsonArray, patch: ChaldeaPatch): Match {
        val code = patch.friendCode?.trim()
        if (!code.isNullOrEmpty()) {
            val index = users.indexOfFirst { u ->
                val id = (u as? JsonObject)?.get("lastImportId") as? JsonPrimitive
                id != null && id !is JsonNull && id.content.trim() == code
            }
            if (index >= 0) {
                val region = ((users[index] as JsonObject)["region"] as? JsonPrimitive)?.contentOrNull ?: DEFAULT_REGION
                return Match.Existing(index, region != patch.server.region)
            }
        }
        if (users.size == 1 && isUntouchedDefault(users[0])) return Match.TakeOver(0)
        return Match.Create
    }

    /** Strict: only a user Chaldea itself created and nobody has used. */
    internal fun isUntouchedDefault(user: JsonElement): Boolean {
        val u = user as? JsonObject ?: return false
        val importId = u["lastImportId"]
        if (importId != null && importId !is JsonNull && !(importId is JsonPrimitive && importId.isString && importId.content.isEmpty())) return false
        if (!emptyCollections.all { isEmptyOrAbsent(u[it]) }) return false
        val plans = u["plans"]
        if (plans != null && plans !is JsonNull) {
            if (plans !is JsonArray) return false
            for (plan in plans) {
                if (plan is JsonNull) continue
                val p = plan as? JsonObject ?: return false
                if (!p.values.all { v -> (v !is JsonObject && v !is JsonArray) || isEmptyOrAbsent(v) }) return false
            }
        }
        fun defaultOrAbsent(key: String, default: String): Boolean {
            val v = u[key]
            return v == null || v is JsonNull || (v is JsonPrimitive && v.isString && v.content == default)
        }
        return defaultOrAbsent("name", DEFAULT_NAME) && defaultOrAbsent("region", DEFAULT_REGION)
    }

    private fun isEmptyOrAbsent(e: JsonElement?) = when (e) {
        null, is JsonNull -> true
        is JsonObject -> e.isEmpty()
        is JsonArray -> e.isEmpty()
        else -> false
    }
}
