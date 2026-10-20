package io.github.fate_grand_automata.runner

import android.app.Service
import android.content.Context
import android.content.DialogInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import io.github.fate_grand_automata.scripts.weekly.AtlasRouteRepository
import io.github.fate_grand_automata.ui.launcher.WeeklyRouteChoice
import io.github.fate_grand_automata.ui.launcher.WeeklyRouteScreen
import io.github.fate_grand_automata.util.FakedComposeView
import io.github.fate_grand_automata.util.ScriptMessages
import io.github.fate_grand_automata.util.WEEKLY_ROUTE_TAG
import io.github.fate_grand_automata.util.showOverlayDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Weekly mission route, step 1 (design 0009 D-1): loads this week's data from Atlas Academy, shows the route confirm
 * dialog and hands the first route quest to the script run. Every user-visible failure is reported here, so a null
 * result always means "nothing to run".
 */
@ServiceScoped
class WeeklyRouteFlow @Inject constructor(
    private val service: Service,
    @param:ApplicationContext private val context: Context,
    private val preferences: IPreferences,
    private val session: WeeklyRouteSession,
    private val messages: ScriptMessages,
    private val messageBox: ScriptRunnerMessageBox
) {
    private fun log(message: String) = Timber.tag(WEEKLY_ROUTE_TAG).i(message)

    private fun serverCode(): String? = when (preferences.gameServer) {
        is GameServer.Jp -> "JP"
        is GameServer.Kr -> "KR"
        else -> null
    }

    private suspend fun load(server: String): Result<AtlasRouteRepository.Dataset> = withContext(Dispatchers.IO) {
        runCatching {
            AtlasRouteRepository(File(context.cacheDir, "atlas-weekly"), log = { log(it) }).load(server)
        }
    }

    /** Loads the data, lets the user confirm the route and prepares the run. Returns false when nothing should run. */
    suspend fun confirm(): Boolean {
        val server = serverCode()
        if (server == null) {
            messageBox.show(context.getString(R.string.weekly_route_title), context.getString(R.string.weekly_route_unsupported_server))
            return false
        }

        log("start server=$server")
        messages.toast(context.getString(R.string.weekly_route_loading))

        val data = load(server).getOrElse { error ->
            log("load failed: ${error.message}")
            messageBox.show(
                context.getString(R.string.weekly_route_title),
                context.getString(R.string.weekly_route_load_failed, error.message ?: error::class.java.simpleName)
            )
            return false
        }

        val choice = routeDialog(data) ?: run {
            log("cancelled")
            return false
        }

        // The ordinary battle flow runs with the confirmed count; AP and apples follow the saved FGA settings.
        preferences.selectedBattleConfig = choice.config
        preferences.selectedServerConfigPref.let {
            it.shouldLimitRuns = true
            it.limitRuns = choice.target.count
        }
        preferences.resetCompletedRuns()
        session.target = choice.target
        log("confirmed quest=${choice.target.questId} name=${choice.target.name} ap=${choice.target.ap} count=${choice.target.count} config=${choice.config.id}")
        return true
    }

    private suspend fun routeDialog(data: AtlasRouteRepository.Dataset) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<WeeklyRouteChoice?> { continuation ->
            var dialog: DialogInterface? = null

            val composeView = FakedComposeView(service) {
                WeeklyRouteScreen(
                    data = data,
                    prefs = preferences,
                    onResult = {
                        continuation.resume(it)
                        dialog?.dismiss()
                    }
                )
            }

            dialog = showOverlayDialog(service) {
                setView(composeView.view)

                setOnDismissListener {
                    composeView.close()
                    try {
                        continuation.resume(null)
                    } catch (e: IllegalStateException) {
                        // Already resumed by a button
                    }
                }
            }
        }
    }
}
