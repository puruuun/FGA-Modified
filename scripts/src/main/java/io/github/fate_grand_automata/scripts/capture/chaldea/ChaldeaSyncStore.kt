package io.github.fate_grand_automata.scripts.capture.chaldea

import kotlinx.serialization.json.*
import java.io.File

/** What FGA persists between runs of the sync: a few hundred bytes in two small JSON files. */
class ChaldeaSyncStore(private val dir: File) {
    /** [backupPath] is the folder holding both original files; [bundleSize]/[bundleSha256] describe their bundle. */
    data class Inflight(
        val packageName: String, val backupPath: String, val baseSha256: String,
        val bundleSize: Long, val bundleSha256: String, val timeMs: Long,
    )

    private val stateFile get() = File(dir, "state.json")
    private val inflightFile get() = File(dir, "inflight.json")
    private val gameDataFile get() = File(dir, "gamedata.json")

    /** Lazy read of cached Chaldea costume/transform data; null if missing, corrupt or unreadable. */
    @Synchronized
    fun readGameData(): ChaldeaGameData? = runCatching {
        val f = gameDataFile
        if (!f.exists()) null else ChaldeaGameData.fromJson(f.readText())
    }.getOrNull()

    @Synchronized
    fun writeGameData(data: ChaldeaGameData) {
        atomicWrite(gameDataFile, data.toJson())
    }

    data class Data(
        val pending: Set<String> = emptySet(),
        /** Elapsed-realtime ms at which Chaldea was first seen in the background in this wait. */
        val backgroundSince: Long? = null,
        /** Elapsed-realtime ms of the last time the state was read while waiting. */
        val lastObservation: Long? = null,
        val syncedPatchHash: Map<String, String> = emptyMap(),
        /** SHA-256 of the bytes FGA wrote last, per Chaldea package. */
        val lastWritten: Map<String, String> = emptyMap(),
    )

    @Synchronized fun read(): Data = try {
        val o = Json.parseToJsonElement(stateFile.readText()).jsonObject
        fun strMap(k: String) = (o[k] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        Data(
            pending = (o["pending"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet(),
            backgroundSince = (o["backgroundSince"] as? JsonPrimitive)?.longOrNull,
            lastObservation = (o["lastObservation"] as? JsonPrimitive)?.longOrNull,
            syncedPatchHash = strMap("synced"),
            lastWritten = strMap("written"),
        )
    } catch (e: Exception) {
        Data()
    }

    @Synchronized fun update(change: (Data) -> Data) {
        val d = change(read())
        val o = buildJsonObject {
            put("pending", JsonArray(d.pending.map { JsonPrimitive(it) }))
            d.backgroundSince?.let { put("backgroundSince", it) }
            d.lastObservation?.let { put("lastObservation", it) }
            put("synced", JsonObject(d.syncedPatchHash.mapValues { JsonPrimitive(it.value) }))
            put("written", JsonObject(d.lastWritten.mapValues { JsonPrimitive(it.value) }))
        }
        atomicWrite(stateFile, o.toString())
    }

    @Synchronized fun readInflight(): Inflight? = try {
        val o = Json.parseToJsonElement(inflightFile.readText()).jsonObject
        Inflight(
            o["package"]!!.jsonPrimitive.content, o["backup"]!!.jsonPrimitive.content,
            o["baseSha256"]!!.jsonPrimitive.content, o["bundleSize"]!!.jsonPrimitive.long,
            o["bundleSha256"]!!.jsonPrimitive.content, o["time"]!!.jsonPrimitive.long,
        )
    } catch (e: Exception) {
        null
    }

    /** @throws java.io.IOException when it cannot be persisted: the caller must not commit then. */
    @Synchronized fun writeInflight(i: Inflight) {
        val o = buildJsonObject {
            put("package", i.packageName)
            put("backup", i.backupPath)
            put("baseSha256", i.baseSha256)
            put("bundleSize", i.bundleSize)
            put("bundleSha256", i.bundleSha256)
            put("time", i.timeMs)
        }
        atomicWrite(inflightFile, o.toString())
    }

    @Synchronized fun clearInflight() {
        inflightFile.delete()
    }

    private fun atomicWrite(target: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw java.io.IOException("rename failed")
        }
    }
}
