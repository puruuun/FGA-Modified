package io.github.fate_grand_automata.widget

import android.app.Application
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.battlelog.BattleLogEntry
import io.github.fate_grand_automata.battlelog.FileBattleLogStorage
import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.capture.CaptureLogItem
import io.github.fate_grand_automata.capture.battleLogSummary
import io.github.fate_grand_automata.capture.mixedCaptureLogs
import io.github.fate_grand_automata.capture.outsideFixture
import io.github.fate_grand_automata.capture.outsideLogSummary
import io.github.fate_grand_automata.prefs.core.WidgetDisplayStyle
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #304: the account info + recent logs widget. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "ko")
class WidgetRecentLogsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val now = 1_800_000_000L
    private val originalLogsLoader = ApWidgetRenderer.logsLoader
    private val originalLoader = ApWidgetRenderer.loader

    @After fun restore() {
        ApWidgetRenderer.logsLoader = originalLogsLoader
        ApWidgetRenderer.loader = originalLoader
    }

    private fun battle(id: String, endedAt: Long, account: String? = "계정A"): BattleLogEntry =
        battleLogFixture(id).copy(endedAt = endedAt, accountName = account)

    private fun outside(id: String, endedAt: Long) = outsideFixture(id, endedAt)

    private val state = ApWidgetState.Data(listOf(ApWidgetModel("7001", "칼데아", 57, 142, now + 3600, now - 60)))

    private fun host(
        items: List<CaptureLogItem>?, width: Int = 272, height: Int = 128, settings: WidgetSettings = WidgetSettings(showLogButton = false),
        state: ApWidgetState = this.state,
    ): AppWidgetHostView {
        val views = buildApWidgetViews(context, 31, state, settings, false, width, now = now, collecting = false, logAvailable = false,
            heightDp = height, logs = items)
        val host = AppWidgetHostView(context)
        host.addView(views.apply(context, host))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return host
    }

    private fun cards(host: View): List<View> = host.findViewById<ViewGroup>(R.id.ap_logs_cards).let { g -> (0 until g.childCount).map(g::getChildAt) }
    private fun text(view: View, id: Int) = view.findViewById<TextView>(id).text.toString()
    private fun visible(view: View, id: Int) = view.findViewById<View>(id).visibility == View.VISIBLE

    private fun items(n: Int) = (1..n).map { CaptureLogItem(battle = battle("b$it", 1_793_750_000_000 + it * 1000L)) }

    // --- model ----------------------------------------------------------------------------------

    @Test fun battleAndOutsideLogsAreMixedAndOrderedLikeTheLogScreen() {
        val battles = listOf(battle("old", 1_000), battle("new", 5_000))
        val closed = listOf(outside("mid", 3_000))
        val open = listOf(outside("live", 4_000))
        val shown = widgetLogItems(battles, closed, open)
        assertEquals(mixedCaptureLogs(battles, closed, open).map { it.id }, shown.map { it.id })
        // An open period leads, then newest end time first.
        assertEquals(listOf("outside:live", "new", "outside:mid", "old"), shown.map { it.id })
        assertTrue(shown.first().inProgress)
    }

    @Test fun anOpenPeriodAlsoSavedAsClosedIsShownOnce() {
        val same = outside("dup", 3_000)
        assertEquals(listOf("outside:dup"), widgetLogItems(emptyList(), listOf(same), listOf(same)).map { it.id })
    }

    @Test fun onlyTheNewestItemsAreKept() {
        val many = (1..20).map { battle("b$it", it * 1000L) }
        val shown = widgetLogItems(many, emptyList(), emptyList(), limit = 3)
        assertEquals(listOf("b20", "b19", "b18"), shown.map { it.id })
    }

    @Test fun cardTextIsTheLogListsSummary() {
        val b = battle("b1", 1_000)
        val card = widgetLogCard(context, CaptureLogItem(battle = b))
        assertEquals(b.configName, card.title)
        assertEquals("계정A", card.account)
        assertEquals(battleLogSummary(context, b), card.summary)
        assertFalse(card.outside)
        assertEquals("b1", card.id)
    }

    @Test fun outsideCardHasTheOutsideTitleAndAnOpenPeriodIsMarkedInProgress() {
        val o = outside("o1", 1_793_754_000_000)
        val closed = widgetLogCard(context, CaptureLogItem(outside = o))
        val open = widgetLogCard(context, CaptureLogItem(outside = o, inProgress = true))
        assertEquals("스크립트 밖 변화", closed.title)
        assertTrue(closed.outside)
        assertEquals("outside:o1", closed.id)
        assertEquals(outsideLogSummary(context, o, false), closed.summary)
        assertFalse(closed.summary.startsWith("진행 중 · "))
        assertEquals("진행 중 · " + closed.summary, open.summary)
    }

    @Test fun cardCountFollowsTheWidth() {
        assertEquals(1, widgetLogCardCount(183)) // the narrowest 3-cell launcher width: one card, never cramped
        assertEquals(2, widgetLogCardCount(214))
        assertEquals(2, widgetLogCardCount(220)) // 3x2 phone
        assertEquals(2, widgetLogCardCount(272)) // 4x2 phone
        assertEquals(5, widgetLogCardCount(560)) // tablet
        assertEquals(1, widgetLogCardCount(100)) // never fewer than one
        assertEquals(WIDGET_LOG_CARD_MAX, widgetLogCardCount(5000))
    }

    // --- rendering ------------------------------------------------------------------------------

    @Test fun phoneAndTabletWidthsShowAsManyCardsAsFit() {
        val all = items(8)
        assertEquals(2, cards(host(all, width = 220)).size)
        assertEquals(2, cards(host(all, width = 272)).size)
        assertEquals(5, cards(host(all, width = 560)).size)
        // Fewer items than fit: only those.
        assertEquals(1, cards(host(items(1), width = 560)).size)
    }

    @Test fun cardsShowIconTitleAccountAndSummary() {
        val b = battle("b1", 1_000)
        val o = outside("o1", 2_000_000_000_000)
        val host = host(listOf(CaptureLogItem(outside = o, inProgress = true), CaptureLogItem(battle = b)), width = 300)
        val (first, second) = cards(host)
        assertEquals("스크립트 밖 변화", text(first, R.id.ap_log_card_title))
        assertTrue(text(first, R.id.ap_log_card_summary).startsWith("진행 중 · "))
        assertEquals("샘플 계정", text(first, R.id.ap_log_card_account))
        assertEquals(b.configName, text(second, R.id.ap_log_card_title))
        assertEquals(battleLogSummary(context, b), text(second, R.id.ap_log_card_summary))
        assertEquals("계정A", text(second, R.id.ap_log_card_account))
        // Never wraps: every text is one line and ellipsizes.
        for (id in listOf(R.id.ap_log_card_title, R.id.ap_log_card_account, R.id.ap_log_card_summary)) {
            val view = second.findViewById<TextView>(id)
            assertEquals(1, view.maxLines)
            assertEquals(android.text.TextUtils.TruncateAt.END, view.ellipsize)
        }
        assertFalse(visible(host, R.id.ap_logs_empty))
    }

    @Test fun narrowCardsDropTheAccountNameAndWiderCardsKeepIt() {
        // 3x2 and 4x2 phone widths and the tablet: cards under 130 dp, so the title gets the room.
        for (width in listOf(220, 272, 560)) {
            val count = widgetLogCardCount(width)
            assertTrue(widgetLogCardWidthDp(width, count) < WIDGET_LOG_CARD_ACCOUNT_MIN_WIDTH_DP, "width $width")
            for (card in cards(host(items(8), width = width))) {
                assertEquals(View.GONE, card.findViewById<View>(R.id.ap_log_card_account).visibility, "width $width")
                // Icon, one-line ellipsized title and summary stay.
                assertEquals(1, card.findViewById<TextView>(R.id.ap_log_card_title).maxLines)
                assertTrue(visible(card, R.id.ap_log_card_icon))
            }
        }
        // Two cards at 300 dp are 137 dp each: the account name stays.
        assertTrue(widgetLogCardWidthDp(300, widgetLogCardCount(300)) >= WIDGET_LOG_CARD_ACCOUNT_MIN_WIDTH_DP)
        for (card in cards(host(items(8), width = 300))) assertTrue(visible(card, R.id.ap_log_card_account))
        // One card on a wide row is wide too.
        assertTrue(visible(cards(host(items(1), width = 272)).single(), R.id.ap_log_card_account))
    }

    @Test fun noLogsShowsTheEmptyText() {
        val host = host(emptyList())
        assertEquals(0, cards(host).size)
        assertTrue(visible(host, R.id.ap_logs_empty))
        assertEquals("로그 없음", text(host, R.id.ap_logs_empty))
        assertEquals(View.GONE, host.findViewById<View>(R.id.ap_logs_cards).visibility)
    }

    @Test fun theWidgetWithoutLogsHasNoLogRow() {
        val views = buildApWidgetViews(context, 32, state, WidgetSettings(showLogButton = false), false, 272, now = now,
            collecting = false, logAvailable = false)
        val host = AppWidgetHostView(context)
        host.addView(views.apply(context, host))
        assertNull(host.findViewById<View>(R.id.ap_logs))
    }

    @Test fun theTopRowKeepsTheApWidgetsViewsAndSettings() {
        val all = WidgetSettings(displayStyle = WidgetDisplayStyle.AllAccounts, showLogButton = false, showServer = true)
        val two = ApWidgetState.Data(listOf(
            ApWidgetModel("7001", "칼데아", 57, 142, now + 3600, now - 60, "KR"), ApWidgetModel("7002", "두번째", 10, 142, null, now - 60, "JP"),
        ))
        val allHost = host(items(2), width = 400, settings = all, state = two)
        assertEquals(2, allHost.findViewById<ViewGroup>(R.id.ap_all_cells).childCount)
        assertEquals(View.GONE, allHost.findViewById<View>(R.id.ap_flipper).visibility)
        assertEquals(2, cards(allHost).size)
        // Single-account style keeps the flipper and the service tile.
        val one = host(items(2))
        assertEquals(View.VISIBLE, one.findViewById<View>(R.id.ap_flipper).visibility)
        assertTrue(visible(one, R.id.ap_tile))
    }

    @Test fun noAccountDataStillShowsTheLogs() {
        val host = host(items(2), state = ApWidgetState.NoData)
        assertTrue(visible(host, R.id.ap_no_data))
        assertEquals(2, cards(host).size)
    }

    // --- taps -----------------------------------------------------------------------------------

    @Test fun cardTapOpensTheLogScreenWithThatEntrySelected() {
        val o = outside("o1", 2_000_000_000_000)
        val host = host(listOf(CaptureLogItem(outside = o), CaptureLogItem(battle = battle("b1", 1_000))), width = 272)
        val (outsideCard, battleCard) = cards(host)
        battleCard.performClick()
        val battleIntent = assertNotNull(shadowOf(context).nextStartedActivity)
        assertEquals("b1", battleIntent.getStringExtra(WidgetIntents.EXTRA_OPEN_LOG_ENTRY))
        outsideCard.performClick()
        val outsideIntent = assertNotNull(shadowOf(context).nextStartedActivity)
        assertEquals("outside:o1", outsideIntent.getStringExtra(WidgetIntents.EXTRA_OPEN_LOG_ENTRY))

        // The app side: the request carries the id once, and the extra is removed from the intent.
        val requests = WidgetLaunchRequests()
        requests.consume(battleIntent)
        assertFalse(battleIntent.hasExtra(WidgetIntents.EXTRA_OPEN_LOG_ENTRY))
        assertEquals(LogRequest("b1"), requests.takeLog())
        assertNull(requests.takeLog())
        requests.consume(outsideIntent)
        assertEquals(LogRequest("outside:o1"), requests.takeLog())
    }

    @Test fun tapElsewhereInTheLogRowOpensTheLogScreenAlone() {
        val host = host(items(2))
        host.findViewById<View>(R.id.ap_logs).performClick()
        val intent = assertNotNull(shadowOf(context).nextStartedActivity)
        assertNull(intent.getStringExtra(WidgetIntents.EXTRA_OPEN_LOG_ENTRY))
        val requests = WidgetLaunchRequests()
        requests.consume(intent)
        assertEquals(LogRequest(null), requests.takeLog())
    }

    @Test fun eachCardHasItsOwnIntentIdentity() {
        val a = WidgetIntents.openLogEntry(context, "b1")
        val b = WidgetIntents.openLogEntry(context, "outside:o1")
        assertFalse(a.filterEquals(b))
        assertFalse(a.filterEquals(WidgetIntents.openLogs(context)))
    }

    @Test fun aRestoredActivityDoesNotReplayALogRequest() {
        val requests = WidgetLaunchRequests()
        requests.consume(WidgetIntents.openLogEntry(context, "b1"), restored = true)
        assertNull(requests.takeLog())
    }

    // --- failure never crashes ------------------------------------------------------------------

    private fun place(id: Int, receiver: Class<*>) {
        shadowOf(AppWidgetManager.getInstance(context)).addBoundWidget(id, AppWidgetProviderInfo().apply {
            provider = ComponentName(context, receiver)
        })
    }

    @Test fun aFailingLogReadStillDrawsTheWidgetWithTheEmptyText(): Unit = runBlocking {
        place(41, ApWidgetLogsReceiver::class.java)
        ApWidgetRenderer.loader = { ApWidgetState.NoData }
        ApWidgetRenderer.logsLoader = { throw java.io.IOException("disk gone") }
        ApWidgetRenderer.updateLogsWidgets(context)
        val view = shadowOf(AppWidgetManager.getInstance(context)).getViewFor(41)
        assertNotNull(view)
        assertEquals("로그 없음", text(view, R.id.ap_logs_empty))
        assertTrue(visible(view, R.id.ap_logs_empty))
    }

    @Test fun unreadableLogFilesGiveNoItemsAndAreLeftUntouched(): Unit = runBlocking {
        val dir = context.filesDir
        File(dir, "battle-log").mkdirs()
        val index = File(dir, "battle-log/index.json").apply { writeText("{ not json") }
        val outsideFile = File(dir, "outside-log.json").apply { writeText("[[[") }
        assertEquals(emptyList(), loadWidgetLogItems(context))
        // Read only: nothing moved, rewritten or deleted.
        assertEquals("{ not json", index.readText())
        assertEquals("[[[", outsideFile.readText())
        assertEquals(listOf("outside-log.json"), dir.list()!!.filter { it.startsWith("outside-log") })
        index.delete()
        outsideFile.delete()
    }

    @Test fun storedLogsAreReadFromTheIndexAndTheOutsideFile(): Unit = runBlocking {
        val dir = context.filesDir
        FileBattleLogStorage(dir).writeIndex(listOf(battle("b1", 1_793_750_400_000), battle("b2", 1_793_750_000_000)))
        File(dir, "outside-log.json").writeText(kotlinx.serialization.json.Json.encodeToString(listOf(outside("o1", 1_793_754_000_000))))
        assertEquals(listOf("o1"), io.github.fate_grand_automata.capture.OutsideLogRepository.readStored(dir).map { it.id })
        val read = loadWidgetLogItems(context)
        // The battle index is read from disk; the outside log comes from memory when the app already loaded it.
        assertEquals(listOf("b1", "b2"), read.filterNot { it.id.startsWith("outside:") }.map { it.id })
        File(dir, "battle-log/index.json").delete()
        File(dir, "outside-log.json").delete()
    }

    @Test fun placedWidgetsOfBothKindsKeepTheRefreshWorkerAlive() {
        place(51, ApWidgetLogsReceiver::class.java)
        assertTrue(WidgetRefresh.hasApWidget(context))
        assertTrue(WidgetRefresh.hasLogsWidget(context))
    }

    // --- picker entry ---------------------------------------------------------------------------

    private fun provider(xml: Int): Map<String, Int> {
        val parser = context.resources.getXml(xml)
        while (parser.eventType != android.content.res.XmlResourceParser.START_TAG) parser.next()
        return (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeResourceValue(it, 0) }
    }

    @Test fun pickerEntryHasItsOwnPreviewNoDescriptionAndA3x2Default() {
        val info = provider(R.xml.ap_logs_widget_info)
        assertEquals(R.layout.widget_picker_account_logs, info["previewLayout"])
        assertEquals(R.drawable.widget_picker_account_logs_image, info["previewImage"])
        assertTrue("description" !in info)
        val parser = context.resources.getXml(R.xml.ap_logs_widget_info)
        while (parser.eventType != android.content.res.XmlResourceParser.START_TAG) parser.next()
        val ns = "http://schemas.android.com/apk/res/android"
        assertEquals(3, parser.getAttributeIntValue(ns, "targetCellWidth", 0))
        assertEquals(2, parser.getAttributeIntValue(ns, "targetCellHeight", 0))
        // resizeMode is a flag: horizontal (1) and vertical (2).
        assertEquals(3, parser.getAttributeIntValue(ns, "resizeMode", 0))
        assertEquals("계정 정보 + 최근 로그", context.getString(R.string.widget_ap_logs_name))
        // The preview shows the top row and two log cards.
        val view = android.view.LayoutInflater.from(context).inflate(R.layout.widget_picker_account_logs, null)
        fun texts(v: View): List<String> = (if (v is TextView) listOf(v.text.toString()) else emptyList()) +
            ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { texts(g.getChildAt(it)) } } ?: emptyList())
        val all = texts(view)
        assertEquals(2, all.count { it == context.getString(R.string.widget_preview_log_title) }, "$all")
        assertTrue("57 / 142" in all)
    }
}
