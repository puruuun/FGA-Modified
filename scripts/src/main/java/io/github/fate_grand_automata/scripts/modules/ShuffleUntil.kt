package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.models.AutoSkillAction
import io.github.fate_grand_automata.scripts.models.FieldSlot
import io.github.fate_grand_automata.scripts.models.ParsedCard
import io.github.fate_grand_automata.scripts.models.TeamSlot
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

enum class ShuffleEndReason { Met, NPShortage, MaxReached, ReadFailed }

@ScriptScope
class ShuffleUntil @Inject constructor(
    api: IFgoAutomataApi,
    private val caster: Caster,
    private val card: Card,
    private val servantTracker: ServantTracker,
    private val state: BattleState,
    private val hand: CommandHand
) : IFgoAutomataApi by api {
    companion object {
        fun counts(cards: List<ParsedCard>, deployed: Map<FieldSlot, TeamSlot>,
                   conditions: List<AutoSkillAction.ShuffleCondition> = FieldSlot.list.map { AutoSkillAction.ShuffleCondition(it, 1) }): List<Int> =
            conditions.map { condition ->
                val servant = deployed[condition.slot]
                if (servant == null || servant == TeamSlot.Unknown) 0
                else cards.count { !it.isStunned && it.servant == servant && (condition.type == null || it.type == condition.type) }
            }
    }

    fun execute(action: AutoSkillAction.ShuffleUntil) {
        if (!servantTracker.faceCardCheckEnabled) {
            throw AutoBattle.BattleExitException(AutoBattle.ExitReason.ShuffleUntilFaceCheckDisabled)
        }
        state.hakunoShuffledThisTurn = true
        var presses = 0
        var finalCounts = action.conditions.map { 0 }
        fun finish(reason: ShuffleEndReason) {
            val counts = action.conditions.zip(finalCounts).joinToString(",") { (condition, count) -> "${condition.key}:$count" }
            messages.log(ScriptLog.ShuffleUntil("presses=$presses reason=$reason counts=$counts"))
            if (reason != ShuffleEndReason.Met && action.stopIfUnmet) {
                throw AutoBattle.BattleExitException(AutoBattle.ExitReason.ShuffleUntilUnmet(reason, presses))
            }
        }
        fun requireBattle(returned: Boolean) {
            if (!returned) throw AutoBattle.BattleExitException(AutoBattle.ExitReason.ShuffleUntilReturnFailed)
        }
        while (true) {
            if (presses >= action.min) {
                var cards = hand.open()
                repeat(2) {
                    if (!CommandHand.valid(cards)) {
                        prefs.waitBeforeCards.wait()
                        cards = card.readCommandCards()
                    }
                }
                val valid = CommandHand.valid(cards)
                finalCounts = counts(cards, servantTracker.deployed, action.conditions)
                val met = valid && action.conditions.zip(finalCounts).any { (condition, count) -> count >= condition.count }
                requireBattle(hand.back())
                if (!valid || met) {
                    finish(if (met) ShuffleEndReason.Met else ShuffleEndReason.ReadFailed)
                    return
                }
            }
            if (presses == action.max) {
                finish(ShuffleEndReason.MaxReached)
                return
            }
            // Confirmation OFF is required. Never call Caster's confirmation path or alter its cache.
            locations.battle.locate(action.skill).click()
            val region = locations.battle.shuffleNPShortageRegion
            if (region.exists(images[Images.Close], 1.seconds)) {
                val close = region.find(images[Images.Close]) ?: throw AutoBattle.BattleExitException(
                    AutoBattle.ExitReason.ShuffleUntilReturnFailed)
                close.region.click()
                requireBattle(hand.battleVisible())
                finish(ShuffleEndReason.NPShortage)
                return
            }
            locations.battle.extraInfoWindowCloseClick.click()
            caster.waitForAnimationToFinish()
            presses++
        }
    }
}
