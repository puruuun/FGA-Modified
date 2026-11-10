package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.*
import kotlin.test.*

/** Synthetic data, including independent normal/event and Grand equipment. */
class FriendGrandTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private val grand = """{"grandGraphId":10002,"svtId":500,"lv":120,"treasureDeviceLv":5,
        "skillLv1":10,"skillLv2":9,"skillLv3":8,
        "appendPassiveSkill":[{"skillId":3001000,"skillLv":5},{"skillId":3034000,"skillLv":4}],
        "equipTarget1":{"svtId":9000,"limitCount":4,"lv":100},
        "equipTarget2":{"svtId":9300000},"equipTarget3":{"svtId":9001,"limitCount":2,"lv":60},
        "script":{"equipTarget2SkillChange":1}}"""
    private fun top(hash: String?) = obj("""{"cache":{"updated":{"otherUserGame":[{
        "userId":"1","userName":"Alpha","userSvtLeaderHash":[{"svtId":600,"supportDeckId":101,"classId":1}]
        """ + (hash?.let { ""","userSvtGrandHash":$it""" } ?: "") + """}]},"serverTime":42}}""")
    private fun follower(hash: String?) = obj("""{"cache":{"updated":{"userFollower":[{"followerInfo":[{
        "type":1,"userId":"1"""" + (hash?.let { ""","userSvtGrandHash":$it""" } ?: "") + """}]}]}}}""")

    @Test fun arrayAndMapKeepEveryGrandFieldAndStreamingMatches() {
        for (hash in listOf("[$grand]", """{"seat":$grand}""")) {
            val body = top(hash)
            val roster = assertNotNull(FriendRoster.fromFriendTop(body))
            val slot = roster.friends.getValue("1").grandSlots.single()
            assertEquals(10002, slot.seat)
            assertEquals(500, slot.svtId)
            assertEquals(120, slot.lv)
            assertEquals(5, slot.np)
            assertEquals(listOf(10, 9, 8), slot.skills)
            assertEquals(listOf(5, null, null, null, 4), slot.appends)
            assertEquals(FriendCe(9000, 4, 100), slot.ce1)
            assertEquals(FriendCe(9001, 2, 60), slot.ce3)
            assertEquals(9300000, slot.bondCeId)
            assertEquals(FriendBondEffect.NpCharge, slot.bondEffect)
            assertEquals(600, roster.friends.getValue("1").slots.single().svtId)
            assertEquals(roster, FriendRoster.fromJson(roster.toJson()))
            assertEquals(roster, FriendRoster.fromStream(roster.toJson().byteInputStream()))
            val streamed = FriendTopStream.parse(body.toString().byteInputStream())
            assertEquals(roster, FriendRoster.fromFriendTop(streamed))
            assertEquals(body["cache"]!!.jsonObject["updated"], streamed["cache"]!!.jsonObject["updated"])
        }
    }

    @Test fun bondEffectsRequireAnEquippedBondCe() {
        val cases = listOf(
            grand to FriendBondEffect.NpCharge,
            grand.replace(""""equipTarget2SkillChange":1""", """"other":1""") to FriendBondEffect.Base,
            grand.replace(""""svtId":9300000""", """"svtId":0""") to FriendBondEffect.None,
            grand.replace(""""equipTarget2":{"svtId":9300000},""", "") to FriendBondEffect.None,
        )
        for ((row, expected) in cases) {
            val slot = FriendRoster.fromFriendTop(top("[$row]"))!!.friends.getValue("1").grandSlots.single()
            assertEquals(expected, slot.bondEffect)
            assertEquals(expected == FriendBondEffect.None, slot.bondCeId == null)
        }
    }

    @Test fun absentHashAndOldJsonLoadEmptyGrandFormation() {
        val parsed = FriendRoster.fromFriendTop(top(null))!!.friends.getValue("1")
        assertTrue(parsed.grandSlots.isEmpty())
        assertEquals(600, parsed.slots.single().svtId)
        val old = FriendRoster.fromJson("""{"friends":{"1":{"userId":"1","slots":[{"deckId":101,"classId":1,"svtId":600}]}}}""")
        assertTrue(old.friends.getValue("1").grandSlots.isEmpty())
    }

    @Test fun followerReplacesOnlyWhenHashPresentIncludingExplicitEmpty() {
        val roster = FriendRoster.fromFriendTop(top("[$grand]"))!!
        assertEquals(roster, FriendRoster.mergeFollowers(roster, follower(null)))
        for (hash in listOf("[]", "{}")) {
            val cleared = FriendRoster.mergeFollowers(roster, follower(hash))
            assertTrue(cleared.friends.getValue("1").grandSlots.isEmpty())
            assertEquals(roster.friends.getValue("1").slots, cleared.friends.getValue("1").slots)
        }
        val changed = grand.replace(""""lv":120""", """"lv":100""")
        for (hash in listOf("[$changed]", """{"seat":$changed}""")) {
            val fresh = FriendRoster.update(roster, "follower/list", follower(hash), 50)!!
            assertEquals(100, fresh.friends.getValue("1").grandSlots.single().lv)
            assertEquals(roster.friends.getValue("1").slots, fresh.friends.getValue("1").slots)
        }
    }

    private val now = 20_000_000L
    private val roster = FriendRoster(friends = listOf(
        Friend("2", "Beta", lastLogin = now - 100 * 86_400, mainDeckIds = listOf(101), eventDeckIds = listOf(201),
            slots = listOf(
                FriendSlot(101, 1, 500, lv = 90, ceId = 9000, ceLimitCount = 4),
                FriendSlot(201, 1, 501, lv = 120, ceId = 9001, ceLimitCount = 0)),
            grandSlots = listOf(FriendGrandSlot(10002, 500, lv = 100, ce1 = FriendCe(9000, 4), ce3 = FriendCe(9001, 1), bondCeId = 9300000))),
        Friend("1", "aLPHa Friend", lastLogin = now - 60, mainDeckIds = listOf(101), eventDeckIds = listOf(201),
            slots = listOf(
                FriendSlot(101, 1, 502, lv = 79, ceId = 9001, ceLimitCount = 0),
                FriendSlot(101, 2, 503, lv = 80),
                FriendSlot(201, 1, 504, lv = 100, ceId = 9000, ceLimitCount = 4)),
            grandSlots = listOf(
                FriendGrandSlot(10003, 505, lv = 120, ce3 = FriendCe(9002, 4)),
                FriendGrandSlot(10001, 506, lv = 90, ce1 = FriendCe(9001, 0)))),
    ).associateBy { it.userId })
    private fun selected(filter: FriendFilter) = FriendList.select(roster, filter, now).map { it.slot.svtId }

    @Test fun nameIsCaseInsensitiveSubstringInEveryFormation() {
        for (formation in FriendFormation.entries) {
            val rows = FriendList.select(roster, FriendFilter(formation = formation, friendName = "LPha"), now)
            assertTrue(rows.isNotEmpty())
            assertTrue(rows.all { it.friend.userId == "1" })
            assertTrue(selected(FriendFilter(formation = formation, friendName = "absent")).isEmpty())
        }
    }

    @Test fun multiCeIsOrAndBondCeNeverCounts() {
        assertEquals(listOf(502L, 500L), selected(FriendFilter(ceIds = setOf(9000, 9001))))
        assertEquals(listOf(504L, 501L), selected(FriendFilter(formation = FriendFormation.Event, ceIds = setOf(9000, 9001))))
        val grandFilter = FriendFilter(formation = FriendFormation.Grand)
        assertEquals(listOf(506L, 505L, 500L), selected(grandFilter.copy(ceIds = setOf(9001, 9002))))
        assertTrue(selected(grandFilter.copy(ceIds = setOf(9300000))).isEmpty())
        assertFalse(9300000L in FriendList.ceCounts(FriendList.rows(roster, FriendFormation.Grand)))
    }

    @Test fun limitStatesApplyToPickedCesOrAnyRowCeInEveryFormation() {
        for (formation in FriendFormation.entries) {
            for (limit in FriendCeLimit.entries) {
                for (picked in listOf(emptySet(), setOf(9000L), setOf(9001L), setOf(9000L, 9001L), setOf(999L))) {
                    val filter = FriendFilter(formation = formation, ceIds = picked, ceLimit = limit)
                    val expected = FriendList.rows(roster, formation).filter { row ->
                        val candidates = row.ces.filter { picked.isEmpty() || it.id in picked }
                        (picked.isEmpty() || candidates.isNotEmpty()) && (limit == FriendCeLimit.All ||
                            candidates.any { (it.limitCount == 4) == (limit == FriendCeLimit.Max) })
                    }.map { it.slot.svtId }.toSet()
                    assertEquals(expected, selected(filter).toSet(), "$formation $limit $picked")
                }
            }
        }
        val grandFilter = FriendFilter(formation = FriendFormation.Grand, ceIds = setOf(9001), ceLimit = FriendCeLimit.Max)
        assertTrue(selected(grandFilter).isEmpty()) // Grand 500 has an unrelated max CE1.
        assertEquals(listOf(506L, 500L), selected(grandFilter.copy(ceLimit = FriendCeLimit.NotMax)))
        assertEquals(listOf(505L, 500L), selected(grandFilter.copy(ceIds = emptySet())))
    }

    @Test fun levelsUseInclusiveThresholdsInEveryFormation() {
        for (formation in FriendFormation.entries) for (level in listOf(0, 80, 90, 100, 120)) {
            val rows = FriendList.rows(roster, formation)
            assertEquals(rows.filter { it.slot.lv >= level }.map { it.slot.svtId }.toSet(),
                selected(FriendFilter(formation = formation, minLevel = level)).toSet())
        }
        assertEquals(listOf(503L, 500L), selected(FriendFilter(minLevel = 80)))
        assertEquals(listOf(500L), selected(FriendFilter(minLevel = 90)))
        assertTrue(selected(FriendFilter(minLevel = 100)).isEmpty())
    }

    @Test fun levelAtMostUsesInclusiveUpperThresholdsInEveryFormation() {
        for (formation in FriendFormation.entries) for (level in listOf(0, 80, 90, 100, 120)) {
            val rows = FriendList.rows(roster, formation)
            val expected = rows.filter { level == 0 || it.slot.lv <= level }.map { it.slot.svtId }.toSet()
            assertEquals(expected, selected(FriendFilter(formation = formation, minLevel = level, levelAtMost = true)).toSet())
        }
        // "all" ignores the direction; at-most is inclusive of the exact level and excludes higher ones.
        assertEquals(selected(FriendFilter()), selected(FriendFilter(levelAtMost = true)))
        assertEquals(setOf(502L, 503L), selected(FriendFilter(minLevel = 80, levelAtMost = true)).toSet())
        assertEquals(setOf(500L, 502L, 503L), selected(FriendFilter(minLevel = 90, levelAtMost = true)).toSet())
        assertEquals(setOf(502L), selected(FriendFilter(minLevel = 79, levelAtMost = true)).toSet())
        assertTrue(selected(FriendFilter(minLevel = 78, levelAtMost = true)).isEmpty())
    }

    @Test fun clampingIncludesZeroAndNinetyNineAndCombinedFilters() {
        for ((stored, clamped) in listOf(-20 to 0, 0 to 0, 99 to 99, 999 to 99)) {
            val filter = FriendFilter(login = FriendLogin.Inactive, loginAmount = stored)
            assertEquals(clamped, filter.inactiveAmount)
            assertEquals(selected(filter.copy(loginAmount = clamped)), selected(filter))
        }
        assertEquals(listOf(502L, 503L, 500L), selected(FriendFilter(login = FriendLogin.Inactive, loginAmount = 0)))
        assertEquals(listOf(500L), selected(FriendFilter(login = FriendLogin.Inactive, loginAmount = 99)))
        for (formation in FriendFormation.entries) {
            val filter = FriendFilter(formation = formation, friendName = "bet", servantIds = setOf(if (formation == FriendFormation.Event) 501 else 500),
                ceIds = setOf(9000, 9001), ceLimit = FriendCeLimit.NotMax, minLevel = 90,
                login = FriendLogin.Inactive, loginAmount = 99)
            assertEquals(if (formation == FriendFormation.Main) emptyList() else filter.servantIds.toList(), selected(filter))
        }
        assertEquals(listOf(10001, 10003, 10002), FriendList.select(roster, FriendFilter(formation = FriendFormation.Grand), now).map { it.grandSlot!!.seat })
    }
}
