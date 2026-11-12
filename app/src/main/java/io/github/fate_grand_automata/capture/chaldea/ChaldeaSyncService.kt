package io.github.fate_grand_automata.capture.chaldea

import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaPackages
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaRunState
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaStateParser
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaStateResult
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncCode
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncEngine
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncResult
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaUserdataEditor
import io.github.fate_grand_automata.scripts.capture.chaldea.JavaChaldeaFileAccess
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.system.exitProcess

/**
 * Shizuku UserService: runs in a separate shell (or root) process and is the only code that touches Chaldea's files.
 * No Context, no Hilt, no Timber (the process has none of the app's setup) and no logging of account data.
 *
 * Memory: nothing is cached between calls. A `state()` call streams the dumpsys output line by line. `prepare` and
 * `commit` each read the two files fresh (about 1 MB each for a normal account) and drop them on return.
 */
@Keep
class ChaldeaSyncService : IChaldeaSyncService.Stub() {
    /** Held by every write sequence and by destroy(), so the process never exits in the middle of a write. */
    private val lock = ReentrantLock()

    private class Shell(val exit: Int?, val out: String)

    private fun run(vararg cmd: String): Shell = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        Shell(if (p.waitFor(10, TimeUnit.SECONDS)) p.exitValue() else null, out)
    } catch (e: Exception) {
        Shell(null, "")
    }

    /** Streams a command's stdout line by line through [scan]; null when the command failed. */
    private fun <T> stream(scan: (Sequence<String>) -> T, vararg cmd: String): T? = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val result = p.inputStream.bufferedReader().use { scan(it.lineSequence()) }
        if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) result else null
    } catch (e: Exception) {
        null
    }

    private fun userDir(pkg: String) = File(ChaldeaPackages.userDir(pkg))

    private fun choosePackage(): String = ChaldeaPackages.choose(
        ChaldeaPackages.all.associateWith { pkg ->
            File(userDir(pkg), ChaldeaSyncEngine.JSON).takeIf { it.isFile }?.lastModified()
        },
    )

    private fun bothExist() = ChaldeaPackages.all.all { File(userDir(it), ChaldeaSyncEngine.JSON).isFile }

    private fun readState(pkg: String): ChaldeaRunState {
        val pid = run("pidof", pkg)
        if (pid.exit == null) return ChaldeaRunState.Unknown
        if (pid.out.trim().isEmpty()) return ChaldeaStateParser.decide(pid.exit, "", null, null)
        val activity = stream({ ChaldeaStateParser.scanActivities(it, pkg) }, "dumpsys", "activity", "activities")
        val window = stream({ ChaldeaStateParser.scanWindows(it, pkg) }, "dumpsys", "window", "windows")
        return ChaldeaStateParser.decide(pid.exit, pid.out, activity, window)
    }

    private fun engine(pkg: String) =
        ChaldeaSyncEngine(JavaChaldeaFileAccess(userDir(pkg)), pkg, { readState(pkg) }, lock)

    override fun destroy() {
        // Wait for a running write and its verify or restore; then exit.
        lock.withLock { exitProcess(0) }
    }

    override fun state(): String = try {
        val pkg = choosePackage()
        ChaldeaStateResult(readState(pkg), pkg).toJson()
    } catch (t: Throwable) {
        ChaldeaStateResult(ChaldeaRunState.Unknown, null).toJson()
    }

    override fun forceStop(): String = try {
        val pkg = choosePackage()
        run("am", "force-stop", pkg)
        val after = ChaldeaStateParser.awaitNotRunning(
            read = { readState(pkg) },
            nowMs = { System.nanoTime() / 1_000_000 },
            sleepMs = { Thread.sleep(it) },
        )
        ChaldeaStateResult(after, pkg).toJson()
    } catch (t: Throwable) {
        ChaldeaStateResult(ChaldeaRunState.Unknown, null).toJson()
    }

    override fun prepare(backupFd: ParcelFileDescriptor?): String {
        var result: ChaldeaSyncResult? = null
        try {
            val pkg = choosePackage()
            val prepared = engine(pkg).prepare()
            result = prepared.result.copy(bothPackages = bothExist())
            val bytes = prepared.bundle
            if (bytes != null && backupFd != null) {
                // The app reads the other end on its own thread, started before this call.
                ParcelFileDescriptor.AutoCloseOutputStream(backupFd).use {
                    it.write(bytes)
                    it.flush()
                }
            } else if (bytes != null) {
                result = ChaldeaSyncResult(ChaldeaSyncCode.ServiceError, packageName = pkg)
            }
        } catch (t: Throwable) {
            result = ChaldeaSyncResult(ChaldeaSyncCode.ServiceError)
        } finally {
            try {
                backupFd?.close()
            } catch (_: Throwable) {
            }
        }
        return (result ?: ChaldeaSyncResult(ChaldeaSyncCode.ServiceError)).toJson()
    }

    override fun restore(backupFd: ParcelFileDescriptor?, expectedSha256: String?, expectedPackage: String?): String = try {
        val pkg = choosePackage()
        val bytes = backupFd?.let { fd ->
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
        val r = if (bytes == null || expectedSha256 == null || bytes.size > 2 * ChaldeaUserdataEditor.MAX_FILE_BYTES + 64) {
            ChaldeaSyncResult(ChaldeaSyncCode.BadPatch, packageName = pkg)
        } else if (expectedPackage != pkg) {
            ChaldeaSyncResult(ChaldeaSyncCode.FileChanged, packageName = pkg)
        } else {
            engine(pkg).restoreFromBackup(bytes, expectedSha256)
        }
        r.toJson()
    } catch (t: Throwable) {
        ChaldeaSyncResult(ChaldeaSyncCode.ServiceError).toJson()
    }

    override fun commit(
        patchJson: String?,
        expectedSha256: String?,
        allowCreate: Boolean,
        expectedPackage: String?,
        expectedBundleSha256: String?,
    ): String = try {
        val pkg = choosePackage()
        val r = if (patchJson == null || expectedSha256 == null) {
            ChaldeaSyncResult(ChaldeaSyncCode.BadPatch, packageName = pkg)
        } else if (expectedPackage != pkg) {
            // The newest-file rule now points to another package than prepare backed up.
            ChaldeaSyncResult(ChaldeaSyncCode.FileChanged, packageName = pkg)
        } else {
            engine(pkg).commit(patchJson, expectedSha256, allowCreate, expectedBundleSha256).copy(bothPackages = bothExist())
        }
        r.toJson()
    } catch (t: Throwable) {
        ChaldeaSyncResult(ChaldeaSyncCode.ServiceError).toJson()
    }
}
