package io.github.fate_grand_automata.scripts.capture

import kotlin.test.*

class FriendListTest {
    private val day = 86_400L
    private val now = 10 * day

    private fun slot(deck: Long, cls: Int, svt: Long, ce: Long = 0, limit: Int = 0) =
        FriendSlot(deckId = deck, classId = cls, svtId = svt, ceId = ce, ceLimitCount = limit)

    private val roster = FriendRoster(now, listOf(
        Friend("1", "A", 100, "111", listOf(101, 102, 103), listOf(201, 202, 203), now - 60,
            listOf(slot(101, 1, 500, 9000, 4), slot(103, 2, 600, 9001, 3), slot(202, 5, 500, 9000, 4))),
        Friend("2", "B", 100, "222", listOf(101, 102, 103), listOf(201, 202, 203), now - 8 * day,
            listOf(slot(102, 1, 500, 9000, 4), slot(103, 3, 700))),
        Friend("3", "C", 100, null, listOf(101, 102, 103), listOf(201, 202, 203), now - 2 * day,
            listOf(slot(101, 1, 600, 9001, 4))),
        Friend("4", "D", 100, null, listOf(101, 102, 103), listOf(201, 202, 203), 0, listOf(slot(101, 1, 500))),
    ).associateBy { it.userId })

    private fun ids(filter: FriendFilter) = FriendList.select(roster, filter, now).map { it.friend.userId + ":" + it.slot.svtId }

    @Test fun deckToggleSplitsMainAndEvent() {
        assertEquals(6, FriendList.select(roster, FriendFilter(), now).size)
        assertEquals(listOf("1:500"), ids(FriendFilter(event = true)))
    }

    @Test fun sortedByNewestLoginThenFriendDeckClass() {
        assertEquals(listOf("1:500", "1:600", "3:600", "2:500", "2:700", "4:500"), ids(FriendFilter()))
    }

    @Test fun servantAndCeFilters() {
        assertEquals(listOf("1:500", "2:500", "4:500"), ids(FriendFilter(servantId = 500)))
        assertEquals(listOf("1:600", "3:600"), ids(FriendFilter(ceId = 9001)))
        assertEquals(listOf("3:600"), ids(FriendFilter(servantId = 600, ceId = 9001, maxLimitBreak = true)))
    }

    @Test fun maxLimitBreakNeedsALimitBrokenCe() {
        assertEquals(listOf("1:500", "3:600", "2:500"), ids(FriendFilter(maxLimitBreak = true)))
    }

    @Test fun loginRanges() {
        assertEquals(listOf("1", "3"), ids(FriendFilter(login = FriendLogin.Within7Days)).map { it.substringBefore(':') }.distinct())
        assertEquals(listOf("2"), ids(FriendFilter(login = FriendLogin.Over7Days)).map { it.substringBefore(':') }.distinct())
    }

    @Test fun deckIndexIsOneBased() {
        val rows = FriendList.rows(roster, false).associate { (it.friend.userId + ":" + it.slot.deckId) to it.deckIndex }
        assertEquals(1, rows["1:101"])
        assertEquals(3, rows["1:103"])
        val event = FriendList.rows(roster, true).single()
        assertEquals(2, event.deckIndex)
    }

    @Test fun pickerCountsCoverTheChosenDeckOnly() {
        val main = FriendList.rows(roster, false)
        assertEquals(mapOf(500L to 3, 600L to 2, 700L to 1), FriendList.servantCounts(main))
        assertEquals(mapOf(9000L to 2, 9001L to 2), FriendList.ceCounts(main))
        assertEquals(mapOf(500L to 1), FriendList.servantCounts(FriendList.rows(roster, true)))
    }
}
