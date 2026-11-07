package io.github.fate_grand_automata.events

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import io.github.fate_grand_automata.ui.log.LocalLogIconLoader
import io.github.fate_grand_automata.ui.log.localIconLoader
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32])
class EventCardScreenshotTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)

    @Test fun card360LightWithOneEventAndThreeCampaigns() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val banner = File(context.filesDir, "capture/icons/events/1.png")
        banner.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(900, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(30, 54, 103))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 222, 140); textSize = 60f }
        canvas.drawText("SAMPLE STARLIGHT FESTIVAL", 34f, 140f, paint)
        banner.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val loader = localIconLoader(context)
        val data = syntheticEvents().copy(banners = mapOf(1L to "https://static.atlasacademy.io/synthetic.png"))
        compose.shoot("issue-229", "EventCard", 360, 800, locale = "ko", beforeCapture = {
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("event-banner-loaded", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("events-campaigns").performClick()
            compose.onNodeWithText("샘플 인연 포인트 UP").assertIsDisplayed()
        }) {
            CompositionLocalProvider(LocalLogIconLoader provides loader) {
                Surface { EventCard(data, EVENT_NOW) {} }
            }
        }
    }
}
