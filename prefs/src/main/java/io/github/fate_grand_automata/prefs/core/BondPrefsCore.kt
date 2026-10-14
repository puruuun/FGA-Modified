package io.github.fate_grand_automata.prefs.core

class BondPrefsCore(maker: PrefMaker) {
    // Stored in each battle config. Legacy launcher/party-position settings are not reused.
    val track = maker.bool("bond_result_track")
    val stopOnMax = maker.bool("bond_result_stop_on_max")
    val stopCharacters = maker.string("bond_result_stop_characters", "1,2,3,4,5").map(
        defaultValue = (1..5).toSet(),
        convert = { raw -> raw.split(',').mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 1..5 } }.toSet() },
        reverse = { values -> values.filter { it in 1..5 }.sorted().joinToString(",") }
    )
}
