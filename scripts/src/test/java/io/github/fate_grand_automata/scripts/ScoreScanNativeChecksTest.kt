package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanCaptureState
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanNative
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanNativeCapture
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanNativeChecks
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanNativeChecks.Verdict
import io.github.lib_automata.Location
import io.github.lib_automata.Region
import io.github.lib_automata.Size
import kotlin.test.Test

class ScoreScanNativeChecksTest {
    private val display = Size(2560, 1440)
    private val geometry = FgoCaptureGeometry.forLandscape(display) { Region(Location(), it) }
    private val crop = Region(0, 0, 1280, 720)
    private val state = ScoreScanCaptureState(Size(1280, 720), 240, rendersIntoReader = true)

    private fun failed(reason: String, detail: String? = null) = ScoreScanNativeCapture.Failed(reason, detail)

    @Test
    fun `before the capture`() {
        assertThat(ScoreScanNativeChecks.before(true, state, geometry, crop)).isNull()
        assertThat(ScoreScanNativeChecks.before(false, null, geometry, crop)).isNull()
        assertThat(ScoreScanNativeChecks.before(true, null, geometry, crop)).isEqualTo(failed(ScoreScanNative.CAPTURE_STOPPED))
        assertThat(ScoreScanNativeChecks.before(true, state, geometry, Region(1, 0, 1280, 720)))
            .isEqualTo(failed(ScoreScanNative.GEOMETRY_MISMATCH, "capture ${crop}, script ${Region(1, 0, 1280, 720)}"))
        assertThat(ScoreScanNativeChecks.before(false, null, geometry, Region(1, 0, 1280, 720))?.reason)
            .isEqualTo(ScoreScanNative.GEOMETRY_MISMATCH)
        assertThat(ScoreScanNativeChecks.before(true, state.copy(size = Size(1600, 720)), geometry, crop))
            .isEqualTo(failed(ScoreScanNative.GEOMETRY_MISMATCH, "capture size ${Size(1600, 720)}, geometry ${Size(1280, 720)}"))
    }

    private fun after(
        before: ScoreScanCaptureState? = state,
        after: ScoreScanCaptureState? = state,
        error: String? = null,
        frame: Size? = display,
        projection: Boolean = true,
        shown: Size = display,
    ) = ScoreScanNativeChecks.after(projection, before, after, error, frame, shown, geometry)

    @Test
    fun `a frame of the display after an unchanged capture is kept with the capture geometry`() {
        val verdict = after()
        assertThat(verdict).isInstanceOf(Verdict.Keep::class)
        val map = (verdict as Verdict.Keep).map
        assertThat(map.frame).isEqualTo(display)
        assertThat(map.capture).isEqualTo(Size(1280, 720))
        assertThat(map.origin).isEqualTo(Location(0, 0))
        // A display reported in portrait is the same landscape frame.
        assertThat(after(shown = Size(1440, 2560))).isInstanceOf(Verdict.Keep::class)
    }

    @Test
    fun `a changed capture state or a display that no longer renders into the reader ends the scan`() {
        assertThat(after(after = state.copy(size = display))).isEqualTo(
            Verdict.Drop(ScoreScanNativeCapture.RestoreFailed("before $state, after ${state.copy(size = display)}"))
        )
        val detached = state.copy(rendersIntoReader = false)
        assertThat(after(after = detached, error = "boom", frame = null)).isEqualTo(
            Verdict.Drop(ScoreScanNativeCapture.RestoreFailed("before $state, after $detached, boom"))
        )
        assertThat(after(after = state.copy(density = 480))).isInstanceOf(Verdict.Drop::class)
        assertThat((after(after = state.copy(density = 480)) as Verdict.Drop).capture).isInstanceOf(ScoreScanNativeCapture.RestoreFailed::class)
    }

    @Test
    fun `a projection that stopped during the capture is its own reason`() {
        assertThat(after(after = null, error = "ScreenCaptureStopped", frame = null))
            .isEqualTo(Verdict.Drop(failed(ScoreScanNative.CAPTURE_STOPPED, "ScreenCaptureStopped")))
    }

    @Test
    fun `an exception, a missing frame and a frame of another size are logged failures`() {
        assertThat(after(error = "NoScreenshotReceived", frame = null)).isEqualTo(Verdict.Drop(failed(ScoreScanNative.ERROR, "NoScreenshotReceived")))
        assertThat(after(frame = null)).isEqualTo(Verdict.Drop(failed(ScoreScanNative.NULL_FRAME)))
        assertThat(after(frame = Size(1440, 2560)))
            .isEqualTo(Verdict.Drop(failed(ScoreScanNative.FRAME_SIZE, "frame ${Size(1440, 2560)}, display $display")))
    }

    @Test
    fun `root capture has no state to compare`() {
        assertThat(after(projection = false, before = null, after = null)).isInstanceOf(Verdict.Keep::class)
        assertThat(after(projection = false, before = null, after = null, error = "su failed", frame = null))
            .isEqualTo(Verdict.Drop(failed(ScoreScanNative.ERROR, "su failed")))
    }
}
