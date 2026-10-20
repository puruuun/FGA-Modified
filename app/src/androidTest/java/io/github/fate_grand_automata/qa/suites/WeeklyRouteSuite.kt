package io.github.fate_grand_automata.qa.suites

import io.github.fate_grand_automata.qa.FixtureApi
import io.github.fate_grand_automata.scripts.locations.QuestPanelLocations
import io.github.fate_grand_automata.scripts.modules.QuestPanelMatch
import io.github.fate_grand_automata.scripts.modules.QuestPanelReader
import org.json.JSONArray
import org.json.JSONObject

/**
 * Offline fixtures of the 0009 quest panel (owner captures, JP and KR): the production panel reader with no gestures.
 * A case names the route quest it expects to find (`targetName`, `targetAp`). `verdict` is the class name of the
 * [io.github.fate_grand_automata.scripts.modules.PanelVerdict] the weekly route script would act on.
 */
internal fun FixtureApi.verifyWeeklyRoute(): JSONObject {
    check(!live) { "weekly route QA must never operate a live game" }
    if (case.has("view")) return verifyWeeklyNavigation()
    val reader = QuestPanelReader(this, QuestPanelLocations(locations), ocr)
    val server = prefs.gameServer
    val panel = reader.read(server)
    val verdict = QuestPanelMatch.verdict(panel, case.getInt("targetAp"), case.getString("targetName"), server)
    val actual = JSONObject()
        .put("free", panel.free)
        .put("ap", panel.ap ?: "unread")
        .put("verdict", verdict::class.simpleName)
        .put("nameWidthPx", panel.nameWidthPx ?: "none")
        .put("nameCells", panel.nameCells?.let { "%.2f".format(it) } ?: "none")
        .put("expectedCells", "%.2f".format(QuestPanelMatch.nameCells(case.getString("targetName"))))
    if (panel.free) actual.put("rawAp", JSONArray(reader.apReadings().map { "${it.text.trim()}@${it.confidence}" }))
    val expected = case.getJSONObject("expected")
    for (key in expected.keys()) check(actual.get(key).toString() == expected.get(key).toString()) { "weeklyRoute $key: $actual != $expected" }
    return actual
}
