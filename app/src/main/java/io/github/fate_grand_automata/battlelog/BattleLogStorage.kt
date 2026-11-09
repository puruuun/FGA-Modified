package io.github.fate_grand_automata.battlelog

import io.github.fate_grand_automata.scripts.capture.BattleGains
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Disk layout of the battle log (issue #280): `battle-log/index.json` holds one summary per entry and
 * `battle-log/details/<id>.json` holds that entry's per-battle rows. Only the index is ever kept in memory.
 */
internal interface BattleLogStorage {
    /** Null when there is no index yet. Throws [SerializationException] for an unreadable index. */
    fun readIndex(): List<BattleLogEntry>?
    fun writeIndex(entries: List<BattleLogEntry>)
    fun writeDetail(id: String, battles: List<BattleGains>)
    fun readDetail(id: String): List<BattleGains>
    fun deleteDetail(id: String)
    /** Deletes every detail file whose entry id is not in [keep]. */
    fun sweepDetails(keep: Set<String>)
    fun backupCorruptIndex()

    fun hasLegacy(): Boolean
    /**
     * Streams the old single-file log one element at a time, writing each detail file and passing the summary on.
     * Throws [SerializationException] when the old file is unreadable; any other failure leaves it untouched.
     */
    fun streamLegacy(onSummary: (BattleLogEntry) -> Unit)
    fun backupCorruptLegacy()
    fun deleteLegacy()
}

@OptIn(ExperimentalSerializationApi::class)
internal class FileBattleLogStorage(private val directory: File) : BattleLogStorage {
    private val json = Json { ignoreUnknownKeys = true }
    private val root get() = File(directory, "battle-log")
    private val index get() = File(root, "index.json")
    private val details get() = File(root, "details")
    private val legacy get() = File(directory, "battle-log.json")

    private fun detailFile(id: String) = File(details, detailName(id))
    private fun detailName(id: String) = URLEncoder.encode(id, "UTF-8") + ".json"

    override fun readIndex(): List<BattleLogEntry>? {
        if (!index.exists()) return null
        return index.inputStream().buffered().use { stream ->
            json.decodeToSequence<BattleLogEntry>(stream, DecodeSequenceMode.ARRAY_WRAPPED).toList()
        }
    }

    override fun writeIndex(entries: List<BattleLogEntry>) = atomicWrite(index) { json.encodeToStream(entries, it) }

    override fun writeDetail(id: String, battles: List<BattleGains>) =
        atomicWrite(detailFile(id)) { json.encodeToStream(ListSerializer(BattleGains.serializer()), battles, it) }

    override fun readDetail(id: String): List<BattleGains> {
        val file = detailFile(id)
        if (!file.exists()) return emptyList()
        return file.inputStream().buffered().use { json.decodeFromStream(ListSerializer(BattleGains.serializer()), it) }
    }

    override fun deleteDetail(id: String) { detailFile(id).delete() }

    override fun sweepDetails(keep: Set<String>) {
        val names = keep.mapTo(HashSet()) { detailName(it) }
        details.listFiles()?.forEach { if (it.name !in names) it.delete() }
    }

    override fun backupCorruptIndex() = backup(index, "index.corrupt")

    override fun hasLegacy() = legacy.exists()

    override fun streamLegacy(onSummary: (BattleLogEntry) -> Unit) {
        if (legacy.length() == 0L) return
        legacy.inputStream().buffered().use { stream ->
            json.decodeToSequence<BattleLogEntry>(stream, DecodeSequenceMode.ARRAY_WRAPPED).forEach { entry ->
                val battles = entry.gains?.battles.orEmpty()
                if (battles.isNotEmpty()) writeDetail(entry.id, battles)
                onSummary(entry.summarized())
            }
        }
    }

    override fun backupCorruptLegacy() {
        val backup = File(directory, "battle-log.corrupt.json").let {
            if (it.exists()) File(directory, "battle-log.corrupt-${UUID.randomUUID()}.json") else it
        }
        if (!legacy.renameTo(backup)) throw IOException("Could not preserve corrupt battle log")
    }

    override fun deleteLegacy() { legacy.delete() }

    private fun backup(file: File, prefix: String) {
        val target = File(file.parentFile, "$prefix-${UUID.randomUUID()}.json")
        if (!file.renameTo(target)) throw IOException("Could not preserve corrupt ${file.name}")
    }

    private fun atomicWrite(file: File, write: (java.io.OutputStream) -> Unit) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "Could not create ${file.parentFile}" }
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.outputStream().use { stream ->
            write(stream)
            stream.flush()
            stream.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}

internal fun BattleLogEntry.summarized(): BattleLogEntry = copy(gains = gains?.summarized())
