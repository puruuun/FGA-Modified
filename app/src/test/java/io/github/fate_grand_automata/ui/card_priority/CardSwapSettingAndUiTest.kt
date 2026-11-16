package io.github.fate_grand_automata.ui.card_priority

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.PreferencesImpl
import io.github.fate_grand_automata.prefs.core.CompletedRunsHolder
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.enums.CardPriorityStyle
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "ko-w360dp-h800dp-hdpi")
class CardSwapSettingAndUiTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun battleConfigCardSwapDefaultsFalseAndRoundTripsExportImport() {
        val prefsCore = PrefsCore(
            PrefMaker(context.getSharedPreferences("card_swap_pref_test", Context.MODE_PRIVATE)),
            context
        )
        val preferences = PreferencesImpl(prefsCore, CompletedRunsHolder())

        val sourceCore = prefsCore.forBattleConfig("cfg_source")
        val sourceConfig = preferences.forBattleConfig("cfg_source")
        assertFalse(sourceCore.cardSwapDetection.get())
        assertFalse(sourceConfig.cardSwapDetection)

        sourceCore.cardSwapDetection.set(true)
        assertTrue(sourceConfig.cardSwapDetection)

        val exported = sourceCore.export()
        val importedCore = prefsCore.forBattleConfig("cfg_imported")
        importedCore.import(exported)
        assertTrue(importedCore.cardSwapDetection.get())
        assertTrue(preferences.forBattleConfig("cfg_imported").cardSwapDetection)
    }

    @Test
    fun cardPriorityViewTogglesSharedBattleConfigSwitchEvenInServantStyle() {
        val prefsCore = PrefsCore(
            PrefMaker(context.getSharedPreferences("card_swap_ui_test", Context.MODE_PRIVATE)),
            context
        )
        val vmProfile2 = CardPriorityViewModel(prefsCore, "cfg_ui", profileIndex = 1)
        vmProfile2.cardPriorityItems.first().style.value = CardPriorityStyle.Servant

        compose.setContent {
            FGATheme {
                CardPriorityScreen(vm = vmProfile2)
            }
        }

        val title = context.getString(R.string.card_swap_detection)
        val summary = context.getString(R.string.card_swap_detection_summary)

        val swapSwitch = isToggleable() and hasAnyAncestor(hasClickAction() and hasAnyDescendant(hasText(title)))
        compose.onNodeWithText("All waves and turns").assertDoesNotExist()
        compose.onNodeWithText(title).performScrollTo().assertExists()
        compose.onNodeWithText(summary).performScrollTo().assertExists()
        compose.onNode(swapSwitch, useUnmergedTree = true).assertIsEnabled().assertIsOff()

        compose.onNodeWithText(title).performClick()

        compose.onNode(swapSwitch, useUnmergedTree = true).assertIsOn()
        assertTrue(prefsCore.forBattleConfig("cfg_ui").cardSwapDetection.get())
        // Shared across profiles: profile 0 sees the same switch state.
        val vmProfile1 = CardPriorityViewModel(prefsCore, "cfg_ui", profileIndex = 0)
        assertTrue(vmProfile1.cardSwapDetection.get())
    }
}
