package io.github.fate_grand_automata.qa.suites

import io.github.fate_grand_automata.qa.FixtureApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.locations.FpSellLocations
import io.github.fate_grand_automata.scripts.modules.FpSellButtons
import io.github.fate_grand_automata.scripts.modules.FpBannerReturn
import io.github.fate_grand_automata.scripts.modules.FpSellReader
import io.github.fate_grand_automata.scripts.modules.FpSellPolicy
import io.github.lib_automata.Region
import org.json.JSONObject
import org.json.JSONArray

/** Offline fixtures only: production geometry, padlock, selected state and policy, without gestures. */
internal fun FixtureApi.verifyFpSell(): JSONObject {
    check(!live) { "FP sell QA must never operate a live game" }
    val reader = FpSellReader(this, FpSellLocations(locations))
    fun cell(card: Region): JSONObject {
        val value = reader.readCard(card)
        return JSONObject().put("present", value.present).put("lock", value.lock.name)
            .put("selected", value.selected ?: "unknown")
            .put("candidate", FpSellPolicy.select(value, FpSellPolicy.Options(true)))
            .put("candidateReal", FpSellPolicy.select(value, FpSellPolicy.Options(false)))
    }
    val actual = when (case.getString("view")) {
        "workshopButtons" -> JSONObject().put("menu",
            useSameSnapIn { FpSellButtons(this).visible(reader.loc.menu, Images.FpSellMenu) })
        "returnButtons" -> {
            val patterns = listOf(Images.FpSellSummonButton, Images.FpSellSummonButtonFree)
                .mapNotNull { runCatching { images[it] }.getOrNull() }
            JSONObject().put("arrow", reader.loc.leftArrowRegion.exists(images[Images.FpSellLeftArrow], similarity = 0.9))
                .put("summon", patterns.any { reader.loc.summonButton.exists(it, similarity = 0.9) || reader.loc.dailySummonButton.exists(it, similarity = 0.9) })
        }
        // Design 0012 D-4: the summon screen, workshop menu and dialogs measured on the owner's 2560x1080 captures.
        "summonMenu" -> JSONObject().put("summonMenu", useSameSnapIn { FpSellButtons(this).visible(reader.loc.summonMenu, Images.FpSellSummonMenu) })
        "summonScreen" -> JSONObject()
            .put("fpLabel", useSameSnapIn { findImage(locations.fp.summonCheck, Images.FriendSummon) || findImage(locations.fp.initialSummonCheck, Images.FriendSummon) })
            .put("arrow", useSameSnapIn { reader.loc.leftArrowRegion.exists(images[Images.FpSellLeftArrow], similarity = 0.9) })
        // Exact AutoFriendGacha validators use the user's default similarity (0.8 in fixtures).
        "fpChecks" -> JSONObject()
            .put("first", useSameSnapIn { images[Images.FriendSummon] in locations.fp.summonCheck })
            .put("initial", useSameSnapIn { images[Images.FriendSummon] in locations.fp.initialSummonCheck })
        "buttonRegions" -> {
            val patterns = listOf(Images.FpSellSummonButton, Images.FpSellSummonButtonFree)
                .mapNotNull { runCatching { images[it] }.getOrNull() }
            JSONObject().put("normal", patterns.any { reader.loc.summonButton.exists(it, similarity = 0.9) })
                .put("daily", patterns.any { reader.loc.dailySummonButton.exists(it, similarity = 0.9) })
        }
        "summonGuard" -> {
            val banner = FpBannerReturn(this, reader.loc)
            try {
                banner.remember()
                banner.verifySummon()
                JSONObject().put("verified", true)
            } finally { banner.release() }
        }
        "limitSell" -> JSONObject().put("sell", reader.loc.fullSell.exists(images[Images.FpSellSell], similarity = 0.9))
        "layout" -> JSONObject().put("seven", reader.isSevenColumns())
            .put("grid", useSameSnapIn { reader.gridReadable() })
            .put("servantTab", useSameSnapIn { reader.onServantTab() })
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
