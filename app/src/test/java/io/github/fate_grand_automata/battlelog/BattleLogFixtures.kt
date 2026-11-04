package io.github.fate_grand_automata.battlelog

import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.MaterialEnum
import io.github.fate_grand_automata.scripts.models.BondSlotSummary
import io.github.fate_grand_automata.scripts.models.BondSummary
import kotlin.time.Duration.Companion.milliseconds

internal fun battleLogFixture(id: String = "sample", extras: Boolean = false): BattleLogEntry =
    BattleLogEntry.snapshot(
        exception = AutoBattle.ExitException(
            AutoBattle.ExitReason.LimitRuns(8),
            AutoBattle.ExitState(
                timesRan = 8, runLimit = 8, timesRefilled = 2, refillLimit = 5,
                ceDropCount = 1, wipeWithdrawCount = 1, turnLimitRetreatCount = 2,
                totalTime = 600_123.milliseconds, averageTimePerRun = 75_015.milliseconds,
                minTurnsPerRun = 3, averageTurnsPerRun = 3.25, maxTurnsPerRun = 4,
                materials = if (extras) mapOf(MaterialEnum.Proof to 12) else emptyMap(),
                bond = if (extras) BondSummary(
                    listOf(
                        BondSlotSummary(1, 12_345, 7, 1, false),
                        BondSlotSummary(2, 98_765, 6, 2, true)
                    ), unmappedLevelUpRuns = 1, levelUpMissedRuns = 2
                ) else null
            )
        ),
        configName = if (extras) "Event farming" else "Daily quests",
        reasonText = "8 runs completed",
        refillEnabled = true,
        endedAt = 1_793_750_400_000,
        id = id
    )
