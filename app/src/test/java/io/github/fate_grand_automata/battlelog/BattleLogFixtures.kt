package io.github.fate_grand_automata.battlelog

import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.MaterialEnum
import io.github.fate_grand_automata.scripts.models.BondSlotSummary
import io.github.fate_grand_automata.scripts.models.BondSummary
import kotlin.time.Duration.Companion.milliseconds
import io.github.fate_grand_automata.capture.GameNameMap
import io.github.fate_grand_automata.scripts.capture.BattleGains
import io.github.fate_grand_automata.scripts.capture.GainedItem
import io.github.fate_grand_automata.scripts.capture.RunGains

/** Handwritten examples; no captured game/account data. Names verified against Atlas KR exports. */
internal val gainsNamesFixture = GameNameMap(
    items = mapOf(6510L to "무간의 톱니바퀴", 6001L to "검의 휘석"),
    servants = mapOf(100100L to "알트리아 펜드래곤"),
    equips = mapOf(9400010L to "완강"),
)

internal fun gainsFixture(missing: Boolean = false): RunGains {
    val battle = BattleGains(
        time = 1_793_750_000, questId = 10001,
        items = listOf(GainedItem(2, 6510, 3), GainedItem(2, 6001, 1), GainedItem(3, 9400010, 1)),
        qp = 1_234_000, masterExp = 12_345, bond = mapOf(100100L to 1234L),
    )
    val run = RunGains(battles = List(if (missing) 6 else 8) { battle.copy(time = battle.time + it * 60) }).finish(null)
    return run.copy(
        items = run.items.map { if (it.type == 2) it.copy(holdings = 12345) else it },
        fruitUsed = mapOf(100L to 2L, 101L to 0L, 102L to null, 104L to 1L),
    ).withRunCount(8)
}

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
