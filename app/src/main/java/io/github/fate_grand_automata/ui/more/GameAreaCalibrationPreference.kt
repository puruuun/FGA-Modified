package io.github.fate_grand_automata.ui.more

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.imaging.DroidCvPattern
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.runner.ScriptRunnerService
import io.github.fate_grand_automata.scripts.GameAreaCalibration
import io.github.fate_grand_automata.scripts.GameAreaCalibrator
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.GameServers
import io.github.fate_grand_automata.ui.prefs.Preference
import kotlinx.coroutines.*
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GameAreaCalibrationPreference(prefs: PrefsCore, preview: GameAreaPreviewManager) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<GameAreaCalibration?>(null) }
    val cancel = {
        job?.cancel()
        running = false
        result = null
        preview.cancelCalibration()
    }
    DisposableEffect(preview, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); cancel() }
    }
    Preference(
        title = stringResource(R.string.p_game_area_calibrate),
        summary = stringResource(if (running) R.string.game_area_calibrating else R.string.p_game_area_calibrate_summary),
        enabled = !running && !preview.scriptActive,
        onClick = {
            running = true
            result = null
            preview.beginCalibration()
            job = scope.launch {
                try {
                    delay(1000)
                    val server = GameServers.deserialize(prefs.gameServerRaw.get()) ?: GameServers.default
                    val found = calibrateDisplay(context.applicationContext, server)
                    ensureActive()
                    result = if (found is GameAreaCalibration.Success && !preview.showCalibration(found))
                        GameAreaCalibration.Failure(GameAreaCalibration.Reason.CaptureUnavailable) else found
                    if (result is GameAreaCalibration.Failure) preview.cancelCalibration()
                } finally { running = false }
            }
        }
    )
    val current = result
    if (current != null) AlertDialog(
        onDismissRequest = cancel,
        title = { Text(stringResource(R.string.p_game_area_calibrate)) },
        text = { Text(when (current) {
            is GameAreaCalibration.Success -> stringResource(R.string.game_area_calibration_result,
                current.offsets.left, current.offsets.top, current.offsets.right, current.offsets.bottom,
                current.region.width, current.region.height, current.scale)
            is GameAreaCalibration.Failure -> stringResource(current.reason.messageResource())
        }) },
        confirmButton = {
            if (current is GameAreaCalibration.Success) {
                val prefix = stringResource(R.string.game_area_auto_preset)
                TextButton(enabled = !preview.scriptActive, onClick = {
                    val name = "$prefix ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())}"
                    if (preview.saveCalibration(name, current)) result = null
                    else { cancel(); result = GameAreaCalibration.Failure(GameAreaCalibration.Reason.CaptureUnavailable) }
                }) { Text(stringResource(R.string.game_area_save_calibration)) }
            } else TextButton(onClick = cancel) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            if (current is GameAreaCalibration.Success) TextButton(onClick = cancel) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}

private fun GameAreaCalibration.Reason.messageResource() = when (this) {
    GameAreaCalibration.Reason.UnsupportedServer -> R.string.game_area_calibration_unsupported
    GameAreaCalibration.Reason.CaptureUnavailable -> R.string.game_area_calibration_no_capture
    GameAreaCalibration.Reason.AnchorsNotFound -> R.string.game_area_calibration_no_anchors
    GameAreaCalibration.Reason.ScalesDisagree -> R.string.game_area_calibration_scales
    GameAreaCalibration.Reason.InvalidRectangle -> R.string.game_area_calibration_bounds
    GameAreaCalibration.Reason.DoubleCheckFailed -> R.string.game_area_calibration_check
}

private suspend fun calibrateDisplay(context: Context, server: GameServer): GameAreaCalibration {
    if (server !is GameServer.Jp && server !is GameServer.Kr)
        return GameAreaCalibration.Failure(GameAreaCalibration.Reason.UnsupportedServer)
    return try {
        val frame = ScriptRunnerService.captureDisplayForCalibration()
            ?: return GameAreaCalibration.Failure(GameAreaCalibration.Reason.CaptureUnavailable)
        frame.use {
            withContext(Dispatchers.Default) {
                val started = System.nanoTime()
                context.assets.open("${server.simple}/${Images.WeeklyNotice.path}").use { noticeStream ->
                    DroidCvPattern(noticeStream, false).use { notice ->
                        val folder = if (context.assets.list(server.simple)?.contains(Images.Menu.path) == true) server.simple else "En"
                        context.assets.open("$folder/${Images.Menu.path}").use { menuStream ->
                            DroidCvPattern(menuStream, false).use { menu ->
                                GameAreaCalibrator(notice, menu).calibrate(frame, server).also { result ->
                                    Timber.i("Game area calibration: %s in %d ms", result, (System.nanoTime() - started) / 1_000_000)
                                }
                            }
                        }
                    }
                }
            }
        }
    } catch (e: CancellationException) { throw e }
      catch (e: Exception) {
        Timber.w(e, "Game area calibration capture unavailable")
        GameAreaCalibration.Failure(GameAreaCalibration.Reason.CaptureUnavailable)
    }
}
