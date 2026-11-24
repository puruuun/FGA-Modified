package io.github.fate_grand_automata.qa.suites

import io.github.fate_grand_automata.qa.FixtureApi
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.modules.NpCardTypeReader
import org.json.JSONObject

/**
 * NP card type in the Servant details dialog and the banner's remaining-turns digit (#654), read by the same
 * reader as the battle. A case with "turns" is a banner over the NP art: no type, and that digit.
 */
internal fun FixtureApi.verifyNpType(): JSONObject =
    if (case.optString("kind") == "portrait") verifyNpPortraits() else verifyNpCardType()

private fun FixtureApi.verifyNpCardType(): JSONObject = useSameSnapIn {
    val reader = NpCardTypeReader(this, ocr)
    val reading = reader.readType(case.getDouble("similarity"))
    val expected = case.getString("type").takeIf { it.isNotEmpty() }?.let { CardTypeEnum.valueOf(it) }
    val scores = reading.scores.entries.joinToString { (type, score) -> "$type=%.3f".format(score) }
    check(reading.type == expected) { "NP type ${reading.type}, expected $expected ($scores)" }
    if (expected != null) {
        check(reading.scores.getValue(expected) >= case.getDouble("minScore")) { "NP type score too low ($scores)" }
    }
    val maxOther = case.getDouble("maxOtherScore")
    check(reading.scores.filterKeys { it != expected }.values.all { it <= maxOther }) { "Other type scored high ($scores)" }

    val result = JSONObject()
        .put("type", reading.type?.name ?: "")
        .put("scores", JSONObject(reading.scores.mapKeys { it.key.name }))
        .put("region", locations.battle.servantNpCardTypeRegion.toString())
    if (case.has("turns")) {
        val turns = checkNotNull(reader.readChangeTurns())
        result.put("turnsText", turns.text).put("turns", turns.turns ?: JSONObject.NULL)
            .put("turnsRegion", locations.battle.servantNpChangeTurnsRegion.toString())
        check(turns.turns == case.getInt("turns")) { "Turns '${turns.text}' -> ${turns.turns}, expected ${case.getInt("turns")}" }
    }
    result
}

/**
 * Spam NP detection (#654): each face card, cut from a details-window capture where the battle cuts it, against
 * the portraits of the three NP cards on a card screen. Its own NP must reach the similarity and the others not.
 * Every score is reported, own and others, also when the case fails.
 */
private fun FixtureApi.verifyNpPortraits(): JSONObject = useSameSnapIn {
    val reader = NpCardTypeReader(this, ocr)
    val similarity = case.getDouble("similarity")
    val faces = case.getJSONArray("faces")
    val result = JSONObject()
    val problems = mutableListOf<String>()
    for (index in 0 until faces.length()) {
        val entry = faces.getJSONObject(index)
        val own = CommandCard.NP.list.first { it.toString() == npCode(entry.getString("np")) }
        val faceFile = imports.getJSONArray(entry.getString("face")).getString(0)
        val scores = framePattern(faceFile).use { details ->
            details.crop(locations.battle.servantDetailsFaceCardRegion * 0.5).use { cut ->
                cut.copy().use { face -> CommandCard.NP.list.associateWith { reader.npPortraitScore(it, face) } }
            }
        }
        val bestOther = scores.filterKeys { it != own }.values.max()
        result.put(entry.getString("face"), JSONObject()
            .put("own", entry.getString("np"))
            .put("ownScore", scores.getValue(own))
            .put("bestOtherScore", bestOther)
            .put("scores", JSONObject(scores.mapKeys { (np, _) -> npName(np) })))
        if (scores.getValue(own) < similarity) problems += "${entry.getString("face")} own ${scores.getValue(own)}"
        if (bestOther >= similarity) problems += "${entry.getString("face")} other $bestOther"
    }
    check(problems.isEmpty()) { "Portrait scores against $similarity: ${problems.joinToString()} | $result" }
    result
}

private fun npCode(name: String) = when (name) {
    "A" -> "4"
    "B" -> "5"
    "C" -> "6"
    else -> error("Unknown NP card: $name")
}

private fun npName(np: CommandCard.NP) = when (np) {
    CommandCard.NP.A -> "A"
    CommandCard.NP.B -> "B"
    CommandCard.NP.C -> "C"
}
