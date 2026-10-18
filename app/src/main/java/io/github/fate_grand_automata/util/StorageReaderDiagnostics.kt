package io.github.fate_grand_automata.util

import io.github.fate_grand_automata.IStorageProvider
import io.github.fate_grand_automata.scripts.diagnostics.IReaderDiagnostics
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import io.github.lib_automata.Pattern
import io.github.lib_automata.dagger.ScriptScope
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Saves reader evidence under `diagnostics/<reader>/<run>/` in the FGA storage folder. Opt-in
 * through the support score diagnostics switch. Scoped to a script run, so each run has its own folder.
 */
@ScriptScope
class StorageReaderDiagnostics @Inject constructor(
    private val prefs: IPreferences,
    private val storage: IStorageProvider
) : IReaderDiagnostics {
    override val enabled: Boolean
        get() = prefs.supportReaderDiagnostics

    override val runId: String =
        SimpleDateFormat("yyyy-MM-dd-HHmmss-SSS", Locale.US).format(Date())

    override fun record(reader: String, stem: String, images: Map<String, Pattern>, json: String) {
        val started = System.nanoTime()
        try {
            // The caller owns the patterns, so they are only read here and never closed.
            for ((suffix, image) in images) {
                storage.writeDiagnosticFile("$reader/$runId/$stem$suffix.png", "image/png") { image.save(it) }
            }
            storage.writeDiagnosticFile("$reader/$runId/$stem.json", "application/json") {
                it.write(json.toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            // Diagnostics must never stop a script run.
            Timber.tag("FGA.Diagnostics").w(e, "Could not save diagnostics for %s", stem)
            return
        }
        Timber.tag("FGA.Diagnostics").i("Saved diagnostics for %s in %d ms", stem, (System.nanoTime() - started) / 1_000_000)
    }
}
