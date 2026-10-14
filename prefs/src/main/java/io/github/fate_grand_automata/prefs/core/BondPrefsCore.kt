package io.github.fate_grand_automata.prefs.core

class BondPrefsCore(maker: PrefMaker) {
    val track = maker.bool("bond_track")
    val stopOnMax = maker.bool("bond_stop_on_max")
    val ignoredPositions = maker.stringSet("bond_max_ignored_positions")
}
