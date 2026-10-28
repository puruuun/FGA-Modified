package io.github.fate_grand_automata.qa.suites

import io.github.fate_grand_automata.qa.FixtureApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.locations.WeeklyRouteLocations
import io.github.fate_grand_automata.scripts.modules.WeeklyRouteScreens
import io.github.fate_grand_automata.scripts.modules.WeeklyVaultReader
import org.json.JSONArray
import org.json.JSONObject

internal fun FixtureApi.verifyWeeklyVault(): JSONObject {
    check(!live)
    val loc = WeeklyRouteLocations(locations)
    val actual = JSONObject()
    val screen = WeeklyRouteScreens(this, loc).read()
    actual.put("screen", screen.name)
    check(screen.name == case.getString("screen")) { "screen=$screen expected=${case.getString("screen")}" }
    for ((name, image, threshold) in listOf(Triple("gate", Images.WeeklyGate, 0.8), Triple("daily", Images.WeeklyDaily, 0.85))) {
        val score = useColor {
            val best = loc.listArea.findAll(images[image], 0.01).maxOfOrNull { it.score } ?: 0.0
            if (name == "gate" && case.getBoolean("gate")) {
                val other = loc.homeOtherBanner
                actual.put("otherHomeBanner", other.findAll(images[image], 0.01).maxOfOrNull { it.score } ?: 0.0)
            }
            best
        }
        actual.put(name, score)
        check((score >= threshold) == case.getBoolean(name)) { "$name score=$score" }
    }
    val panels = if (case.has("accepted")) WeeklyVaultReader(this, loc, ocr).read() else emptyList()
    actual.put("panels", JSONArray(panels.map { panel ->
        JSONObject().put("hue", panel.color?.hue).put("saturation", panel.color?.saturation).put("yellow", panel.color?.yellow)
            .put("name", panel.name).put("ap", panel.ap).put("accepted", panel.accepted).put("reason", panel.reason)
            .put("tap720", JSONArray(listOf(panel.anchor.tap.x / 2.0, panel.anchor.tap.y / 2.0)))
            .put("rawNames", JSONArray(panel.rawNames.map { "${it.text}@${it.confidence}" }))
    }))
    if (case.has("accepted")) check(panels.any { it.accepted } == case.getBoolean("accepted")) { actual.toString() }
    if (case.has("tap720")) {
        val panel = panels.single { it.accepted }
        actual.put("rawAp", JSONArray(panel.anchor.ap.getPattern().use { image ->
            WeeklyVaultReader.AP_PROFILES.map {
                ocr.detectTextLine(image, it).let { read -> "${read.text}@${read.confidence}" }
            }
        }))
        if (case.has("expectedAp")) check(panel.ap == case.getInt("expectedAp")) { "AP is log-only but should read the fixture: $actual" }
        val tap = case.getJSONArray("tap720")
        check(kotlin.math.abs(panel.anchor.tap.x / 2.0 - tap.getDouble(0)) <= 1 &&
            kotlin.math.abs(panel.anchor.tap.y / 2.0 - tap.getDouble(1)) <= 1) { actual.toString() }
        check(panel.anchor.name.contains(io.github.lib_automata.Region(panel.anchor.tap.x, panel.anchor.tap.y, 1, 1)))
    }
    return actual
}
