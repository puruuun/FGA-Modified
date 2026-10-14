package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@ScriptScope
class QuestLaunch @Inject constructor(
    api: IFgoAutomataApi,
    private val partySelection: PartySelection,
    private val items: QuestItems
) : IFgoAutomataApi by api {
    fun start() {
        partySelection.selectParty()
        items.prepare()
        // A failed setting/close confirmation throws before this point.
        locations.menuStartQuestClick.click()
        2.seconds.wait()
    }
}
