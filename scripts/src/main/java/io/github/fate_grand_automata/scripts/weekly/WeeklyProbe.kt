package io.github.fate_grand_automata.scripts.weekly

/** Design 0009 D-25: what the navigation probe found for one quest. The probe never taps a quest panel. */
enum class ProbeStatus(val csv: String) {
    Success("success"),
    MapFitFailed("map_fit_failed"),
    NoPanel("no_panel"),
    Mismatch("panel_mismatch"),
    ChapterNotFound("chapter_not_found"),
    NotRun("not_run");

    companion object {
        private val mapFit = setOf("map not recognized", "pinch failed", "spot out of reach", "zoom out failed", "chapter map not recognized", "entered map is not the free quest map")

        /** Maps the reason text of a [SpotOutcome.NotFound] or of a failed chapter visit to a status. */
        fun classify(reason: String): ProbeStatus = when {
            reason.startsWith("panel_mismatch ") -> Mismatch
            reason in mapFit || reason.endsWith("image unavailable") -> MapFitFailed
            reason == "no panel at the spot" -> NoPanel
            reason == "no free panel with the AP and name" -> Mismatch
            else -> ChapterNotFound
        }
    }
}

/**
 * D-30: why a success is believable. [file] is the evidence picture (panel name band above the spot ribbon), [by] the rule that
 * matched (`quest-name` or `spot-name+cells`), [questRead] and [spotRead] the canonical names the OCR settled on (blank when unread).
 */
data class ProbeEvidence(val file: String = "", val by: String = "", val questRead: String = "", val spotRead: String = "")

data class ProbeResult(val quest: RouteQuest, val status: ProbeStatus, val note: String, val elapsedMillis: Long,
    val evidence: ProbeEvidence = ProbeEvidence())

enum class ProbeScope { All, Chapters, Enabled }

/** The probe picks quests from the downloaded chapter data; Mictlan and the Ordeal Calls are never in it. */
object WeeklyProbe {
    /** Cap for failure PNGs in probe mode (the ordinary route keeps 8). */
    const val DIAGNOSTIC_LIMIT = 100

    fun select(server: String, quests: List<RouteQuest>, scope: ProbeScope, chapters: Set<Int>, disabled: Set<String>): List<RouteItem> =
        quests.filter { it.type == "free" && it.ap > 0 && it.warId !in WeeklyRouteSolver.EXCLUDED_WARS }
            .filter {
                when (scope) {
                    ProbeScope.All -> true
                    ProbeScope.Chapters -> it.warId in chapters
                    ProbeScope.Enabled -> WeeklyCandidateSettings.candidates(server, listOf(it), disabled).isNotEmpty()
                }
            }
            .map { RouteItem(it, 1) }

    const val CSV_HEADER = "quest_id,war_id,chapter,spot,quest,ap,status,elapsed_ms,note,evidence_file,by,read_quest,read_spot"

    fun csv(results: List<ProbeResult>): String = buildString {
        append(CSV_HEADER).append('\n')
        results.forEach {
            val q = it.quest
            append(listOf(q.id, q.warId, field(q.chapter), field(q.spot), field(q.name), q.ap, it.status.csv, it.elapsedMillis, field(it.note),
                field(it.evidence.file), it.evidence.by, field(it.evidence.questRead), field(it.evidence.spotRead))
                .joinToString(",")).append('\n')
        }
    }

    private fun field(text: String): String {
        val flat = text.replace('\n', ' ').replace('\r', ' ')
        return if (flat.any { it == ',' || it == '"' }) "\"" + flat.replace("\"", "\"\"") + "\"" else flat
    }
}
