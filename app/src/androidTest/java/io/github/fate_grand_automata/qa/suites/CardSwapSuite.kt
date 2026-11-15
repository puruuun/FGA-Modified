package io.github.fate_grand_automata.qa.suites

import io.github.fate_grand_automata.qa.FixtureApi
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.modules.CardSwapIcon
import org.json.JSONObject

internal fun FixtureApi.verifyCardSwap(): JSONObject = useSameSnapIn {
    val cardName = case.getString("card")
    val card = when (cardName) {
        "A" -> CommandCard.Face.A
        "B" -> CommandCard.Face.B
        "C" -> CommandCard.Face.C
        "D" -> CommandCard.Face.D
        "E" -> CommandCard.Face.E
        else -> error("Unknown card: $cardName")
    }
    val detector = CardSwapIcon(this)
    val found = detector.isPresent(card)
    val score = detector.score(card)
    val minScore = case.getDouble("minScore")
    val maxScore = case.getDouble("maxScore").let { if (it == 1.0) 1.01 else it }
    check(found == case.getBoolean("found")) { "CardSwap $cardName found=$found score=$score" }
    check(score >= minScore && score <= maxScore) {
        "CardSwap $cardName score=$score"
    }
    JSONObject()
        .put("found", found)
        .put("score", score)
        .put("region", locations.attack.swapIconRegion(card).toString())
}
