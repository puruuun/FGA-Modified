package io.github.fate_grand_automata.ui.more

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.accessibility.TapperService
import io.github.fate_grand_automata.prefs.core.GameAreaMode
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.runner.GameAreaCaptureSignal
import io.github.fate_grand_automata.scripts.FgoGameAreaManager
import io.github.fate_grand_automata.scripts.GameAreaCalibration
import io.github.fate_grand_automata.util.CutoutManager
import io.github.fate_grand_automata.util.DisplayHelper
import io.github.lib_automata.Region
import io.github.lib_automata.Size
import io.github.lib_automata.GameAreaOffsets
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Settings-only overlay. All window operations and state changes run on the main thread. */
@Singleton
class GameAreaPreviewManager @Inject constructor(
    private val cutoutManager: CutoutManager,
    private val prefs: PrefsCore,
    private val display: DisplayHelper,
    private val captureSignal: GameAreaCaptureSignal
) {
    val screenSize: Size
        get() = display.metrics.let { Size(it.widthPixels, it.heightPixels) }

    var visible by mutableStateOf(false)
        private set
    var scriptActive by mutableStateOf(false)
        private set

    private var attachedTo: TapperService? = null
    private var previewView: GameAreaPreviewView? = null
    private var appliedOffsets: GameAreaOffsets? = null
    private var calibrationRegion: Region? = null
    private var calibrationRotation: Int? = null
    private var restorePreview = false

    /** Preview a proposal without changing any stored values or capture settings. */
    @MainThread
    fun beginCalibration() {
        restorePreview = visible
        calibrationRotation = display.rotation
        hide()
    }

    @MainThread
    fun showCalibration(result: GameAreaCalibration.Success): Boolean {
        if (scriptActive || result.displaySize != screenSize || calibrationRotation != display.rotation) return false
        calibrationRegion = result.region
        show()
        return visible
    }

    @MainThread
    fun cancelCalibration() {
        if (calibrationRotation == null) return
        calibrationRegion = null
        calibrationRotation = null
        hide()
        val restore = restorePreview
        restorePreview = false
        if (restore) show()
    }

    @MainThread
    fun saveCalibration(name: String, result: GameAreaCalibration.Success): Boolean {
        if (scriptActive || result.displaySize != screenSize || calibrationRotation != display.rotation) return false
        // CutoutManager mirrors persisted horizontal offsets in reverse landscape. Store in that same convention.
        val offsets = result.offsets.let {
            if (display.rotation == Surface.ROTATION_270) it.copy(left = it.right, right = it.left) else it
        }
        val preset = prefs.gameArea.addPreset(name, offsets)
        prefs.gameArea.presetId.set(preset.id)
        prefs.gameArea.mode.set(GameAreaMode.Preset)
        calibrationRegion = null
        calibrationRotation = null
        restorePreview = false
        applyCaptureSettings()
        update()
        return true
    }

    fun beginSettings() {
        appliedOffsets = prefs.gameArea.customOffsets()
    }

    fun applyCaptureSettings() {
        appliedOffsets = prefs.gameArea.customOffsets()
        captureSignal.apply()
    }

    fun finishSettings() {
        if (appliedOffsets != null && appliedOffsets != prefs.gameArea.customOffsets()) {
            applyCaptureSettings()
        }
        appliedOffsets = null
        calibrationRegion = null
        calibrationRotation = null
        restorePreview = false
        hide()
    }

    @MainThread
    fun show() {
        if (scriptActive || calibrationRegion == null && prefs.gameAreaMode.get() == GameAreaMode.Default) return

        // Resolve the service again on every attach: accessibility tokens can die independently
        // of both the activity and the script runner.
        val service = TapperService.instance ?: run {
            hide()
            return
        }
        if (attachedTo === service) {
            update()
            return
        }
        hide()

        val region = readRegion() ?: return
        val view = GameAreaPreviewView(service, region)
        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(0)
            }
        }
        try {
            service.getSystemService(WindowManager::class.java).addView(view, params)
        } catch (e: WindowManager.BadTokenException) {
            Timber.w(e, "Accessibility service disconnected before game area preview attached")
            return
        } catch (e: RuntimeException) {
            Timber.w(e, "Failed to attach game area preview")
            return
        }
        attachedTo = service
        previewView = view
        visible = true
    }

    @MainThread
    fun update() {
        if (!visible) return
        if (attachedTo !== TapperService.instance || calibrationRegion == null && prefs.gameAreaMode.get() == GameAreaMode.Default) {
            hide()
            return
        }
        val region = readRegion() ?: run {
            hide()
            return
        }
        previewView?.update(region)
    }

    private fun readRegion(): Region? = try {
        calibrationRegion ?: cutoutManager.getCutoutAppliedRegion()
    } catch (e: IllegalArgumentException) {
        // Existing offsets may exceed the display after rotation. Do not change stored values or
        // script behaviour; an invalid rectangle simply cannot be previewed.
        Timber.w(e, "Invalid custom game area for preview")
        null
    }

    @MainThread
    fun hide() {
        val view = previewView
        val service = attachedTo
        visible = false
        previewView = null
        attachedTo = null
        if (view != null) {
            runCatching {
                service?.getSystemService(WindowManager::class.java)?.removeViewImmediate(view)
            }.onFailure { Timber.w(it, "Failed to remove game area preview") }
        }
    }

    /** Called before auto-detection takes its first screenshot, not just before entryPoint.run(). */
    @MainThread
    fun beginScript(): Boolean {
        scriptActive = true
        val wasVisible = visible
        hide()
        return wasVisible
    }

    @MainThread
    fun endScript() {
        scriptActive = false
        // The user must explicitly switch the preview back on.
    }
}

private class GameAreaPreviewView(context: Context, region: Region) : View(context) {
    private val density = resources.displayMetrics.density
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4 * density
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14 * resources.displayMetrics.scaledDensity
    }
    private val background = Paint().apply { color = Color.argb(220, 0, 0, 0) }
    private var userRegion = region
    private var gameRegion = fit(region)
    private val screenOrigin = IntArray(2)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun fit(region: Region) = FgoGameAreaManager(region.size) { region.location }.gameArea

    fun update(region: Region) {
        userRegion = region
        gameRegion = fit(region)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Regions use physical screen pixels, including system bars. Account for any OEM window
        // origin instead of assuming that the overlay always starts at (0, 0).
        getLocationOnScreen(screenOrigin)
        canvas.save()
        canvas.translate(-screenOrigin[0].toFloat(), -screenOrigin[1].toFloat())
        drawBorder(canvas, userRegion, Color.RED)
        drawBorder(canvas, gameRegion, Color.GREEN)

        val label = context.getString(
            R.string.game_area_preview_dimensions,
            userRegion.width, userRegion.height, gameRegion.width, gameRegion.height,
            gameRegion.width.toDouble() / gameRegion.height
        )
        val padding = 6 * density
        val left = userRegion.x.toFloat() + border.strokeWidth
        val top = userRegion.y.toFloat() + border.strokeWidth
        val baseline = top + padding - text.fontMetrics.top
        canvas.drawRect(left, top, left + text.measureText(label) + 2 * padding,
            baseline + text.fontMetrics.bottom + padding, background)
        canvas.drawText(label, left + padding, baseline, text)
        canvas.restore()
    }

    private fun drawBorder(canvas: Canvas, region: Region, color: Int) {
        border.color = color
        // Keep the whole stroke visible at the physical edges of the screen.
        val inset = minOf(border.strokeWidth / 2, region.width / 2f, region.height / 2f)
        canvas.drawRect(region.x + inset, region.y + inset,
            region.right - inset, region.bottom - inset, border)
    }
}
