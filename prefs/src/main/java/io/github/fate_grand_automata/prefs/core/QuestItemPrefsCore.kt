package io.github.fate_grand_automata.prefs.core

/** Launch choices shared by battle configurations on the same server. */
class QuestItemPrefsCore(maker: PrefMaker) {
    val useStormPod = maker.bool("quest_use_storm_pod")
    val useTeapot = maker.bool("quest_use_teapot")
}
