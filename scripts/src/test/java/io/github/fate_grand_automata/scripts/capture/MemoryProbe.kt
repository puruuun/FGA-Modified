package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Runs in a child JVM with a small max heap (issue 258). args: mode (new|old), size in MB, what (login|resident).
 * Prints OK <peak MB> or RESIDENT <MB>, or dies with an OutOfMemoryError. The peak is sampled, so it is a lower bound.
 */
object MemoryProbe {
    @Volatile private var peak = 0L

    private fun used() = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    private fun sampled(block: () -> Unit): Long {
        peak = 0
        val sampler = Thread {
            while (true) {
                peak = maxOf(peak, used())
                try { Thread.sleep(2) } catch (_: InterruptedException) { return@Thread }
            }
        }
        sampler.isDaemon = true
        sampler.start()
        block()
        sampler.interrupt()
        return peak
    }

    /** Heap held by one parsed state after a full GC, as the resident cost of an account. */
    private fun resident(mode: String, text: String): Long {
        System.gc(); System.gc()
        val before = used()
        val held: Any = if (mode == "new") AccountState.fromJson(text) else OldAccountState.fromJson(text)
        System.gc(); System.gc()
        val after = used()
        check(held.toString().isNotEmpty())
        return after - before
    }

    @JvmStatic fun main(args: Array<String>) {
        val mode = args[0]
        val megabytes = args[1].toDouble()
        val what = args.getOrElse(2) { "login" }
        val bytes = SyntheticAccount.loginBody(megabytes = megabytes).toByteArray()
        if (what == "resident") {
            val tree = Json.parseToJsonElement(String(bytes)) as JsonObject
            val file = if (mode == "new") AccountState.reduce(null, "https://x/login/top", tree, 1)!!.toJson()
                else Json.encodeToString(OldAccountState.reduce(null, "https://x/login/top", tree, 1)!!)
            println("RESIDENT ${file.length / 1_000_000.0} MB json, ${resident(mode, file) / 1_000_000} MB heap")
            return
        }
        val peakBytes = sampled {
            if (mode == "new") {
                val read = AccountState.readLogin(bytes.inputStream(), "https://x/login/top")
                check(read.reducer(null, 1) != null)
            } else {
                val tree = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as JsonObject
                check(OldAccountState.reduce(null, "https://x/login/top", tree, 1) != null)
            }
        }
        println("OK ${peakBytes / 1_000_000}")
    }
}
