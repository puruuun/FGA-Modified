package io.github.fate_grand_automata.qa.suites

import io.github.fate_grand_automata.qa.FixtureApi
import io.github.fate_grand_automata.scripts.locations.FpSellLocations
import io.github.fate_grand_automata.scripts.modules.FpBannerReturn
import io.github.fate_grand_automata.scripts.modules.FpSellReader
import io.github.fate_grand_automata.scripts.modules.FpSellPolicy
import io.github.lib_automata.Region
import org.json.JSONObject
import org.json.JSONArray

/** Offline fixtures only: production geometry, lock/exclusion, selected state and policy, without gestures. */
internal fun FixtureApi.verifyFpSell(): JSONObject {
    check(!live) { "FP sell QA must never operate a live game" }
    val reader = FpSellReader(this, FpSellLocations(locations))
    fun cell(card: Region): JSONObject {
        val value = reader.readCard(card)
        return JSONObject().put("present", value.present).put("lock", value.lock.name).put("excluded", value.excluded)
            .put("selected", value.selected ?: "unknown")
            .put("candidate", FpSellPolicy.select(value, FpSellPolicy.Options(true)))
            .put("candidateReal", FpSellPolicy.select(value, FpSellPolicy.Options(false)))
    }
    val actual = when (case.getString("view")) {
        "layout" -> JSONObject().put("seven", reader.isSevenColumns()).put("sellScreen", useSameSnapIn { reader.onSellScreen() })
        "bannerTitle" -> fixturePattern(case.getJSONArray("pieces").getJSONObject(0).getString("file")).use {
            JSONObject().put("matches", useSameSnapIn { FpBannerReturn(this, reader.loc).matches(it) })
        }
        "fullDialog" -> JSONObject().put("servantWarning", reader.servantFullWarning())
        "selection" -> {
            val b = case.getJSONArray("card720")
            val card = Region(b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3)) * 2.0
            val distance = reader.selectionDistance(card)
            JSONObject().put("selected", FpSellReader.selectionState(distance) ?: "unknown")
                .put("selectionDistance", distance ?: -1.0)
        }
        "page" -> {
            val tiles = reader.readPage()
            val rows = JSONArray()
            tiles.forEach { tile ->
                rows.put((if (tile.skip == null) cell(tile.region) else JSONObject())
                    .put("col", tile.column).put("x", tile.region.x / 2).put("y", tile.region.y / 2)
                    .put("skip", tile.skip ?: ""))
            }
            val evaluated = FpSellPolicy.evaluatedRows(tiles)
            JSONObject().put("tiles", rows).put("evaluatedRows", evaluated.size)
                .put("bottomEvaluatedColumns", tiles.count { it.skip == null && it.region.y == evaluated.lastOrNull() })
                .put("tileCount", tiles.size)
        }
        else -> {
            val b = case.getJSONArray("card720")
            val card = Region(b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3)) * 2.0
            val fitted = if (case.optBoolean("checkGrid")) reader.readPage().firstOrNull {
                it.skip == null && it.column == case.getInt("column") && kotlin.math.abs(it.region.y - card.y) <= 16
            }?.region else null
            val result = cell(fitted ?: card)
            // Coverage checks require every expected fully visible tile to be part of the page's fitted grid.
            if (case.optBoolean("checkGrid")) result.put("inGrid", reader.readPage().any {
                it.skip == null && it.column == case.getInt("column") && kotlin.math.abs(it.region.y - card.y) <= 16
            })
            result
        }
    }
    val expected = case.getJSONObject("expected")
    for (key in expected.keys()) check(actual.get(key) == expected.get(key)) { "fpSell $key: $actual != $expected" }
    return actual
}
