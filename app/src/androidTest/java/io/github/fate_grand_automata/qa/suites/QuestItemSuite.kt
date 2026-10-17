package io.github.fate_grand_automata.qa.suites

import io.github.fate_grand_automata.qa.FixtureApi
import io.github.fate_grand_automata.scripts.modules.QuestItemReader
import org.json.JSONObject

/**
 * Quest item suite: reads a composed screen with the production QuestItemReader.
 * Expected values: view "Single" (kind "StormPod" or "Teapot", on) or "Dialog" (storm_on, tea_on).
 */
internal fun FixtureApi.verifyQuestItems(): JSONObject {
    val view = QuestItemReader(this).read()
    val expected = case.getJSONObject("expected")
    val actual = when (view) {
        is QuestItemReader.View.Single -> JSONObject().put("view", "Single")
            .put("kind", view.kind.name).put("on", view.button.on)
        is QuestItemReader.View.Dialog -> JSONObject().put("view", "Dialog")
            .put("storm_on", view.stormPod.on).put("tea_on", view.teapot.on)
        QuestItemReader.View.Unavailable -> JSONObject().put("view", "Unavailable")
        QuestItemReader.View.Unknown -> JSONObject().put("view", "Unknown")
    }
    check(actual.toString() == expected.toString()) { "quest item view $actual != $expected" }
    return actual
}
