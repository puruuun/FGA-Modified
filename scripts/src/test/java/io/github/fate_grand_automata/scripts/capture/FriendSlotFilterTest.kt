package io.github.fate_grand_automata.scripts.capture

import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

class FriendSlotFilterTest {
    private fun roster(skills: List<List<Int>>, appends: List<List<Int?>>): FriendRoster {
        val slots = skills.indices.map { index ->
            FriendSlot(101, index + 1, 100L + index, skills = skills[index], appends = appends[index])
        }
        val friend = Friend("1", slots = slots + slots.map { it.copy(deckId = 201) },
            mainDeckIds = listOf(101), eventDeckIds = listOf(201), grandSlots = slots.map {
                FriendGrandSlot(it.classId, it.svtId, skills = it.skills, appends = it.appends)
            })
        return FriendRoster(friends = mapOf("1" to friend))
    }

    private fun select(roster: FriendRoster, filter: FriendFilter) = FriendList.select(roster, filter, 0).map { it.slot.svtId }

    @TestFactory fun everySkillSlotInEveryFormation() = FriendFormation.entries.flatMap { formation ->
        (0..2).flatMap { index -> listOf(FriendSkillLevel.Ten, FriendSkillLevel.NotTen).map { state ->
            dynamicTest("$formation skill ${index + 1} $state") {
                val roster = roster(listOf(List(3) { if (it == index) 10 else 9 }, listOf(9, 9, 9)), List(2) { List(5) { null } })
                val filter = FriendFilter(formation = formation, skillLevels = List(3) { if (it == index) state else FriendSkillLevel.All })
                assertEquals(listOf(if (state == FriendSkillLevel.Ten) 100L else 101L), select(roster, filter))
            }
        } }
    }

    @TestFactory fun everyAppendSlotIncludingLockedInEveryFormation() = FriendFormation.entries.flatMap { formation ->
        (0..4).flatMap { index -> listOf(FriendSkillLevel.Ten, FriendSkillLevel.NotTen).map { state ->
            dynamicTest("$formation append ${index + 1} $state including locked") {
                val roster = roster(List(3) { listOf(9, 9, 9) },
                    listOf(List(5) { if (it == index) 10 else 9 }, List(5) { 9 }, List(5) { null }))
                val filter = FriendFilter(formation = formation, appendLevels = List(5) { if (it == index) state else FriendSkillLevel.All })
                assertEquals(if (state == FriendSkillLevel.Ten) listOf(100L) else listOf(101L, 102L), select(roster, filter))
            }
        } }
    }

    @TestFactory fun servantOrAndSlotAndInEveryFormation() = FriendFormation.entries.map { formation ->
        dynamicTest("$formation servant OR and skill/append AND") {
            val roster = roster(listOf(listOf(10, 9, 10), listOf(10, 10, 9), listOf(9, 9, 10)),
                listOf(listOf(null, 10, 9, 10, null), listOf(10, 9, 10, null, 10), listOf(null, 10, 9, 10, null)))
            val all = FriendFilter(formation = formation)
            assertEquals(listOf(100L, 101L, 102L), select(roster, all))
            assertEquals(listOf(100L, 102L), select(roster, all.copy(servantIds = setOf(100, 102, 999))))
            assertTrue(select(roster, all.copy(servantIds = setOf(999))).isEmpty())
            val skills = all.copy(skillLevels = listOf(FriendSkillLevel.Ten, FriendSkillLevel.NotTen, FriendSkillLevel.All))
            assertEquals(listOf(100L), select(roster, skills))
            val combined = skills.copy(appendLevels = listOf(FriendSkillLevel.NotTen, FriendSkillLevel.Ten,
                FriendSkillLevel.All, FriendSkillLevel.Ten, FriendSkillLevel.NotTen))
            assertEquals(listOf(100L), select(roster, combined))
            assertTrue(select(roster, combined.copy(servantIds = setOf(101, 102))).isEmpty())
            assertTrue(select(roster, combined.copy(appendLevels = List(5) { FriendSkillLevel.Ten })).isEmpty())
            assertEquals(select(roster, all), select(roster, FriendFilter(formation = formation)))
        }
    }
}
