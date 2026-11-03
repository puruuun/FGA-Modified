package io.github.fate_grand_automata.ui.adaptive

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.ui.ListDetailPanes
import io.github.fate_grand_automata.ui.battle_config_item.BattleConfigContent
import io.github.fate_grand_automata.ui.battle_config_list.BattleConfigListContent
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders [ListDetailPanes] with the battle config list on the left, in Korean: with a config open
 * on the right, and with the hint, at 840x600, 1067x600 and 1280x800 dp, plus one dark shot at
 * 1067x600. Images land in `build/outputs/roborazzi/list_detail/`, named
 * `<panes>_<W>x<H>dp_ko[_dark].png`. Recorded by `:app:recordRoborazziCi`, never part of the CI run.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ListDetailScreenshotTest(
    private val widthDp: Int,
    private val heightDp: Int
) {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules = screenshotRules(composeRule)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun shoot(name: String, withDetail: Boolean, dark: Boolean = false) {
        val configs = sampleConfigList(context, "list_detail_$name")
        val open = sampleConfig(context, "list_detail_open_$name")

        composeRule.shoot(
            folder = "list_detail",
            name = name,
            widthDp = widthDp,
            heightDp = heightDp,
            dark = dark,
            locale = "ko"
        ) {
            ListDetailPanes(
                list = {
                    BattleConfigListContent(
                        configs = configs,
                        selectionMode = false,
                        selectedConfigs = emptySet(),
                        expandedFolders = setOf("events"),
                        folderRevision = 0,
                        openConfigId = if (withDetail) configs.first().id else null,
                        action = {}
                    )
                },
                detail = if (withDetail) {
                    @Composable {
                        BattleConfigContent(
                            config = open,
                            maxSkillText = "",
                            onExport = {},
                            onCopy = {},
                            onDelete = {},
                            navigate = {}
                        )
                    }
                } else null,
                detailKey = "open"
            )
        }
    }

    @Test
    fun listAndConfig() = shoot("list_and_config", withDetail = true)

    @Test
    fun listAndHint() = shoot("list_and_hint", withDetail = false)

    @Test
    fun listAndConfigDark() {
        if (widthDp != 1067) return // dark mode is covered at one size

        shoot("list_and_config", withDetail = true, dark = true)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}x{1}dp")
        fun params(): List<Array<Any?>> = listOf(
            arrayOf<Any?>(840, 600),
            arrayOf<Any?>(1067, 600),
            arrayOf<Any?>(1280, 800)
        )
    }
}
