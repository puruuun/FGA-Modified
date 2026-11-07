package io.github.fate_grand_automata.capture

import android.content.Context
import android.os.Build
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

sealed interface RootCaResult {
    data object RootMissing : RootCaResult
    data object Denied : RootCaResult
    data class Failed(val reason: String) : RootCaResult
    data object Injected : RootCaResult
    data object AlreadyPresent : RootCaResult
}

data class RootShellOutput(val exitCode: Int, val lines: List<String>, val timedOut: Boolean = false)

fun interface RootShell {
    fun run(command: List<String>, stdin: String, timeoutSeconds: Long): RootShellOutput
}

private fun logRootOutput(out: RootShellOutput) {
    Timber.i("JP CA: exit=%d timeout=%s output=%s", out.exitCode, out.timedOut,
        out.lines.filter { Regex("FGA_[A-Z_]+(=[0-9]+)?").matches(it) }.takeLast(12))
}

/** Runs the script in init's mount namespace; `su` from an app inherits the app's own namespace. */
val ROOT_GLOBAL_NS_COMMAND = listOf("su", "-c", "nsenter --mount=/proc/1/ns/mnt -- /system/bin/sh")

/** Independently testable runner. Tests supply a shell and never start a process. */
class RootCaRunner(private val shell: RootShell) {
    fun inject(script: String): RootCaResult = try {
        val out = shell.run(ROOT_GLOBAL_NS_COMMAND, script, 10)
        logRootOutput(out)
        RootCaInjector.parse(out)
    } catch (e: IOException) {
        if (e.message?.contains("error=2") == true || e is java.io.FileNotFoundException) RootCaResult.RootMissing
        else RootCaResult.Failed("I/O error")
    } catch (_: Exception) { RootCaResult.Failed("Shell error") }
}

/** Timeout includes stdin writes and output draining. Never logs arbitrary shell output. */
class ProcessRootShell : RootShell {
    override fun run(command: List<String>, stdin: String, timeoutSeconds: Long): RootShellOutput {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val workers = Executors.newFixedThreadPool(2) { Thread(it, "capture-root-io").apply { isDaemon = true } }
        val lines = ArrayDeque<String>()
        try {
            workers.submit { process.outputStream.bufferedWriter().use { it.write(stdin) } }
            val reader = workers.submit {
                process.inputStream.bufferedReader().useLines { output ->
                    output.forEach { line ->
                        // Keep only protocol markers and id; never a PEM body or private path.
                        if (Regex("FGA_[A-Z_]+(=[0-9]+)?").matches(line) || line.startsWith("uid=")) synchronized(lines) {
                            if (lines.size == 12) lines.removeFirst()
                            lines.addLast(line)
                        }
                    }
                }
            }
            if (!process.waitFor(maxOf(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                return RootShellOutput(-1, emptyList(), true)
            }
            try {
                reader.get(maxOf(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) { return RootShellOutput(-1, emptyList(), true) }
            return RootShellOutput(process.exitValue(), synchronized(lines) { lines.toList() })
        } finally {
            process.destroyForcibly()
            workers.shutdownNow()
        }
    }
}

interface JpCaInstaller {
    fun hasRoot(): Boolean
    fun ensureCertificate()
    fun isPresent(): Boolean
    fun inject(): RootCaResult
    /** True only when the certificate is no longer visible after unmounting. */
    fun remove(): Boolean
    fun isGameRunning(): Boolean
}

class RootCaInjector(
    private val context: Context,
    private val shell: RootShell = ProcessRootShell(),
    private val api: Int = Build.VERSION.SDK_INT,
) : JpCaInstaller {
    override fun hasRoot(): Boolean = runCatching {
        val out = shell.run(listOf("su", "-c", "id"), "", 5)
        !out.timedOut && out.exitCode == 0 && out.lines.any { Regex("(^|\\s)uid=0(?:\\D|$)").containsMatchIn(it) }
    }.getOrDefault(false)

    override fun ensureCertificate() = CertStore.ensureJp(context)

    private fun <T> withPem(build: (Int, String, String) -> String, run: (String) -> T): T {
        val cert = CertStore.jpCertificate(context)
        val pem = File.createTempFile("jp-ca-", ".pem", context.cacheDir)
        try {
            pem.writeText(CertStore.pem(cert), Charsets.US_ASCII)
            val script = build(api, CertStore.subjectHashOld(cert), pem.canonicalPath)
            return run(script)
        } finally { pem.delete() }
    }

    private fun execute(build: (Int, String, String) -> String, timeout: Long = 10) =
        withPem(build) { shell.run(ROOT_GLOBAL_NS_COMMAND, it, timeout).also(::logRootOutput) }

    override fun isPresent(): Boolean = runCatching {
        val out = execute(RootCaScripts::isPresent, 5)
        !out.timedOut && out.exitCode == 0 && "FGA_PRESENT" in out.lines
    }.getOrDefault(false)

    override fun inject(): RootCaResult = synchronized(storeLock) {
        try {
            withPem(RootCaScripts::inject) { RootCaRunner(shell).inject(it) }
        } catch (_: Exception) { RootCaResult.Failed("Certificate or shell error") }
    }

    override fun remove(): Boolean = synchronized(storeLock) {
        runCatching {
            val out = execute(RootCaScripts::remove)
            !out.timedOut && out.exitCode == 0 && "FGA_REMOVED" in out.lines
        }.getOrDefault(false)
    }

    override fun isGameRunning(): Boolean = runCatching {
        val out = shell.run(listOf("su", "-c", "sh"),
            "if pidof ${CaptureVpnService.JP_GAME_PACKAGE} >/dev/null 2>&1; then echo FGA_GAME_RUNNING; fi\n", 5)
        !out.timedOut && out.exitCode == 0 && "FGA_GAME_RUNNING" in out.lines
    }.getOrDefault(false)

    companion object {
        // Settings and service startup can overlap; never run two mount mutations at once.
        private val storeLock = Any()
        fun parse(out: RootShellOutput): RootCaResult = when {
            out.timedOut -> RootCaResult.Failed("Timed out (10 s)")
            "FGA_WRONG_NS" in out.lines || out.exitCode == 18 -> RootCaResult.Failed("Not in the global mount namespace")
            out.exitCode == 127 -> RootCaResult.Failed("nsenter missing")
            "FGA_DENIED" in out.lines || (out.exitCode != 0 && "FGA_ROOT" !in out.lines) -> RootCaResult.Denied
            out.exitCode != 0 -> RootCaResult.Failed(when {
                "FGA_HASH_COLLISION" in out.lines -> "Certificate hash collision"
                "FGA_VERIFY_FAILED" in out.lines -> "Certificate verification failed"
                else -> "Root script exited ${out.exitCode}"
            })
            "FGA_ALREADY_PRESENT" in out.lines -> RootCaResult.AlreadyPresent
            "FGA_INJECTED" in out.lines -> RootCaResult.Injected
            else -> RootCaResult.Failed("Missing installation result")
        }
    }
}
