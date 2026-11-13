package io.github.fate_grand_automata.capture

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** Reads and caches public Atlas Academy data version hashes for KR and JP. */
internal class AtlasVersions(
    private val directory: File,
    private val download: (String) -> String,
    private val now: () -> Long,
) {
    @Serializable
    private data class State(
        val checkedAt: Long? = null,
        val attemptAt: Long? = null,
        val kr: String? = null,
        val jp: String? = null,
    )

    private val lock = Any()
    private val stateFile get() = File(directory, STATE_FILE)

    fun current(): Map<String, String>? = synchronized(lock) {
        val currentTime = now()
        val saved = readState()
        val kr = saved.kr?.takeIf { it.isNotBlank() }
        val jp = saved.jp?.takeIf { it.isNotBlank() }
        if (saved.checkedAt != null && kr != null && jp != null && isRecent(currentTime, saved.checkedAt)) {
            return mapOf("KR" to kr, "JP" to jp)
        }
        if (saved.attemptAt != null && isRecent(currentTime, saved.attemptAt)) {
            return null
        }
        saveState(saved.copy(attemptAt = currentTime))
        val parsed = runCatching { parseInfo(download(INFO_URL)) }.getOrNull() ?: return null
        saveState(
            State(
                checkedAt = currentTime,
                attemptAt = currentTime,
                kr = parsed["KR"],
                jp = parsed["JP"],
            ),
        )
        return parsed
    }

    private fun isRecent(currentTime: Long, timestamp: Long): Boolean =
        currentTime - timestamp in 0 until CHECK_INTERVAL_MS

    private fun readState(): State = runCatching {
        if (!stateFile.isFile) return State()
        json.decodeFromString<State>(stateFile.readText())
    }.getOrDefault(State())

    private fun saveState(state: State) {
        runCatching { writeCaptureFile(stateFile, json.encodeToString(state)) }
    }

    companion object {
        internal const val INFO_URL = "https://api.atlasacademy.io/info"
        internal const val CHECK_INTERVAL_MS = 3_600_000L
        private const val STATE_FILE = "atlas_info.json"
        private val json = Json { ignoreUnknownKeys = true }

        internal fun parseInfo(body: String): Map<String, String>? {
            val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            val kr = regionHash(root, "KR") ?: return null
            val jp = regionHash(root, "JP") ?: return null
            return mapOf("KR" to kr, "JP" to jp)
        }

        private fun regionHash(root: JsonObject, region: String): String? {
            val obj = root[region] as? JsonObject ?: return null
            val primitive = obj["hash"] as? JsonPrimitive ?: return null
            if (!primitive.isString) return null
            return primitive.content.takeIf { it.isNotBlank() }
        }
    }
}
