package io.github.fate_grand_automata.widget

import android.app.PendingIntent
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.RemoteViews
import android.widget.StackView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.WidgetTheme
import io.github.fate_grand_automata.ui.main.MainActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "ko")
class ApWidgetItemsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000L
    private fun model(id: String, ap: Long = 57, fullAt: Long? = now + 3600) = ApWidgetModel(
        accountId = id, name = "계정 $id", ap = ap, apMax = 142, fullAt = fullAt, dataTime = now - 600,
    )
    private val models = listOf(model("7003"), model("7001"), model("7002"))
    private val fields = ApWidgetFields(12, 10)

    @Test fun itemContentHasTheTextsProgressAndAccountExtra() {
        val content = apItemContent(context, model("7001"), now)
        val clock = android.text.format.DateFormat.getTimeFormat(context).format(Date((now + 3600) * 1000))
        assertEquals("계정 7001", content.texts.name)
        assertEquals("10분 전", content.texts.age)
        assertEquals("57 / 142", content.texts.ap)
        assertEquals("완충 $clock", content.texts.fullLine)
        assertEquals(401, content.progress)
        assertEquals("7001", content.fillIn.getStringExtra(WidgetIntents.EXTRA_OPEN_ACCOUNT))
        assertEquals(apWidgetItemId("7001"), content.itemId)
    }

    @Test fun fullApItemSaysSoAndFillsTheBar() {
        val content = apItemContent(context, model("7001", ap = 142, fullAt = null), now)
        assertEquals("AP 가득 참", content.texts.fullLine)
        assertEquals(AP_BAR_MAX, content.progress)
    }

    @Test fun emptyOrOverfullApStaysInsideTheBar() {
        assertEquals(0, apItemContent(context, model("a", ap = 0), now).progress)
        assertEquals(AP_BAR_MAX, apItemContent(context, model("a", ap = 400), now).progress)
    }

    @Test fun itemsKeepTheTrackersOrderAndStableDistinctIds() {
        val items = apItemContents(context, models, now)
        assertEquals(listOf("7003", "7001", "7002"), items.map { it.accountId })
        assertEquals(3, items.map { it.itemId }.toSet().size)
        assertEquals(items.map { it.itemId }, apItemContents(context, models, now + 60).map { it.itemId })
    }

    private fun host(views: RemoteViews): StackView {
        val parent = android.appwidget.AppWidgetHostView(context)
        parent.addView(views.apply(context, parent))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        fun find(v: View): StackView? =
            if (v is StackView) v else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        return assertNotNull(find(parent))
    }

    @Test fun stackHoldsOneStableItemPerAccount() {
        val stack = host(buildApStackViews(context, 11, models, widgetPalette(WidgetTheme.Dark), fields, now))
        val adapter = assertNotNull(stack.adapter)
        assertEquals(3, adapter.count)
        assertTrue(adapter.hasStableIds())
        assertEquals(models.map { apWidgetItemId(it.accountId) }, (0 until 3).map { adapter.getItemId(it) })
        assertEquals(1, adapter.viewTypeCount)
    }

    @Test fun oneAccountIsASingleCard() {
        val stack = host(buildApStackViews(context, 11, models.take(1), widgetPalette(WidgetTheme.Dark), fields, now))
        assertEquals(1, stack.adapter.count)
    }

    @Test fun stackItemRendersTheAccountInTheThemeColours() {
        val dark = widgetPalette(WidgetTheme.Dark)
        val light = widgetPalette(WidgetTheme.Light)
        fun render(p: WidgetPalette): View {
            val parent = FrameLayout(context)
            return buildApItemViews(context, apItemContent(context, model("7001"), now), p, fields).apply(context, parent)
        }
        val darkView = render(dark)
        assertEquals("계정 7001", darkView.findViewById<TextView>(R.id.ap_item_name).text.toString())
        assertEquals("57 / 142", darkView.findViewById<TextView>(R.id.ap_item_ap).text.toString())
        assertEquals(ContextCompat.getColor(context, dark.fullAt), darkView.findViewById<TextView>(R.id.ap_item_full).currentTextColor)
        assertEquals(View.VISIBLE, darkView.findViewById<View>(R.id.ap_item_bar_dark).visibility)
        assertEquals(View.GONE, darkView.findViewById<View>(R.id.ap_item_bar_light).visibility)
        assertEquals(401, darkView.findViewById<ProgressBar>(R.id.ap_item_bar_dark).progress)

        val lightView = render(light)
        assertEquals(ContextCompat.getColor(context, light.fullAt), lightView.findViewById<TextView>(R.id.ap_item_full).currentTextColor)
        assertEquals(View.GONE, lightView.findViewById<View>(R.id.ap_item_bar_dark).visibility)
        assertEquals(View.VISIBLE, lightView.findViewById<View>(R.id.ap_item_bar_light).visibility)
        assertEquals(401, lightView.findViewById<ProgressBar>(R.id.ap_item_bar_light).progress)
    }

    @Test fun tapTemplateIsMutableExplicitAndOpensTheApp() {
        val shadow = shadowOf(openAccountTemplate(context))
        assertTrue(shadow.isActivityIntent)
        val component = assertNotNull(shadow.savedIntent.component)
        assertEquals(MainActivity::class.java.name, component.className)
        // Android 12+ needs a mutable template so the fill-in extras can be merged in.
        assertTrue(shadow.flags and PendingIntent.FLAG_MUTABLE != 0)
    }

    @Test fun fillInExtrasReachTheMainActivityRequest() {
        val template = WidgetIntents.openAccountTemplate(context)
        template.fillIn(WidgetIntents.openAccountFillIn("7002"), 0)
        val requests = WidgetLaunchRequests()
        requests.consume(template)
        assertEquals("7002", requests.takeAccount())
    }
}
