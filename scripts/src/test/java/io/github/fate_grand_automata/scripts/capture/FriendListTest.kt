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

    private fun inactive(amount: Int, unit: LoginUnit, vararg offsets: Pair<String, Long>, filterNow: Long = now) = FriendList.select(
        FriendRoster(friends = offsets.map { (id, ago) -> Friend(id, lastLogin = filterNow - ago, slots = listOf(slot(101, 1, 500))) }
            .associateBy { it.userId } + ("unknown" to Friend("unknown", slots = listOf(slot(101, 1, 500))))),
        FriendFilter(login = FriendLogin.Inactive, loginAmount = amount, loginUnit = unit), filterNow,
    ).map { it.friend.userId }.toSet()

    @Test fun inactiveBoundaryIsInclusiveForMinutesHoursAndDays() {
        for ((unit, seconds) in listOf(LoginUnit.Minutes to 60L, LoginUnit.Hours to 3_600L, LoginUnit.Days to 86_400L)) {
            assertEquals(seconds, unit.seconds)
            assertEquals(setOf("exact", "older"),
                inactive(5, unit, "exact" to 5 * seconds, "older" to 5 * seconds + 1, "newer" to 5 * seconds - 1))
        }
    }

    @Test fun unknownLoginIsNeverInactive() {
        assertEquals(emptySet(), inactive(1, LoginUnit.Minutes))
        assertEquals(setOf("a"), inactive(1, LoginUnit.Minutes, "a" to 600))
    }

    @Test fun inactiveDefaultsToThreeDays() {
        val filter = FriendFilter(login = FriendLogin.Inactive)
        assertEquals(3, filter.loginAmount)
        assertEquals(LoginUnit.Days, filter.loginUnit)
        assertEquals(3, FRIEND_INACTIVE_DEFAULT)
        assertEquals(setOf("2"), FriendList.select(roster, filter, now).map { it.friend.userId }.toSet())
    }

    @Test fun allLoginsPassWithoutALoginFilter() {
        assertEquals(6, FriendList.select(roster, FriendFilter(login = FriendLogin.All), now).size)
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
