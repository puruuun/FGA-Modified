package io.github.fate_grand_automata.capturespike

import android.util.Log
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList

/** In-memory ring of the last lines, mirrored to logcat. */
object SpikeLog {
    const val TAG = "FgaCaptureSpike"
    private const val MAX = 300
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val lines = ArrayDeque<String>()
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Also appended here, because the tablet's 256 KiB logcat buffer rolls over within minutes. */
    @Volatile
    var file: java.io.File? = null

    fun log(msg: String) {
        Log.i(TAG, msg)
        synchronized(lines) {
            lines.addLast("${fmt.format(LocalTime.now())} $msg")
            while (lines.size > MAX) lines.removeFirst()
            file?.let { f -> runCatching { f.appendText("${java.time.LocalDateTime.now()} $msg
") } }
        }
        listeners.forEach { it() }
    }

    fun snapshot(): String = synchronized(lines) { lines.joinToString("\n") }
}
