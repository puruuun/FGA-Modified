package io.github.fate_grand_automata.ui.log

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.scripts.capture.AccountDelta
import io.github.fate_grand_automata.scripts.capture.ChangeSource
import io.github.fate_grand_automata.scripts.capture.OutsidePeriod
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Only generated icons and fictional counts. Never contacts Atlas or a device. */
internal fun cardCountNames(context: Context): GameNameMap {
    val names = localIconNames(context)
    listOf("servants" to 9700100L, "servants" to 9400010L, "commandCodes" to 10L).forEach { (kind, id) ->
        val target = File(context.filesDir, "capture/icons/$kind/$id.png")
        target.parentFile!!.mkdirs()
        File(context.filesDir, "capture/icons/6510.png").copyTo(target, overwrite = true)
    }
    return names.copy(svtData = mapOf(
        9700100L to AtlasBasic(9700100, "예지의 맹화", "combineMaterial", "all"),
        9400010L to AtlasBasic(9400010, "합성 예장", "servantEquip"),
    ), commandCodeData = mapOf(10L to AtlasBasic(10, "합성 코드")))
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class CardCountLogScreenshotTest {
    private val rule = createComposeRule()
    @get:Rule val rules = screenshotRules(rule)

    @Test fun outsideDetail360Light() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val names = cardCountNames(context)
        val period = OutsidePeriod("synthetic", 1_793_754_000)
            .add(ChangeSource.of("svtCombine/execute"), AccountDelta(items = mapOf(6510L to -2L), svts = mapOf(9700100L to -12L)), 1_793_754_010)
            .add(ChangeSource.of("shop/sell"), AccountDelta(qp = 3000, svts = mapOf(9400010L to -3L), commandCodes = mapOf(10L to -1L)), 1_793_754_020)
        val entry = outsideFixture().copy(accountName = "합성 샘플", period = period,
            startedAt = period.startedAt * 1000, endedAt = period.endedAt * 1000)
        rule.shoot("log-round10", "OutsideCardCounts", 360, 800, false, "ko", beforeCapture = {
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("log-icon-loaded", useUnmergedTree = true).fetchSemanticsNodes().size == 5 }
            rule.onNodeWithTag("resource:-1:9700100").assertExists()
            rule.onNodeWithTag("resource:-1:9400010").assertExists()
            rule.onNodeWithTag("resource:2:6510").assertExists()
        }) {
            CompositionLocalProvider(LocalLogIconLoader provides localIconLoader(context)) { OutsideLogDetail(entry, {}, names) }
        }
    }
}
