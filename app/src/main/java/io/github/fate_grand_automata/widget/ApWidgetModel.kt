package io.github.fate_grand_automata.widget

import io.github.fate_grand_automata.capture.CaptureAccount
import io.github.fate_grand_automata.capture.captureFeaturesAvailable
import io.github.fate_grand_automata.capture.fullApAt

/** Everything the account AP widget shows, computed from stored account data and the clock only. */
data class ApWidgetModel(
    val accountId: String,
    val name: String,
    val ap: Long,
    val apMax: Long,
    /** Epoch seconds when AP is full, or null when it is already full. */
    val fullAt: Long?,
    /** Epoch seconds of the last captured data for the account. */
    val dataTime: Long,
) {
    val fraction: Float get() = if (apMax <= 0) 0f else (ap.toFloat() / apMax).coerceIn(0f, 1f)
}

/** Same AP math as the main-screen card: the summary's current AP and [fullApAt]. */
fun apWidgetModel(account: CaptureAccount, now: Long): ApWidgetModel {
    val fullAt = fullApAt(account.summary, now)
    return ApWidgetModel(
        accountId = account.id,
        name = account.displayName,
        ap = account.summary.ap,
        apMax = account.summary.apMax,
        fullAt = fullAt,
        dataTime = account.lastUpdateTime,
    )
}

sealed interface ApWidgetState {
    /** No collected accounts, log collection off, or Android below 10. */
    data object NoData : ApWidgetState
    data class Data(val models: List<ApWidgetModel>) : ApWidgetState
}

fun apWidgetState(
    captureEnabled: Boolean,
    api: Int,
    accounts: List<CaptureAccount>,
    now: Long,
): ApWidgetState {
    if (!captureFeaturesAvailable(captureEnabled, api) || accounts.isEmpty()) return ApWidgetState.NoData
    // accountSummaries already orders the current account first, then by last collection time.
    return ApWidgetState.Data(accounts.map { apWidgetModel(it, now) })
}

/** Stable across refreshes and reordering, including non-numeric legacy account IDs. */
fun apWidgetItemId(accountId: String): Long {
    // A 64-bit FNV-1a hash avoids String.hashCode collisions (e.g. "Aa" and "BB").
    var hash = -3750763034362895579L
    for (byte in accountId.toByteArray(Charsets.UTF_8)) {
        hash = (hash xor (byte.toLong() and 0xff)) * 1099511628211L
    }
    // Glance reserves negative IDs for automatically assigned items.
    return hash and Long.MAX_VALUE
}
