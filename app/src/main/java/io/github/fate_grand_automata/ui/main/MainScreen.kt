package io.github.fate_grand_automata.ui.main

import android.Manifest.permission.POST_NOTIFICATIONS
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import io.github.fate_grand_automata.BuildConfig
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.accessibility.TapperService
import io.github.fate_grand_automata.capture.CaptureMainState
import io.github.fate_grand_automata.capture.CaptureProfileRow
import io.github.fate_grand_automata.capture.CaptureServiceButtons
import io.github.fate_grand_automata.capture.CaptureServiceLifecycle
import io.github.fate_grand_automata.capture.rememberCaptureMain
import io.github.fate_grand_automata.runner.ScriptRunnerService
import io.github.fate_grand_automata.scripts.prefs.wantsMediaProjectionToken
import io.github.fate_grand_automata.ui.CappedWidth
import io.github.fate_grand_automata.ui.Heading
import io.github.fate_grand_automata.ui.HeadingButton
import io.github.fate_grand_automata.ui.OnResume
import io.github.fate_grand_automata.ui.StartMediaProjection
import io.github.fate_grand_automata.ui.dialog.FgaDialog
import io.github.fate_grand_automata.ui.icon
import io.github.fate_grand_automata.ui.prefs.Preference
import io.github.fate_grand_automata.util.OpenDocTreePersistable
import io.github.fate_grand_automata.widget.WidgetStartEffect
import io.github.fate_grand_automata.widget.WidgetCaptureStartEffect

/** How long after a widget tap the screen may still close itself when the service comes up. */
private const val WIDGET_START_WINDOW_MS = 3 * 60_000L

@Composable
@OptIn(ExperimentalPermissionsApi::class)
fun MainScreen(
    vm: MainScreenViewModel = viewModel(),
    navigate: (MainScreenDestinations) -> Unit
) {
    var dirPicker: ActivityResultLauncher<Uri?>? by remember { mutableStateOf(null) }
    val permissionState = rememberPermissionState(permission = POST_NOTIFICATIONS)

    var toggling by rememberSaveable { mutableStateOf(false) }
    var startTrackerWithService by rememberSaveable { mutableStateOf(false) }
    // Widget-initiated start: the screen closes once the service (and the log-collection VPN) is up.
    var widgetStartDeadline by rememberSaveable { mutableLongStateOf(0L) }
    var widgetTrackerPending by remember { mutableStateOf(false) }

    val accessibilityDisabledDialog = FgaDialog()
    accessibilityDisabledDialog.build {
        title(stringResource(R.string.accessibility_disabled_title))
        message(stringResource(R.string.accessibility_disabled_message))

        buttons(
            okLabel = stringResource(R.string.accessibility_disabled_go_to_settings),
            onSubmit = {
                navigate(MainScreenDestinations.AccessibilitySettings)
                toggling = true
            }
        )
    }

    val overlayDisabledDialog = FgaDialog()
    overlayDisabledDialog.build {
        title(stringResource(R.string.draw_overlay_disabled_title))
        message(stringResource(R.string.draw_overlay_disabled_message))

        buttons(
            okLabel = stringResource(R.string.accessibility_disabled_go_to_settings),
            onSubmit = {
                navigate(MainScreenDestinations.OverlaySettings)
                toggling = true
            }
        )
    }

    val context = LocalContext.current

    val startMediaProjection = rememberLauncherForActivityResult(StartMediaProjection()) {
        if (it == null) {
            startTrackerWithService = false
            widgetStartDeadline = 0L
        }
        vm.onStartMediaProjectionResult(context, it)
    }

    val pickDirectory: () -> Unit = { dirPicker?.launch(Uri.EMPTY) }

    val requestNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && !permissionState.status.isGranted
        ) {
            permissionState.launchPermissionRequest()
        }
    }

    fun toggleOverlayService() {
        toggling = false

        toggleOverlayService(
            context = context,
            vm = vm,
            pickDirectory = pickDirectory,
            requestNotifications = requestNotifications,
            showOverlayDisabled = { overlayDisabledDialog.show() },
            showAccessibilityDisabled = { accessibilityDisabledDialog.show() },
            startMediaProjection = { startMediaProjection.launch() },
            onStartingService = { startTrackerWithService = true }
        )
    }

    dirPicker = rememberLauncherForActivityResult(OpenDocTreePersistable()) {
        if (it != null) {
            vm.storageProvider.setRoot(it)

            toggleOverlayService()
        }
    }

    OnResume {
        if (toggling || vm.autoStartService) {
            toggleOverlayService()
        }
    }

    WidgetStartEffect(
        requests = vm.widgetRequests,
        serviceStarted = { ScriptRunnerService.serviceStarted.value },
    ) {
        widgetStartDeadline = SystemClock.elapsedRealtime() + WIDGET_START_WINDOW_MS
        toggleOverlayService()
    }

    var widgetCapturePending by rememberSaveable { mutableStateOf(false) }
    val capture = rememberCaptureMain(vm.prefsCore, onStartFinished = { started ->
        if (widgetCapturePending && started) (context as? Activity)?.finish()
        widgetCapturePending = false
    }) { navigate(MainScreenDestinations.GainsTracker) }
    WidgetCaptureStartEffect(vm.widgetRequests, capture.state.running) {
        widgetCapturePending = true
        capture.toggle()
    }
    val overlayServiceStarted by ScriptRunnerService.serviceStarted
    val trackerConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK && ScriptRunnerService.serviceStarted.value) {
            CaptureServiceLifecycle.onStart(context, vm.prefsCore, requestConsent = {})
        }
        widgetTrackerPending = false
    }
    LaunchedEffect(overlayServiceStarted, startTrackerWithService) {
        if (overlayServiceStarted && startTrackerWithService) {
            startTrackerWithService = false
            if (capture.state.showButton) {
                widgetTrackerPending = true
                capture.withAgreement(false) {
                    var waitingForConsent = false
                    if (ScriptRunnerService.serviceStarted.value) {
                        CaptureServiceLifecycle.onStart(context, vm.prefsCore) {
                            waitingForConsent = true
                            trackerConsent.launch(it)
                        }
                    }
                    // A consent prompt keeps the screen open until its result arrives.
                    if (!waitingForConsent) widgetTrackerPending = false
                }
            }
        }
    }
    LaunchedEffect(overlayServiceStarted, startTrackerWithService, widgetTrackerPending, widgetStartDeadline) {
        if (widgetStartDeadline != 0L && overlayServiceStarted && !startTrackerWithService && !widgetTrackerPending) {
            val inWindow = SystemClock.elapsedRealtime() <= widgetStartDeadline
            widgetStartDeadline = 0L
            if (inWindow) (context as? Activity)?.finish()
        }
    }
    val accessibilityServiceStarted by TapperService.serviceStarted

    MainScreenContent(
        navigate = {
            if (it is MainScreenDestinations.BattleConfigs) {
                if (vm.ensureRootDir(context, pickDirectory)) {
                    navigate(it)
                }
            } else navigate(it)
        },
        capture = capture.state,
        toggleCapture = capture.toggle,
        overlayServiceStarted = overlayServiceStarted,
        toggleOverlayService = { toggleOverlayService() },
        accessibilityServiceStarted = accessibilityServiceStarted,
        toggleAccessibilityService = {
            if (accessibilityServiceStarted) {
                TapperService.instance?.disableSelf()
            } else {
                navigate(MainScreenDestinations.AccessibilitySettings)
            }
        }
    )
}

private fun toggleOverlayService(
    context: Context,
    vm: MainScreenViewModel,
    pickDirectory: () -> Unit,
    requestNotifications: () -> Unit,
    showOverlayDisabled: () -> Unit,
    showAccessibilityDisabled: () -> Unit,
    startMediaProjection: () -> Unit,
    onStartingService: () -> Unit
) {
    if (!Settings.canDrawOverlays(context)) {
        showOverlayDisabled()
        return
    }

    if (!TapperService.serviceStarted.value) {
        showAccessibilityDisabled()
        return
    }

    if (!vm.ensureRootDir(context, pickDirectory)) {
        return
    }

    requestNotifications()

    if (ScriptRunnerService.serviceStarted.value) {
        ScriptRunnerService.stopService(context)
    } else {
        onStartingService()
        if (vm.prefs.wantsMediaProjectionToken) {
            if (ScriptRunnerService.mediaProjectionToken == null) {
                startMediaProjection()
                return
            }
        }

        // if we already have a token or are using root mode
        ScriptRunnerService.startService(context)
    }
}

sealed class MainScreenDestinations {
    object Releases : MainScreenDestinations()
    object BattleConfigs : MainScreenDestinations()
    object Logs : MainScreenDestinations()
    data class AccountDashboard(val userId: String) : MainScreenDestinations()
    object MoreOptions : MainScreenDestinations()
    object GainsTracker : MainScreenDestinations()
    object AccessibilitySettings : MainScreenDestinations()
    object OverlaySettings : MainScreenDestinations()
}

@Composable
internal fun MainScreenContent(
    navigate: (MainScreenDestinations) -> Unit,
    overlayServiceStarted: Boolean,
    toggleOverlayService: () -> Unit,
    accessibilityServiceStarted: Boolean,
    toggleAccessibilityService: () -> Unit,
    capture: CaptureMainState = CaptureMainState(),
    toggleCapture: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        CappedWidth(modifier = Modifier.weight(1f)) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                item {
                    Heading(stringResource(R.string.app_name))
                    MainBuildStatusRow(
                        accessibilityServiceStarted, overlayServiceStarted,
                        { navigate(MainScreenDestinations.Releases) }, toggleAccessibilityService,
                    )
                }

                if (capture.profiles.isNotEmpty()) {
                    item {
                        CaptureProfileRow(capture.profiles, capture.now) { navigate(MainScreenDestinations.AccountDashboard(it)) }
                    }
                }

                item {
                    Card(
                        modifier = Modifier
                            .padding(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Column {
                            Preference(
                                title = stringResource(R.string.p_battle_config),
                                icon = icon(R.drawable.ic_formation),
                                onClick = { navigate(MainScreenDestinations.BattleConfigs) }
                            )

                            HorizontalDivider()

                            Preference(
                                title = stringResource(R.string.p_logs),
                                icon = icon(Icons.Default.History),
                                onClick = { navigate(MainScreenDestinations.Logs) }
                            )

                            HorizontalDivider()

                            Preference(
                                title = stringResource(R.string.p_more_options),
                                icon = icon(R.drawable.ic_dots_horizontal),
                                onClick = { navigate(MainScreenDestinations.MoreOptions) }
                            )
                        }
                    }
                }
            }
        }

        CaptureServiceButtons(capture, toggleCapture) {
            OverlayServiceToggleButton(
                serviceStarted = overlayServiceStarted,
                toggleService = toggleOverlayService,
            )
        }
    }
}

@Composable
private fun OverlayServiceToggleButton(
    serviceStarted: Boolean,
    toggleService: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor by animateColorAsState(
        if (serviceStarted)
            MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.secondary
    )

    val foregroundColor =
        if (serviceStarted)
            MaterialTheme.colorScheme.onError
        else MaterialTheme.colorScheme.onSecondary

    ExtendedFloatingActionButton(
        text = {
            Text(
                stringResource(if (serviceStarted) R.string.stop_service else R.string.start_service),
                color = foregroundColor
            )
        },
        onClick = toggleService,
        icon = {
            Icon(
                painterResource(if (serviceStarted) R.drawable.ic_close else R.drawable.ic_launch),
                contentDescription = "Toggle service",
                tint = foregroundColor
            )
        },
        containerColor = backgroundColor,
        modifier = modifier
    )
}

/** Measure both controls at their natural width; wrap the status without losing its end alignment. */
@Composable
internal fun MainBuildStatusRow(
    serviceStarted: Boolean,
    overlayServiceStarted: Boolean,
    openBuild: () -> Unit,
    toggleService: () -> Unit,
) {
    Layout(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        content = {
            Box(Modifier.testTag("buildBadge")) {
                HeadingButton(text = "Build: ${BuildConfig.VERSION_NAME}", onClick = openBuild)
            }
            Surface(
                onClick = toggleService,
                // Preserve the old guard against disabling accessibility during an overlay run.
                enabled = !serviceStarted || !overlayServiceStarted,
                shape = CircleShape,
                color = if (serviceStarted) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.errorContainer,
                contentColor = if (serviceStarted) Color(0xFF1B5E20) else MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.testTag("accessibilityStatus"),
            ) {
                Text(
                    stringResource(if (serviceStarted) R.string.accessibility_status_enabled else R.string.accessibility_status_disabled),
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val badge = measurables[0].measure(loose)
        val status = measurables[1].measure(loose)
        val gap = 8.dp.roundToPx()
        val fits = badge.width + gap + status.width <= constraints.maxWidth
        val height = if (fits) maxOf(badge.height, status.height) else badge.height + gap + status.height
        layout(constraints.maxWidth, height) {
            badge.placeRelative(0, if (fits) (height - badge.height) / 2 else 0)
            status.placeRelative(constraints.maxWidth - status.width, if (fits) (height - status.height) / 2 else badge.height + gap)
        }
    }
}
