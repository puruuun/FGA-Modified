package io.github.fate_grand_automata.qa.suites

import io.github.fate_grand_automata.qa.FixtureApi
import io.github.fate_grand_automata.scripts.locations.SkillLevelLocations
import io.github.fate_grand_automata.scripts.modules.SkillLevelDigits
import io.github.fate_grand_automata.scripts.modules.SkillLevelMode
import io.github.fate_grand_automata.scripts.modules.SkillLevelScreens
import org.json.JSONArray
import org.json.JSONObject

/**
 * Offline fixtures of the 0008 skill / append level screens: production readers on the owner captures, no gestures.
 * Views: mode (centre label + servant selected), button (bottom-right button label), dialog (confirm dialog),
 * levels (OCR of `Lv.current > Lv.next`), locks (padlock per append slot), result.
 */
internal fun FixtureApi.verifySkillLevel(): JSONObject {
    check(!live) { "skill level QA must never operate a live game" }
    val loc = SkillLevelLocations(locations)
    val screens = SkillLevelScreens(this, loc)
    fun mode(): SkillLevelMode = SkillLevelMode.valueOf(case.getString("slotMode"))
    val actual = when (case.getString("view")) {
        "mode" -> JSONObject().put("mode", screens.detectMode()?.name ?: "none")
            .put("servantSelected", screens.servantSelected())
        "button" -> JSONObject().put("unlockButton", screens.unlockButtonVisible()).put("maxButton", screens.maxButtonVisible())
        "dialog" -> JSONObject().put("dialog", screens.dialogVisible()).put("enhanceTitle", screens.dialogIsEnhance(mode()))
        "levels" -> {
            val digits = SkillLevelDigits(this, loc, ocr)
            val level = digits.read(mode()).inferMissing()
            fun raw(region: io.github.lib_automata.Region) = JSONArray(digits.readings(region, mode()).map { "${it.text.trim()}@${it.confidence}" })
            fun scores(region: io.github.lib_automata.Region): JSONObject {
                // A positive floor lets the matcher finish after it suppresses a match to zero.
                // Scores below 0.01 are reported as zero; this diagnostic never accepts a digit.
                val scores = digits.templateScores(region, similarity = 0.01)
                val nines = scores.filter { it.first == 9 }
                val other = scores.filter { it.first != 9 }.maxByOrNull { it.second }
                return JSONObject().put("SkillLevelDigit9", nines.getOrNull(0)?.second ?: JSONObject.NULL)
                    .put("SkillLevelDigit9Kr", nines.getOrNull(1)?.second ?: JSONObject.NULL)
                    .put("SkillLevelDigit9Next", nines.getOrNull(2)?.second ?: JSONObject.NULL)
                    .put("bestOtherDigit", other?.first ?: JSONObject.NULL)
                    .put("bestOtherScore", other?.second ?: JSONObject.NULL)
            }
            JSONObject().put("current", level.current ?: "unread").put("next", level.next ?: "unread")
                .put("currentInferred", level.currentInferred).put("nextInferred", level.nextInferred)
                .put("rawCurrent", raw(loc.currentLevel(mode()))).put("rawNext", raw(loc.nextLevel(mode())))
                .put("templatesCurrent", scores(loc.currentLevel(mode()))).put("templatesNext", scores(loc.nextLevel(mode())))
        }
        "locks" -> JSONObject().put("locks", JSONArray((0 until mode().slots).map { screens.slotLocked(mode(), it) }))
        "result" -> JSONObject().put("result", screens.onResult())
        else -> error("unknown skillLevel view")
    }
    val expected = case.getJSONObject("expected")
    for (key in expected.keys()) check(actual.get(key).toString() == expected.get(key).toString()) { "skillLevel $key: $actual != $expected" }
    return actual
}
