package io.github.fate_grand_automata.capture

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.more.AdditionalToolsContent
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "en-w360dp-h800dp-hdpi")
class CaptureToolsUiTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)

    @Test fun importsKeepTheirActionsAndExportChoosesCorrectAccount() {
        var portraits = 0
        var original = 0
        var exported: String? = null
        compose.setContent { FGATheme { AdditionalToolsContent(mainProfiles(3), { portraits++ }, { original++ }, { exported = it }) } }
        compose.onNodeWithText("Search and save support images").performClick()
        compose.onNodeWithText("Import original FGA data").performClick()
        assertEquals(1, portraits)
        assertEquals(1, original)
        compose.onNodeWithText("Export Chaldea file").performClick()
        assertNull(exported)
        compose.onNodeWithText("마스터 2").performClick()
        assertEquals("sample-2", exported)
    }

    @Test fun singleAccountExportsDirectly() {
        var exported: String? = null
        compose.setContent { FGATheme { AdditionalToolsContent(mainProfiles(1), {}, {}, { exported = it }) } }
        compose.onNodeWithText("Export Chaldea file").performClick()
        assertEquals("sample-1", exported)
        compose.onNodeWithText("Choose an account").assertDoesNotExist()
    }

    @Test fun noDataHidesExport() {
        compose.setContent { FGATheme { AdditionalToolsContent(emptyList(), {}, {}, {}) } }
        compose.onNodeWithText("Export Chaldea file").assertDoesNotExist()
    }

    @Test @Config(sdk = [28]) fun oldAndroidHidesExportEvenWithData() {
        compose.setContent { FGATheme { AdditionalToolsContent(mainProfiles(1), {}, {}, {}) } }
        compose.onNodeWithText("Export Chaldea file").assertDoesNotExist()
    }
}
