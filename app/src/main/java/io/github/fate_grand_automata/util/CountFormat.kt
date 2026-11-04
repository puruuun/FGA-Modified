package io.github.fate_grand_automata.util

import java.text.NumberFormat

fun Long.grouped(): String = NumberFormat.getIntegerInstance().format(this)
