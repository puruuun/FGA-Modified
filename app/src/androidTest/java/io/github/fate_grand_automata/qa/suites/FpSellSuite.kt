package io.github.fate_grand_automata.qa.suites

import io.github.fate_grand_automata.qa.FixtureApi
import io.github.fate_grand_automata.scripts.locations.FpSellLocations
import io.github.fate_grand_automata.scripts.modules.FpSellReader
import io.github.fate_grand_automata.scripts.modules.FpBannerReturn
import io.github.fate_grand_automata.scripts.modules.FpSellPolicy
import io.github.lib_automata.Region
import org.json.JSONObject

/** Offline fixtures only: the exact production reader and selection policy, without gestures. */
internal fun FixtureApi.verifyFpSell(): JSONObject {
    check(!live) { "FP sell QA must never operate a live game" }
    val reader = FpSellReader(this, FpSellLocations(locations))
    val actual = when (case.getString("view")) {
        "layout" -> JSONObject().put("seven", reader.isSevenColumns()).put("sellScreen", useSameSnapIn { reader.onSellScreen() })
        "fullDialog" -> JSONObject().put("servantWarning", reader.servantFullWarning())
        "banner" -> FpBannerReturn(this, reader.loc).readDots().let {
            JSONObject().put("active", it?.active ?: -1).put("count", it?.count ?: 0)
        }
        "page" -> {
            val enabled = setOf(FpSellPolicy.Category.Servant1, FpSellPolicy.Category.Servant2,
                FpSellPolicy.Category.Servant3, FpSellPolicy.Category.Servant4)
            val options = FpSellPolicy.Options(enabled, true, true)
            val rows = org.json.JSONArray()
            val summary = StringBuilder()
            for (tile in reader.readPage()) {
                val row = JSONObject().put("col", tile.column).put("x", tile.region.x / 2).put("y", tile.region.y / 2)
                    .put("skip", tile.skip ?: "")
                val cell = tile.cell
                if (cell != null) {
                    val distance = reader.selectionDistance(tile.region)
                    val selected = FpSellReader.selectionState(distance)
                    val chosen = FpSellPolicy.select(cell.copy(selected = selected), options)
                    row.put("kind", cell.kind?.name ?: "Unknown").put("stars", cell.stars ?: 0)
                        .put("starScores", tile.stars.take(2).joinToString(" ") { "${it.first}:${Math.round(it.second * 1000) / 1000.0}" })
                        .put("slots", reader.starSlotScores(tile.region).joinToString(" ") { "${Math.round(it * 100) / 100.0}" })
                        .put("new", cell.new ?: false).put("newScore", Math.round(tile.newScore * 1000) / 1000.0)
                        .put("lock", cell.lock.name).put("excluded", cell.excluded)
                        .put("selected", selected ?: "unknown").put("select", chosen)
                    summary.append(if (chosen) '1' else '0')
                } else summary.append('-')
                rows.put(row)
            }
            JSONObject().put("tiles", rows).put("tileCount", rows.length()).put("selectMap", summary.toString())
        }
        else -> {
            val box = case.getJSONArray("card720")
            val card = Region(box.getInt(0), box.getInt(1), box.getInt(2), box.getInt(3)) * 2.0
            val cell = useSameSnapIn { reader.readCard(card) }
            // The selection is read from its own colour frame, never inside a snapshot (D-19).
            val distance = reader.selectionDistance(card)
            val selected = FpSellReader.selectionState(distance)
            val enabled = setOf(FpSellPolicy.Category.Servant1, FpSellPolicy.Category.Servant2,
                FpSellPolicy.Category.Exp4, FpSellPolicy.Category.Exp5)
            val every = FpSellPolicy.Category.entries.toSet()
            // A borderline read is reported as "unknown" so an expectation of true or false fails instead of passing by default.
            JSONObject().put("kind", cell.kind?.name ?: "Unknown").put("stars", cell.stars ?: 0)
                .put("new", cell.new ?: false).put("selected", selected ?: "unknown")
                .put("selectionDistance", distance?.let { Math.round(it * 10) / 10.0 } ?: -1.0)
                .put("lock", cell.lock.name).put("excluded", cell.excluded)
                .put("newScore", Math.round(reader.newScore(card) * 1000) / 1000.0)
                .put("slots", reader.starSlotScores(card).joinToString(" ") { "${Math.round(it * 100) / 100.0}" })
                .put("candidate", FpSellPolicy.select(cell.copy(selected = selected), FpSellPolicy.Options(enabled, true, true)))
                // Strongest setting: every category, NEW! not required, real selling. Excluded tiles must stay false here.
                .put("candidateReal", FpSellPolicy.select(cell.copy(selected = selected), FpSellPolicy.Options(every, false, false)))
                .put("candidateDryAll", FpSellPolicy.select(cell.copy(selected = selected), FpSellPolicy.Options(every, false, true)))
        }
    }
    val expected = case.getJSONObject("expected")
    for (key in expected.keys()) check(actual.get(key) == expected.get(key)) { "fpSell $key: $actual != $expected" }
    return actual
}
