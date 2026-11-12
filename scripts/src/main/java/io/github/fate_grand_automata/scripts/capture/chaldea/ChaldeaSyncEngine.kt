package io.github.fate_grand_automata.scripts.capture.chaldea

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The two-phase read / edit / write / verify / restore sequence on one Chaldea `user/` folder (amendments A, F, J).
 * It has no Android or Shizuku dependency; the service wires it to the real folder and to the process state.
 *
 * [lock] is held for the whole of [prepare] and [commit]: the service takes it in `destroy()` so that neither a
 * destroy nor a binder death ends the process in the middle of a write or its restore.
 */
class ChaldeaSyncEngine(
    private val files: ChaldeaFileAccess,
    private val packageName: String,
    private val runState: () -> ChaldeaRunState,
    val lock: ReentrantLock = ReentrantLock(),
) {
    class Prepared(val result: ChaldeaSyncResult, val baseBytes: ByteArray?)

    private class Loaded(val json: ByteArray?, val bak: ByteArray?, val base: ByteArray, val source: ChaldeaBaseSource)
    private sealed interface Load {
        class Ok(val loaded: Loaded) : Load
        class Fail(val code: ChaldeaSyncCode) : Load
    }

    /** Checks Chaldea is not running, picks the base and returns its bytes, size and hash. Writes nothing. */
    fun prepare(): Prepared = lock.withLock {
        notRunningFailure()?.let { return Prepared(fail(it), null) }
        when (val load = load()) {
            is Load.Fail -> Prepared(fail(load.code), null)
            is Load.Ok -> Prepared(
                ChaldeaSyncResult(
                    ChaldeaSyncCode.Ok, packageName = packageName, base = load.loaded.source,
                    size = load.loaded.base.size.toLong(), sha256 = sha256Hex(load.loaded.base),
                ),
                load.loaded.base,
            )
        }
    }

    /**
     * Re-reads and re-hashes the base, edits it, and writes `userdata.json` and then `userdata.json.bak` in place.
     * On any write or verify failure each file is put back to its own original bytes.
     */
    fun commit(patchJson: String, expectedSha256: String, allowCreate: Boolean): ChaldeaSyncResult = lock.withLock {
        notRunningFailure()?.let { return fail(it) }
        val patch = ChaldeaPatch.fromJson(patchJson) ?: return fail(ChaldeaSyncCode.BadPatch)
        val loaded = when (val load = load()) {
            is Load.Fail -> return fail(load.code)
            is Load.Ok -> load.loaded
        }
        val baseHash = sha256Hex(loaded.base)
        if (baseHash != expectedSha256) return fail(ChaldeaSyncCode.FileChanged)
        // In place only: a missing userdata.json (base came from .bak) cannot be written without creating it.
        if (loaded.json == null) return fail(ChaldeaSyncCode.NotFound)

        val edit = ChaldeaUserdataEditor.edit(String(loaded.base, Charsets.UTF_8), patch, allowCreate)
        val success = when (edit) {
            is ChaldeaEditResult.Refused -> return fail(edit.reason.toCode())
            is ChaldeaEditResult.Success -> edit
        }
        val out = success.output.toByteArray(Charsets.UTF_8)
        if (out.size > ChaldeaUserdataEditor.MAX_FILE_BYTES) return fail(ChaldeaSyncCode.TooLarge)

        // Original bytes per file, for the restore. A missing .bak is neither written nor restored.
        val originals = LinkedHashMap<String, ByteArray>()
        originals[JSON] = loaded.json
        loaded.bak?.let { originals[BAK] = it }

        val attempted = ArrayList<String>()
        var cause: ChaldeaSyncCode? = null
        for ((name, _) in originals) {
            attempted += name
            cause = try {
                files.overwrite(name, out)
                if (files.read(name).contentEquals(out)) null else ChaldeaSyncCode.VerifyFailed
            } catch (e: ChaldeaIoException) {
                when (e.kind) {
                    ChaldeaIoException.Kind.NoAccess -> ChaldeaSyncCode.NoAccess
                    ChaldeaIoException.Kind.NotFound -> ChaldeaSyncCode.NotFound
                    else -> ChaldeaSyncCode.Unreadable
                }
            }
            if (cause != null) break
        }
        if (cause != null) {
            val restored = attempted.map { restore(it, originals.getValue(it)) }.all { it }
            return ChaldeaSyncResult(
                if (restored) ChaldeaSyncCode.Restored else ChaldeaSyncCode.RestoreFailed,
                cause = cause, packageName = packageName, base = loaded.source,
            )
        }
        ChaldeaSyncResult(
            ChaldeaSyncCode.Ok,
            packageName = packageName, base = loaded.source,
            size = out.size.toLong(), sha256 = sha256Hex(out), baseSha256 = baseHash,
            itemCount = success.itemCount, mysticCodeCount = success.mysticCodeCount, boardCount = success.boardCount,
            created = success.created, tookOver = success.tookOverDefault, regionMismatch = success.regionMismatch,
            bakWritten = loaded.bak != null,
        )
    }

    /**
     * Recovery after an interrupted write (amendment K): puts [backup], the base FGA stored before `commit`, back into
     * both existing files. Refuses when Chaldea may be running or the bytes are not the announced ones.
     */
    fun restoreFromBackup(backup: ByteArray, expectedSha256: String): ChaldeaSyncResult = lock.withLock {
        notRunningFailure()?.let { return fail(it) }
        if (sha256Hex(backup) != expectedSha256) return fail(ChaldeaSyncCode.FileChanged)
        if (!files.exists(JSON)) return fail(ChaldeaSyncCode.NotFound)
        val allRestored = listOf(JSON, BAK).filter { files.exists(it) }.map { restore(it, backup) }.all { it }
        if (allRestored) {
            ChaldeaSyncResult(ChaldeaSyncCode.Ok, packageName = packageName, size = backup.size.toLong(), sha256 = expectedSha256)
        } else {
            ChaldeaSyncResult(ChaldeaSyncCode.RestoreFailed, packageName = packageName)
        }
    }

    private fun restore(name: String, original: ByteArray): Boolean = try {
        files.overwrite(name, original)
        files.read(name).contentEquals(original)
    } catch (e: ChaldeaIoException) {
        false
    }

    private fun fail(code: ChaldeaSyncCode) = ChaldeaSyncResult(code, packageName = packageName)

    private fun notRunningFailure(): ChaldeaSyncCode? = when (runState()) {
        ChaldeaRunState.NotRunning -> null
        ChaldeaRunState.Unknown -> ChaldeaSyncCode.StateUnknown
        else -> ChaldeaSyncCode.ChaldeaRunning
    }

    private fun readOptional(name: String): Pair<ByteArray?, ChaldeaSyncCode?> {
        if (!files.exists(name)) return null to null
        if (files.length(name) > ChaldeaUserdataEditor.MAX_FILE_BYTES) return null to ChaldeaSyncCode.TooLarge
        return try {
            files.read(name) to null
        } catch (e: ChaldeaIoException) {
            null to when (e.kind) {
                ChaldeaIoException.Kind.NotFound -> null
                ChaldeaIoException.Kind.NoAccess -> ChaldeaSyncCode.NoAccess
                else -> ChaldeaSyncCode.Unreadable
            }
        }
    }

    private fun load(): Load {
        val (json, jsonErr) = readOptional(JSON)
        if (jsonErr != null) return Load.Fail(jsonErr)
        val (bak, bakErr) = readOptional(BAK)
        // An existing .bak that cannot be read would be left stale, so it stops the sync.
        if (bakErr != null) return Load.Fail(bakErr)
        if (json == null && bak == null) return Load.Fail(ChaldeaSyncCode.NotFound)
        return when (val base = ChaldeaUserdataEditor.selectBase(json, bak)) {
            is ChaldeaBase.Refused -> Load.Fail(base.reason.toCode())
            is ChaldeaBase.Selected -> Load.Ok(
                Loaded(
                    json, bak,
                    when (base.source) {
                        ChaldeaBaseSource.UserdataJson -> json!!
                        ChaldeaBaseSource.Bak -> bak!!
                    },
                    base.source,
                ),
            )
        }
    }

    companion object {
        const val JSON = "userdata.json"
        const val BAK = "userdata.json.bak"
    }
}
