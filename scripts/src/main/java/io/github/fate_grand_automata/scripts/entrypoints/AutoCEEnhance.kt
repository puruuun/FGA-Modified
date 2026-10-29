package io.github.fate_grand_automata.scripts.entrypoints

import io.github.fate_grand_automata.scripts.prefs.IAddedFeatureTuning
import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.modules.CEEnhanceScreens
import io.github.fate_grand_automata.scripts.modules.ConnectionRetry
import io.github.lib_automata.EntryPoint
import io.github.lib_automata.ExitManager
import io.github.lib_automata.ScriptAbortException
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/** Uses only the owner's in-game recommended-selection filters. */
@ScriptScope
class AutoCEEnhance @Inject constructor(
    private val connectionRetry: ConnectionRetry,
    private val screens: CEEnhanceScreens,
    exitManager: ExitManager,
    api: IFgoAutomataApi,
    private val addedTuning: IAddedFeatureTuning = IAddedFeatureTuning.Default
) : EntryPoint(exitManager), IFgoAutomataApi by api {
    sealed class ExitReason {
        data object MaxLevel : ExitReason()
        data object NoMatchingCE : ExitReason()
        data object NoMaterialsOrQP : ExitReason()
        data object Abort : ExitReason()
        class Unexpected(val exception: Exception) : ExitReason()
    }
    class ExitException(val reason: ExitReason) : Exception()

    private var stateAutoFill = false
    private var justExecuted = false
    private var isRefundWindowClicked = false

    override fun script(): Nothing = try {
        loop()
    } catch (e: ExitException) {
        throw e
    } catch (e: ScriptAbortException) {
        throw ExitException(ExitReason.Abort)
    } catch (e: Exception) {
        throw ExitException(ExitReason.Unexpected(e))
    }

    private fun loop(): Nothing {
        while (true) {
            val action: () -> Unit = useSameSnapIn {
                when {
                    connectionRetry.needsToRetry() -> ({ connectionRetry.retry() })
                    screens.loc.execute.exists(images[Images.CEEnhanceExecute], similarity = addedTuning.ce.defaultSimilarity { prefs.platformPrefs.minSimilarity }) -> ({ execute() })
                    locations.servant.emptyEmberOrQPDialogRegion.exists(images[Images.Close], similarity = addedTuning.ce.defaultSimilarity { prefs.platformPrefs.minSimilarity }) -> ({ handlePromptDialog() })
                    confirmVisible() -> ({ confirm() })
                    else -> when (screens.recommended()) {
                        CEEnhanceScreens.Recommended.Active -> ({ autoSelect() })
                        CEEnhanceScreens.Recommended.Disabled -> ({
                            isRefundWindowClicked = false
                            throw ExitException(ExitReason.MaxLevel)
                        })
                        CEEnhanceScreens.Recommended.Absent -> ({ locations.enhancementSkipRapidClick.click(5) })
                    }
                }
            }
            action()
            addedTuning.ce.delay(0.5.seconds).wait()
        }
    }

    internal fun execute() {
        stateAutoFill = screens.loc.autoFill.exists(images[Images.StateON], similarity = addedTuning.ce.defaultSimilarity { prefs.platformPrefs.minSimilarity })
        repeat(2) {
            if (!stateAutoFill) {
                screens.loc.autoFill.click()
                addedTuning.ce.delay(0.5.seconds).wait()
                stateAutoFill = screens.loc.autoFill.exists(images[Images.StateON], similarity = addedTuning.ce.defaultSimilarity { prefs.platformPrefs.minSimilarity })
            }
        }
        screens.loc.execute.click()
        justExecuted = true
        addedTuning.ce.delay(1.seconds).wait()
        locations.enhancementClick.click()
    }

    internal fun handlePromptDialog() {
        when {
            justExecuted -> throw ExitException(ExitReason.NoMatchingCE)
            isRefundWindowClicked -> throw ExitException(ExitReason.NoMaterialsOrQP)
            else -> {
                isRefundWindowClicked = true
                locations.servant.emptyEmberOrQPDialogRegion.click()
                addedTuning.ce.delay(0.5.seconds).wait()
            }
        }
    }

    internal fun confirm() {
        locations.servant.finalConfirmRegion.click()
        addedTuning.ce.delay(1.seconds).wait()
        justExecuted = false
    }

    internal fun autoSelect() {
        if (stateAutoFill) {
            locations.enhancementClick.click()
            addedTuning.ce.delay(0.5.seconds).wait()
            if (!okPatterns().associateWith { locations.servant.finalConfirmRegion }.exists(
                    timeout = 3.seconds, similarity = addedTuning.ce.defaultSimilarity { prefs.platformPrefs.minSimilarity })) {
                throw ExitException(ExitReason.NoMaterialsOrQP)
            }
        } else locations.servant.servantAutoSelectRegion.click()
    }

    private fun okPatterns() = if (prefs.gameServer is GameServer.Kr)
        listOf(images[Images.Ok], images[Images.OkKR]) else listOf(images[Images.Ok])

    private fun confirmVisible() = okPatterns().any {
        locations.servant.finalConfirmRegion.exists(it, similarity = addedTuning.ce.defaultSimilarity { prefs.platformPrefs.minSimilarity })
    }
}
