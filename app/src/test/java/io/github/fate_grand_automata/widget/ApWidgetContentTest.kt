package io.github.fate_grand_automata.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
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

    private val wide = DpSize(272.dp, 64.dp)
    private val narrow = DpSize(200.dp, 64.dp)

    @Test fun textsShowApFullTimeTimeLeftAndAge() {
        val fullAt = now + (3 * 60 + 23) * 60
        val texts = apWidgetTexts(context, model(fullAt, minutes = 3 * 60 + 23), now)
        assertEquals("AP", texts.apLabel)
        assertEquals("96", texts.ap)
        assertEquals("/ 144", texts.apMax)
        assertEquals("Full ${clock(fullAt)} (3:23)", texts.fullLine)
        assertEquals("Full ${clock(fullAt)}", texts.fullLineShort)
        assertEquals(clock(fullAt), texts.fullLineTime)
        assertEquals("10m ago", texts.age)
        assertEquals("Fictional", texts.name)
        assertEquals("KR", texts.server)
    }

    @Test fun fullApSaysSoWithoutATime() {
        val texts = apWidgetTexts(context, model(fullAt = null, ap = 144), now)
        assertEquals("AP full", texts.fullLine)
        assertEquals("AP full", texts.fullLineShort)
        assertEquals("AP full", texts.fullLineTime)
    }

    @Test fun ageTextCoversEveryUnit() {
        assertEquals("Just now", dataAgeText(context, DataAge.Now))
        assertEquals("5m ago", dataAgeText(context, DataAge.Minutes(5)))
        assertEquals("2h ago", dataAgeText(context, DataAge.Hours(2)))
        assertEquals("3d ago", dataAgeText(context, DataAge.Days(3)))
    }

    @Test fun timeFormatFollowsTheDevice12And24HourSetting() {
        val fullAt = now + 3600 * 3
        val resolver = context.contentResolver
        android.provider.Settings.System.putString(resolver, android.provider.Settings.System.TIME_12_24, "24")
        val h24 = apWidgetTexts(context, model(fullAt, minutes = 180), now).fullLineShort
        assertTrue(Regex("""\d{2}:\d{2}$""").containsMatchIn(h24), h24)
        android.provider.Settings.System.putString(resolver, android.provider.Settings.System.TIME_12_24, "12")
        val h12 = apWidgetTexts(context, model(fullAt, minutes = 180), now).fullLineShort
        assertTrue(h12.contains("AM") || h12.contains("PM"), h12)
    }

    private val refresh = hasRunCallbackClickAction<RefreshApWidgetCallback>()
    private val openDashboard get() = hasStartActivityClickAction(WidgetIntents.openAccount(context, "7001"))

    @Test fun wideLayoutShowsEveryFieldAndBothActions() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(wide)
        val m = model(now + 3600, minutes = 60)
        val texts = apWidgetTexts(context, m)
        provideComposable { ApWidgetContent(ApWidgetState.Data(m)) }
        onNode(hasText("Fictional")).assertExists()
        onNode(hasText("KR")).assertExists()
        onNode(hasText(texts.ap)).assertExists()
        onNode(hasText(texts.apMax)).assertExists()
        onNode(hasText(texts.fullLine)).assertExists()
        onNode(hasText(texts.age)).assertExists()
        onNode(refresh).assertExists()
        onNode(openDashboard).assertExists()
    }

    @Test fun wideLayoutAtFullApShowsApFull() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ApWidgetContent(ApWidgetState.Data(model(null, ap = 144))) }
        onNode(hasText(context.getString(R.string.widget_ap_full))).assertExists()
        onNode(refresh).assertExists()
    }

    @Test fun narrowLayoutHidesTheTimeLeftAndTheRefreshIcon() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(narrow)
        val m = model(now + 3600, minutes = 60)
        val texts = apWidgetTexts(context, m)
        provideComposable { ApWidgetContent(ApWidgetState.Data(m)) }
        onNode(hasText(texts.fullLineShort)).assertExists()
        onNode(hasText(texts.fullLine)).assertDoesNotExist()
        onNode(refresh).assertDoesNotExist()
        onNode(hasText("Fictional")).assertExists()
        onNode(hasText(texts.ap)).assertExists()
        onNode(openDashboard).assertExists()
    }

    @Test fun narrowestLayoutShowsTheFullTimeAloneWithoutTheApLabel() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(180.dp, 66.dp))
        val m = model(now + 3600, minutes = 60)
        val texts = apWidgetTexts(context, m)
        provideComposable { ApWidgetContent(ApWidgetState.Data(m)) }
        onNode(hasText(texts.fullLineTime)).assertExists()
        onNode(hasText(texts.fullLineShort)).assertDoesNotExist()
        onNode(hasText(texts.apLabel)).assertDoesNotExist()
        onNode(hasText(texts.ap)).assertExists()
        onNode(openDashboard).assertExists()
    }

    @Test fun apLabelShowsOnlyOnAFull4x1() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(272.dp, 66.dp))
        val m = model(now + 3600, minutes = 60)
        provideComposable { ApWidgetContent(ApWidgetState.Data(m)) }
        onNode(hasText(apWidgetTexts(context, m).apLabel)).assertExists()
    }

    @Test fun narrowLayoutAtFullApShowsApFull() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(narrow)
        provideComposable { ApWidgetContent(ApWidgetState.Data(model(null, ap = 144))) }
        onNode(hasText(context.getString(R.string.widget_ap_full))).assertExists()
    }

    @Test fun alreadyPlaced3x2WidgetStillRendersTheRow() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(250.dp, 110.dp))
        val m = model(now + 3600, minutes = 60)
        provideComposable { ApWidgetContent(ApWidgetState.Data(m)) }
        onNode(hasText("Fictional")).assertExists()
        onNode(hasText(apWidgetTexts(context, m).fullLineShort)).assertExists()
    }

    @Test fun missingDataShowsTheNoDataMessageAndKeepsTheToggle() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ApWidgetContent(ApWidgetState.NoData) }
        onNode(hasText(context.getString(R.string.widget_ap_no_data))).assertExists()
        onNode(hasText(context.getString(R.string.widget_service_off))).assertExists()
        onNode(hasStartActivityClickAction(WidgetIntents.startService(context))).assertExists()
        onNode(refresh).assertDoesNotExist()
    }

    @Test fun apWidgetToggleTileFollowsTheServiceState() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ApWidgetContent(ApWidgetState.Data(model(now + 3600, minutes = 60)), serviceStarted = true) }
        onNode(hasText(context.getString(R.string.widget_service_on))).assertExists()
        onNode(hasText(context.getString(R.string.widget_service_off))).assertDoesNotExist()
        onNode(hasRunCallbackClickAction<StopServiceCallback>()).assertExists()
    }

    @Test fun serviceWidgetShowsOffAndStartsWhileStopped() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { ServiceWidgetContent(started = false) }
        onNode(hasText(context.getString(R.string.widget_service_off))).assertExists()
        onNode(hasText(context.getString(R.string.widget_service_on))).assertDoesNotExist()
        onNode(hasStartActivityClickAction(WidgetIntents.startService(context))).assertExists()
    }

    @Test fun serviceWidgetShowsOnAndStopsWhileRunning() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { ServiceWidgetContent(started = true) }
        onNode(hasText(context.getString(R.string.widget_service_on))).assertExists()
        onNode(hasText(context.getString(R.string.widget_service_off))).assertDoesNotExist()
        onNode(hasRunCallbackClickAction<StopServiceCallback>()).assertExists()
    }
}
