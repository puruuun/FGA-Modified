package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

/** Detects the command-card swap icon (Aoko Aozaki) via masked template matching. */
@ScriptScope
class CardSwapIcon @Inject constructor(
    api: IFgoAutomataApi
) : IFgoAutomataApi by api {
    fun isPresent(card: CommandCard.Face): Boolean =
        locations.attack.swapIconRegion(card)
            .findAll(images[Images.CardSwap], SIMILARITY)
            .isNotEmpty()

    fun score(card: CommandCard.Face): Double =
        locations.attack.swapIconRegion(card)
            .findAll(images[Images.CardSwap], 0.01)
            .maxOfOrNull { it.score }
            ?: 0.0

    companion object {
        const val SIMILARITY = 0.70
    }
}
