package io.github.fate_grand_automata.prefs.core

/** Global opt-in, separate from the item choices remembered for each game server. */
class QuestItemControlPrefsCore(maker: PrefMaker) {
    // Keep explicit choices from previous versions; an unset preference now bypasses control.
    val ignore = maker.bool("ignore_teapot_storm_pod_usage", true)
    val enabled = ignore.map(
        defaultValue = false,
        convert = { !it },
        reverse = { !it }
    )
}
