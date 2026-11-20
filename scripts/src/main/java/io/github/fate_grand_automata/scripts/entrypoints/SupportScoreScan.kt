package io.github.fate_grand_automata.scripts.entrypoints

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.modules.SupportScreen
import io.github.fate_grand_automata.scripts.supportSelection.ApiScoreScanFrames
import io.github.fate_grand_automata.scripts.supportSelection.ISupportScoreReader
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanDevice
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanResult
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanStorage
import io.github.fate_grand_automata.scripts.supportSelection.SupportBoundsFinder
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreEngine
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreScanner
import io.github.lib_automata.EntryPoint
import io.github.lib_automata.ExitManager
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

/**
 * Issue #568: the owner's support score scan, started only from the rust build type's launcher
 * (no [io.github.fate_grand_automata.scripts.enums.ScriptModeEnum] value). [ScoreScanStorage] and
 * [ScoreScanDevice] are bound only in that build type, so no other build can construct it.
 */
@ScriptScope
class SupportScoreScan @Inject constructor(
    exitManager: ExitManager,
    private val api: IFgoAutomataApi,
    private val screen: SupportScreen,
    private val boundsFinder: SupportBoundsFinder,
    private val reader: ISupportScoreReader,
    private val engine: SupportScoreEngine,
    private val storage: ScoreScanStorage,
    private val device: ScoreScanDevice,
) : EntryPoint(exitManager), IFgoAutomataApi by api {
    class ExitException(val result: ScoreScanResult) : Exception("support score scan: ${result.reason.wireName}")

    override fun script(): Nothing {
        val result = SupportScoreScanner(
            screen = screen,
            frames = ApiScoreScanFrames(api, boundsFinder),
            reader = reader,
            engine = engine,
            storage = storage,
            device = device,
            exitManager = exitManager,
            server = prefs.gameServer,
        ).run()
        throw ExitException(result)
    }
}
