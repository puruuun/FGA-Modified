package io.github.fate_grand_automata.widget

import android.app.Application
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The widget picker shows each provider's own preview, and nothing else. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "ko")
class WidgetPickerPreviewTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private fun texts(v: View): List<String> =
        (if (v is TextView) listOf(v.text.toString()) else emptyList()) +
            ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { texts(g.getChildAt(it)) } } ?: emptyList())

    private fun bars(v: View): Int =
        (if (v is ProgressBar) 1 else 0) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).sumOf { bars(g.getChildAt(it)) } } ?: 0)

    private fun inflate(layout: Int) = LayoutInflater.from(context).inflate(layout, null)

    /** Reads the provider info XML: the attribute resource ids of the root tag. */
    private fun provider(xml: Int): Map<String, Int> {
        val parser = context.resources.getXml(xml)
        while (parser.eventType != android.content.res.XmlResourceParser.START_TAG) parser.next()
        return (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeResourceValue(it, 0) }
    }

    @Test fun serviceEntryPreviewIsOnlyTheStartStopTile() {
        val view = inflate(R.layout.widget_picker_service)
        // Just the label of the tile: no account name, AP, full time, age or bar.
        assertEquals(listOf(context.getString(R.string.widget_service_off)), texts(view))
        assertEquals(0, bars(view))
    }

    @Test fun accountEntryPreviewIsTheOneAccountLook() {
        val view = inflate(R.layout.widget_picker_account)
        val texts = texts(view)
        assertTrue(context.getString(R.string.widget_preview_name) in texts, "$texts")
        assertTrue(context.getString(R.string.widget_age_now) in texts, "$texts")
        assertTrue("57 / 142" in texts, "$texts")
        assertTrue(context.getString(R.string.widget_preview_full_at) in texts, "$texts")
        assertTrue(context.getString(R.string.widget_service_off) in texts, "$texts")
        assertEquals(1, bars(view))
    }

    @Test fun eachProviderPointsAtItsOwnPreviewAndHasNoDescription() {
        val ap = provider(R.xml.ap_widget_info)
        val service = provider(R.xml.service_widget_info)
        assertEquals(R.layout.widget_picker_account, ap["previewLayout"])
        assertEquals(R.drawable.widget_picker_account_image, ap["previewImage"])
        assertEquals(R.layout.widget_picker_service, service["previewLayout"])
        assertEquals(R.drawable.widget_picker_service_image, service["previewImage"])
        assertTrue("description" !in ap && "description" !in service)
    }

    @Test fun accountEntryIsNamedAccountInfo() {
        assertEquals("계정 정보", context.getString(R.string.widget_ap_name))
    }
}
