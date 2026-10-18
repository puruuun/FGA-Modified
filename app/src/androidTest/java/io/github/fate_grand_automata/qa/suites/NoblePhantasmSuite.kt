package io.github.fate_grand_automata.qa.suites

import io.github.fate_grand_automata.qa.FixtureApi
import io.github.fate_grand_automata.scripts.locations.SupportNpLayout
import io.github.fate_grand_automata.scripts.supportSelection.NoblePhantasmSelection
import io.github.fate_grand_automata.scripts.supportSelection.SupportBounds
import io.github.fate_grand_automata.scripts.supportSelection.SupportBoundsFinder
import io.github.fate_grand_automata.scripts.supportSelection.SupportNpLevelReader
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Noble Phantasm suite (0006 §4.4, §8). Each case names a support row either by its position on the
 * frame ("rowY720", the row's top in 720p, matched within 3 px) or by its detected order ("rowIndex").
 * Each match says which saved NP image must (or must not) match that row's NP block. The production
 * selection decides pass/fail; bestScore is recorded so the threshold can be checked.
 */
internal fun FixtureApi.verifyNoblePhantasm(): JSONObject {
    val result = JSONObject()
    val selection = NoblePhantasmSelection(this)
    val bounds = useSameSnapIn { SupportBoundsFinder(this).all() }
    result.put("boundsY720", JSONArray(bounds.map { it.region.y / 2.0 }))
    val expected = case.getJSONArray("rows")
    val rows = JSONArray()
    for (i in 0 until expected.length()) {
        val row = expected.getJSONObject(i)
        val bound = findRow(bounds, row)
        if (row.has("crop720")) {
            useSameSnapIn {
                val prefix = checkNotNull(SupportNpLevelReader(this, ocr).findLevelPrefix(bound)) { "Missing Lv. marker" }
                val crop = SupportNpLayout.noblePhantasmCrop(bound.region, row.getBoolean("grand"), prefix) * 0.5
                val expectedCrop = row.getJSONArray("crop720")
                check(listOf(crop.x, crop.y, crop.width, crop.height) == (0..3).map(expectedCrop::getInt)) {
                    "Maker crop $crop differs from fixture $expectedCrop"
                }
            }
        }
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
                "NP \"$name\" on row ${bound.region.y / 2} (720p): matched=$pass, expected=${item.getBoolean("expected")}, bestScore=$score"
            }
            matches.put(JSONObject().put("entry", name).put("bestScore", score ?: JSONObject.NULL).put("matched", pass))
        }
        rows.put(JSONObject().put("rowY720", bound.region.y / 2).put("matches", matches))
    }
    return result.put("rows", rows).put("threshold", NoblePhantasmSelection.NP_NAME_SIMILARITY)
}

private fun findRow(bounds: List<SupportBounds>, row: JSONObject): SupportBounds {
    if (row.has("rowY720")) {
        val y = row.getInt("rowY720")
        val nearest = checkNotNull(bounds.minByOrNull { abs(it.region.y / 2 - y) }) { "No support rows found" }
        check(abs(nearest.region.y / 2 - y) <= 3) { "No support row near $y (720p): ${bounds.map { it.region.y / 2 }}" }
        return nearest
    }
    val index = row.getInt("rowIndex")
    return checkNotNull(bounds.getOrNull(index)) { "Missing support row $index among ${bounds.size} rows" }
}
