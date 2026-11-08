package io.github.fate_grand_automata.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "en")
class ApWidgetContentTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000L

    private fun model(fullAt: Long?, ap: Long = 96, max: Long = 144, minutes: Long? = null) = ApWidgetModel(
        accountId = "7001", name = "Fictional", server = "KR", ap = ap, apMax = max,
        fullAt = fullAt, minutesLeft = minutes, dataTime = now - 600,
    )

    private fun clock(epochSeconds: Long) = android.text.format.DateFormat.getTimeFormat(context).format(Date(epochSeconds * 1000))

    @Test fun textsShowApFullTimeTimeLeftAndDataTime() {
        val fullAt = now + (4 * 60 + 35) * 60
        val texts = apWidgetTexts(context, model(fullAt, minutes = 4 * 60 + 35))
        assertEquals("AP 96 / 144", texts.apLine)
        assertEquals("Full ${clock(fullAt)} (in 4h 35m)", texts.fullLine)
        assertEquals("Full ${clock(fullAt)}", texts.fullLineShort)
        assertEquals("Data ${clock(now - 600)}", texts.dataLine)
        assertEquals("Fictional", texts.name)
        assertEquals("KR", texts.server)
    }

    @Test fun timeLeftUnderAnHourShowsOnlyMinutes() {
        val texts = apWidgetTexts(context, model(now + 1500, minutes = 25))
        assertTrue(texts.fullLine.endsWith("(in 25m)"), texts.fullLine)
    }

    @Test fun fullApSaysSoWithoutATime() {
        val texts = apWidgetTexts(context, model(fullAt = null, ap = 144))
        assertEquals("AP full", texts.fullLine)
        assertEquals("AP full", texts.fullLineShort)
    }

    @Test fun timeFormatFollowsTheDevice12And24HourSetting() {
        val fullAt = now + 3600 * 3
        val resolver = context.contentResolver
        android.provider.Settings.System.putString(resolver, android.provider.Settings.System.TIME_12_24, "24")
        val h24 = apWidgetTexts(context, model(fullAt, minutes = 180)).fullLineShort
        assertTrue(Regex("""\d{2}:\d{2}$""").containsMatchIn(h24), h24)
        android.provider.Settings.System.putString(resolver, android.provider.Settings.System.TIME_12_24, "12")
        val h12 = apWidgetTexts(context, model(fullAt, minutes = 180)).fullLineShort
        assertTrue(h12.contains("AM") || h12.contains("PM"), h12)
    }

    @Test fun fullLayoutShowsEveryField() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(180.dp, 110.dp))
        val m = model(now + 3600, minutes = 60)
        val texts = apWidgetTexts(context, m)
        provideComposable { ApWidgetContent(ApWidgetState.Data(m)) }
        onNode(hasText("Fictional")).assertExists()
        onNode(hasText("KR")).assertExists()
        onNode(hasText(texts.apLine)).assertExists()
        onNode(hasText(texts.fullLine)).assertExists()
        onNode(hasText(texts.dataLine)).assertExists()
    }

    @Test fun compactLayoutShowsOnlyApAndFullTime() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(110.dp, 40.dp))
        val m = model(now + 3600, minutes = 60)
        val texts = apWidgetTexts(context, m)
        provideComposable { ApWidgetContent(ApWidgetState.Data(m)) }
        onNode(hasText(texts.apLine)).assertExists()
        onNode(hasText(texts.fullLineShort)).assertExists()
        onNode(hasText("Fictional")).assertDoesNotExist()
        onNode(hasText(texts.dataLine)).assertDoesNotExist()
    }

    @Test fun missingDataShowsTheNoDataMessage() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(180.dp, 110.dp))
        provideComposable { ApWidgetContent(ApWidgetState.NoData) }
        onNode(hasText(context.getString(R.string.widget_ap_no_data))).assertExists()
    }

    @Test fun serviceWidgetShowsStartWhileStoppedAndStopWhileRunning() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { ServiceWidgetContent(started = false) }
        onNode(hasText(context.getString(R.string.widget_service_start))).assertExists()
        onNode(hasText(context.getString(R.string.widget_service_stop))).assertDoesNotExist()
    }

    @Test fun serviceWidgetShowsStopWhileRunning() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { ServiceWidgetContent(started = true) }
        onNode(hasText(context.getString(R.string.widget_service_stop))).assertExists()
        onNode(hasText(context.getString(R.string.widget_service_start))).assertDoesNotExist()
    }
}
