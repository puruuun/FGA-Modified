package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.battlelog.BattleLogEntry

/** Presentation adapter only. Neither persisted schema acquires a discriminator or wrapper. */
data class CaptureLogItem(val battle: BattleLogEntry? = null, val outside: OutsideLogEntry? = null, val inProgress: Boolean = false) {
    init { require((battle == null) != (outside == null)) }
    val id get() = battle?.id ?: "outside:${outside!!.id}"
    val endedAt get() = battle?.endedAt ?: outside!!.endedAt
}

fun mixedCaptureLogs(battles: List<BattleLogEntry>, outside: List<OutsideLogEntry>, open: List<OutsideLogEntry> = emptyList()): List<CaptureLogItem> =
    (battles.map { CaptureLogItem(battle = it) } +
        outside.filterNot { saved -> open.any { it.id == saved.id } }.map { CaptureLogItem(outside = it) } +
        open.map { CaptureLogItem(outside = it, inProgress = true) })
        .sortedWith(compareByDescending<CaptureLogItem> { it.inProgress }.thenByDescending { it.endedAt }.thenBy { it.id })
