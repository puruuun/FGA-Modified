package io.github.fate_grand_automata.util

import io.github.fate_grand_automata.BuildConfig
import io.github.fate_grand_automata.IStorageProvider
import io.github.fate_grand_automata.scripts.weekly.IWeeklyPanelDiagnostics
import io.github.fate_grand_automata.scripts.weekly.WeeklyProbe
import io.github.lib_automata.Pattern
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

/** Automatic only in beta (8 per run), or in probe mode (up to 100); bounded per run, including failed writes. */
@ScriptScope
class WeeklyPanelDiagnostics @Inject constructor(private val storage: IStorageProvider) : IWeeklyPanelDiagnostics {
    private val run = System.currentTimeMillis()
    private var count = 0
    private var probe = false
    override val enabled get() = BuildConfig.BUILD_TYPE == "beta" && count < if (probe) WeeklyProbe.DIAGNOSTIC_LIMIT else 8
    override fun startProbe() { probe = true }

    private val mapLines = ArrayList<String>()

    /** Issue #91: the map lines of this run (localize, plan, scroll, tap point) in `weeklyRoute/<run>/map.log`; beta builds only, bounded. */
    override fun mapLog(line: String) {
        if (BuildConfig.BUILD_TYPE != "beta" || mapLines.size >= MAP_LOG_LIMIT) return
        mapLines += line
        // Issue #102: one file per run. Buffer the lines and rewrite the whole file every few lines (and at run end), so a
        // crash loses at most the last few lines and the provider never sees one create per line.
        if (mapLines.size - flushed >= MAP_LOG_FLUSH_EVERY) flushMapLog()
    }

    private var flushed = 0

    override fun flushMapLog() {
        if (BuildConfig.BUILD_TYPE != "beta" || mapLines.size == flushed) return
        val path = "weeklyRoute/$run/map.log"
        try {
            // Not text/plain: SAF providers append ".txt" to that type, so the next findFile("map.log") misses and creates "map (1).log.txt".
            storage.writeDiagnosticFile(path, "application/octet-stream") { it.write(mapLines.joinToString("\n").toByteArray(Charsets.UTF_8)) }
            flushed = mapLines.size
        } catch (e: Exception) {
            timber.log.Timber.tag(WEEKLY_ROUTE_TAG).w(e, "map log write failed path=%s", path)
        }
    }

    private companion object { const val MAP_LOG_LIMIT = 600; const val MAP_LOG_FLUSH_EVERY = 10 }

    override fun saveEvidence(image: Pattern, questId: Int): String? {
        if (BuildConfig.BUILD_TYPE != "beta" || !probe) return null
        val path = "weeklyRoute/$run/evidence/q$questId.png"
        return try {
            storage.writeDiagnosticFile(path, "image/png") { image.save(it) }
            "diagnostics/$path"
        } catch (e: Exception) {
            timber.log.Timber.tag(WEEKLY_ROUTE_TAG).w(e, "evidence write failed path=%s", path)
            null
        }
    }

    override fun saveAbort(frame: Pattern): String? {
        if (BuildConfig.BUILD_TYPE != "beta") return null
        val path = "weeklyRoute/$run/abort-${System.nanoTime()}.png"
        return try {
            storage.writeDiagnosticFile(path, "image/png") { frame.save(it) }
            "diagnostics/$path"
        } catch (e: Exception) {
            timber.log.Timber.tag(WEEKLY_ROUTE_TAG).w(e, "abort diagnostic write failed")
            null
        }
    }

    override fun save(frame: Pattern, questId: Int, attempt: Int): String? {
        if (!enabled) return null
        val path = "weeklyRoute/$run/${++count}-q$questId-a$attempt.png"
        return try {
            storage.writeDiagnosticFile(path, "image/png") { frame.save(it) }
            "diagnostics/$path"
        } catch (e: Exception) {
            timber.log.Timber.tag(WEEKLY_ROUTE_TAG).w(e, "diagnostic write failed path=%s", path)
            null
        }
    }
}
