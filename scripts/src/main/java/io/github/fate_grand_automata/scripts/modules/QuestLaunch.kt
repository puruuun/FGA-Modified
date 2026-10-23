package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds

@ScriptScope
class QuestLaunch @Inject constructor(
    api: IFgoAutomataApi,
    private val partySelection: PartySelection,
    private val items: QuestItems
) : IFgoAutomataApi by api {
    /** Read-only detector, evaluated under the battle loop's shared screenshot. */
    fun isScreen(): Boolean {
        // These screen templates are currently available only for KR and JP.
        if (prefs.gameServer !is GameServer.Kr && prefs.gameServer !is GameServer.Jp) return false
        val loc = locations.questItems
        return QuestItemReader.partyImages(loc.isWide, prefs.gameServer).any {
            loc.partyHeading.exists(images[it], similarity = QuestItemReader.SCREEN_SIMILARITY)
        } &&
            // Do not select a party or press Quest Start through an open item dialog.
            !loc.dialogClose.exists(images[Images.QuestItemDialogClose], similarity = QuestItemReader.SCREEN_SIMILARITY)
    }

    fun start(): Boolean {
        if (prefs.gameServer is GameServer.Kr || prefs.gameServer is GameServer.Jp) {
            var confirmed = false
            for (poll in 0..10) {
                if (useSameSnapIn { isScreen() }) { confirmed = true; break }
                if (poll < 10) 300.milliseconds.wait(applyMultiplier = false)
            }
            if (!confirmed) {
                messages.log(io.github.fate_grand_automata.scripts.ScriptLog.QuestItemsPartySkipped)
                return false
            }
        }
        partySelection.selectParty()
        items.prepare()
        // A failed setting/close confirmation throws before this point.
        locations.menuStartQuestClick.click()
        2.seconds.wait()
        return true
    }
}
