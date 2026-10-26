package io.github.fate_grand_automata.util

import io.github.fate_grand_automata.BuildConfig

fun skillLevelCheckAvailable(buildType: String = BuildConfig.BUILD_TYPE): Boolean =
    buildType == "beta" || buildType == "deviceTest"
