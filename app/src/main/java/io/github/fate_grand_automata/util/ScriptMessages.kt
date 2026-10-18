package io.github.fate_grand_automata.util

import android.app.Service
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dagger.hilt.android.scopes.ServiceScoped
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.runner.ScriptRunnerNotification
import io.github.fate_grand_automata.scripts.IScriptMessages
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.ScriptNotify
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import timber.log.Timber
import javax.inject.Inject
import kotlin.time.DurationUnit

@ServiceScoped
class ScriptMessages @Inject constructor(
    service: Service,
    private val notification: ScriptRunnerNotification,
    private val prefs: IPreferences
) : IScriptMessages {
    private val context: Context = service

    private val handler by lazy {
        Handler(Looper.getMainLooper())
    }

    fun toast(message: String) {
        if (message.isNotBlank()) {
            handler.post {
                Toast
                    .makeText(context, message, Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    fun notify(message: String) = notification.message(message)

    override fun log(item: ScriptLog) =
        when (item) {
            ScriptLog.DefaultSupportBounds -> {
                Timber.d("Default Region being returned")
            }

            is ScriptLog.CurrentParty -> {
                Timber.d("Current Party: ${item.party}")
            }

            is ScriptLog.MaxSkills -> {
                Timber.d(
                    // Detected skill levels as string for debugging
                    item.isSkillMaxed
                        .zip(item.needMaxedSkills)
                        .joinToString("/") { (success, shouldBeMaxed) ->
                            when {
                                !shouldBeMaxed -> "x"
                                success -> "10"
                                else -> "f"
                            }
                        }
                )
            }
            is ScriptLog.MaxAppendSkills -> Timber.d("Append skills: %s", item.required.zip(item.detected)
                .joinToString("/") { (required, detected) -> if (!required) "x" else if (detected) "10" else "f" })
            is ScriptLog.SupportScore62 -> Timber.d("Support score +62: %s", item.matched)
            is ScriptLog.SupportScoreConditions -> Timber.tag("FGA.SupportScore").i(
                "Support Class/Grand: %s/%s, required: %s/%s, display: %s, matched: %s",
                item.classScore ?: "unknown", item.grandScore ?: "unknown",
                item.checkClass, item.checkGrand, item.display, item.matched
            )
            is ScriptLog.SupportNpLevel -> Timber.d("Support NP: %s, minimum: %d, matched: %s",
                item.detected ?: "unreadable", item.required, item.matched)
            is ScriptLog.GrandScoreIgnored -> Timber.tag("FGA.SupportScore").i(
                "Grand score is not shown on %s servers, so the +16 condition is ignored", item.server
            )
            is ScriptLog.SupportDiagnosticsLimit -> Timber.tag("FGA.Diagnostics").w(
                "Support score diagnostics stopped after %d candidates in this run", item.limit
            )

            ScriptLog.DefaultMasterOffset -> {
                Timber.d("Defaulting master offset")
            }

            is ScriptLog.ClickingNPs -> {
                Timber.d("Clicking NP(s): ${item.nps}")
            }

            is ScriptLog.ClickingCards -> {
                Timber.d("Clicking cards: ${item.cards}")
            }

            is ScriptLog.NPsGroupedByFaceCards -> {
                Timber.d("NPs grouped with Face-cards: ${item.groups}")
            }

            is ScriptLog.SupportFaceCardGroup -> {
                Timber.d("Support group: ${item.group}")
            }

            is ScriptLog.FaceCardGroups -> {
                Timber.d("Face-card groups: ${item.groups}")
            }

            is ScriptLog.ServantEnteredSlot -> {
                Timber.d("Servant: ${item.servant} in Slot: ${item.slot}")
            }

            is ScriptLog.CardsBelongToServant -> {
                val supportText = if (item.isSupport) "Support " else ""
                Timber.d("${item.cards} belong to ${supportText}${item.servant}")
            }
        }

    override fun notify(action: ScriptNotify) =
        when (action) {
            ScriptNotify.CEDropped -> notify(context.getString(R.string.ce_dropped))
            ScriptNotify.CEGet -> notify(context.getString(R.string.ce_get))
            is ScriptNotify.FailedToDetermineCards -> {
                var msg = context.getString(R.string.failed_to_determine_card, action.cards)
                if (action.unknownCardTypes) {
                    msg += "\n" + context.getString(R.string.unknown_card_type)
                }
                if (action.unknownServants) {
                    msg += "\n" + context.getString(R.string.unknown_servant_card)
                }

                toast(msg)

                Timber.d(msg)
            }

            is ScriptNotify.SupportListUpdatingIn -> {
                toast(
                    context.getString(R.string.support_list_updated_in, action.time.toString(DurationUnit.SECONDS, 2))
                )
            }

            ScriptNotify.GrandScoreNotSupported -> {
                toast(context.getString(R.string.grand_score_not_supported))
            }

            is ScriptNotify.WaitForAPRegen -> {
                toast(
                    context.getString(R.string.wait_ap_regen_toast_message, action.minutes)
                )
            }

            is ScriptNotify.BetweenRuns -> {
                val msg = makeRefillAndRunsMessage(
                    timesRefilled = action.refills,
                    timesRan = action.runs,
                    ceDropCount = action.ceDrops
                )

                toast(msg)
            }
            is ScriptNotify.BondLevelUp -> {
                notify(
                    context.getString(R.string.bond_level_up)
                )
            }
            is ScriptNotify.CommandStartWaiting -> notification.status(
                context.getString(R.string.command_start_waiting, action.servant, action.count, action.required)
            )
            is ScriptNotify.CommandStartWaitingBoth -> notification.status(
                context.getString(
                    R.string.command_start_waiting_both,
                    action.firstServant, action.firstCount, action.firstRequired,
                    action.secondServant, action.secondCount, action.secondRequired
                )
            )
            is ScriptNotify.CommandStarted -> notification.status(
                context.getString(R.string.command_started_profile, action.profileIndex + 1, action.stage, action.turn)
            )
            is ScriptNotify.CommandStartSkipped -> {
                val msg = context.getString(R.string.command_start_skipped, action.waitingTurns)
                Timber.i(msg)
                notification.status(msg)
            }
        }

    private fun makeRefillAndRunsMessage(
        timesRan: Int,
        timesRefilled: Int,
        ceDropCount: Int
    ) = buildString {
        val perServerConfigPref = prefs.selectedServerConfigPref

        if (perServerConfigPref.shouldLimitRuns && perServerConfigPref.limitRuns > 0) {
            appendLine(
                context.getString(R.string.times_ran_out_of, timesRan, perServerConfigPref.limitRuns)
            )
        }

        if (ceDropCount > 0) {
            appendLine(
                context.getString(R.string.ces_dropped, ceDropCount)
            )
        }

        if (perServerConfigPref.resources.isNotEmpty()) {
            val refillRepetitions = perServerConfigPref.currentAppleCount
            if (refillRepetitions > 0) {
                appendLine(
                    context.getString(R.string.refills_used_out_of, timesRefilled, refillRepetitions)
                )
            }
        }
    }.trimEnd()
}
