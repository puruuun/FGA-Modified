package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.AccountSummary

/** Increment when the first-use wording changes enough to require agreement again. */
object CaptureConsent {
    const val VERSION = 1
    fun agreed(version: Int) = version >= VERSION
    fun enableAction(requested: Boolean, version: Int): CaptureEnableAction = when {
        !requested -> CaptureEnableAction.Disable
        agreed(version) -> CaptureEnableAction.Enable
        else -> CaptureEnableAction.RequestConsent
    }
}

enum class CaptureEnableAction { Disable, Enable, RequestConsent }
enum class CaptureButtonState { Start, Stop }

fun captureButtonVisible(enabled: Boolean, api: Int, gameInstalled: Boolean) =
    enabled && api >= 29 && gameInstalled

fun captureButtonState(running: Boolean) = if (running) CaptureButtonState.Stop else CaptureButtonState.Start

/** The server gives the full-recovery instant, including a partially recovered AP point. */
fun fullApAt(summary: AccountSummary, now: Long): Long? =
    summary.actRecoverAt.takeIf { summary.ap < summary.apMax && it > now }

val captureFruitOrder = listOf(100L, 101L, 104L, 102L)
