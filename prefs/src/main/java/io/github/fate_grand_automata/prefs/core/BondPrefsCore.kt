package io.github.fate_grand_automata.prefs.core

class BondPrefsCore(maker: PrefMaker) {
    // Stored in each battle config. Legacy launcher/party-position settings are not reused.
    val track = maker.bool("bond_result_track")
    val stopOnMax = maker.bool("bond_result_stop_on_max")
    // Retain the 0.9.0 storage format (watched characters) and expose its complement.
    // Existing scripts and imported files keep their stop behavior while checked now means ignored.
    val ignoredCharacters = maker.string("bond_result_stop_characters", "1,2,3,4,5").map(
        defaultValue = emptySet<Int>(),
        convert = { raw -> (1..5).toSet() - raw.split(',').mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 1..5 } }.toSet() },
        reverse = { ignored -> ((1..5).toSet() - ignored).sorted().joinToString(",") }
    )
}
