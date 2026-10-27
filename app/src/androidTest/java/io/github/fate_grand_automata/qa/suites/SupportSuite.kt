package io.github.fate_grand_automata.qa.suites

import io.github.fate_grand_automata.qa.FixtureApi
import io.github.fate_grand_automata.qa.properties
import io.github.fate_grand_automata.scripts.*
import io.github.fate_grand_automata.scripts.enums.*
import io.github.fate_grand_automata.scripts.prefs.*
import io.github.fate_grand_automata.scripts.supportSelection.*
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

/**
 * Support-screen suite. Full-screen cases read every row with the production readers and compare with the labels.
 * Crop cases (frame "crop") place one row fragment at a fixed position, so only the reads the case asks for run;
 * their `policy` expectations use the same rule as tools/validate_support_scores.py.
 */
internal fun FixtureApi.verifySupport(): JSONObject {
    if (case.optString("view") == "CESelection") return verifySupportCe()
    val result = JSONObject(); val display = SupportSkillDisplay(this)
    val full = !isCrop
    var view = SupportSkillDisplay.View.Normal
    if (full) {
        if (live) display.ensureNormal()
        view = display.read(); result.put("view", view.name)
        if (case.has("view")) check(view.name == case.getString("view")) { "View $view != ${case.getString("view")}" }
    }
    val bounds = if (full) useSameSnapIn { SupportBoundsFinder(this).all() } else emptyList()
    if (full) result.put("bounds", JSONArray(bounds.map { it.region.y / 2.0 }))
    val expected = case.getJSONArray("rows"); val rows = JSONArray()
    val scoreEvidence = JSONArray()
    val scoreDiagnostics = object : io.github.fate_grand_automata.scripts.diagnostics.IReaderDiagnostics {
        override val enabled = case.optBoolean("scoreDiagnostics")
        override val runId = "device-qa"
        override fun record(reader: String, stem: String, images: Map<String, Pattern>, json: String) {
            scoreEvidence.put(JSONObject(json))
        }
    }
    val np = SupportNpLevelReader(this, ocr); val score = SupportScoreReader(this, ocr, scoreDiagnostics); val skills = SupportSkillLevelReader(this)
    for (i in 0 until expected.length()) {
        val row = expected.getJSONObject(i)
        val y = if (full) row.getInt("y") else row.getInt("rowY720")
        val bound = if (full) {
            checkNotNull(bounds.minByOrNull { abs(it.region.y / 2 - y) }).also {
                check(abs(it.region.y / 2 - y) <= 2) { "Missing row $y: $bounds" }
            }
        } else SupportBounds(locations.support.defaultBounds.copy(y = y * 2))
        val values = useSameSnapIn {
            val n = if (full || row.has("np")) np.read(bound) else null
            val readsScore = full || row.optBoolean("readScore") || row.has("class") || row.has("grand") || row.has("policy")
            val s = if (readsScore) score.read(bound, true, true) else null
            val k = if (full || row.has("skills")) skills.read(bound, view == SupportSkillDisplay.View.Append) else emptyList()
            JSONObject().put("np", n ?: JSONObject.NULL).put("class", s?.classScore ?: JSONObject.NULL)
                .put("grand", s?.grandScore ?: JSONObject.NULL).put("display", s?.display?.name ?: "Unknown").put("skills", JSONArray(k))
        }
        rows.put(values)
        // D-1 may intentionally veto a previously readable field. Preserve its ground-truth
        // label and require an actual null, rather than relabelling the image or skipping it.
        val unknownFields = row.optJSONArray("protectiveUnknown")?.let { fields ->
            (0 until fields.length()).map { fields.getString(it) }.toSet()
        } ?: emptySet()
        require(unknownFields.all { it == "class" || it == "grand" })
        for (key in unknownFields) check(values.isNull(key)) { "Expected protective unknown $key at $y: $values" }
        for (key in listOf("np", "class", "grand", "display")) if (row.has(key)) {
            if (key in unknownFields) continue
            // Keep the ground-truth Grand label while asserting a protective unknown is allowed.
            val allowed = if (key == "grand") row.optJSONArray("grandAllowed") else null
            val actual = values.get(key).toString()
            check(if (allowed == null) actual == row.get(key).toString() else
                (0 until allowed.length()).any { allowed.get(it).toString() == actual }) {
                "$key at $y: $values != $row"
            }
        }
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
        if (row.has("policy")) {
            val policy = row.getJSONObject("policy")
            for (key in listOf("classOnly", "grandOnly", "bothScores")) {
                val veto = when (key) {
                    "classOnly" -> "class" in unknownFields
                    "grandOnly" -> "grand" in unknownFields
                    else -> unknownFields.isNotEmpty()
                }
                check(values.getBoolean(key) == (policy.getBoolean(key) && !veto)) { "$key at $y: $values != $policy" }
            }
        }
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
    if (scoreDiagnostics.enabled) result.put("scoreDiagnostics", scoreEvidence)
    return result.put("rows", rows)
}

/** Owner Grand CE settings through CESelection.check, with the product ImageLoader and OpenCV. No gestures. */
private fun FixtureApi.verifySupportCe(): JSONObject {
    check(!live && !isCrop) { "CE selection QA requires a saved full frame" }
    val ces = case.getJSONArray("builtInCes").let { list -> (0 until list.length()).map { list.getString(it) } }
    val bounds = useSameSnapIn { SupportBoundsFinder(this).all() }
    val rows = JSONArray()
    val expected = case.getJSONArray("rows")
    for (i in 0 until expected.length()) {
        val row = expected.getJSONObject(i)
        val y = row.getInt("y")
        // The bottom row's confirm button is outside the capture. Only that explicitly labelled partial
        // row uses measured bounds; complete rows must be found by the product SupportBoundsFinder.
        val bound = if (row.optBoolean("partial")) SupportBounds(locations.support.defaultBounds.copy(y = y * 2))
            else checkNotNull(bounds.minByOrNull { abs(it.region.y / 2 - y) }).also {
                check(abs(it.region.y / 2 - y) <= 2) { "Missing CE row $y: $bounds" }
            }
        val star = SupportSelectionStarChecker(this)
        val grand = SupportSelectionGrandChecker(this)
        val search = bound.region.clip(locations.support.grandCeListRegion)
        val isGrand = useSameSnapIn {
            grand.isGrandPresent(locations.support.grandCeLabelRegion.copy(y = search.y + 40))
        }
        check(isGrand) { "Expected Grand row at $y" }
        val checks = JSONArray()
        val effects = row.getJSONObject("effects")
        for (effect in BondCEEffectEnum.entries) {
            if (!effects.has(effect.name)) continue
            val prefs = properties<ISupportPreferences> {
                when (it) {
                    "getMlb" -> case.getBoolean("mlb")
                    "getRequireBothNormalAndRewardMatch" -> case.getBoolean("bothSlots")
                    "getBondCEEffect" -> effect
                    else -> error("Unexpected CE preference $it")
                }
            }
            val before = supportCeLogs.size
            val matched = useSameSnapIn { CESelection(this, prefs, star, grand).check(ces, bound) }
            check(supportCeLogs.size == before + 1) { "Expected one Grand CE diagnostic per check" }
            val evidence = supportCeLogs.last()
            fun match(value: Match?) = value?.let {
                JSONObject().put("score", it.score).put("region", it.region.toString())
            } ?: JSONObject.NULL
            checks.put(JSONObject().put("effect", effect.name).put("matched", matched)
                .put("bondNP", match(evidence.np)).put("bondDefault", match(evidence.default))
                .put("log", evidence.message))
            check(matched == effects.getBoolean(effect.name)) { "CE $effect at $y: ${evidence.message}" }
            check(evidence.matched == matched)
            val expectedBond = row.getString("bondIcon")
            check((evidence.np?.score ?: -1.0) >= CESelection.BOND_EFFECT_SIMILARITY == (expectedBond == "NP"))
            check((evidence.default?.score ?: -1.0) >= CESelection.BOND_EFFECT_SIMILARITY == (expectedBond == "Default"))
        }
        rows.put(JSONObject().put("y", y).put("grand", isGrand).put("checks", checks))
    }
    return JSONObject().put("view", "CESelection").put("rows", rows)
}
