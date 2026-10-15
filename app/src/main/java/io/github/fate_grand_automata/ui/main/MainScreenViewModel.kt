package io.github.fate_grand_automata.ui.main

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.fate_grand_automata.BuildConfig
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.runner.ScriptRunnerService
import io.github.fate_grand_automata.scripts.enums.GameServers
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import io.github.fate_grand_automata.util.StorageProvider
import io.github.fate_grand_automata.util.GitHubUpdate
import io.github.fate_grand_automata.util.GitHubUpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@HiltViewModel
class MainScreenViewModel @Inject constructor(
    val prefsCore: PrefsCore,
    val prefs: IPreferences,
    val storageProvider: StorageProvider,
    private val updateChecker: GitHubUpdateChecker
) : ViewModel() {

    val availableUpdate = mutableStateOf<GitHubUpdate?>(null)

    fun dismissUpdate() { availableUpdate.value = null }

    init {
        // init the perServerConfigs
        viewModelScope.launch(Dispatchers.IO) {
            GameServers.values.filterNot {
                it.betterFgo
            }.forEach { server ->
                prefs.addPerServerConfigPref(server)
            }
        }
        viewModelScope.launch {
            if (!prefsCore.checkForUpdates.get()) return@launch
            try {
                val update = updateChecker.findUpdate(BuildConfig.VERSION_NAME, beta = BuildConfig.BUILD_TYPE == "beta")
                if (prefsCore.checkForUpdates.get()) availableUpdate.value = update
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline, rate limited or malformed response: continue using the installed app.
                Timber.w(e, "GitHub update check failed")
            }
        }
    }

    val autoStartService
        get() =
            prefsCore.autoStartService.get()
                    && oncePerActivityStart.getAndSet(false)
                    && !ScriptRunnerService.serviceStarted.value

    private val oncePerActivityStart = AtomicBoolean(false)
    fun activityStarted() = oncePerActivityStart.set(true)

    // Activity context is needed since we can't show AlertDialog with Application context.
    fun ensureRootDir(context: Context, pickDirectory: () -> Unit): Boolean {
        val dirRoot = prefsCore.dirRoot.get()

        if (dirRoot.isBlank()) {
            AlertDialog.Builder(context)
                .setTitle(R.string.p_choose_folder_title)
                .setMessage(R.string.p_choose_folder_message)
                .setPositiveButton(R.string.p_choose_folder_action) { _, _ ->
                    pickDirectory()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

            return false
        }

        val docFile = DocumentFile.fromTreeUri(context, Uri.parse(dirRoot))

        if (docFile?.exists() != true) {
            AlertDialog.Builder(context)
                .setTitle(R.string.p_choose_folder_not_exist_title)
                .setMessage(R.string.p_choose_folder_not_exist_message)
                .setPositiveButton(R.string.p_choose_folder_action) { _, _ ->
                    pickDirectory()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

            return false
        }

        return true
    }

    fun onStartMediaProjectionResult(context: Context, intent: Intent?) {
        if (intent == null) {
            Timber.i("MediaProjection cancelled by user")
            ScriptRunnerService.stopService(context)
        } else {
            ScriptRunnerService.startService(context)
            ScriptRunnerService.mediaProjectionToken = intent
        }
    }
}
