package io.github.fate_grand_automata.qa.suites

import io.github.fate_grand_automata.qa.FixtureApi
import io.github.fate_grand_automata.scripts.locations.SkillLevelLocations
import io.github.fate_grand_automata.scripts.modules.SkillLevelMode
import io.github.fate_grand_automata.scripts.modules.SkillLevelPanelReader
import org.json.JSONArray
import org.json.JSONObject

/** Native captures only. Product coordinates and image path, with no synthetic ROI adapter. */
internal fun FixtureApi.verifySkillLevelPanel(): JSONObject {
    check(!live) { "Offline panels only" }
    val mode = SkillLevelMode.valueOf(case.getString("mode"))
    val loc = SkillLevelLocations(locations)
    val e = SkillLevelPanelReader(this, loc, ocr).read(mode, case.getInt("slot"))
    val expected = case.getJSONObject("expected").getInt("level")
    val status = if (expected == 0 && e.locked && e.level == null) "locked"
        else if (e.level == expected) "correct" else if (e.level == null) "abstained" else "wrong"
    val actual = JSONObject().put("level", e.level ?: JSONObject.NULL).put("locked", e.locked)
        .put("candidate", e.candidate ?: JSONObject.NULL).put("score", e.score).put("margin", e.margin)
        .put("reason", e.reason).put("status", status)
    val region = loc.panelLevel(mode, case.getInt("slot")) * 0.5
    actual.put("region720", JSONArray(listOf(region.x, region.y, region.width, region.height)))
    if (e.readings.isNotEmpty()) actual.put("ocr", JSONArray(e.readings.map { JSONArray(listOf(it.text, it.confidence)) }))
    check(status != "wrong" && (expected != 0 || e.locked) && (mode != SkillLevelMode.Skill || status == "correct")) {
        "panel $actual expected=$expected"
    }
    return actual
}
