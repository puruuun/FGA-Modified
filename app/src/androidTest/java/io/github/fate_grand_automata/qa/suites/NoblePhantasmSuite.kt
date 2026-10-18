package io.github.fate_grand_automata.qa.suites

import io.github.fate_grand_automata.qa.FixtureApi
import io.github.fate_grand_automata.scripts.locations.SupportNpLayout
import io.github.fate_grand_automata.scripts.supportSelection.NoblePhantasmSelection
import io.github.fate_grand_automata.scripts.supportSelection.SupportBoundsFinder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Noble Phantasm name suite (0006 §4.4). Each case names the support rows by their order on the
 * frame, and each match says which saved NP name must (or must not) match that row's NP band.
 * The production selection decides pass/fail; bestScore is recorded so the threshold can be checked.
 */
internal fun FixtureApi.verifyNoblePhantasm(): JSONObject {
    val result = JSONObject()
    val selection = NoblePhantasmSelection(this)
    val bounds = useSameSnapIn { SupportBoundsFinder(this).all() }
    val expected = case.getJSONArray("rows")
    val rows = JSONArray()
    for (i in 0 until expected.length()) {
        val row = expected.getJSONObject(i)
        val index = row.getInt("rowIndex")
        val bound = checkNotNull(bounds.getOrNull(index)) { "Missing support row $index among ${bounds.size} rows" }
        val matches = JSONArray()
        val items = row.getJSONArray("matches")
        for (j in 0 until items.length()) {
            val item = items.getJSONObject(j)
            val name = item.getString("entry")
            val (score, pass) = useSameSnapIn {
                val band = SupportNpLayout.noblePhantasmBand(bound.region)
                selection.bestScore(listOf(name), band) to selection.check(listOf(name), bound)
            }
            check(pass == item.getBoolean("expected")) {
                "NP \"$name\" on row $index: matched=$pass, expected=${item.getBoolean("expected")}, bestScore=$score"
            }
            matches.put(JSONObject().put("entry", name).put("bestScore", score ?: JSONObject.NULL).put("matched", pass))
        }
        rows.put(JSONObject().put("rowIndex", index).put("matches", matches))
    }
    return result.put("rows", rows).put("threshold", NoblePhantasmSelection.NP_NAME_SIMILARITY)
}
