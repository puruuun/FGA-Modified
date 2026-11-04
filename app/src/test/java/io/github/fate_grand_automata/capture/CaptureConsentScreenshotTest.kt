package io.github.fate_grand_automata.capture

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class CaptureConsentScreenshotTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)

    @Test fun consentAt360dp() {
        // The dialog is a separate window; capture it explicitly instead of the activity root.
        org.robolectric.RuntimeEnvironment.setQualifiers("ko-w360dp-h480dp-hdpi")
        compose.setContent {
            io.github.fate_grand_automata.ui.FGATheme(darkTheme = false) {
                Text("")
                CaptureConsentDialog(onCancel = {}, onAgree = {})
            }
        }
        compose.waitForIdle()
        compose.onNode(isDialog()).captureRoboImage("build/outputs/roborazzi/log-ui/Consent_360x480dp_ko.png")
        compose.onNodeWithText("이 기능의 사용과 그 결과에 대한 책임은 사용자 본인에게 있습니다.").performScrollTo()
        compose.onNode(isDialog()).captureRoboImage("build/outputs/roborazzi/log-ui/ConsentScrolled_360x480dp_ko.png")
    }
}
