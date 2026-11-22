package io.github.fate_grand_automata.ui.pref_support

import android.content.Context
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.dialog.FaceThumbnails
import io.github.fate_grand_automata.ui.dialog.ImageDelete
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "ko-w360dp-h800dp-hdpi")
class SupportPickerChipsScreenshotTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)

    private fun capture(deleteMode: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shared = context.getSharedPreferences("chips627-shot-$deleteMode", Context.MODE_PRIVATE)
        shared.edit().clear().commit()
        val pref = PrefsCore(PrefMaker(shared), context).forBattleConfig("t").support.preferredServants
        val names = listOf("Arcueid", "Artoria (Caster)", "BB Dubai", "Chloe (Avenger)", "Ciel", "마슈 키리에라이트", "스카사하=스카디", "No.444 Ｕ－オルガマリー", "Mine")
        val colors = listOf(0xFF3F51B5, 0xFFE91E63, 0xFF009688, 0xFFFF9800).map { it.toInt() }
        pref.set(setOf("Ciel", "Mine"))
        val faces = FaceThumbnails { name -> if (name == "Mine") null else ColorDrawable(colors[names.indexOf(name) % colors.size]) }
        val delete = ImageDelete<String>(isProtected = { it == "Arcueid" }, delete = { it })
        compose.setContent {
            FGATheme { pref.SupportSelectPreference("서번트 선호", names.associateWith { it }, thumbnails = faces, imageDelete = delete) }
        }
        compose.onNodeWithText("서번트 선호").performClick()
        if (deleteMode) {
            compose.onNode(hasText("Mine") and isToggleable()).performTouchInput { longClick() }
            compose.onNode(hasText("BB Dubai") and isToggleable()).performClick()
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onAllNodes(isDialog()).onLast().captureRoboImage(
            "build/outputs/roborazzi/support-picker-chips/servant_${if (deleteMode) "delete" else "normal"}_light.png")
    }

    @Test fun servantNormalLight() = capture(false)
    @Test fun servantDeleteLight() = capture(true)
}
