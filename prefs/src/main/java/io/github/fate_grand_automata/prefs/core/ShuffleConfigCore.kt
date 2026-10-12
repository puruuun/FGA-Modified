package io.github.fate_grand_automata.prefs.core

import io.github.fate_grand_automata.scripts.enums.ShuffleCardsEnum

/** Common shuffle settings, using the original unprefixed keys. */
class ShuffleConfigCore(maker: PrefMaker) {
    val mode = maker.enum("shuffle_cards", ShuffleCardsEnum.None)
    val wave = maker.int("shuffle_cards_wave", 3)
}
