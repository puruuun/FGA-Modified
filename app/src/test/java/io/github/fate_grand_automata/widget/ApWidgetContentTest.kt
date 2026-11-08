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
@Config(sdk = [32], qualifiers = "ko")
class ApWidgetContentTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000L
    private fun model(id: String = "7001", fullAt: Long? = now + 3600) = ApWidgetModel(
        accountId = id, name = "계정 $id", ap = 57, apMax = 142, fullAt = fullAt, dataTime = now - 600,
    )
    private val wide = DpSize(272.dp, 66.dp)
    private fun clock(t: Long) = android.text.format.DateFormat.getTimeFormat(context).format(Date(t * 1000))

    @Test fun textsShowApFullTimeAndThisAccountsCollectionAge() {
        val texts = apWidgetTexts(context, model(), now)
        assertEquals("57 / 142", texts.ap)
        assertEquals("완충 ${clock(now + 3600)}", texts.fullLine)
        assertEquals("10분 전", texts.age)
        assertEquals("계정 7001", texts.name)
        assertTrue(!texts.fullLine.contains("("))
    }

    @Test fun fullApSaysSoWithoutATime() {
        assertEquals("AP 가득 참", apWidgetTexts(context, model(fullAt = null), now).fullLine)
    }

    @Test fun ageTextCoversEveryUnit() {
        assertEquals("방금", dataAgeText(context, DataAge.Now))
        assertEquals("5분 전", dataAgeText(context, DataAge.Minutes(5)))
        assertEquals("2시간 전", dataAgeText(context, DataAge.Hours(2)))
        assertEquals("3일 전", dataAgeText(context, DataAge.Days(3)))
    }

    @Test fun timeFormatFollowsDevice12And24HourSetting() {
        val resolver = context.contentResolver
        android.provider.Settings.System.putString(resolver, android.provider.Settings.System.TIME_12_24, "24")
        val h24 = apWidgetTexts(context, model(), now).fullLine
        assertTrue(Regex("""\d{2}:\d{2}$""").containsMatchIn(h24), h24)
        android.provider.Settings.System.putString(resolver, android.provider.Settings.System.TIME_12_24, "12")
        val h12 = apWidgetTexts(context, model(), now).fullLine
        assertTrue(h12.contains("오전") || h12.contains("오후"), h12)
    }

    @Test fun serviceWidgetShowsOffAndStartsWhileStopped() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { ServiceWidgetContent(false) }
        onNode(hasText("OFF")).assertExists()
        onNode(hasStartActivityClickAction(WidgetIntents.startService(context))).assertExists()
    }

    @Test fun serviceWidgetShowsOnAndStopsWhileRunning() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { ServiceWidgetContent(true) }
        onNode(hasText("ON")).assertExists()
        onNode(hasRunCallbackClickAction<StopServiceCallback>()).assertExists()
    }
}
