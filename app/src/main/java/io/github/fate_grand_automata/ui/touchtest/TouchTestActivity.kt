package io.github.fate_grand_automata.ui.touchtest

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.Shell
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.accessibility.TapperService
import io.github.fate_grand_automata.capture.chaldea.ShizukuStateReader
import io.github.fate_grand_automata.scripts.capture.chaldea.ShizukuState
import io.github.fate_grand_automata.touch.TouchInjectorClient
import io.github.fate_grand_automata.touch.TouchInjectorSession
import io.github.fate_grand_automata.ui.FgaScreen
import io.github.fate_grand_automata.ui.prefs.initializeApplicationLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.File
import kotlin.coroutines.resume

/**
 * Resizeable split-screen diagnostic activity for issue #387: injects a fixed tap/drag pattern into its own pad via
 * Accessibility, Shizuku or root while the owner touches another app in the other split-screen window.
 */
class TouchTestActivity : AppCompatActivity() {
    private val padCounter = TouchPadCounter()
    private val shizukuClient by lazy { TouchInjectorClient.forShizuku(this) }
    private val rootClient by lazy { TouchInjectorClient.forRoot(this) }

    @Volatile
    private var padViewRef: TouchPadView? = null

    @Volatile
    private var isActivityResumed: Boolean = false

    private var activeSession: TouchInjectorSession? = null
    private var activeSessionPath: TouchTestPath? = null
    private var runJob: Job? = null
    private var runStartElapsedMs: Long = 0L
    private var runInjectorInfo: String = ""

    private var selectedPath by mutableStateOf(TouchTestPath.Accessibility)
    private var accessibilityReady by mutableStateOf(false)
    private var shizukuState by mutableStateOf(ShizukuState.NotRunning)
    private var rootStatus by mutableStateOf("?")
    private var running by mutableStateOf(false)
    private var countdownSeconds by mutableIntStateOf(0)
    private var padCounts by mutableStateOf(TouchPadCounts())
    private var sentTaps by mutableIntStateOf(0)
    private var sentDrags by mutableIntStateOf(0)
    private var accessibilityCancelled by mutableIntStateOf(0)
    private var injectorFailures by mutableIntStateOf(0)
    private var noteText by mutableStateOf("")
    private var lastRecord by mutableStateOf<TouchTestRunRecord?>(null)

    private var pendingShizukuPermission: CompletableDeferred<Boolean>? = null
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQ_CODE) {
            shizukuState = ShizukuStateReader.current(this)
            pendingShizukuPermission?.complete(grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeApplicationLanguage()
        runCatching { Shizuku.addRequestPermissionResultListener(shizukuPermissionListener) }
        refreshPathStates()

        setContent {
            FgaScreen {
                TouchTestScreen(
                    selectedPath = selectedPath,
                    onSelectPath = { if (!running) selectedPath = it },
                    accessibilityReady = accessibilityReady,
                    shizukuState = shizukuState,
                    rootStatus = rootStatus,
                    running = running,
                    countdownSeconds = countdownSeconds,
                    counts = padCounts,
                    sentTaps = sentTaps,
                    sentDrags = sentDrags,
                    accessibilityCancelled = accessibilityCancelled,
                    injectorFailures = injectorFailures,
                    note = noteText,
                    onNoteChange = ::updateNote,
                    lastRecord = lastRecord,
                    onStart = ::startTestRun,
                    onStop = { stopActiveRun("stopped") },
                    onAttachPadView = { view -> padViewRef = view },
                    onPadTouchEvent = { action, x, y ->
                        padCounts = padCounter.onEvent(action, x, y)
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        refreshPathStates()
    }

    override fun onPause() {
        isActivityResumed = false
        if (running) {
            stopActiveRun("aborted")
        }
        closeActiveSession()
        super.onPause()
    }

    override fun onDestroy() {
        isActivityResumed = false
        if (running) {
            stopActiveRun("aborted")
        }
        closeActiveSession()
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
        padViewRef = null
        super.onDestroy()
    }

    /**
     * Passively checks the status of each path without requesting Shizuku or root permission (R1).
     */
    private fun refreshPathStates() {
        accessibilityReady = TapperService.instance != null
        shizukuState = runCatching { ShizukuStateReader.current(this) }.getOrDefault(ShizukuState.NotRunning)
        rootStatus = passiveRootStatus()
    }

    private fun passiveRootStatus(): String {
        val granted = runCatching { Shell.isAppGrantedRoot() }.getOrNull()
        return when (granted) {
            true -> "ready"
            false -> "denied"
            null -> if (hasSuBinary()) "available" else "unavailable"
        }
    }

    private fun updateNote(updated: String) {
        noteText = updated
        val current = lastRecord ?: return
        val withNote = current.copy(note = updated)
        lastRecord = withNote
        val file = TouchTestJsonlWriter.fileIn(filesDir)
        TouchTestJsonlWriter.updateLast(file, withNote)
    }

    private fun startTestRun() {
        if (running) return
        refreshPathStates()
        padCounts = padCounter.reset()
        sentTaps = 0
        sentDrags = 0
        accessibilityCancelled = 0
        injectorFailures = 0
        lastRecord = null
        runInjectorInfo = ""
        running = true
        countdownSeconds = TouchTestPattern.COUNTDOWN_SECONDS
        runStartElapsedMs = SystemClock.elapsedRealtime()

        val chosenPath = selectedPath
        runJob = lifecycleScope.launch {
            var status = "completed"
            try {
                val session = preparePathSession(chosenPath)
                if (chosenPath != TouchTestPath.Accessibility && session == null) {
                    injectorFailures++
                    status = "aborted"
                    return@launch
                }
                if (chosenPath == TouchTestPath.Accessibility && TapperService.instance == null) {
                    injectorFailures++
                    status = "aborted"
                    return@launch
                }
                runInjectorInfo = when (chosenPath) {
                    TouchTestPath.Accessibility -> "TapperService"
                    TouchTestPath.Shizuku, TouchTestPath.Root -> session?.describe().orEmpty()
                }

                for (remaining in TouchTestPattern.COUNTDOWN_SECONDS downTo 1) {
                    if (!isActivityResumed) {
                        status = "aborted"
                        return@launch
                    }
                    countdownSeconds = remaining
                    delay(1_000L)
                }
                countdownSeconds = 0
                runStartElapsedMs = SystemClock.elapsedRealtime()

                val initialBounds = readPadBoundsOnScreen()
                val steps = TouchTestPattern.generate(initialBounds)
                if (steps.isEmpty()) {
                    status = "aborted"
                    return@launch
                }

                for ((index, step) in steps.withIndex()) {
                    if (!isActive) {
                        status = "stopped"
                        return@launch
                    }
                    val stepStart = SystemClock.elapsedRealtime()
                    val liveBounds = readPadBoundsOnScreen()
                    if (!TouchBoundsGuard.canInject(isActivityResumed, liveBounds, step)) {
                        status = "aborted"
                        return@launch
                    }

                    when (step) {
                        is TouchTestStep.Tap -> {
                            sentTaps++
                            executeTap(chosenPath, session, step)
                        }

                        is TouchTestStep.Drag -> {
                            sentDrags++
                            executeDrag(chosenPath, session, step)
                        }
                    }

                    if (!isActivityResumed) {
                        status = "aborted"
                        return@launch
                    }

                    if (index < steps.lastIndex) {
                        val elapsed = SystemClock.elapsedRealtime() - stepStart
                        val waitMs = step.intervalMs - elapsed
                        if (waitMs > 0L) {
                            delay(waitMs)
                        }
                    } else {
                        // Allow the final UP event to be delivered to the pad view before snapshotting.
                        delay(100L)
                    }
                }
            } catch (_: CancellationException) {
                if (status == "completed") {
                    status = if (isActivityResumed) "stopped" else "aborted"
                }
            } finally {
                countdownSeconds = 0
                if (running) {
                    finishRun(chosenPath, status)
                }
            }
        }
    }

    private fun stopActiveRun(reason: String) {
        if (!running) return
        val path = selectedPath
        runJob?.cancel()
        runJob = null
        countdownSeconds = 0
        finishRun(path, reason)
    }

    private fun finishRun(path: TouchTestPath, status: String) {
        if (!running) return
        running = false
        val durationMs = (SystemClock.elapsedRealtime() - runStartElapsedMs).coerceAtLeast(0L)
        val counts = padCounter.snapshot()
        padCounts = counts
        val record = TouchTestRunRecord(
            timestampMs = System.currentTimeMillis(),
            path = path.wireName,
            status = status,
            androidRelease = Build.VERSION.RELEASE ?: "",
            sdkInt = Build.VERSION.SDK_INT,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            injectorInfo = runInjectorInfo,
            sentTaps = sentTaps,
            sentDrags = sentDrags,
            completedTaps = counts.completedTaps,
            completedDrags = counts.completedDrags,
            downCount = counts.down,
            moveCount = counts.move,
            upCount = counts.up,
            cancelCount = counts.cancel,
            accessibilityCancelled = accessibilityCancelled,
            injectorFailures = injectorFailures,
            durationMs = durationMs,
            note = noteText,
        )
        lastRecord = record
        val file = TouchTestJsonlWriter.fileIn(filesDir)
        runCatching { TouchTestJsonlWriter.append(file, record) }
        refreshPathStates()
    }

    /**
     * Binds Shizuku or root on demand only when the user starts a test with that path (R1).
     */
    private suspend fun preparePathSession(path: TouchTestPath): TouchInjectorSession? {
        if (path == TouchTestPath.Accessibility) return null
        if (activeSession != null && activeSessionPath == path) {
            return activeSession
        }
        closeActiveSession()
        return when (path) {
            TouchTestPath.Accessibility -> null
            TouchTestPath.Shizuku -> {
                var current = ShizukuStateReader.current(this)
                shizukuState = current
                if (current == ShizukuState.NeedsPermission) {
                    val deferred = CompletableDeferred<Boolean>()
                    pendingShizukuPermission = deferred
                    val requested = runCatching {
                        Shizuku.requestPermission(SHIZUKU_PERMISSION_REQ_CODE)
                        true
                    }.getOrDefault(false)
                    if (requested) {
                        withTimeoutOrNull(15_000L) { deferred.await() }
                    }
                    pendingShizukuPermission = null
                    current = ShizukuStateReader.current(this)
                    shizukuState = current
                }
                if (current != ShizukuState.Ready) return null
                shizukuClient.open()?.also {
                    activeSession = it
                    activeSessionPath = TouchTestPath.Shizuku
                }
            }

            TouchTestPath.Root -> {
                val opened = rootClient.open()
                rootStatus = if (opened != null) "ready" else "unavailable"
                opened?.also {
                    activeSession = it
                    activeSessionPath = TouchTestPath.Root
                }
            }
        }
    }

    private fun closeActiveSession() {
        pendingShizukuPermission?.cancel()
        pendingShizukuPermission = null
        activeSession?.close()
        activeSession = null
        activeSessionPath = null
    }

    private fun readPadBoundsOnScreen(): PadBounds {
        val view = padViewRef ?: return PadBounds(0, 0, 0, 0)
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) {
            return PadBounds(0, 0, 0, 0)
        }
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return PadBounds(left = loc[0], top = loc[1], width = view.width, height = view.height)
    }

    private suspend fun executeTap(
        path: TouchTestPath,
        session: TouchInjectorSession?,
        step: TouchTestStep.Tap,
    ) {
        when (path) {
            TouchTestPath.Accessibility -> {
                val tapPath = Path().apply { moveTo(step.x.toFloat(), step.y.toFloat()) }
                val stroke = GestureDescription.StrokeDescription(tapPath, 0L, step.holdMs)
                when (dispatchAccessibilityStroke(stroke, step.holdMs + 2_000L)) {
                    AccessibilityOutcome.Completed -> {}
                    AccessibilityOutcome.Cancelled -> accessibilityCancelled++
                    AccessibilityOutcome.Failed -> injectorFailures++
                }
            }

            TouchTestPath.Shizuku, TouchTestPath.Root -> {
                val ok = session?.tap(step.x, step.y, step.holdMs) == true
                if (!ok) injectorFailures++
            }
        }
    }

    private suspend fun executeDrag(
        path: TouchTestPath,
        session: TouchInjectorSession?,
        step: TouchTestStep.Drag,
    ) {
        when (path) {
            TouchTestPath.Accessibility -> {
                val dragPath = Path().apply {
                    moveTo(step.x1.toFloat(), step.y1.toFloat())
                    lineTo(step.x2.toFloat(), step.y2.toFloat())
                }
                val stroke = GestureDescription.StrokeDescription(dragPath, 0L, step.durationMs)
                when (dispatchAccessibilityStroke(stroke, step.durationMs + 2_000L)) {
                    AccessibilityOutcome.Completed -> {}
                    AccessibilityOutcome.Cancelled -> accessibilityCancelled++
                    AccessibilityOutcome.Failed -> injectorFailures++
                }
            }

            TouchTestPath.Shizuku, TouchTestPath.Root -> {
                val ok = session?.drag(step.x1, step.y1, step.x2, step.y2, step.durationMs) == true
                if (!ok) injectorFailures++
            }
        }
    }

    private enum class AccessibilityOutcome { Completed, Cancelled, Failed }

    private suspend fun dispatchAccessibilityStroke(
        stroke: GestureDescription.StrokeDescription,
        timeoutMs: Long,
    ): AccessibilityOutcome {
        val service = TapperService.instance ?: return AccessibilityOutcome.Failed
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<AccessibilityOutcome> { cont ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(AccessibilityOutcome.Completed)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(AccessibilityOutcome.Cancelled)
                    }
                }
                val dispatched = runCatching { service.dispatchGesture(gesture, callback, null) }.getOrDefault(false)
                if (!dispatched && cont.isActive) {
                    cont.resume(AccessibilityOutcome.Failed)
                }
            }
        } ?: AccessibilityOutcome.Failed
    }

    companion object {
        private const val SHIZUKU_PERMISSION_REQ_CODE = 3871

        private val SU_CANDIDATE_DIRS = listOf(
            "/system/bin",
            "/system/xbin",
            "/sbin",
            "/debug_ramdisk",
            "/product/bin",
            "/apex/com.android.runtime/bin",
        )

        internal fun hasSuBinary(): Boolean {
            val pathDirs = System.getenv("PATH")?.split(File.pathSeparator).orEmpty()
            return (SU_CANDIDATE_DIRS + pathDirs).any { dir ->
                dir.isNotBlank() && File(dir, "su").exists()
            }
        }
    }
}

/**
 * Custom [View] for the touch test pad so `getLocationOnScreen` returns the exact window coordinates and every
 * [MotionEvent] (`DOWN`, `MOVE`, `UP`, `CANCEL`) is captured directly.
 */
internal class TouchPadView(context: Context) : View(context) {
    var onMotion: ((actionMasked: Int, x: Float, y: Float) -> Unit)? = null

    private var lastX = -1f
    private var lastY = -1f
    private var activeDown = false

    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x664CAF50
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val touchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA2196F3.toInt()
        style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        lastX = event.x
        lastY = event.y
        activeDown = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE
        onMotion?.invoke(action, event.x, event.y)
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val dragEndX = cx + width * 0.30f
        canvas.drawLine(cx, 0f, cx, height.toFloat(), crossPaint)
        canvas.drawLine(0f, cy, width.toFloat(), cy, crossPaint)
        canvas.drawCircle(cx, cy, 18f, crossPaint)
        canvas.drawCircle(dragEndX, cy, 12f, crossPaint)
        if (lastX >= 0f && lastY >= 0f) {
            touchPaint.alpha = if (activeDown) 200 else 90
            canvas.drawCircle(lastX, lastY, 24f, touchPaint)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TouchTestScreen(
    selectedPath: TouchTestPath,
    onSelectPath: (TouchTestPath) -> Unit,
    accessibilityReady: Boolean,
    shizukuState: ShizukuState,
    rootStatus: String,
    running: Boolean,
    countdownSeconds: Int,
    counts: TouchPadCounts,
    sentTaps: Int,
    sentDrags: Int,
    accessibilityCancelled: Int,
    injectorFailures: Int,
    note: String,
    onNoteChange: (String) -> Unit,
    lastRecord: TouchTestRunRecord?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAttachPadView: (TouchPadView) -> Unit,
    onPadTouchEvent: (Int, Float, Float) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Compact control & status area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.touch_test_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onStart,
                            enabled = !running,
                        ) {
                            Text(stringResource(R.string.touch_test_start))
                        }
                        OutlinedButton(
                            onClick = onStop,
                            enabled = running,
                        ) {
                            Text(stringResource(R.string.touch_test_stop))
                        }
                    }
                }

                if (countdownSeconds > 0) {
                    Text(
                        text = "${stringResource(R.string.touch_test_countdown_notice)} (${countdownSeconds}s)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "${stringResource(R.string.touch_test_path)}:",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    val accState = if (accessibilityReady) "ON" else "OFF"
                    FilterChip(
                        selected = selectedPath == TouchTestPath.Accessibility,
                        onClick = { onSelectPath(TouchTestPath.Accessibility) },
                        label = { Text("${stringResource(R.string.touch_test_path_accessibility)} ($accState)") },
                    )
                    FilterChip(
                        selected = selectedPath == TouchTestPath.Shizuku,
                        onClick = { onSelectPath(TouchTestPath.Shizuku) },
                        label = { Text("${stringResource(R.string.touch_test_path_shizuku)} (${shizukuState.name})") },
                    )
                    FilterChip(
                        selected = selectedPath == TouchTestPath.Root,
                        onClick = { onSelectPath(TouchTestPath.Root) },
                        label = { Text("${stringResource(R.string.touch_test_path_root)} ($rootStatus)") },
                    )
                }

                Text(
                    text = buildString {
                        append("${stringResource(R.string.touch_test_taps_label)}: $sentTaps / ${counts.completedTaps}   ")
                        append("${stringResource(R.string.touch_test_drags_label)}: $sentDrags / ${counts.completedDrags}\n")
                        append("DOWN=${counts.down} MOVE=${counts.move} UP=${counts.up} ")
                        append("${stringResource(R.string.touch_test_cancel_label)}=${counts.cancel} (a11y=$accessibilityCancelled) ")
                        append("${stringResource(R.string.touch_test_failure_label)}=$injectorFailures")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )

                OutlinedTextField(
                    value = note,
                    onValueChange = onNoteChange,
                    label = { Text(stringResource(R.string.touch_test_note_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (lastRecord != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = "${stringResource(R.string.touch_test_result_label)} (${lastRecord.status})",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text = buildString {
                                    append("${stringResource(R.string.touch_test_path)}: ${lastRecord.path} · ")
                                    append("Android ${lastRecord.androidRelease} (SDK ${lastRecord.sdkInt}) · ${lastRecord.deviceModel}\n")
                                    if (lastRecord.injectorInfo.isNotBlank()) {
                                        append("${lastRecord.injectorInfo}\n")
                                    }
                                    append("${stringResource(R.string.touch_test_taps_label)}: ${lastRecord.sentTaps} / ${lastRecord.completedTaps} · ")
                                    append("${stringResource(R.string.touch_test_drags_label)}: ${lastRecord.sentDrags} / ${lastRecord.completedDrags}\n")
                                    append("DOWN=${lastRecord.downCount} MOVE=${lastRecord.moveCount} UP=${lastRecord.upCount} ")
                                    append("${stringResource(R.string.touch_test_cancel_label)}=${lastRecord.cancelCount} ")
                                    append("onCancelled=${lastRecord.accessibilityCancelled} ")
                                    append("${stringResource(R.string.touch_test_failure_label)}=${lastRecord.injectorFailures} ")
                                    append("(${lastRecord.durationMs} ms)\n")
                                    append(TouchTestJsonlWriter.RELATIVE_PATH)
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            // Large touch pad taking the rest of the window
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .heightIn(min = 140.dp),
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                ),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { ctx ->
                            TouchPadView(ctx).apply {
                                onMotion = onPadTouchEvent
                                onAttachPadView(this)
                            }
                        },
                        update = { view ->
                            view.onMotion = onPadTouchEvent
                            onAttachPadView(view)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
