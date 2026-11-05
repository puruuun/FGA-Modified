package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.battlelog.BattleLogEntry

/** Presentation adapter only. Neither persisted schema acquires a discriminator or wrapper. */
data class CaptureLogItem(val battle: BattleLogEntry? = null, val outside: OutsideLogEntry? = null) {
    init { require((battle == null) != (outside == null)) }
    val id get() = battle?.id ?: "outside:${outside!!.id}"
    val endedAt get() = battle?.endedAt ?: outside!!.endedAt
}

fun mixedCaptureLogs(battles: List<BattleLogEntry>, outside: List<OutsideLogEntry>): List<CaptureLogItem> =
    (battles.map { CaptureLogItem(battle = it) } + outside.map { CaptureLogItem(outside = it) })
        .sortedWith(compareByDescending<CaptureLogItem> { it.endedAt }.thenBy { it.id })
