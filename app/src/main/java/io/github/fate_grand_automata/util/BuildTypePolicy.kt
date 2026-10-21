package io.github.fate_grand_automata.util

fun isStableBuild(buildType: String): Boolean = buildType == "ci" || buildType == "release"
