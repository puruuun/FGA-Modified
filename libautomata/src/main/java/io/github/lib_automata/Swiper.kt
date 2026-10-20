package io.github.lib_automata

import javax.inject.Inject

interface Swiper {
    /**
     * Swipes from one [Location] to another [Location].
     *
     * @param start the [Location] where the swipe should start
     * @param end the [Location] where the swipe should end
     */
    operator fun invoke(start: Location, end: Location)

    /** Bounded scroll: exact displacement, no user multiplier, and a stationary hold before release. */
    fun scroll(start: Location, end: Location)

    /** Two-finger pinch (script coordinates); see [PinchGesture]. */
    fun pinch(plan: PinchPlan): Boolean
}

class RealSwiper @Inject constructor(
    private val gestureService: GestureService,
    private val platformImpl: PlatformImpl,
    private val transform: Transformer
): Swiper {
    override fun pinch(plan: PinchPlan): Boolean {
        return gestureService.pinch(
            transform.toScreen(plan.startA), transform.toScreen(plan.endA),
            transform.toScreen(plan.startB), transform.toScreen(plan.endB),
            plan.durationMs
        )
    }

    override fun scroll(start: Location, end: Location) {
        gestureService.scroll(transform.toScreen(start), transform.toScreen(end))
    }

    override fun invoke(start: Location, end: Location) {
        val endX = lerp(
            start.x,
            end.x,
            platformImpl.prefs.swipeMultiplier
        ).coerceAtLeast(0)

        val endY = lerp(
            start.y,
            end.y,
            platformImpl.prefs.swipeMultiplier
        ).coerceAtLeast(0)

        gestureService.swipe(
            transform.toScreen(start),
            transform.toScreen(Location(endX, endY))
        )
    }

    /**
     * linear interpolation
     */
    private fun lerp(start: Int, end: Int, fraction: Double) =
        (start + (end - start) * fraction).toInt()
}