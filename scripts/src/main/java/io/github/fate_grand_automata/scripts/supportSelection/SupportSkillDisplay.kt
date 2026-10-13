package io.github.fate_grand_automata.scripts.supportSelection

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/** The global list display cycles normal -> append -> both -> normal. */
@ScriptScope
class SupportSkillDisplay @Inject constructor(api: IFgoAutomataApi) : IFgoAutomataApi by api {
    enum class View { Normal, Append, Both, Unknown }

    companion object {
        // Color is essential: the normal and append diamonds have the same grayscale shape.
        const val LABEL_SIMILARITY = 0.90
    }

    fun read(): View = useColor {
        useSameSnapIn {
            val region = locations.support.skillDisplayLabelsRegion
            val normal = region.exists(images[Images.SupportNormalSkills], similarity = LABEL_SIMILARITY)
            val append = region.exists(images[Images.SupportAppendSkills], similarity = LABEL_SIMILARITY)
            when {
                normal && append -> View.Both
                normal -> View.Normal
                append -> View.Append
                else -> View.Unknown
            }
        }
    }

    fun ensureNormal() {
        repeat(3) { attempt ->
            when (knownView()) {
                View.Normal -> return
                View.Unknown -> fail()
                else -> if (attempt < 2) toggle() else fail()
            }
        }
    }

    private fun knownView(): View {
        repeat(4) {
            val view = read()
            if (view != View.Unknown) return view
            0.2.seconds.wait()
        }
        return View.Unknown
    }

    fun showAppend() {
        ensureNormal()
        toggle()
        expect(View.Append)
    }

    fun restoreNormal() {
        // Do not scroll or select another candidate in append/combined display.
        repeat(2) { toggle() }
        expect(View.Normal)
    }

    private fun toggle() {
        locations.support.skillDisplaySwitchClick.click()
        0.3.seconds.wait()
    }

    private fun expect(view: View) {
        repeat(4) {
            if (read() == view) return
            0.2.seconds.wait()
        }
        fail()
    }

    private fun fail(): Nothing = throw AutoBattle.BattleExitException(AutoBattle.ExitReason.SupportSkillDisplayFailed)
}
