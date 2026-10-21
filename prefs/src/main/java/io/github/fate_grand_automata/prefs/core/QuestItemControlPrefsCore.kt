package io.github.fate_grand_automata.prefs.core

/** Global control, separate from the item choices remembered for each game server. */
class QuestItemControlPrefsCore(maker: PrefMaker) {
    // Keep explicit choices from previous versions; an unset preference enables control.
    val ignore = maker.bool("ignore_teapot_storm_pod_usage", false)
    val enabled = ignore.map(
        defaultValue = true,
        convert = { !it },
        reverse = { !it }
    )
}
