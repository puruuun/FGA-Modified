package io.github.fate_grand_automata.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.robolectric.RuntimeEnvironment

/** A phone, the Medium threshold, a tablet, the cap boundary and a wider control window. */
internal val ScreenshotWidths = listOf(360, 600, 840, 1280, 1600)

/** Folder, under the module's build directory, where the recorded images land. */
internal const val ScreenshotDir = "build/outputs/roborazzi"

/**
 * Sizes the Robolectric window to [widthDp] x [heightDp], renders [content] in the app theme and
 * writes `<folder>/<name>_<W>x<H>dp[_<locale>][_dark].png` under [ScreenshotDir]. Call it once per test method (a compose
 * rule hosts one content). The default [density] is hdpi (1.5 pixels per dp). [locale] (for example `ko`) picks the
 * app language; null keeps the default (English) strings.
 */
internal fun ComposeContentTestRule.shoot(
    folder: String,
    name: String,
    widthDp: Int,
    heightDp: Int,
    dark: Boolean = false,
    locale: String? = null,
    density: String = "hdpi",
    beforeCapture: () -> Unit = {},
    content: @Composable () -> Unit
) {
    RuntimeEnvironment.setQualifiers("${locale?.let { "$it-" } ?: ""}w${widthDp}dp-h${heightDp}dp-${if (dark) "night" else "notnight"}-$density")

    setContent {
        FGATheme(darkTheme = dark) {
            content()
        }
    }
    waitForIdle()
    beforeCapture()
    // Let Material's entrance/shape animations finish before recording their content layers.
    mainClock.advanceTimeBy(500)
    waitForIdle()

    onRoot().captureRoboImage("$ScreenshotDir/$folder/${name}_${widthDp}x${heightDp}dp${locale?.let { "_$it" } ?: ""}${if (dark) "_dark" else ""}.png")
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

/** Shared bitmap capture for pixel identity checks; copy because the next frame reuses buffers. */
internal fun ComposeContentTestRule.captureBitmap(): android.graphics.Bitmap =
    onRoot().captureToImage().asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, false)
