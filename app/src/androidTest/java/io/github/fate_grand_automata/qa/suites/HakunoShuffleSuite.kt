package io.github.fate_grand_automata.qa.suites

import io.github.fate_grand_automata.qa.FixtureApi
import io.github.fate_grand_automata.scripts.Images
import org.json.JSONObject

internal fun FixtureApi.verifyHakunoShuffle(): JSONObject = useSameSnapIn {
    val region = locations.battle.shuffleNPShortageRegion
    val close = images[Images.Close]
    val best = region.findAll(close, 0.01).maxByOrNull { it.score }
    val found = region.exists(close)
    check(found == case.getBoolean("found")) { "Close found=$found score=${best?.score}" }
    val score = best?.score ?: 0.0
    check(score >= case.getDouble("minScore") && score <= case.getDouble("maxScore")) { "Close score=$score" }
    JSONObject().put("found", found).put("score", score).put("region", region.toString())
        .put("match", best?.region?.toString())
}
