package io.github.fate_grand_automata.qa.suites

import io.github.fate_grand_automata.qa.FixtureApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.entrypoints.AutoDetect
import io.github.fate_grand_automata.scripts.locations.CEEnhanceLocations
import io.github.fate_grand_automata.scripts.locations.SkillLevelLocations
import io.github.fate_grand_automata.scripts.modules.CEEnhanceScreens
import io.github.fate_grand_automata.scripts.modules.SkillLevelScreens
import org.json.JSONObject

/** Recorded frames only: no script actions or game input. */
internal fun FixtureApi.verifyCEEnhance(): JSONObject = useSameSnapIn {
    val loc = CEEnhanceLocations(locations)
    val ce = CEEnhanceScreens(this, loc)
    val actual = JSONObject()
    val regions = mapOf(
        "execute" to (Images.CEEnhanceExecute to loc.execute),
        "reselect" to (Images.CEEnhanceReselect to loc.reselect),
        "active" to (Images.ServantAutoSelect to locations.servant.servantAutoSelectRegion),
        "disabled" to (Images.ServantAutoSelectOff to locations.servant.servantAutoSelectRegion),
        "on" to (Images.StateON to loc.autoFill),
        "close" to (Images.Close to locations.servant.emptyEmberOrQPDialogRegion),
        "ok" to (Images.Ok to locations.servant.finalConfirmRegion)
    ) + if (prefs.gameServer is io.github.fate_grand_automata.scripts.enums.GameServer.Kr)
        mapOf("okKR" to (Images.OkKR to locations.servant.finalConfirmRegion)) else emptyMap()
    val expected = case.getJSONObject("scores")
    for ((name, pair) in regions) {
        val score = pair.second.findAll(images[pair.first], 0.01).maxOfOrNull { it.score } ?: 0.0
        actual.put(name, score)
        if (expected.has(name)) {
            val range = expected.getJSONArray(name)
            check(score >= range.getDouble(0) && score <= range.getDouble(1)) { "$name=$score outside $range" }
        }
    }
    actual.put("recommended", ce.recommended().name)
    if (case.has("recommended")) check(actual.getString("recommended") == case.getString("recommended"))
    if (case.has("detect")) {
        val detected = AutoDetect(this, SkillLevelScreens(this, SkillLevelLocations(locations)), ce).get()
        actual.put("detect", detected.name)
        check(detected.name == case.getString("detect")) { "detected=$detected" }
    }
    actual
}
