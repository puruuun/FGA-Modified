package io.github.fate_grand_automata.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTestTag
import io.github.fate_grand_automata.prefs.core.WidgetTheme
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

    @Test fun oneAccountShowsTheStackAndTheStartTile() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ApWidgetContent(ApWidgetState.Data(listOf(model()))) }
        // The cards are RemoteViews inside the stack, so only the stack and the tile are Glance nodes.
        onNode(hasTestTag(AP_STACK_TAG)).assertExists()
        onNode(hasText("OFF")).assertExists()
        onNode(hasText("계정 데이터 없음")).assertDoesNotExist()
        // The only clickable Glance node is the start tile: card taps come from the stack's template.
        onAllNodes(androidx.glance.testing.unit.hasClickAction()).assertCountEquals(1)
        onNode(hasStartActivityClickAction(WidgetIntents.startService(context))).assertExists()
    }

    @Test fun threeAccountsStillUseOneStackAndKeepTheStopTile() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(wide)
        val models = listOf(model("7003"), model("7001"), model("7002"))
        provideComposable { ApWidgetContent(ApWidgetState.Data(models), serviceStarted = true) }
        onAllNodes(hasTestTag(AP_STACK_TAG)).assertCountEquals(1)
        onNode(hasText("ON")).assertExists()
        onNode(hasRunCallbackClickAction<StopServiceCallback>()).assertExists()
        onAllNodes(androidx.glance.testing.unit.hasClickAction()).assertCountEquals(1)
    }

    @Test fun narrowestWidgetStillHasTheStack() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(180.dp, 66.dp))
        provideComposable { ApWidgetContent(ApWidgetState.Data(listOf(model()))) }
        onNode(hasTestTag(AP_STACK_TAG)).assertExists()
        assertEquals(ApWidgetFields(9, 8), apWidgetFields(180f))
    }

    @Test fun lightThemeKeepsTheStackAndTile() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable {
            ApWidgetContent(ApWidgetState.Data(listOf(model())), settings = WidgetSettings(WidgetTheme.Light, 50))
        }
        onNode(hasTestTag(AP_STACK_TAG)).assertExists()
        onNode(hasText("OFF")).assertExists()
    }

    @Test fun noDataHasNoStack() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ApWidgetContent(ApWidgetState.NoData) }
        onNode(hasTestTag(AP_STACK_TAG)).assertDoesNotExist()
    }

    @Test fun noDataKeepsTheStartTile() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ApWidgetContent(ApWidgetState.NoData) }
        onNode(hasText("계정 데이터 없음")).assertExists()
        onNode(hasText("OFF")).assertExists()
        onNode(hasStartActivityClickAction(WidgetIntents.startService(context))).assertExists()
    }

    @Test fun noDataKeepsTheStopTile() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ApWidgetContent(ApWidgetState.NoData, serviceStarted = true) }
        onNode(hasText("계정 데이터 없음")).assertExists()
        onNode(hasText("ON")).assertExists()
        onNode(hasRunCallbackClickAction<StopServiceCallback>()).assertExists()
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
