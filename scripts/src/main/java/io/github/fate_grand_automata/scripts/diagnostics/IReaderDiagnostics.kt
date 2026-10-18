package io.github.fate_grand_automata.scripts.diagnostics

import io.github.lib_automata.Pattern

/**
 * Receives the evidence of a reader that is being diagnosed. The Android implementation writes
 * the files under the FGA storage folder.
 *
 * Readers check [enabled] first. While it is false they neither build the JSON nor copy patterns.
 */
interface IReaderDiagnostics {
    val enabled: Boolean

    /** Identifies this script run. Files of one run are grouped under it. */
    val runId: String

    /**
     * Saves one candidate. [stem] names the files, e.g. "3-2". Each key of [images] is appended to
     * [stem] before ".png": "" for the score region and "-wide" for the widened region. [json] is
     * saved as "$stem.json".
     *
     * The caller keeps ownership of [images] and closes them itself; implementations must not close them.
     */
    fun record(reader: String, stem: String, images: Map<String, Pattern>, json: String)
}

/** Diagnostics are off. Records nothing. */
object NoReaderDiagnostics : IReaderDiagnostics {
    override val enabled = false
    override val runId = ""

    override fun record(reader: String, stem: String, images: Map<String, Pattern>, json: String) = Unit
}
