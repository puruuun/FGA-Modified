package io.github.fate_grand_automata.runner

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Settings completion events only. With no running service, events are discarded. */
@Singleton
class GameAreaCaptureSignal @Inject constructor() {
    private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes = events.asSharedFlow()
    fun apply() { events.tryEmit(Unit) }
}
