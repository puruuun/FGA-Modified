package io.github.fate_grand_automata.widget

import io.github.fate_grand_automata.capture.CaptureAccount
import io.github.fate_grand_automata.capture.captureFeaturesAvailable
import io.github.fate_grand_automata.capture.fullApAt

/** Everything the account AP widget shows, computed from stored account data and the clock only. */
data class ApWidgetModel(
    val accountId: String,
    val name: String,
    val server: String,
    val ap: Long,
    val apMax: Long,
    /** Epoch seconds when AP is full, or null when it is already full. */
    val fullAt: Long?,
    /** Whole minutes until [fullAt], rounded up; null when AP is full. */
    val minutesLeft: Long?,
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
        server = account.server,
        ap = account.summary.ap,
        apMax = account.summary.apMax,
        fullAt = fullAt,
        minutesLeft = fullAt?.let { (it - now + 59) / 60 },
        dataTime = account.lastUpdateTime,
    )
}

sealed interface ApWidgetState {
    /** Account deleted, log collection off, or Android below 10. */
    data object NoData : ApWidgetState
    data class Data(val model: ApWidgetModel) : ApWidgetState
}

fun apWidgetState(
    boundAccountId: String?,
    captureEnabled: Boolean,
    api: Int,
    accounts: List<CaptureAccount>,
    now: Long,
): ApWidgetState {
    if (boundAccountId == null || !captureFeaturesAvailable(captureEnabled, api)) return ApWidgetState.NoData
    val account = accounts.find { it.id == boundAccountId } ?: return ApWidgetState.NoData
    return ApWidgetState.Data(apWidgetModel(account, now))
}

/** What the configuration screen shows. */
sealed interface ApWidgetChoices {
    /** Log collection is off, Android is below 10, or no account has been collected yet. */
    data object Unavailable : ApWidgetChoices
    data class Accounts(val accounts: List<CaptureAccount>) : ApWidgetChoices
}

fun apWidgetChoices(captureEnabled: Boolean, api: Int, accounts: List<CaptureAccount>): ApWidgetChoices =
    if (!captureFeaturesAvailable(captureEnabled, api) || accounts.isEmpty()) ApWidgetChoices.Unavailable
    else ApWidgetChoices.Accounts(accounts)
