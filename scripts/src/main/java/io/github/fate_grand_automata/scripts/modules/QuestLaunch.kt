package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.prefs.IAddedFeatureTuning
import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@ScriptScope
class QuestLaunch @Inject constructor(
    api: IFgoAutomataApi,
    private val partySelection: PartySelection,
    private val items: QuestItems,
    private val addedTuning: IAddedFeatureTuning = IAddedFeatureTuning.Default
) : IFgoAutomataApi by api {
    fun start() {
        partySelection.selectParty()
        addedTuning.quest.delay(1.seconds).wait()
        items.prepare()
        addedTuning.quest.delay(1.seconds).wait()
        // A failed setting/close confirmation throws before this point.
        locations.menuStartQuestClick.click()
        addedTuning.quest.delay(2.seconds).wait()
    }
}
