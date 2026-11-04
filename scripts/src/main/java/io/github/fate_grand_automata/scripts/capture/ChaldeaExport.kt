package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.*

/** A login-shaped body for Chaldea's HTTPS sniffing file importer. */
object ChaldeaExport {
    fun body(state: AccountState): String = buildJsonObject {
        put("response", state.loginResponse)
        putJsonObject("cache") {
            put("updated", JsonObject(emptyMap()))
            put("deleted", JsonObject(emptyMap()))
            put("replaced", JsonObject(state.tables))
            put("serverTime", state.serverTime ?: JsonNull)
        }
    }.toString()
}
