package io.github.fate_grand_automata.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.ui.skill_maker.SkillMakerEnemyTarget
import io.github.fate_grand_automata.ui.skill_maker.SkillMakerTopRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the Skill Maker enemy target screen in each layout, and the main screen's top row with
 * the new button, on a 16:9 phone, a 21:9 phone and a 16:10 tablet, light and dark. Recorded by
 * `:app:recordRoborazziCi` into `build/outputs/roborazzi/skill_maker`, never part of the CI run.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SkillMakerEnemyTargetScreenshotTest(
    private val widthDp: Int,
    private val heightDp: Int,
    private val dark: Boolean
) {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules = screenshotRules(composeRule)

    private fun screen(name: String, selected: String) = composeRule.shoot(
        folder = Folder,
        name = name,
        widthDp = widthDp,
        heightDp = heightDp,
        dark = dark
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            SkillMakerEnemyTarget(selected = selected, onSelect = {})
        }
    }

    @Test
    fun enemyTargetNormal() = screen("enemy_target_normal", selected = "2")

    @Test
    fun enemyTargetSix() = screen("enemy_target_six", selected = "5")

    @Test
    fun enemyTargetRaid() = screen("enemy_target_raid", selected = "R")

    @Test
    fun mainTopRow() = composeRule.shoot(
        folder = Folder,
        name = "main_top_row",
        widthDp = widthDp,
        heightDp = TopRowHeightDp,
        dark = dark
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                SkillMakerTopRow(
                    commandSpellRemaining = 3,
                    wave = 2,
                    turn = 4,
                    onEnemyTarget = {},
                    onCommandSpell = {},
                    onMasterSkills = {}
                )
            }
        }
    }

    companion object {
        private const val Folder = "skill_maker"
        private const val TopRowHeightDp = 96

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}x{1} dark={2}")
        fun sizes(): List<Array<Any>> = listOf(
            // 16:9 phone, 21:9 phone, 16:10 tablet (landscape)
            640 to 360,
            840 to 360,
            1280 to 800
        ).flatMap { (w, h) -> listOf(false, true).map { dark -> arrayOf<Any>(w, h, dark) } }
    }
}
