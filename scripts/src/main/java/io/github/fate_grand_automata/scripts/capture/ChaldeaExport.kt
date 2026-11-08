package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.*
import java.io.Writer

/** A login-shaped body for Chaldea's HTTPS sniffing file importer. */
object ChaldeaExport {
    fun body(state: AccountState): String = java.io.StringWriter().also { write(state, it) }.toString()

    /**
     * Writes the same text as `buildJsonObject { response, cache { updated, deleted, replaced, serverTime } }.toString()`
     * with the tables' saved text embedded as it is, so nothing is parsed and no whole body String exists (issue #258).
     */
    fun write(state: AccountState, out: Writer) {
        out.write("{\"response\":")
        out.write(state.rawLoginResponse)
        out.write(",\"cache\":{\"updated\":{},\"deleted\":{},\"replaced\":{")
        var first = true
        for ((name, rows) in state.rawTables) {
            if (!first) out.write(",")
            first = false
            out.write(JsonPrimitive(name).toString())
            out.write(":")
            out.write(rows)
        }
        out.write("},\"serverTime\":")
        out.write((state.serverTime ?: JsonNull).toString())
        out.write("}}")
    }
}
