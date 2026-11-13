package io.github.fate_grand_automata.touch

import androidx.annotation.Keep
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.system.exitProcess

/**
 * Shizuku UserService shell around [TouchInjector] (issue #387). Runs in a separate shell (or root) process started
 * by Shizuku; [TouchRootService] reuses the same [ITouchInjector.Stub] implementation in a libsu root process.
 */
@Keep
class TouchInjectorService internal constructor(
    private val injector: TouchInjector,
    private val onDestroyProcess: () -> Unit,
) : ITouchInjector.Stub() {
    @Keep
    constructor() : this(TouchInjector(), { exitProcess(0) })

    private val lock = ReentrantLock()

    override fun destroy() {
        lock.withLock { onDestroyProcess() }
    }

    override fun tap(x: Int, y: Int, holdMs: Long): Boolean = lock.withLock {
        injector.tap(x, y, holdMs)
    }

    override fun drag(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = lock.withLock {
        injector.drag(x1, y1, x2, y2, durationMs)
    }

    override fun describe(): String = lock.withLock {
        injector.describe()
    }
}
