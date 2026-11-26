package io.github.fate_grand_automata.scripts.supportSelection

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@ScriptScope
class FirstSupportSelection @Inject constructor(
    api: IFgoAutomataApi
) : SupportSelectionProvider, IFgoAutomataApi by api {
    override fun select(): SupportSelectionResult {
        0.5.seconds.wait()

        // Match whichever support-screen header (10-tab or 11-tab) is currently on screen so
        // waitVanish tracks the real transition instead of returning early on the lower 11-tab score.
        val screenTemplate = locations.support.screenCheckRegion.getPattern().use { crop ->
            val standard = crop.findMatches(images[Images.SupportScreen], 0.85).maxOfOrNull { it.score }
            val compact = crop.findMatches(images[Images.SupportScreenCompact], 0.85).maxOfOrNull { it.score }
            if (compact != null && (standard == null || compact > standard)) {
                images[Images.SupportScreenCompact]
            } else {
                images[Images.SupportScreen]
            }
        }

        locations.support.firstSupportClick.click()

        // Handle the case of a friend not having set a support servant
        val supportPicked = locations.support.screenCheckRegion.waitVanish(
            screenTemplate,
            similarity = 0.85,
            timeout = 10.seconds
        )

        return if (supportPicked)
            SupportSelectionResult.Done
        else SupportSelectionResult.Refresh
    }
}