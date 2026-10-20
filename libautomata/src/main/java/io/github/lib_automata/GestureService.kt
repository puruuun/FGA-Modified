package io.github.lib_automata

/**
 * Interface for classes which can perform gestures.
 */
interface GestureService : AutoCloseable {
    /**
     * Swipes from one [Location] to another [Location].
     *
     * @param start the [Location] where the swipe should start
     * @param end the [Location] where the swipe should end
     */
    fun swipe(start: Location, end: Location)

    /** Scroll without a release fling; implementations must stop at the endpoint before lifting. */
    fun scroll(start: Location, end: Location)

    /**
     * Two-finger gesture: both fingers touch down together, move in a straight line to their end points over
     * [durationMs] and lift together. The locations are screen coordinates. Pinching in (zoom out) moves the fingers
     * towards each other.
     */
    fun pinch(startA: Location, endA: Location, startB: Location, endB: Location, durationMs: Long): Boolean

    /**
     * Clicks on a given [location].
     *
     * @param location the location to click on
     * @param times the number of times to click
     */
    fun click(location: Location, times: Int = 1)
}