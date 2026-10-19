package io.github.fate_grand_automata

import io.github.fate_grand_automata.di.app.AppProvidesModule
import kotlin.test.Test
import kotlin.test.assertEquals

class FpSellBuildFlavorTest {
    @Test fun `app build type supplies stable policy without persisted settings`() {
        assertEquals(BuildConfig.BUILD_TYPE == "ci" || BuildConfig.BUILD_TYPE == "release",
            AppProvidesModule().provideFpSellStableBuild())
    }
}
