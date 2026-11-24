package io.github.fate_grand_automata.qa.suites

import io.github.fate_grand_automata.qa.FixtureApi
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.scripts.modules.NpCardTypeReader
import org.json.JSONObject

/**
 * NP card type in the Servant details dialog and the banner's remaining-turns digit (#654), read by the same
 * reader as the battle. A case with "turns" is a banner over the NP art: no type, and that digit.
 */
internal fun FixtureApi.verifyNpType(): JSONObject = useSameSnapIn {
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
