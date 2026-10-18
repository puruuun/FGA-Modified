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
        else -> {
            val box = case.getJSONArray("card720")
            val cell = useSameSnapIn { reader.readCard(Region(box.getInt(0), box.getInt(1), box.getInt(2), box.getInt(3)) * 2.0) }
            val enabled = setOf(FpSellPolicy.Category.Servant1, FpSellPolicy.Category.Servant2,
                FpSellPolicy.Category.Exp4, FpSellPolicy.Category.Exp5)
            val every = FpSellPolicy.Category.entries.toSet()
            JSONObject().put("kind", cell.kind?.name ?: "Unknown").put("stars", cell.stars ?: 0)
                .put("new", cell.new ?: false).put("selected", cell.selected ?: false)
                .put("lock", cell.lock.name).put("excluded", cell.excluded)
                .put("candidate", FpSellPolicy.select(cell, FpSellPolicy.Options(enabled, true, true)))
                // Strongest setting: every category, NEW! not required, real selling. Excluded tiles must stay false here.
                .put("candidateReal", FpSellPolicy.select(cell, FpSellPolicy.Options(every, false, false)))
                .put("candidateDryAll", FpSellPolicy.select(cell, FpSellPolicy.Options(every, false, true)))
        }
    }
    val expected = case.getJSONObject("expected")
    for (key in expected.keys()) check(actual.get(key) == expected.get(key)) { "fpSell $key: $actual != $expected" }
    return actual
}
