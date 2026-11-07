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
        assertEquals(listOf("1", "3"), ids(FriendFilter(login = FriendLogin.After, loginDayStart = now - 7 * day)).map { it.substringBefore(':') }.distinct())
        assertEquals(listOf("2"), ids(FriendFilter(login = FriendLogin.Before, loginDayStart = now - 7 * day)).map { it.substringBefore(':') }.distinct())
    }

    @Test fun loginBoundaryIsTheSelectedLocalMidnightAndUnknownIsExcluded() {
        val picker = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear(); set(2026, 10, 7)
        }.timeInMillis
        for (zone in listOf("Asia/Seoul", "America/New_York")) {
            val midnight = friendLocalMidnight(picker, java.util.TimeZone.getTimeZone(zone))
            val local = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone(zone)).apply { timeInMillis = midnight * 1000 }
            assertEquals(7, local.get(java.util.Calendar.DAY_OF_MONTH))
            assertEquals(0, local.get(java.util.Calendar.HOUR_OF_DAY))
            val sample = FriendRoster(friends = listOf(-1L, 0L, 1L).mapIndexed { index, offset ->
                Friend("$index", lastLogin = midnight + offset, slots = listOf(slot(101, 1, 500)))
            }.associateBy { it.userId } + ("unknown" to Friend("unknown", slots = listOf(slot(101, 1, 500)))))
            assertEquals(setOf("1", "2"), FriendList.select(sample, FriendFilter(login = FriendLogin.After, loginDayStart = midnight), now).map { it.friend.userId }.toSet())
            assertEquals(listOf("0"), FriendList.select(sample, FriendFilter(login = FriendLogin.Before, loginDayStart = midnight), now).map { it.friend.userId })
        }
    }

    @Test fun cePickerPinsPresentBondCesInNameOrderThenOtherCes() {
        val names = mapOf(FRIEND_TEATIME_CE_ID to "Teatime", 9407480L to "Zulu bond", 9407740L to "Alpha bond",
            1L to "Zulu", 2L to "Alpha", 3L to "Absent", 9407850L to "Absent bond")
        val counts = mapOf(1L to 2, 9407480L to 3, FRIEND_TEATIME_CE_ID to 4, 2L to 1, 9407740L to 2)
        val options = friendCeOptions(counts, names)
        assertEquals(listOf(FRIEND_TEATIME_CE_ID, 9407740L, 9407480L, 2L, 1L), options.map { it.id })
        assertEquals(listOf(4, 2, 3, 1, 2), options.map { it.count })
    }

    @Test fun servantSearchIsCaseInsensitiveSubstringAndEmptyListsAllByName() {
        val counts = mapOf(1L to 2, 2L to 1, 3L to 3)
        val names = mapOf(1L to "샘플 Alpha", 2L to "베타", 3L to "Alpha 서번트")
        assertEquals(listOf(3L, 1L), friendServantOptions(counts, names, "ALp").map { it.id })
        assertEquals(listOf(3L, 2L, 1L), friendServantOptions(counts, names).map { it.id })
        assertTrue(friendServantOptions(counts, names, "missing").isEmpty())
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
