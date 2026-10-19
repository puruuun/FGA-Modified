package io.github.fate_grand_automata.scripts.entrypoints

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.locations.SkillLevelLocations
import io.github.fate_grand_automata.scripts.modules.SkillLevelDevice
import io.github.fate_grand_automata.scripts.modules.SkillLevelDigits
import io.github.fate_grand_automata.scripts.modules.SkillLevelFlow
import io.github.fate_grand_automata.scripts.modules.SkillLevelStep
import io.github.fate_grand_automata.scripts.modules.SkillLevelMode
import io.github.fate_grand_automata.scripts.modules.SkillLevelScreens
import io.github.fate_grand_automata.scripts.modules.SlotResult
import io.github.lib_automata.EntryPoint
import io.github.lib_automata.ExitManager
import io.github.lib_automata.ScriptAbortException
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

/**
 * Raises the skill (3 slots) or append skill (5 slots) levels of the selected servant up to the targets in the
 * settings dialog. One shared flow, two entry points. Never unlocks a slot, never goes above a target.
 */
abstract class SkillLevelScript(
    exitManager: ExitManager,
    private val api: IFgoAutomataApi,
    private val loc: SkillLevelLocations,
    private val screens: SkillLevelScreens,
    private val digits: SkillLevelDigits,
    val mode: SkillLevelMode
) : EntryPoint(exitManager), IFgoAutomataApi by api {

    sealed class ExitReason {
        /** The flow ran through every slot; the per-slot results say how each one ended. */
        class Done(val results: List<SlotResult>) : ExitReason()
        data object NoServantSelected : ExitReason()
        /** The skill / append enhancement screen is not showing. */
        data object WrongScreen : ExitReason()
        data object UnsupportedServer : ExitReason()
        /** Every slot is set to 0 (off). */
        data object NothingSelected : ExitReason()
        class Abort(val results: List<SlotResult>) : ExitReason()
        class Unexpected(val exception: Exception, val results: List<SlotResult>) : ExitReason()
    }

    class ExitException(val reason: ExitReason, val mode: SkillLevelMode, val step: SkillLevelStep = SkillLevelStep.Select) : Exception()

    private var flow: SkillLevelFlow? = null

    private fun log(message: String) = messages.log(ScriptLog.SkillLevel(message))

    override fun script(): Nothing {
        try {
            throw ExitException(start(), mode, flow?.activeStep ?: SkillLevelStep.Select)
        } catch (e: ExitException) {
            log("stop slot=${flow?.activeSlot?.plus(1) ?: 0} step=${e.step.token} reason=${e.reason::class.simpleName}")
            throw e
        } catch (e: ScriptAbortException) {
            log("stop slot=${flow?.activeSlot?.plus(1) ?: 0} step=${flow?.activeStep?.token ?: "select"} reason=Abort")
            throw ExitException(ExitReason.Abort(flow?.results.orEmpty()), mode, flow?.activeStep ?: SkillLevelStep.Select)
        } catch (e: Exception) {
            log("stop slot=${flow?.activeSlot?.plus(1) ?: 0} step=${flow?.activeStep?.token ?: "select"} reason=Unexpected error=${e.message}")
            throw ExitException(ExitReason.Unexpected(e, flow?.results.orEmpty()), mode, flow?.activeStep ?: SkillLevelStep.Select)
        }
    }

    private fun start(): ExitReason {
        val server = prefs.gameServer
        log("start mode=$mode server=${server.simple}")
        if (server !is GameServer.Jp && server !is GameServer.Kr) return ExitReason.UnsupportedServer
        val targets = prefs.skillLevel.targets(mode)
        if (targets.none { it > 0 }) return ExitReason.NothingSelected
        val (onScreen, selected) = useSameSnapIn { screens.onTitle(mode) to screens.servantSelected() }
        if (!onScreen) return ExitReason.WrongScreen
        if (!selected) return ExitReason.NoServantSelected
        val device = SkillLevelDevice(api, mode, loc, screens, digits)
        val run = SkillLevelFlow(device, ::log).also { flow = it }
        return ExitReason.Done(run.runAll(targets))
    }
}

@ScriptScope
class AutoSkillLevel @Inject constructor(
    exitManager: ExitManager, api: IFgoAutomataApi, loc: SkillLevelLocations,
    screens: SkillLevelScreens, digits: SkillLevelDigits
) : SkillLevelScript(exitManager, api, loc, screens, digits, SkillLevelMode.Skill)

@ScriptScope
class AutoAppendLevel @Inject constructor(
    exitManager: ExitManager, api: IFgoAutomataApi, loc: SkillLevelLocations,
    screens: SkillLevelScreens, digits: SkillLevelDigits
) : SkillLevelScript(exitManager, api, loc, screens, digits, SkillLevelMode.Append)
