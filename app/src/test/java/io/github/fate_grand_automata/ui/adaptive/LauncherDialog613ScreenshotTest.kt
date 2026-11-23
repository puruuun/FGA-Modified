package io.github.fate_grand_automata.ui.adaptive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.capture.CaptureLauncherFixture
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.ScreenshotDir
import io.github.fate_grand_automata.ui.launcher.battleLauncher
import io.github.fate_grand_automata.util.FakedComposeView
import io.github.fate_grand_automata.util.showOverlayDialog
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The launcher in its overlay dialog window, to show the dialog corners (#613). Recorded by `:app:recordRoborazziCi`. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class LauncherDialog613ScreenshotTest(private val dark: Boolean) {
    @Test
    fun launcherDialog() {
        RuntimeEnvironment.setQualifiers("ko-w800dp-h360dp-${if (dark) "night" else "notnight"}-hdpi")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = CaptureLauncherFixture(context, GameServer.Jp.Original)
        val composeView = FakedComposeView(context) {
            FGATheme(darkTheme = dark) { battleLauncher(fixture.prefs, prefsCore = fixture.core) }
        }
        val dialog = showOverlayDialog(context) { setView(composeView.view) }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        // Espresso-based view capture needs an activity, so the window's decor view is drawn onto a backdrop by hand.
        val decor = dialog.window!!.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(decor.width.coerceAtLeast(1), decor.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(0xFF808080.toInt())
        decor.draw(canvas)
        val out = java.io.File("$ScreenshotDir/ui613/LauncherDialog_800x360dp_ko${if (dark) "_dark" else ""}.png")
        out.parentFile!!.mkdirs()
        out.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        dialog.dismiss()
        composeView.close()
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params(): List<Array<Any?>> = listOf(arrayOf<Any?>(false), arrayOf<Any?>(true))
    }
}
