package io.github.fate_grand_automata.qa.suites

import io.github.fate_grand_automata.qa.FixtureApi
import io.github.fate_grand_automata.scripts.*
import io.github.fate_grand_automata.scripts.enums.*
import io.github.fate_grand_automata.scripts.prefs.*
import io.github.fate_grand_automata.scripts.supportSelection.*
import io.github.fate_grand_automata.qa.properties
import io.github.lib_automata.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

private fun supportPrefs(minimum: Int = 2) = properties<ISupportPreferences> {
    when (it) {
        "getMinimumNpLevel" -> minimum
        "getNpLevelCheck", "getAppendSkillCheck" -> true
        "getAppendSkillsMax" -> listOf(false, true, false, false, false)
        "getPreferredServants" -> listOf("device-test")
        "getBondCEEffect" -> BondCEEffectEnum.Ignore
        else -> false
    }
}

/** Support-screen suite: reads every expected row with the production readers and compares against the case's labels. */
internal fun FixtureApi.verifySupport(): JSONObject {
    val result = JSONObject(); val display = SupportSkillDisplay(this)
    if (live) display.ensureNormal()
    val view = display.read(); result.put("view", view.name)
    if (case.has("view")) check(view.name == case.getString("view")) { "View $view != ${case.getString("view")}" }
    val bounds = useSameSnapIn { SupportBoundsFinder(this).all() }
    result.put("bounds", JSONArray(bounds.map { it.region.y / 2.0 }))
    val expected = case.getJSONArray("rows"); val rows = JSONArray()
    val np = SupportNpLevelReader(this, ocr); val score = SupportScoreReader(this, ocr); val skills = SupportSkillLevelReader(this)
    for (i in 0 until expected.length()) {
        val row = expected.getJSONObject(i); val y = row.getInt("y")
        val bound = checkNotNull(bounds.minByOrNull { abs(it.region.y / 2 - y) })
        check(abs(bound.region.y / 2 - y) <= 2) { "Missing row $y: $bounds" }
        val values = useSameSnapIn {
            val n = np.read(bound); val s = score.read(bound, true, true); val k = skills.read(bound, view == SupportSkillDisplay.View.Append)
            JSONObject().put("np", n ?: JSONObject.NULL).put("class", s.classScore ?: JSONObject.NULL)
                .put("grand", s.grandScore ?: JSONObject.NULL).put("display", s.display.name).put("skills", JSONArray(k))
        }
        rows.put(values)
        for (key in listOf("np", "class", "grand")) if (row.has(key))
            check(values.get(key).toString() == row.get(key).toString()) { "$key at $y: $values != $row" }
        if (row.has("skills")) check(values.getJSONArray("skills").toString() == row.getJSONArray("skills").toString()) { "Skills at $y: $values != $row" }
        val npChecks = JSONArray()
        if (row.has("np") && !row.isNull("np")) for (minimum in 1..5) {
            val pass = useSameSnapIn { SupportNpSelection(this, supportPrefs(minimum), np).check(bound) }
            check(pass == (row.getInt("np") >= minimum)) { "NP minimum $minimum at $y" }; npChecks.put(pass)
        }
        values.put("npMinimumChecks", npChecks)
        val scores = SupportScores(if (values.isNull("class")) null else values.getInt("class"),
            if (values.isNull("grand")) null else values.getInt("grand"), SupportScoreDisplay.valueOf(values.getString("display")))
        values.put("classOnly", SupportScoreSelection.matches(scores, true, false))
            .put("grandOnly", SupportScoreSelection.matches(scores, false, true)).put("bothScores", SupportScoreSelection.matches(scores, true, true))
        if (live && case.optBoolean("appendCycle")) {
            val pass = AppendSkillSelection(this, supportPrefs(), display, skills).check(bound)
            values.put("appendSlot2", pass).put("restoredView", display.read().name)
            if (row.has("appendSlot2")) check(pass == row.getBoolean("appendSlot2"))
            check(display.read() == SupportSkillDisplay.View.Normal) { "Normal display was not restored" }
        }
        val items = row.optJSONArray("matches") ?: JSONArray(); val checks = JSONArray()
        val star = SupportSelectionStarChecker(this); val grand = SupportSelectionGrandChecker(this)
        for (j in 0 until items.length()) {
            val item = items.getJSONObject(j); val name = item.getString("name")
            val pass = useSameSnapIn {
                if (item.getString("kind") == "Servant") ServantSelection(this, supportPrefs(), star, grand, skills).check(listOf(name), bound)
                else CESelection(this, supportPrefs(), star, grand).check(listOf(name), bound)
            }
            checks.put(JSONObject().put("name", name).put("kind", item.getString("kind")).put("matched", pass))
            check(pass == item.getBoolean("expected")) { "Imported image mismatch at $y: $item -> $pass" }
        }
        values.put("importMatches", checks)
    }
    return result.put("rows", rows)
}
