package io.github.fate_grand_automata.qa.suites

import io.github.fate_grand_automata.qa.FixtureApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.entrypoints.isRankUpScreen
import org.json.JSONObject

/** Offline frames only: executes the battle loop's detector without any gameplay action. */
internal fun FixtureApi.verifyRankUp(): JSONObject = useSameSnapIn {
    check(!case.optBoolean("live")) { "Rank-up QA requires an offline fixture" }
    val detected = isRankUpScreen()
    val expected = case.getJSONObject("expected").getBoolean("rankUp")
    // A low diagnostic threshold records negative evidence, never changes product detection.
    val score = locations.rankUpRegion.find(images[Images.RankUp], similarity = 0.0)?.score ?: 0.0
    check(detected == expected) { "rankUp=$detected != $expected (score=$score)" }
    JSONObject().put("rankUp", detected).put("score", score)
}
