package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.ScriptNotify
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.ShuffleCardsEnum
import io.github.fate_grand_automata.scripts.models.NPUsage
import io.github.fate_grand_automata.scripts.models.ParsedCard
import io.github.fate_grand_automata.scripts.models.Skill
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@ScriptScope
class Battle @Inject constructor(
    api: IFgoAutomataApi,
    private val servantTracker: ServantTracker,
    private val state: BattleState,
    private val battleConfig: IBattleConfig,
    private val autoSkill: AutoSkill,
    private val caster: Caster,
    private val card: Card,
    private val skillSpam: SkillSpam,
    private val shuffleChecker: ShuffleChecker,
    private val stageTracker: StageTracker,
    private val autoChooseTarget: AutoChooseTarget,
    private val commandStart: CommandStartGate,
    private val execution: BattleExecution
) : IFgoAutomataApi by api {
    init {
        prefs.stopAfterThisRun = false
        state.markStartTime()

        resetState()
    }

    fun resetState() {
        // Don't increment no. of runs if we're just clicking on quest again and again
        // This can happen due to lags introduced during some events
        if (state.stage != -1) {
            if (commandStart.isWaiting && commandStart.waitingTurns > 0) {
                messages.notify(ScriptNotify.CommandStartSkipped(commandStart.waitingTurns))
            }
            state.nextRun()
            commandStart.nextRun()
            execution.nextRun()

            servantTracker.nextRun()

            prefs.updateCompletedRuns(state.runs)
        }

        if (prefs.stopAfterThisRun) {
            prefs.stopAfterThisRun = false
            throw AutoBattle.BattleExitException(AutoBattle.ExitReason.StopAfterThisRun)
        }

        if (prefs.selectedServerConfigPref.shouldLimitRuns && state.runs >= prefs.selectedServerConfigPref.limitRuns) {
            throw AutoBattle.BattleExitException(AutoBattle.ExitReason.LimitRuns(state.runs))
        }
    }

    fun isIdle() = images[Images.BattleScreen] in locations.battle.screenCheckRegion

    fun clickAttack(): List<ParsedCard> {
        locations.battle.attackClick.click()

        // Wait for Attack button to disappear
        locations.battle.screenCheckRegion.waitVanish(images[Images.BattleScreen], 5.seconds)

        prefs.waitBeforeCards.wait()

        return card.readCommandCards()
    }

    fun performBattle() {
        prefs.waitBeforeTurn.wait()

        onTurnStarted()

        if (battleConfig.addRaidTurnDelay){
            battleConfig.raidTurnDelaySeconds.seconds.wait()
        }

        servantTracker.beginTurn()

        if (commandStart.isWaiting && !tryStartCommand()) return

        state.commandPosition = commandStart.position(state.stage, state.turn)

        val npUsage = autoSkill.execute(state.commandStage, state.commandTurn)
        skillSpam.spamSkills()

        val cards = clickAttack()
            .takeUnless { shouldShuffle(it, npUsage) }
            ?: shuffleCards()

        card.clickCommandCards(cards, npUsage)

        0.5.seconds.wait()
    }

    /** Returns false only after consuming a waiting turn with face cards alone. */
    private fun tryStartCommand(): Boolean {
        if (commandStart.hasDuplicateTargets) {
            throw AutoBattle.BattleExitException(AutoBattle.ExitReason.CommandStartDuplicateTargets)
        }
        if (!commandStart.hasAvailableTarget(servantTracker.deployed.values)) {
            throw AutoBattle.BattleExitException(
                if (commandStart.secondaryEnabled) AutoBattle.ExitReason.CommandStartTargetsUnavailable
                else AutoBattle.ExitReason.CommandStartTargetUnavailable(commandStart.target.position)
            )
        }

        var (cards, result) = checkStartHand(clickAttack())
        // Only a reliably recognized, insufficient first-turn hand can trigger this mode.
        // Recheck both targets before spending a turn or selecting an execution profile.
        if (result is CommandStartGate.Result.Wait &&
            execution.shuffleCards == ShuffleCardsEnum.FirstTurnStartCondition &&
            state.stage == 0 && state.turn == 0 && !state.shuffled
        ) {
            val reshuffled = checkStartHand(shuffleCards(verifyReturn = true))
            cards = reshuffled.first
            result = reshuffled.second
        }

        when (val checked = result) {
            is CommandStartGate.Result.Ready -> {
                locations.attack.backClick.click()
                if (!locations.battle.screenCheckRegion.exists(images[Images.BattleScreen], 5.seconds)) {
                    throw AutoBattle.BattleExitException(AutoBattle.ExitReason.CommandStartReturnFailed)
                }
                execution.select(checked.profileIndex)
                commandStart.startAt(state.stage, state.turn, checked.profileIndex)
                messages.notify(ScriptNotify.CommandStarted(state.stage + 1, state.turn + 1, checked.profileIndex))
                return true
            }

            is CommandStartGate.Result.Wait -> {
                messages.notify(if (checked.secondaryCount != null) {
                    ScriptNotify.CommandStartWaitingBoth(
                        commandStart.target.position, checked.count, commandStart.requiredCards,
                        checkNotNull(commandStart.secondaryTarget).position, checked.secondaryCount, commandStart.secondaryRequired
                    )
                } else {
                    ScriptNotify.CommandStartWaiting(
                        commandStart.target.position, checked.count, commandStart.requiredCards
                    )
                })
                // NPUsage.none alone is insufficient: Card normally appends NP Spam.
                card.clickCommandCards(cards, NPUsage.none, allowNpSpam = false)
                commandStart.recordWaitingTurn()
                0.5.seconds.wait()
                return false
            }

            CommandStartGate.Result.RecognitionFailed -> throw AutoBattle.BattleExitException(
                AutoBattle.ExitReason.CommandStartRecognitionFailed
            )

            CommandStartGate.Result.TargetUnavailable -> throw AutoBattle.BattleExitException(
                AutoBattle.ExitReason.CommandStartTargetUnavailable(commandStart.target.position)
            )

            CommandStartGate.Result.WaitLimitReached -> throw AutoBattle.BattleExitException(
                AutoBattle.ExitReason.CommandStartWaitLimit(commandStart.waitingTurns)
            )
            CommandStartGate.Result.DuplicateTargets -> throw AutoBattle.BattleExitException(
                AutoBattle.ExitReason.CommandStartDuplicateTargets
            )
        }
    }

    private fun checkStartHand(initialCards: List<ParsedCard>): Pair<List<ParsedCard>, CommandStartGate.Result> {
        var cards = initialCards
        var result = commandStart.check(cards, servantTracker.deployed.values)
        // A failed match retries the same hand, both before and after shuffling.
        repeat(2) {
            if (result == CommandStartGate.Result.RecognitionFailed) {
                prefs.waitBeforeCards.wait()
                cards = card.readCommandCards()
                result = commandStart.check(cards, servantTracker.deployed.values)
            }
        }
        return cards to result
    }

    private fun shouldShuffle(cards: List<ParsedCard>, npUsage: NPUsage): Boolean {
        // Not this wave
        if (state.commandStage != (execution.shuffleCardsWave - 1)) {
            return false
        }

        // Already shuffled
        if (state.shuffled) {
            return false
        }

        return shuffleChecker.shouldShuffle(
            mode = execution.shuffleCards,
            cards = cards,
            npUsage = npUsage
        )
    }

    private fun shuffleCards(verifyReturn: Boolean = false): List<ParsedCard> {
        locations.attack.backClick.click()
        if (verifyReturn && !locations.battle.screenCheckRegion.exists(images[Images.BattleScreen], 5.seconds)) {
            throw AutoBattle.BattleExitException(AutoBattle.ExitReason.CommandStartReturnFailed)
        }

        caster.castMasterSkill(Skill.Master.C)
        state.shuffled = true

        return clickAttack()
    }

    private fun onTurnStarted() = useSameSnapIn {
        stageTracker.checkCurrentStage()

        state.nextTurn()

        if (battleConfig.autoChooseTarget) {
            autoChooseTarget.choose()
        }
    }
}
