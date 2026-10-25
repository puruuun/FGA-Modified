package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.lib_automata.Location
import io.github.lib_automata.Match
import io.github.lib_automata.Region
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.math.abs

@ScriptScope
class QuestItemReader @Inject constructor(api: IFgoAutomataApi) : IFgoAutomataApi by api {
    enum class Kind { StormPod, Teapot }
    data class Button(val on: Boolean, val click: Location)
    sealed interface View {
        data class Single(val kind: Kind, val button: Button) : View
        data class Dialog(val stormPod: Button, val teapot: Button, val close: Location) : View
        data object Unavailable : View
        data object Unknown : View
    }

    companion object {
        // Two native screenshot resampling orders: lowest true OFF match exceeds 0.89.
        const val STATE_SIMILARITY = 0.88
        const val ICON_SIMILARITY = 0.88
        const val SCREEN_SIMILARITY = 0.90
    }

    /** Each invocation captures one fresh frame. Never retain it across a click. */
    fun read(): View = useSameSnapIn {
        val loc = locations.questItems
        val dialog = loc.dialogHeading.exists(images[Images.QuestItemDialog], similarity = SCREEN_SIMILARITY) ||
            // Native KR captures and window-resampled captures have different glyph antialiasing.
            (prefs.gameServer is GameServer.Kr && loc.dialogHeading.exists(
                images[Images.QuestItemDialogKrNative], similarity = SCREEN_SIMILARITY))
        val close = loc.dialogClose.findAll(images[Images.QuestItemDialogClose], similarity = SCREEN_SIMILARITY).maxByOrNull { it.score }
        if (dialog || close != null) {
            val storm = button(loc.stormPodControl)
            val tea = button(loc.teapotControl)
            return@useSameSnapIn if (dialog && close != null && storm != null && tea != null)
                View.Dialog(storm, tea, close.region.center) else View.Unknown
        }
        val tea = loc.mainControl.exists(images[Images.QuestItemTeapot], similarity = ICON_SIMILARITY)
        val storm = loc.mainControl.exists(images[Images.QuestItemStormPod], similarity = ICON_SIMILARITY)
        val button = button(loc.mainControl)
        when {
            tea && !storm && button != null -> View.Single(Kind.Teapot, button)
            storm && !tea && button != null -> View.Single(Kind.StormPod, button)
            !tea && !storm && stateMatches(loc.mainControl).isEmpty() -> View.Unavailable
            else -> View.Unknown
        }
    }

    private fun stateMatches(region: Region): List<Pair<Boolean, Match>> =
        listOf(true to Images.QuestItemOn, true to Images.QuestItemOnMain,
            false to Images.QuestItemOff, false to Images.QuestItemOffMain).flatMap { (on, image) ->
            region.findAll(images[image], similarity = STATE_SIMILARITY).map { on to it }
        }

    private fun button(region: Region): Button? {
        val matches = stateMatches(region)
        val best = matches.maxByOrNull { it.second.score } ?: return null
        // Alternative crops of the same button may overlap; different states/locations are unsafe.
        if (matches.any { (on, match) -> on != best.first ||
                abs(match.region.center.x - best.second.region.center.x) > 20 ||
                abs(match.region.center.y - best.second.region.center.y) > 20 }) return null
        return Button(best.first, best.second.region.center)
    }
}
