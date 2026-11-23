package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.SupportListCounts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Experiment (#636): what the support list filter did to the last follower/list, counts only. It lives in memory
 * for the life of the app process and is set from the proxy thread; nothing is written to a file or to prefs.
 */
object SupportListLastResult {
    sealed interface Result {
        data class Filtered(val counts: SupportListCounts) : Result
        data object Unchanged : Result
    }

    private val state = MutableStateFlow<Result?>(null)

    /** Null until the first follower/list has been seen in this process. */
    val last: StateFlow<Result?> get() = state

    fun filtered(counts: SupportListCounts) {
        state.value = Result.Filtered(counts)
    }

    fun unchanged() {
        state.value = Result.Unchanged
    }

    /** Called when the filter stops running, so an old result is not shown for a filter that is off. */
    fun clear() {
        state.value = null
    }

    internal fun resetForTesting() = clear()
}
