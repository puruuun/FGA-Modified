package io.github.fate_grand_automata.ui.battle_config_item

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.prefs.core.BattleConfigCore
import io.github.fate_grand_automata.scripts.models.CardPriorityPerWave
import androidx.compose.ui.unit.sp
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The chain line of the card priority summary sits under the card list, so it never wraps by character (#721). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ChainSummaryLayoutTest(private val widthDp: Int, private val locale: String, private val waves: Int) {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules = screenshotRules(composeRule)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun chainTextKeepsMostOfTheRow() {
        val config = BattleConfigCore("chain_layout_${widthDp}_${locale}_$waves", context).apply {
            cardPriority.set(CardPriorityPerWave.of(List(waves) { "A, B, Q, WA, WB, WQ, RA, RB, RQ" }.joinToString("\n")))
            useChainPriorities.set(List(waves) { it == waves - 1 })
        }
        RuntimeEnvironment.setQualifiers("$locale-w${widthDp}dp-h2400dp-hdpi")
        composeRule.setContent {
            FGATheme(darkTheme = false) {
                BattleConfigContent(config, "", {}, {}, {}, {})
            }
        }
        composeRule.waitForIdle()

        // Chain Priority is on for the last wave only.
        assertEquals(1, composeRule.onAllNodesWithTag(ChainSummaryTag, useUnmergedTree = true).fetchSemanticsNodes().size)
        // The old layout squeezed the text beside the card list into a column one character wide, about ten lines high.
        val chain = composeRule.onNodeWithTag(ChainSummaryTag, useUnmergedTree = true).fetchSemanticsNode().size
        val root = composeRule.onRoot().fetchSemanticsNode().size.width
        val lineHeight = with(composeRule.density) { 16.sp.toPx() }
        assertTrue("chain text is ${chain.width} x ${chain.height} px in a $root px row", chain.height <= 2 * lineHeight)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}dp {1} {2} waves")
        fun params(): List<Array<Any?>> = listOf(360, 1280).flatMap { w ->
            listOf("ko", "ja").flatMap { l -> listOf(1, 3).map { n -> arrayOf<Any?>(w, l, n) } }
        }
    }
}
