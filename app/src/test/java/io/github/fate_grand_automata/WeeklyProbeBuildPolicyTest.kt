package io.github.fate_grand_automata

import io.github.fate_grand_automata.util.isStableBuild
import kotlin.test.Test
import kotlin.test.assertEquals

class WeeklyProbeBuildPolicyTest {
    @Test fun `stable builds hide probe while beta and debug expose it`() {
        for (type in listOf("ci", "release", "beta", "debug")) {
            assertEquals(type in setOf("ci", "release"), isStableBuild(type), type)
        }
    }
}
