package io.github.fate_grand_automata.ui.log

import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.scripts.capture.AccountDelta
import io.github.fate_grand_automata.scripts.capture.ChangeSource
import io.github.fate_grand_automata.scripts.capture.OutsidePeriod
import java.util.TimeZone
import kotlin.test.*

class LogResourceSetTest {
    private fun out(id: String, vararg items: Pair<Long, Long>) = CaptureLogItem(outside = outsideFixture(id).copy(period = OutsidePeriod("a", 1)
        .add(ChangeSource.of("present/receive"), AccountDelta(items = items.toMap()), 2)))
    private val a = out("a", 1L to 1L)
    private val b = out("b", 2L to 1L)
    private val c = out("c", 3L to 1L)
    private val ab = out("ab", 1L to 1L, 2L to 1L)
    private val zone = TimeZone.getTimeZone("Asia/Seoul")
    private fun filter(resources: Set<String>) = filterCaptureLogs(listOf(a, b, c, ab), LogTimeFilter.All, resources, 0, zone)

    @Test fun emptySetMatchesEverythingAndASetMatchesAnyMember() {
        assertEquals(listOf(a, b, c, ab), filter(emptySet()))
        assertEquals(listOf(a, ab), filter(setOf("2:1")))
        assertEquals(listOf(a, b, ab), filter(setOf("2:1", "2:2")))
        assertEquals(listOf(c), filter(setOf("2:3", "2:99")))
    }

    @Test fun keysMissingFromTheAvailableListAreDropped() {
        assertEquals(setOf("2:1"), availableLogResources(setOf("2:1", "2:9"), logResources(listOf(a, b))))
        assertEquals(emptySet(), availableLogResources(setOf("2:9"), logResources(listOf(a, b))))
    }

    @Test fun defaultStateIsEmptyAndSummaryListsUpToThreeNames() {
        assertEquals(LogFilterState(), LogFilterState.DEFAULT)
        assertEquals("A · B · C" to 0, resourceSummaryParts(listOf("A", "B", "C")))
        assertEquals("A · B" to 2, resourceSummaryParts(listOf("A", "B", "C", "D")))
    }
}
