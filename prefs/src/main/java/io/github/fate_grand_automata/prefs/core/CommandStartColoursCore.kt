package io.github.fate_grand_automata.prefs.core

import io.github.fate_grand_automata.scripts.enums.CommandStartCombineMode

class CommandStartColoursCore(maker: PrefMaker, prefix: String) {
    val buster = maker.int("${prefix}buster", 0)
    val arts = maker.int("${prefix}arts", 0)
    val quick = maker.int("${prefix}quick", 0)
    val combineMode = maker.enum("${prefix}combine_mode", CommandStartCombineMode.And)
}
