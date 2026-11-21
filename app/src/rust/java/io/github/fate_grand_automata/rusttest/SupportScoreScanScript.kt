package io.github.fate_grand_automata.rusttest

import android.content.Context
import android.os.Build
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.github.fate_grand_automata.BuildConfig
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.di.script.ScriptComponent
import io.github.fate_grand_automata.runner.OwnerTestScript
import io.github.fate_grand_automata.scripts.entrypoints.SupportScoreScan
import io.github.fate_grand_automata.scripts.enums.ScriptModeEnum
import io.github.fate_grand_automata.scripts.supportSelection.DirectoryScoreScanStorage
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanDevice
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanEndReason
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanLog
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanNativeFrames
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanStorage
import io.github.lib_automata.EntryPoint
import java.io.File
import javax.inject.Inject

/**
 * Issue #568, rust build type only (this source set): the owner's support score scan, offered
 * on the support selection screen next to the support image maker.
 */
class SupportScoreScanScript @Inject constructor() : OwnerTestScript {
    override fun offeredFor(mode: ScriptModeEnum) = mode == ScriptModeEnum.SupportImageMaker

    override fun label(context: Context): String = context.getString(R.string.support_score_scan)

    override fun entryPoint(component: ScriptComponent): EntryPoint =
        EntryPoints.get(component, SupportScoreScanEntryPoint::class.java).supportScoreScan()

    override fun exitMessage(context: Context, e: Exception): String? {
        val result = (e as? SupportScoreScan.ExitException)?.result ?: return null
        val reason = context.getString(reasonText(result.reason))
        val text = if (result.folder == null) {
            context.getString(R.string.support_score_scan_not_started, reason)
        } else {
            context.getString(
                R.string.support_score_scan_result, reason, result.screens, result.rows, result.agree, result.differ,
                "${ScoreScanLog.ROOT}/${result.folder}"
            )
        }
        return if (result.detail != null) "$text\n${result.detail}" else text
    }

    private fun reasonText(reason: ScoreScanEndReason) = when (reason) {
        ScoreScanEndReason.NotSupportListOrDialog -> R.string.support_score_scan_reason_not_support_list_or_dialog
        ScoreScanEndReason.ListNotLoaded -> R.string.support_score_scan_reason_list_not_loaded
        ScoreScanEndReason.NoRows -> R.string.support_score_scan_reason_no_rows
        ScoreScanEndReason.RowsLost -> R.string.support_score_scan_reason_rows_lost
        ScoreScanEndReason.EngineUnavailable -> R.string.support_score_scan_reason_engine_unavailable
        ScoreScanEndReason.LowSpace -> R.string.support_score_scan_reason_low_space
        ScoreScanEndReason.UserStopped -> R.string.support_score_scan_reason_user_stopped
        ScoreScanEndReason.Bottom -> R.string.support_score_scan_reason_bottom
        ScoreScanEndReason.ScreenLimit -> R.string.support_score_scan_reason_screen_limit
        ScoreScanEndReason.NativeRestoreFailed -> R.string.support_score_scan_reason_native_restore_failed
    }
}

@dagger.hilt.EntryPoint
@InstallIn(ScriptComponent::class)
interface SupportScoreScanEntryPoint {
    fun supportScoreScan(): SupportScoreScan
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SupportScoreScanBindings {
    @Binds
    @IntoSet
    abstract fun supportScoreScan(script: SupportScoreScanScript): OwnerTestScript
}

@Module
@InstallIn(ScriptComponent::class)
object SupportScoreScanModule {
    /**
     * The app-specific external files folder: `adb pull` reads it without root on Android 7 to 15,
     * which the user-chosen SAF folder does not promise (it may be anywhere, or on another volume).
     * Without that storage the scan reports too little space and writes nothing.
     */
    @Provides
    fun storage(@ApplicationContext context: Context): ScoreScanStorage {
        val external = context.getExternalFilesDir(null)
            ?: return DirectoryScoreScanStorage(File(context.cacheDir, ScoreScanLog.ROOT)) { 0L }
        return DirectoryScoreScanStorage(File(external, ScoreScanLog.ROOT))
    }

    @Provides
    fun nativeFrames(frames: HolderScoreScanNativeFrames): ScoreScanNativeFrames = frames

    @Provides
    fun device() = ScoreScanDevice(
        appVersion = BuildConfig.VERSION_NAME,
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
        androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    )
}
