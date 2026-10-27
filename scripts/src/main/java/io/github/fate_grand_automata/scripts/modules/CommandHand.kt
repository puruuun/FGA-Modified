package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.models.ParsedCard
import io.github.fate_grand_automata.scripts.models.TeamSlot
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/** The shared Attack/read/Back path; it never chooses or plays a card. */
class CommandHand @Inject constructor(api: IFgoAutomataApi, private val card: Card) : IFgoAutomataApi by api {
    fun open(): List<ParsedCard> {
        locations.battle.attackClick.click()
        locations.battle.screenCheckRegion.waitVanish(images[Images.BattleScreen], 5.seconds)
        prefs.waitBeforeCards.wait()
        return card.readCommandCards()
    }

    fun back(): Boolean {
        locations.attack.backClick.click()
        return battleVisible()
    }

    fun battleVisible() = locations.battle.screenCheckRegion.exists(images[Images.BattleScreen], 5.seconds)

    companion object {
        fun valid(cards: List<ParsedCard>) = cards.size == CommandCard.Face.list.size &&
            cards.map { it.card }.toSet() == CommandCard.Face.list.toSet() &&
            cards.none { !it.isStunned && (it.type == CardTypeEnum.Unknown || it.servant == TeamSlot.Unknown) }
    }
}
