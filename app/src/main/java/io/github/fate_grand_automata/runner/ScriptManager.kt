package io.github.fate_grand_automata.runner

import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.widget.Toast
import dagger.hilt.EntryPoints
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.accessibility.TapperService
import io.github.fate_grand_automata.di.script.ScriptComponent
import io.github.fate_grand_automata.di.script.ScriptComponentBuilder
import io.github.fate_grand_automata.di.script.ScriptEntryPoint
import io.github.fate_grand_automata.di.service.ServiceCoroutineScope
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.entrypoints.AutoCEEnhance
import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha
import io.github.fate_grand_automata.scripts.entrypoints.AutoGiftBox
import io.github.fate_grand_automata.scripts.entrypoints.AutoLottery
import io.github.fate_grand_automata.scripts.entrypoints.AutoServantLevel
import io.github.fate_grand_automata.scripts.entrypoints.AutoWeeklyRoute
import io.github.fate_grand_automata.scripts.entrypoints.SkillLevelScript
import io.github.fate_grand_automata.scripts.modules.SkillLevelStep
import io.github.fate_grand_automata.scripts.modules.SlotStop
import io.github.fate_grand_automata.scripts.entrypoints.SupportImageMaker
import io.github.fate_grand_automata.scripts.enums.GameServers
import io.github.fate_grand_automata.scripts.enums.ScriptModeEnum
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import io.github.fate_grand_automata.ui.exit.BattleExit
import io.github.fate_grand_automata.ui.launcher.ScriptLauncher
import io.github.fate_grand_automata.ui.launcher.ScriptLauncherResponse
import io.github.fate_grand_automata.ui.runner.ScriptRunnerUIState
import io.github.fate_grand_automata.ui.runner.ScriptRunnerUIStateHolder
import io.github.fate_grand_automata.ui.support_img_namer.showSupportImageNamer
import io.github.fate_grand_automata.util.FakedComposeView
import io.github.fate_grand_automata.util.ImageLoader
import io.github.fate_grand_automata.util.KnownException
import io.github.fate_grand_automata.util.ScriptMessages
import io.github.fate_grand_automata.util.ScriptState
import io.github.fate_grand_automata.util.StorageProvider
import io.github.fate_grand_automata.util.WEEKLY_ROUTE_TAG
import io.github.fate_grand_automata.util.messageAndStackTrace
import io.github.fate_grand_automata.util.set
import io.github.fate_grand_automata.util.skillLevelStepMessage
import io.github.fate_grand_automata.util.skillLevelSummary
import io.github.fate_grand_automata.util.showOverlayDialog
import io.github.lib_automata.EntryPoint
import io.github.lib_automata.OcrService
import io.github.lib_automata.ScreenshotService
import io.github.lib_automata.ScriptAbortException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

private const val PROBE_SUMMARY_LINES = 25

@ServiceScoped
class ScriptManager @Inject constructor(
    private val service: Service,
    @param:ApplicationContext private val context: Context,
    private val imageLoader: ImageLoader,
    private val preferences: IPreferences,
    private val prefsCore: PrefsCore,
    private val storageProvider: StorageProvider,
    private val messages: ScriptMessages,
    private val uiStateHolder: ScriptRunnerUIStateHolder,
    private val clipboardManager: ClipboardManager,
    private val messageBox: ScriptRunnerMessageBox,
    @param:ServiceCoroutineScope private val scope: CoroutineScope,
    private val launcherResponseHandler: ScriptLauncherResponseHandler,
    private val weeklyRoute: WeeklyRouteFlow
) {
    var scriptState: ScriptState = ScriptState.Stopped
        private set

    /**
     * The OCR engine belonging to the current [ScriptComponent]. Held so its native Tesseract
     * context can be freed as soon as the run ends instead of waiting on the finalizer.
     */
    private var ocrService: OcrService? = null

    fun releaseOcrService() {
        ocrService?.let {
            ocrService = null
            runCatching { it.close() }
                .onFailure { e -> Timber.w(e, "Failed to release the OCR service") }
        }
    }

    /** D-25: saves the probe's CSV below the FGA storage root, remembers the successes and builds the summary text. */
    private fun probeSummary(context: Context, reason: AutoWeeklyRoute.ExitReason.Probed): String {
        val results = reason.results
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val path = "weeklyRoute/probe/probe-${reason.server}-$stamp.csv"
        val saved = runCatching {
            storageProvider.writeDiagnosticFile(path, "text/csv") { out ->
                out.write(io.github.fate_grand_automata.scripts.weekly.WeeklyProbe.csv(results).toByteArray(Charsets.UTF_8))
            }
        }.onFailure { Timber.tag(WEEKLY_ROUTE_TAG).w(it, "mode=probe csv write failed path=%s", path) }.isSuccess
        weeklyRoute.rememberProbeSucceeded(reason.server, results.filter {
            it.status == io.github.fate_grand_automata.scripts.weekly.ProbeStatus.Success
        }.map { it.quest.id })
        Timber.tag(WEEKLY_ROUTE_TAG).i("mode=probe summary quests=${results.size} csv=${if (saved) "diagnostics/$path" else "failed"}")
        return buildString {
            append(context.getString(if (reason.userStopped) R.string.weekly_probe_summary_user else R.string.weekly_probe_summary_finished))
            for (status in io.github.fate_grand_automata.scripts.weekly.ProbeStatus.entries) {
                val count = results.count { it.status == status }
                if (count > 0) append("\n").append(context.getString(R.string.weekly_probe_count, context.getString(probeStatusName(status)), count))
            }
            val failures = results.filter {
                it.status != io.github.fate_grand_automata.scripts.weekly.ProbeStatus.Success &&
                    it.status != io.github.fate_grand_automata.scripts.weekly.ProbeStatus.NotRun
            }
            failures.take(PROBE_SUMMARY_LINES).forEach {
                append("\n").append(context.getString(R.string.weekly_probe_line, it.quest.chapter.replace('\n', ' '), it.quest.name,
                    context.getString(probeStatusName(it.status))))
            }
            if (failures.size > PROBE_SUMMARY_LINES) append("\n").append(context.getString(R.string.weekly_probe_more, failures.size - PROBE_SUMMARY_LINES))
            append("\n").append(if (saved) context.getString(R.string.weekly_probe_csv, "diagnostics/$path") else context.getString(R.string.weekly_probe_csv_failed))
        }
    }

    private fun probeStatusName(status: io.github.fate_grand_automata.scripts.weekly.ProbeStatus) = when (status) {
        io.github.fate_grand_automata.scripts.weekly.ProbeStatus.Success -> R.string.weekly_probe_status_success
        io.github.fate_grand_automata.scripts.weekly.ProbeStatus.MapFitFailed -> R.string.weekly_probe_status_map
        io.github.fate_grand_automata.scripts.weekly.ProbeStatus.NoPanel -> R.string.weekly_probe_status_no_panel
        io.github.fate_grand_automata.scripts.weekly.ProbeStatus.Mismatch -> R.string.weekly_probe_status_mismatch
        io.github.fate_grand_automata.scripts.weekly.ProbeStatus.ChapterNotFound -> R.string.weekly_probe_status_chapter
        io.github.fate_grand_automata.scripts.weekly.ProbeStatus.NotRun -> R.string.weekly_probe_status_not_run
    }

    private suspend fun showBattleExit(
        context: Context,
        exception: AutoBattle.ExitException
    ) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { continuation ->
            var dialog: DialogInterface? = null

            val composeView = FakedComposeView(context) {
                BattleExit(
                    exception = exception,
                    prefs = preferences,
                    prefsCore = prefsCore,
                    onClose = { dialog?.dismiss() },
                    onCopy = {
                        clipboardManager.set(context, exception)
                    }
                )
            }

            dialog = showOverlayDialog(context) {
                setView(composeView.view)

                setOnDismissListener {
                    composeView.close()
                    continuation.resume(Unit)
                }
            }
        }
    }

    private fun onScriptExit(e: Exception) = scope.launch {
        uiStateHolder.uiState = ScriptRunnerUIState.Idle
        uiStateHolder.isPlayButtonEnabled = false
        imageLoader.clearSupportCache()
        releaseOcrService()

        // Stop recording
        scriptState.let { state ->
            if (state is ScriptState.Started) {
                scriptState = ScriptState.Stopping(state)
            }

            val recording = when (state) {
                ScriptState.Stopped -> null
                is ScriptState.Started -> state.recording
                is ScriptState.Stopping -> state.start.recording
            }

            if (recording != null) {
                // A little bit of delay so the exit message can be recorded
                launch {
                    try {
                        delay(500.milliseconds)
                        withContext(Dispatchers.Main) {
                            recording.close()
                        }
                    } catch (e: Exception) {
                        val msg = context.getString(R.string.cannot_stop_recording)
                        Timber.e(e, msg)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(service, "${msg}: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }

                    uiStateHolder.isRecording = false
                }
            }
        }

        val scriptExitedString by lazy { context.getString(R.string.script_exited) }

        when (e) {
            is SupportImageMaker.ExitException -> {
                when (e.reason) {
                    SupportImageMaker.ExitReason.NotFound -> {
                        val msg = context.getString(R.string.support_img_maker_not_found)

                        messages.notify(msg)
                        messageBox.show(scriptExitedString, msg)
                    }

                    SupportImageMaker.ExitReason.Success -> showSupportImageNamer(context, storageProvider, preferences)
                }
            }

            is AutoLottery.ExitException -> {
                val msg = when (e.reason) {
                    AutoLottery.ExitReason.PresentBoxFull -> context.getString(R.string.present_box_full)
                    AutoLottery.ExitReason.RanOutOfCurrency -> context.getString(R.string.lottery_currency_depleted)
                }

                messages.notify(msg)
                messageBox.show(scriptExitedString, msg)
            }

            is AutoGiftBox.ExitException -> {
                val msg = when (val reason = e.reason) {
                    is AutoGiftBox.ExitReason.CannotSelectAnyMore -> context.getString(
                        R.string.picked_exp_stacks,
                        reason.pickedStacks,
                        reason.pickedGoldEmbers
                    )

                    AutoGiftBox.ExitReason.NoEmbersFound -> context.getString(R.string.no_embers_found)
                }

                messages.notify(msg)
                messageBox.show(scriptExitedString, msg)
            }

            is AutoFriendGacha.ExitException -> {
                val msg = when (val reason = e.reason) {
                    AutoFriendGacha.ExitReason.InventoryFull -> context.getString(R.string.inventory_full)
                    AutoFriendGacha.ExitReason.InventoryFullNothingToSell -> context.getString(R.string.fp_sell_nothing)
                    AutoFriendGacha.ExitReason.InventoryFullNotServant -> context.getString(R.string.fp_sell_not_servant)
                    is AutoFriendGacha.ExitReason.BannerReturnStopped -> context.getString(when (reason) {
                        AutoFriendGacha.ExitReason.WorkshopNotReached -> R.string.fp_sell_return_workshop
                        AutoFriendGacha.ExitReason.WorkshopMenuFailed -> R.string.fp_sell_return_menu
                        AutoFriendGacha.ExitReason.SummonMenuFailed -> R.string.fp_sell_return_summon
                        AutoFriendGacha.ExitReason.BannerTitleNotFound -> R.string.fp_sell_return_title
                    })
                    AutoFriendGacha.ExitReason.SellScreenUnknown -> context.getString(R.string.fp_sell_screen_unknown)
                    is AutoFriendGacha.ExitReason.SellStopped -> {
                        val text = when (reason) {
                            is AutoFriendGacha.ExitReason.SellEntryFailed -> R.string.fp_sell_stop_entry
                            is AutoFriendGacha.ExitReason.ColumnSwitchFailed -> R.string.fp_sell_stop_columns
                            is AutoFriendGacha.ExitReason.SelectionScreenLost -> R.string.fp_sell_stop_grid
                            is AutoFriendGacha.ExitReason.SellFlowFailed -> R.string.fp_sell_stop_flow
                        }
                        val state = context.getString(if (reason.dryRun) R.string.fp_sell_state_on else R.string.fp_sell_state_off)
                        context.getString(text) + "\n" + context.getString(R.string.fp_sell_stop_state, state, reason.selected, reason.unknownLock)
                    }
                    is AutoFriendGacha.ExitReason.SellDryRunDone -> context.getString(R.string.fp_sell_dry_done) + "\n" +
                        io.github.fate_grand_automata.util.fpSellSummary(context, reason.summary)
                    is AutoFriendGacha.ExitReason.Limit -> context.getString(R.string.times_rolled, reason.count)
                }

                messages.notify(msg)
                messageBox.show(scriptExitedString, msg)
            }

            is AutoBattle.ExitException -> {
                preferences.hidePlayButton = false

                if (e.reason !is AutoBattle.ExitReason.Abort) {
                    messages.notify(scriptExitedString)
                }

                showBattleExit(service, e)
            }
            is AutoServantLevel.ExitException -> {
                val msg = when (val reason = e.reason) {
                    AutoServantLevel.ExitReason.NoServantSelected ->
                        context.getString(R.string.servant_enhancement_none_selected)

                    is AutoServantLevel.ExitReason.Unexpected -> {
                        e.let {
                            "${context.getString(R.string.unexpected_error)}: ${reason.exception.message}"
                        }
                    }

                    AutoServantLevel.ExitReason.MaxLevelAchieved ->
                        context.getString(R.string.servant_enhancement_max_level)

                    AutoServantLevel.ExitReason.NoEmbersOrQPLeft ->
                        context.getString(R.string.servant_enhancement_no_embers_or_qp_left)

                    AutoServantLevel.ExitReason.Abort ->
                        context.getString(R.string.servant_enhancement_aborted)

                    AutoServantLevel.ExitReason.RedirectAscension ->
                        context.getString(R.string.servant_enhancement_redirect_ascension_success)
                    AutoServantLevel.ExitReason.RedirectGrail ->
                        context.getString(R.string.servant_enhancement_redirect_grail_success)

                    AutoServantLevel.ExitReason.UnableToPerformAscension ->
                        context.getString(R.string.servant_enhancement_perform_ascension_failed)
                }
                messageBox.show(scriptExitedString, msg)
            }

            is SkillLevelScript.ExitException -> {
                val msg = when (val reason = e.reason) {
                    is SkillLevelScript.ExitReason.Done ->
                        context.getString(if (reason.results.any { it.stop == SlotStop.Stuck }) R.string.skill_level_stopped else R.string.skill_level_done) + "\n" + skillLevelSummary(context, reason.results)

                    SkillLevelScript.ExitReason.NoServantSelected -> context.getString(R.string.skill_level_select_servant)
                    SkillLevelScript.ExitReason.WrongScreen -> context.getString(R.string.skill_level_wrong_screen)
                    SkillLevelScript.ExitReason.UnsupportedServer -> context.getString(R.string.skill_level_unsupported_server)
                    SkillLevelScript.ExitReason.NothingSelected -> context.getString(R.string.skill_level_nothing_selected)
                    is SkillLevelScript.ExitReason.Abort ->
                        context.getString(R.string.skill_level_aborted) + "\n" + skillLevelSummary(context, reason.results)

                    is SkillLevelScript.ExitReason.Unexpected ->
                        "${context.getString(R.string.unexpected_error)}: ${reason.exception.message}\n" +
                            skillLevelSummary(context, reason.results)
                }

                val stagedMessage = skillLevelStepMessage(context, e.step, msg)
                messages.notify(stagedMessage)
                messageBox.show(scriptExitedString, stagedMessage)
            }

            is AutoWeeklyRoute.ExitException -> {
                val msg = when (val reason = e.reason) {
                    AutoWeeklyRoute.ExitReason.NoRoute -> context.getString(R.string.weekly_route_stop_no_route)
                    AutoWeeklyRoute.ExitReason.NotOnMainScreen -> context.getString(R.string.weekly_route_stop_not_main)
                    is AutoWeeklyRoute.ExitReason.Probed -> probeSummary(context, reason)
                    is AutoWeeklyRoute.ExitReason.Finished -> buildString {
                        append(context.getString(when {
                            reason.userStopped -> R.string.weekly_route_summary_user
                            reason.stop != null -> R.string.weekly_route_summary_battle
                            reason.results.any { it.status in setOf(io.github.fate_grand_automata.scripts.weekly.StopStatus.Aborted,io.github.fate_grand_automata.scripts.weekly.StopStatus.NotRun) } -> R.string.weekly_route_summary_stopped
                            else -> R.string.weekly_route_summary_finished
                        }))
                        reason.results.forEach { result ->
                            val status = when (result.status) {
                                io.github.fate_grand_automata.scripts.weekly.StopStatus.Done -> context.getString(R.string.weekly_route_status_done)
                                io.github.fate_grand_automata.scripts.weekly.StopStatus.NotFound -> context.getString(R.string.weekly_route_status_not_found,context.getString(R.string.weekly_route_check_log))
                                io.github.fate_grand_automata.scripts.weekly.StopStatus.Aborted -> context.getString(R.string.weekly_route_status_aborted,context.getString(R.string.weekly_route_check_log))
                                io.github.fate_grand_automata.scripts.weekly.StopStatus.NotRun -> context.getString(R.string.weekly_route_status_not_run)
                            }
                            append("\n")
                            append(context.getString(R.string.weekly_route_summary_line,result.item.quest.chapter,result.item.quest.name,
                                result.item.quest.ap,result.done,result.item.count,status))
                        }
                        if(reason.manual.isNotEmpty()) append("\n"+context.getString(R.string.weekly_route_summary_manual,reason.manual.joinToString("\n")))
                    }
                }
                messages.notify(msg)
                messageBox.show(scriptExitedString,msg)
                (e.reason as? AutoWeeklyRoute.ExitReason.Finished)?.stop?.let { showBattleExit(context,it) }
            }

            is ScriptAbortException -> {
                // user aborted. do nothing
            }

            is AutoCEEnhance.ExitException -> {
                val msg = when (val reason = e.reason) {
                    AutoCEEnhance.ExitReason.MaxLevel -> context.getString(R.string.ce_enhance_max_level)
                    AutoCEEnhance.ExitReason.NoMatchingCE -> context.getString(R.string.ce_enhance_no_matching_ce)
                    AutoCEEnhance.ExitReason.NoMaterialsOrQP -> context.getString(R.string.ce_enhance_no_materials_or_qp)
                    AutoCEEnhance.ExitReason.Abort -> context.getString(R.string.ce_enhance_aborted)
                    is AutoCEEnhance.ExitReason.Unexpected -> context.getString(R.string.unexpected_error) + ": " + reason.exception.message
                }

                messages.notify(msg)
                messageBox.show(scriptExitedString, msg)
            }

            is KnownException -> {
                messages.notify(scriptExitedString)

                messageBox.show(scriptExitedString, e.reason.msg(context))
            }

            else -> {
                println(e.messageAndStackTrace)

                val msg = context.getString(R.string.unexpected_error)
                messages.notify(msg)

                messageBox.show(msg, e.messageAndStackTrace, e)
            }
        }

        scriptState = ScriptState.Stopped
        delay(250.milliseconds)
        uiStateHolder.isPlayButtonEnabled = true
    }

    private fun getEntryPoint(entryPoint: ScriptEntryPoint): EntryPoint =
        when (preferences.scriptMode) {
            ScriptModeEnum.Battle -> entryPoint.battle()
            ScriptModeEnum.FP -> entryPoint.fp()
            ScriptModeEnum.Lottery -> entryPoint.lottery()
            ScriptModeEnum.PresentBox -> entryPoint.giftBox()
            ScriptModeEnum.SupportImageMaker -> entryPoint.supportImageMaker()
            ScriptModeEnum.CEEnhance -> entryPoint.ceEnhance()
            ScriptModeEnum.ServantLevel -> entryPoint.servantLevel()
            ScriptModeEnum.SkillLevel -> entryPoint.skillLevel()
            ScriptModeEnum.AppendLevel -> entryPoint.appendLevel()
            ScriptModeEnum.WeeklyRoute -> entryPoint.weeklyRoute()
        }

    enum class PauseAction {
        Pause, Resume, Toggle
    }

    fun pause(action: PauseAction): Boolean {
        scriptState.let { state ->
            if (state is ScriptState.Started) {
                if (state.paused && action != PauseAction.Pause) {
                    uiStateHolder.uiState = ScriptRunnerUIState.Running
                    state.entryPoint.exitManager.resume()

                    state.paused = false

                    return true
                } else if (!state.paused && action != PauseAction.Resume) {
                    state.entryPoint.exitManager.pause()
                    uiStateHolder.uiState = ScriptRunnerUIState.Paused(state.entryPoint.pausedStatus())

                    state.paused = true

                    return true
                }
            }
        }

        return false
    }

    /**
     * Sets [IPreferences.gameServer] from the Game Server setting.
     *
     * @return false if the setting is auto-detect and no FGO app has been detected,
     * in which case the server is left unchanged.
     */
    private fun updateGameServer(): Boolean {
        val server = prefsCore.gameServerRaw.get()

        if (server == PrefsCore.GAME_SERVER_AUTO_DETECT) {
            val detected = TapperService.instance?.detectedFgoServer
                ?: return false

            Timber.d("Using auto-detected Game Server: $detected")
            preferences.gameServer = detected

            return true
        }

        preferences.gameServer =
            try {
                GameServers.deserialize(server)?.also {
                    Timber.d("Using Game Server: $it")
                } ?: GameServers.default
            } catch (e: Exception) {
                Timber.e(e, "Game Server: Falling back to NA")

                GameServers.default
            }

        return true
    }

    fun startScript(
        context: Context,
        screenshotService: ScreenshotService,
        componentBuilder: ScriptComponentBuilder
    ) {
        val isGameServerKnown = updateGameServer()

        if (scriptState !is ScriptState.Stopped) {
            return
        }

        if (!isGameServerKnown) {
            Timber.w("Game Server: auto-detect found no FGO app, not starting")

            scope.launch {
                messageBox.show(
                    context.getString(R.string.p_game_server),
                    context.getString(
                        R.string.error_game_server_not_detected,
                        context.getString(R.string.p_more_options),
                        context.getString(R.string.p_script_mode_battle),
                        context.getString(R.string.p_game_server)
                    )
                )
            }

            return
        }

        uiStateHolder.isPlayButtonEnabled = false

        val scriptComponent = componentBuilder
            .screenshotService(screenshotService)
            .build()

        val hiltEntryPoint = EntryPoints.get(scriptComponent, ScriptEntryPoint::class.java)

        // A fresh ScriptComponent means a fresh OCR engine; drop the previous one first
        releaseOcrService()
        ocrService = hiltEntryPoint.ocrService()

        scope.launch {
            /*
             * Screen detection needs a screenshot, which can fail when the projection never
             * delivered a frame. Don't let that take the whole app down.
             */
            val detectedMode = try {
                hiltEntryPoint.autoDetect().get()
            } catch (e: Exception) {
                Timber.e(e, "Screen detection failed")

                val msg = if (e is KnownException)
                    e.reason.msg(context)
                else "${context.getString(R.string.unexpected_error)}: ${e.message}"

                uiStateHolder.isPlayButtonEnabled = true
                releaseOcrService()

                messageBox.show(context.getString(R.string.unexpected_error), msg)

                return@launch
            }

            // D-10: no settings dialog on a skill / append screen that has no servant selected.
            if ((detectedMode == ScriptModeEnum.SkillLevel || detectedMode == ScriptModeEnum.AppendLevel) &&
                !hiltEntryPoint.autoDetect().skillServantSelected()
            ) {
                uiStateHolder.isPlayButtonEnabled = true
                releaseOcrService()

                messageBox.show(
                    context.getString(R.string.script_exited),
                    skillLevelStepMessage(context, SkillLevelStep.Select, context.getString(R.string.skill_level_select_servant))
                )

                return@launch
            }

            val resp = scriptPicker(context, detectedMode)

            uiStateHolder.isPlayButtonEnabled = true
            val effectiveResp = resp
            launcherResponseHandler.handle(effectiveResp)

            if (effectiveResp !is ScriptLauncherResponse.Cancel) {
                delay(500.milliseconds)
                runEntryPoint(
                    screenshotService = screenshotService,
                    entryPointProvider = { getEntryPoint(hiltEntryPoint) }
                )
            } else {
                // onScriptExit never runs on this path
                releaseOcrService()
            }
        }
    }

    fun stopScript() {
        scriptState.let { state ->
            if (state is ScriptState.Started) {
                uiStateHolder.isPlayButtonEnabled = false
                scriptState = ScriptState.Stopping(state)
                state.entryPoint.stop()
            }
        }
    }

    private suspend fun runEntryPoint(screenshotService: ScreenshotService, entryPointProvider: () -> EntryPoint) {
        if (scriptState !is ScriptState.Stopped) {
            return
        }

        val recording = try {
            if (preferences.recordScreen) {
                withContext(Dispatchers.Main) {
                    screenshotService.startRecording()
                }
            } else null
        } catch (e: Exception) {
            val msg = context.getString(R.string.cannot_start_recording)
            Timber.e(e, msg)
            withContext(Dispatchers.Main) {
                Toast.makeText(service, "${msg}: ${e.message}", Toast.LENGTH_SHORT).show()
            }

            null
        }

        val entryPoint = entryPointProvider().apply {
            scriptExitListener = { onScriptExit(it) }
        }

        scriptState = ScriptState.Started(entryPoint, recording)
        uiStateHolder.uiState = ScriptRunnerUIState.Running

        if (recording != null) {
            uiStateHolder.isRecording = true
        }

        entryPoint.run()
    }

    private suspend fun scriptPicker(
        context: Context,
        detectedMode: ScriptModeEnum
    ) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<ScriptLauncherResponse> { continuation ->

            var dialog: DialogInterface? = null
            var launcherBaseWidth: Int? = null

            val composeView = FakedComposeView(context) {
                ScriptLauncher(
                    scriptMode = detectedMode,
                    onResponse = {
                        continuation.resume(it)
                        dialog?.dismiss()
                    },
                    prefs = preferences,
                    prefsCore = prefsCore,
                    prepareWeekly = weeklyRoute::prepare,
                    prepareProbe = weeklyRoute::prepareProbe,
                    onWeeklyExpanded = { expanded ->
                        val window = (dialog as? androidx.appcompat.app.AlertDialog)?.window
                        val base = launcherBaseWidth ?: window?.decorView?.width?.takeIf { it > 0 }
                        if (base != null) {
                            launcherBaseWidth = base
                            val available = service.resources.displayMetrics.widthPixels
                            window?.setLayout(
                                if (expanded) minOf(available - 24, (base * 1.7).toInt()) else base,
                                android.view.WindowManager.LayoutParams.WRAP_CONTENT
                            )
                        }
                    }
                )
            }

            dialog = showOverlayDialog(context) {
                setView(composeView.view)

                setOnDismissListener {
                    uiStateHolder.isPlayButtonEnabled = true
                    composeView.close()
                    try {
                        continuation.resume(ScriptLauncherResponse.Cancel)
                    } catch (e: IllegalStateException) {
                        // Ignore exception on resuming twice
                    }
                }
            }
        }
    }

    fun showStatus(status: Exception) {
        if (status is AutoBattle.ExitException) {
            scope.launch { showBattleExit(service, status) }
        }
    }
}
