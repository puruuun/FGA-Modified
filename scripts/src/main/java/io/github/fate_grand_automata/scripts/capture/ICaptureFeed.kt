package io.github.fate_grand_automata.scripts.capture

import kotlin.time.Duration

interface ICaptureFeed {
    fun isCollecting(): Boolean
    fun isStarting(): Boolean = false
    fun battleToken(): Long
    fun awaitBattle(token: Long, timeout: Duration): CapturedBattle?

    object None : ICaptureFeed {
        override fun isCollecting() = false
        override fun battleToken() = 0L
        override fun awaitBattle(token: Long, timeout: Duration): CapturedBattle? = null
    }
}
