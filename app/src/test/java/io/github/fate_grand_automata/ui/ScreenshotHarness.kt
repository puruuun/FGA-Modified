package io.github.fate_grand_automata.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.robolectric.RuntimeEnvironment

/** The widths the owner reviews: a phone, the Medium threshold, a tablet and a wide window. */
internal val ScreenshotWidths = listOf(360, 600, 840, 1280)

/** Folder, under the module's build directory, where the recorded images land. */
internal const val ScreenshotDir = "build/outputs/roborazzi"

/**
 * Sizes the Robolectric window to [widthDp] x [heightDp], renders [content] in the app theme and
 * writes `<folder>/<name>_<W>x<H>dp[_dark].png` under [ScreenshotDir]. Call it once per test method (a compose rule hosts one
 * content). The density is hdpi, so the images are 1.5 pixels per dp.
 */
internal fun ComposeContentTestRule.shoot(
    folder: String,
    name: String,
    widthDp: Int,
    heightDp: Int,
    dark: Boolean = false,
    content: @Composable () -> Unit
) {
    RuntimeEnvironment.setQualifiers("w${widthDp}dp-h${heightDp}dp-hdpi")

    setContent {
        FGATheme(darkTheme = dark) {
            content()
        }
    }
    waitForIdle()

    onRoot().captureRoboImage("$ScreenshotDir/$folder/${name}_${widthDp}x${heightDp}dp${if (dark) "_dark" else ""}.png")
}

/**
 * `ui-test-manifest` would have to be merged into the app manifest to declare the host activity,
 * which would ship it in the beta APK. Declaring it to Robolectric directly keeps it test-only.
 */
internal class ComposeHostActivityRule : org.junit.rules.TestWatcher() {
    override fun starting(description: org.junit.runner.Description) {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        org.robolectric.Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(
            android.content.ComponentName(app.packageName, androidx.activity.ComponentActivity::class.java.name)
        )
    }
}

/** Registers the host activity first, then creates the compose rule. */
internal fun screenshotRules(composeRule: ComposeContentTestRule): org.junit.rules.RuleChain =
    org.junit.rules.RuleChain.outerRule(ComposeHostActivityRule()).around(composeRule)
