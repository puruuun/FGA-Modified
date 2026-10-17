package io.github.fate_grand_automata.qa.suites

import io.github.fate_grand_automata.qa.FixtureApi
import io.github.fate_grand_automata.scripts.models.BondFrame
import io.github.fate_grand_automata.scripts.models.BondValue
import io.github.fate_grand_automata.scripts.modules.BondReader
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bond suite: reads a full 16:9 frame with the production BondReader and compares it with the case labels.
 * kind: "normal" (five columns: [base, bonus], "MAX" or "UNKNOWN"), "popup" (level-up popup),
 * or "other" (a frame that is not a bond result).
 */
internal fun FixtureApi.verifyBond(): JSONObject {
    val frame = BondReader(this, ocr).read()
    val result = JSONObject().put("frame", frame::class.java.simpleName)
    when (case.getString("kind")) {
        "normal" -> {
            check(frame is BondFrame.Normal) { "expected a normal bond result, got $frame" }
            val columns = JSONArray(frame.values.map { value ->
                when (value) {
                    is BondValue.Gain -> JSONArray(listOf(value.base, value.bonus))
                    BondValue.Max -> "MAX"
                    BondValue.Unknown -> "UNKNOWN"
                }
            })
            result.put("columns", columns)
            check(columns.toString() == case.getJSONArray("expected").toString()) { "columns $columns != ${case.getJSONArray("expected")}" }
        }
        "popup" -> {
            check(frame is BondFrame.LevelUp) { "expected a level-up popup, got $frame" }
            val level = frame.reachedLevel
            val column = frame.resultIndex
            result.put("level", level ?: JSONObject.NULL).put("column", column ?: JSONObject.NULL)
            check(level == case.optIntOrNull("expected_level")) { "level $level != ${case.opt("expected_level")}" }
            check(column == case.optIntOrNull("expected_column")) { "column $column != ${case.opt("expected_column")}" }
        }
        "other" -> check(frame is BondFrame.Other) { "expected a non-bond frame, got $frame" }
        else -> error("Unknown bond kind ${case.getString("kind")}")
    }
    return result
}

private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
