package io.github.fate_grand_automata.widget

import android.app.Application
import android.appwidget.AppWidgetHostView
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.WidgetDisplayStyle
import io.github.fate_grand_automata.prefs.core.WidgetTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "ko")
class ApWidgetAllAccountsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val now = 1_800_000_000L

    private fun model(n: Int, ap: Long = 57, fullAt: Long? = now + 3600, age: Long = 600, server: String = "KR") = ApWidgetModel(
        accountId = "700$n", name = "계정$n", ap = ap, apMax = 142, fullAt = fullAt, dataTime = now - age, server = server,
    )

    private fun data(n: Int) = ApWidgetState.Data((1..n).map { model(it, age = 600L * it) })

    private val all = WidgetSettings(displayStyle = WidgetDisplayStyle.AllAccounts, showLogButton = false)

    private fun host(
        state: ApWidgetState, settings: WidgetSettings = all, width: Int = 400, logAvailable: Boolean = false, height: Int = 74,
    ): AppWidgetHostView {
        val views: RemoteViews = buildApWidgetViews(context, 21, state, settings, false, width, now = now, collecting = false, logAvailable = logAvailable, heightDp = height)
        val host = AppWidgetHostView(context)
        host.addView(views.apply(context, host))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return host
    }

    private fun cells(host: View): List<View> = host.findViewById<ViewGroup>(R.id.ap_all_cells).let { g -> (0 until g.childCount).map(g::getChildAt) }

    // --- settings -------------------------------------------------------------------------------

    @Test fun defaultsKeepTheOldLookExceptTheLogButton() {
        val defaults = WidgetSettings()
        assertEquals(WidgetDisplayStyle.OneAccount, defaults.displayStyle)
        assertTrue(defaults.showLogButton)
        assertTrue(defaults.showServiceButton)
        assertFalse(defaults.showServer)
    }

    @Test fun oldPreferencesWithoutTheNewKeysReadAsDefaults() {
        val prefs = context.getSharedPreferences("old_widget_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("widget_theme", "Light").putInt("widget_background_transparency", 30).commit()
        assertEquals(WidgetSettings(WidgetTheme.Light, 30), readWidgetSettings(prefs))
    }

    @Test fun storedNewValuesAreRead() {
        val prefs = context.getSharedPreferences("new_widget_prefs", Context.MODE_PRIVATE)
        val maker = PrefMaker(prefs)
        maker.enum("widget_display_style", WidgetDisplayStyle.OneAccount).set(WidgetDisplayStyle.AllAccounts)
        maker.bool("widget_show_service_button", true).set(false)
        maker.bool("widget_show_server", false).set(true)
        maker.bool("widget_show_log_button", true).set(false)
        val read = readWidgetSettings(prefs)
        assertEquals(WidgetDisplayStyle.AllAccounts, read.displayStyle)
        assertFalse(read.showServiceButton)
        assertTrue(read.showServer)
        assertFalse(read.showLogButton)
    }

    // --- how many cells fit ------------------------------------------------------------------------

    @Test fun cellCountFollowsTheWidthAndTheTiles() {
        // Phones share the age label's width with the tiles.
        assertEquals(1, allAccountsCellCount(150, showLog = false, showService = true, accountCount = 5))
        assertEquals(2, allAccountsCellCount(272, showLog = false, showService = true, accountCount = 5))
        assertEquals(3, allAccountsCellCount(350, showLog = false, showService = true, accountCount = 5))
        assertEquals(2, allAccountsCellCount(300, showLog = false, showService = true, accountCount = 5))
        assertEquals(1, allAccountsCellCount(272, showLog = true, showService = true, accountCount = 5))
        assertEquals(3, allAccountsCellCount(400, showLog = false, showService = true, accountCount = 5))
        assertEquals(4, allAccountsCellCount(400, showLog = false, showService = false, accountCount = 5))
        assertEquals(5, allAccountsCellCount(900, showLog = true, showService = true, accountCount = 5))
        // Never more than there are accounts, never fewer than one.
        assertEquals(2, allAccountsCellCount(900, showLog = true, showService = true, accountCount = 2))
        assertEquals(1, allAccountsCellCount(10, showLog = true, showService = true, accountCount = 3))
    }

    @Test fun oneTwoThreeAndFiveAccountsAtSeveralWidths() {
        val cases = listOf(
            // accounts, width, expected cells (service tile on, log off)
            Triple(1, 250, 1), Triple(1, 600, 1),
            Triple(2, 250, 2), Triple(2, 300, 2), Triple(2, 600, 2),
            Triple(3, 300, 2), Triple(3, 400, 3), Triple(3, 700, 3),
            Triple(5, 272, 2), Triple(5, 350, 3), Triple(5, 400, 3), Triple(5, 500, 4), Triple(5, 700, 5),
        )
        for ((n, width, expected) in cases) {
            val host = host(data(n), width = width)
            assertEquals(expected, cells(host).size, "accounts=$n width=$width")
            assertEquals(View.VISIBLE, host.findViewById<View>(R.id.ap_all).visibility)
            // The shown cells are the first ones, in model order.
            assertEquals((1..expected).map { "계정$it" }, cells(host).map { it.findViewById<TextView>(R.id.ap_all_name).text.toString() })
        }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [36], qualifiers = "ko-xhdpi")
    @Test fun threeLinesFitAtTheMinimumAndTypicalOneRowHeights() {
        val models = data(5).models.map { it.copy(name = "계정이름") }
        for (height in listOf(40, 56, 65, 66, 73, 74)) {
            for (width in listOf(272, 350, 560)) {
                for (log in listOf(false, true)) {
                    val host = host(ApWidgetState.Data(models), all.copy(showLogButton = log), width, log, height)
                    val density = context.resources.displayMetrics.density
                    val w = (width * density).toInt()
                    val h = (height * density).toInt()
                    host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                    host.layout(0, 0, w, h)
                    for (id in listOf(R.id.ap_tile_circle, R.id.ap_log_circle, R.id.ap_compact_age)) {
                        val view = host.findViewById<View>(id)
                        if (view.isShown) {
                            val visible = android.graphics.Rect()
                            assertTrue(view.getGlobalVisibleRect(visible), "width=$width height=$height id=$id")
                            assertEquals(view.height, visible.height(), "Clipped control at width=$width height=$height id=$id")
                        }
                    }
                    for (cell in cells(host)) {
                        for (id in listOf(R.id.ap_all_name, R.id.ap_all_ap, R.id.ap_all_full)) {
                            val text = cell.findViewById<TextView>(id)
                            val bounds = android.graphics.Rect(0, 0, text.width, text.height)
                            host.offsetDescendantRectToMyCoords(text, bounds)
                            val label = "width=$width height=$height log=$log text=${text.text} layout=${text.layout.height} view=${text.height} padding=${text.paddingTop},${text.paddingBottom}"
                            assertTrue(bounds.top >= 0 && bounds.bottom <= h, label)
                            assertTrue(text.layout.height <= text.height - text.paddingTop - text.paddingBottom, label)
                            assertEquals(0, text.layout.getEllipsisCount(0), label)
                        }
                    }
                }
            }
        }
    }

    @Test fun reapplyingTheOneAccountStyleRestoresItsPaddingAndButtonLabels() {
        val host = host(data(3), width = 272, height = 40)
        val root = host.getChildAt(0)
        assertEquals(View.GONE, root.findViewById<View>(R.id.ap_tile_label).visibility)
        buildApWidgetViews(context, 21, data(3), WidgetSettings(showLogButton = false), false, 272, now = now)
            .reapply(context, root)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.ap_tile_label).visibility)
        assertEquals(View.GONE, root.findViewById<View>(R.id.ap_compact_age).visibility)
        assertEquals((8 * context.resources.displayMetrics.density).toInt(), root.paddingTop)
    }

    // --- cell contents --------------------------------------------------------------------------------

    @Test fun cellShowsNameApAndFullTimeWithoutBarOrNavigation() {
        val host = host(data(2), width = 400)
        val cell = cells(host).first()
        assertEquals("계정1", cell.findViewById<TextView>(R.id.ap_all_name).text.toString())
        assertEquals("57 / 142", cell.findViewById<TextView>(R.id.ap_all_ap).text.toString())
        assertTrue(cell.findViewById<TextView>(R.id.ap_all_full).text.toString().startsWith("완충 "))
        assertEquals(View.GONE, host.findViewById<View>(R.id.ap_nav).visibility)
        assertEquals(View.GONE, host.findViewById<View>(R.id.ap_flipper).visibility)
        assertEquals(View.GONE, host.findViewById<View>(R.id.ap_no_data).visibility)
        assertNull(cell.findViewById<View>(R.id.ap_item_bar_dark))
    }

    @Test fun alreadyFullAccountsUseTheFullText() {
        val state = ApWidgetState.Data(listOf(model(1, ap = 142, fullAt = null)))
        val cell = cells(host(state)).single()
        assertEquals("AP 가득 참", cell.findViewById<TextView>(R.id.ap_all_full).text.toString())
    }

    @Test fun oneAgeLabelUsesTheOldestShownAccount() {
        // Accounts are 10, 20 and 30 minutes old; with room for two cells the oldest shown is 20 minutes.
        val two = host(data(3), width = 300)
        assertEquals(2, cells(two).size)
        assertEquals("20분 전", two.findViewById<TextView>(R.id.ap_compact_age).text.toString())
        assertEquals(View.GONE, two.findViewById<View>(R.id.ap_all_age).visibility)
        val three = host(data(3), width = 400)
        assertEquals("30분 전", three.findViewById<TextView>(R.id.ap_all_age).text.toString())
        assertEquals(View.GONE, three.findViewById<View>(R.id.ap_compact_age).visibility)
    }

    @Test fun noButtonsKeepsTheAgeBesideTheAccounts() {
        val host = host(data(3), all.copy(showServiceButton = false), width = 272)
        assertEquals(2, cells(host).size)
        assertEquals(View.VISIBLE, host.findViewById<View>(R.id.ap_all_age).visibility)
        assertEquals("20분 전", host.findViewById<TextView>(R.id.ap_all_age).text.toString())
        assertEquals(View.GONE, host.findViewById<View>(R.id.ap_compact_age).visibility)
    }

    @Test fun serverBadgeShowsOnlyWhenEnabled() {
        val state = ApWidgetState.Data(listOf(model(1, server = "KR"), model(2, server = "JP")))
        val off = cells(host(state, all.copy(showServer = false)))
        assertTrue(off.all { it.findViewById<View>(R.id.ap_all_server).visibility == View.GONE })
        val on = cells(host(state, all.copy(showServer = true)))
        assertEquals(listOf("KR", "JP"), on.map { it.findViewById<TextView>(R.id.ap_all_server).text.toString() })
        assertTrue(on.all { it.findViewById<View>(R.id.ap_all_server).visibility == View.VISIBLE })
    }

    @Test fun serverBadgeAlsoWorksInTheOneAccountStyle() {
        val state = ApWidgetState.Data(listOf(model(1, server = "JP")))
        val flipper = findFlipper(host(state, WidgetSettings(showServer = true, showLogButton = false)))!!
        val item = flipper.adapter.getView(0, null, flipper)
        assertEquals(View.VISIBLE, item.findViewById<View>(R.id.ap_item_server).visibility)
        assertEquals("JP", item.findViewById<TextView>(R.id.ap_item_server).text.toString())
        val off = findFlipper(host(state, WidgetSettings(showLogButton = false)))!!
        assertEquals(View.GONE, off.adapter.getView(0, null, off).findViewById<View>(R.id.ap_item_server).visibility)
    }

    private fun findFlipper(v: View): android.widget.AdapterViewFlipper? =
        if (v is android.widget.AdapterViewFlipper) v
        else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { findFlipper(g.getChildAt(it)) } }

    @Test fun tappingACellOpensThatAccount() {
        val host = host(data(2), width = 400)
        cells(host)[1].performClick()
        val intent = shadowOf(context).nextStartedActivity
        assertEquals("7002", intent.getStringExtra(WidgetIntents.EXTRA_OPEN_ACCOUNT))
    }

    // --- tiles ---------------------------------------------------------------------------------------

    @Test fun serviceTileCanBeHiddenInBothStyles() {
        for (style in WidgetDisplayStyle.entries) {
            val base = WidgetSettings(displayStyle = style, showLogButton = false)
            assertEquals(View.VISIBLE, host(data(2), base).findViewById<View>(R.id.ap_tile).visibility, "$style")
            assertEquals(View.GONE, host(data(2), base.copy(showServiceButton = false)).findViewById<View>(R.id.ap_tile).visibility, "$style")
        }
    }

    @Test fun hidingTheServiceTileGivesTheRowRoomForAnotherCell() {
        assertEquals(3, cells(host(data(5), all.copy(showServiceButton = true), width = 400)).size)
        assertEquals(4, cells(host(data(5), all.copy(showServiceButton = false), width = 400)).size)
    }

    @Test fun logTileKeepsItsWidthRule() {
        val settings = all.copy(showLogButton = true)
        assertEquals(View.VISIBLE, host(data(2), settings, width = 300, logAvailable = true).findViewById<View>(R.id.ap_log_tile).visibility)
        assertEquals(View.GONE, host(data(2), settings, width = 200, logAvailable = true).findViewById<View>(R.id.ap_log_tile).visibility)
        assertEquals(View.GONE, host(data(2), settings, width = 300, logAvailable = false).findViewById<View>(R.id.ap_log_tile).visibility)
        // A visible log tile takes room from the cells.
        assertEquals(2, cells(host(data(5), settings, width = 400, logAvailable = true)).size)
    }

    @Test fun noDataStillShowsTheMessageInTheAllAccountsStyle() {
        val host = host(ApWidgetState.NoData)
        assertEquals(View.VISIBLE, host.findViewById<View>(R.id.ap_no_data).visibility)
        assertEquals(View.GONE, host.findViewById<View>(R.id.ap_all).visibility)
        assertEquals(View.GONE, host.findViewById<View>(R.id.ap_nav).visibility)
    }

    @Test fun oneAccountStyleHidesTheRowAndKeepsTheNavigation() {
        val host = host(data(3), WidgetSettings(showLogButton = false))
        assertEquals(View.GONE, host.findViewById<View>(R.id.ap_all).visibility)
        assertEquals(View.VISIBLE, host.findViewById<View>(R.id.ap_nav).visibility)
        assertEquals(View.VISIBLE, host.findViewById<View>(R.id.ap_tile).visibility)
    }

    @Test fun logTileUsesTheKeyIcon() {
        val host = host(data(2), all.copy(showLogButton = true), width = 400, logAvailable = true)
        val icon = host.findViewById<android.widget.ImageView>(R.id.ap_log_icon)
        assertEquals(
            R.drawable.ic_key,
            shadowOf(icon.drawable).createdFromResId,
        )
    }

    // --- change observer -------------------------------------------------------------------------------

    @Test fun badgeTextHelper() {
        assertEquals("JP", serverBadgeText(true, " jp "))
        assertNull(serverBadgeText(false, "KR"))
        assertNull(serverBadgeText(true, ""))
    }
}
