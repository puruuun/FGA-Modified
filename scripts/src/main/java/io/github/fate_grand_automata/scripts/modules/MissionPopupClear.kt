package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.lib_automata.Location
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.TimeSource

@ScriptScope
class MissionPopupClear(
    api: IFgoAutomataApi,
    private val location: Location = api.locations.missionPopupClearClick,
    private val clock: TimeSource = TimeSource.Monotonic,
) : IFgoAutomataApi by api {
    @Inject
    constructor(api: IFgoAutomataApi) : this(
        api = api,
        location = api.locations.missionPopupClearClick,
        clock = TimeSource.Monotonic,
    )

    companion object {
        const val MIN_SECONDS = 1
        const val MAX_SECONDS = 60
        const val DEFAULT_SECONDS = 10

        fun clampSeconds(raw: Int): Int = raw.coerceIn(MIN_SECONDS, MAX_SECONDS)
    }

    /** Rapidly taps the top-centre mission popup spot in batches of 10 until [duration] elapses. */
    fun clear(duration: Duration) {
        val start = clock.markNow()
        do {
            location.click(times = 10)
        } while (start.elapsedNow() < duration)
    }
}
