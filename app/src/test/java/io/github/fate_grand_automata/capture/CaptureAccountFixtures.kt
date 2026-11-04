package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.AccountSummary

internal fun captureAccountFixture(id: String = "7001", name: String = "주회 계정") = CaptureAccount(
    id, name,
    AccountSummary(
        masterName = "마스터", level = 120, ap = 85, apMax = 142, carryOverActPoint = 20,
        stone = 312, freeStone = 200, chargeStone = 112, fragments = 45, goldenApples = 12,
        silverApples = 30, bronzeApples = 8, copperApples = 150, bronzeSaplings = 3, tickets = 7,
        manaPrisms = 9000, rarePrisms = 120, qp = 123_456_789,
        snapshotTime = 1_762_000_000, lastUpdateTime = 1_762_003_600,
    ), 1_762_003_600,
)
