package io.github.fate_grand_automata.capturespike

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object CaptureNames {
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val skipPrefixes = listOf("NMAccount", "NetmarbleService", "NetmarbleUserDataService")

    fun stripQuery(target: String): String = target.substringBefore('?').substringBefore('#')

    fun isAccountPath(path: String): Boolean {
        val p = stripQuery(path).trimStart('/')
        return skipPrefixes.any { p.startsWith(it) }
    }

    /** `<yyyyMMdd-HHmmss>-<seq>-<path with / replaced by _>.json` */
    fun fileName(time: LocalDateTime, seq: Int, path: String): String {
        val safe = stripQuery(path).trim('/')
            .replace('/', '_')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(120)
            .ifEmpty { "root" }
        return "${stamp.format(time)}-$seq-$safe.json"
    }
}
