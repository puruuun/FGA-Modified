package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.capture.MaterialItemIds
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.MaterialEnum
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

@ScriptScope
class MaterialsTracker @Inject constructor(
    api: IFgoAutomataApi,
    private val battleConfig: IBattleConfig,
    private val state: BattleState,
    private val capture: CaptureBattleTracker? = null,
) : IFgoAutomataApi by api {
    // Set all Materials to 0
    private var matsGot =
        battleConfig
            .materials
            .associateWith { 0 }
            .toMutableMap()

    val farmed: Map<MaterialEnum, Int> get() = matsGot.filterValues { it > 0 }
    private var capturedBattleNumber: Int? = null

    fun autoDecrement() {
//        val refill = prefs.refill
        val perServerConfigPref = prefs.selectedServerConfigPref

        // Auto-decrement materials
        if (perServerConfigPref.shouldLimitMats) {
            perServerConfigPref.limitMats -= matsGot.values.sum()

            // Turn off limit by materials when done
            if (perServerConfigPref.limitMats <= 0) {
                perServerConfigPref.limitMats = 1
                perServerConfigPref.shouldLimitMats = false
            }
        }
    }

    fun parseMaterials() {
        if (capture?.enabled == true) {
            if (capturedBattleNumber == capture.battleNumber) return
            val drops = capture.response()?.drops
            capturedBattleNumber = capture.battleNumber
            for (material in battleConfig.materials) {
                val count = drops?.get(MaterialItemIds.ids.getValue(material)) ?: 0L
                matsGot[material] = ((matsGot[material] ?: 0).toLong() + count).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
        } else {
            for (material in battleConfig.materials) {
                val pattern = images.loadMaterial(material)

            // TODO: Make the search region smaller
                val count = locations.scriptArea
                    .findAll(pattern)
                    .count()

            // Increment material count
                matsGot.merge(material, count, Int::plus)
            }
        }

        if (prefs.selectedServerConfigPref.shouldLimitMats) {
            val totalMats = matsGot
                .values
                .sum()

            if (totalMats >= prefs.selectedServerConfigPref.limitMats) {
                // Count the current run
                state.nextRun()

                throw AutoBattle.BattleExitException(AutoBattle.ExitReason.LimitMaterials(totalMats))
            }
        }
    }
}
