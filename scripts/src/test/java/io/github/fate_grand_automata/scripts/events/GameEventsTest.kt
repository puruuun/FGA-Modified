package io.github.fate_grand_automata.scripts.events

import java.io.ByteArrayInputStream
import java.io.SequenceInputStream
import kotlin.test.*

class GameEventsTest {
    private fun event(id: Long = 1, type: String = "eventQuest", start: Long = 100, end: Long = 200) =
        GameEvent(id, type, "Event $id", start, end)

    @Test fun boundariesAreInclusive() {
        val events = listOf(event())
        assertFalse(GameEvents.running(events, 99).visible)
        assertEquals(events, GameEvents.running(events, 100).limited)
        assertEquals(events, GameEvents.running(events, 200).limited)
        assertFalse(GameEvents.running(events, 201).visible)
    }

    @Test fun permanentEntriesAreExcludedBut180DaysIsAllowed() {
        val events = listOf(event(1, end = 100 + 180L * 86_400), event(2, end = 101 + 180L * 86_400))
        assertEquals(listOf(1L), GameEvents.running(events, 150).limited.map { it.id })
    }

    @Test fun splitsTypesIgnoresOthersAndSortsEachListByEndThenId() {
        val events = listOf(event(3, end = 201), event(2, "warBoard"), event(1),
            event(6, "questCampaign", end = 201), event(5, "combineCampaign"), event(4, "futureCampaign"),
            event(7, "genderSelection"), event(8, "combineCosutumeItem"), event(9, "CampaignSuffix"))
        val running = GameEvents.running(events, 150)
        assertEquals(listOf(1L, 2L, 3L), running.limited.map { it.id })
        assertEquals(listOf(4L, 5L, 6L), running.campaigns.map { it.id })
    }

    @Test fun parsesNamesAndSkipsMalformedScheduleRows() {
        val input = """[null,4,{}, {"id":0,"type":"eventQuest","name":"bad","startedAt":1,"endedAt":2},
            {"id":2,"type":"eventQuest","name":"bad","startedAt":3,"endedAt":2},
            {"id":3,"type":"eventQuest","name":"bad","startedAt":"oops","endedAt":2},
            {"id":4,"type":"eventQuest","name":"   ","startedAt":1,"endedAt":2},
            {"id":1,"type":"eventQuest","name":"First\r\n  second\nthird","startedAt":100,"endedAt":200,"ignored":true}]"""
        assertEquals(listOf(event().copy(name = "First second third")), GameEvents.parse(input.byteInputStream()))
    }

    private fun ce(id: String, functions: String) = """{"id":$id,"skills":[{"functions":[$functions]}]}"""
    private fun function(type: String, events: String) = """{"funcType":"$type","svals":[$events]}"""

    @Test fun severalCesOneEventAndOneCeSeveralEventsAreDeduplicated() {
        val rows = listOf(ce("10", function("eventDropUp", """{"EventId":1},{"EventId":2},{"EventId":1}""")),
            ce("20", function("eventPointUp", """{"EventId":1}""")))
        val map = GameEvents.bonusMap(rows.joinToString(",", "[", "]").byteInputStream())
        assertEquals(listOf(BonusEquip(10, setOf(BonusKind.Drop)), BonusEquip(20, setOf(BonusKind.Point))), map[1])
        assertEquals(listOf(BonusEquip(10, setOf(BonusKind.Drop))), map[2])
    }

    @Test fun mapsEveryKindIncludingUnknownFunctions() {
        val types = mapOf("eventDropUp" to BonusKind.Drop, "eventDropRateUp" to BonusKind.Drop, "classDropUp" to BonusKind.Drop,
            "eventPointUp" to BonusKind.Point, "eventFortificationPointUp" to BonusKind.Point,
            "servantFriendshipUp" to BonusKind.Bond, "enemyEncountRateUp" to BonusKind.Encounter,
            "enemyEncountCopyRateUp" to BonusKind.Encounter, "futureBonus" to BonusKind.Other)
        val rows = types.keys.mapIndexed { index, type -> ce((index + 1).toString(), function(type, """{"EventId":1}""")) }
        val map = GameEvents.bonusMap(rows.joinToString(",", "[", "]").byteInputStream())
        assertEquals(types.values.map { setOf(it) }, map.getValue(1).map { it.kinds })
    }

    @Test fun mergesKindsAndSkipsMalformedRowsAndNonEventFunctions() {
        val functions = listOf(function("eventDropUp", """{"EventId":1}"""), function("eventPointUp", """{"EventId":1}"""))
        val input = listOf("null", "{}", ce("\"oops\"", functions.first()), """{"id":30,"skills":"bad"}""",
            ce("40", function("eventDropUp", """{"Value":5},{"EventId":"bad"},{"EventId":0}""")),
            ce("10", functions.joinToString(","))).joinToString(",", "[", "]")
        assertEquals(mapOf(1L to listOf(BonusEquip(10, setOf(BonusKind.Drop, BonusKind.Point)))), GameEvents.bonusMap(input.byteInputStream()))
    }

    @Test fun consumesAnInputStreamOfIndividuallyGeneratedRows() {
        // No full export string exists, even in this fixture. The parser sees an ordinary input stream.
        val chunks = sequence {
            yield("[")
            repeat(10_000) { index ->
                if (index > 0) yield(",")
                yield(ce((index + 1).toString(), function("eventDropUp", """{"EventId":1}""")))
            }
            yield("]")
        }.map { ByteArrayInputStream(it.toByteArray()) }.iterator()
        val stream = SequenceInputStream(object : java.util.Enumeration<ByteArrayInputStream> {
            override fun hasMoreElements() = chunks.hasNext()
            override fun nextElement() = chunks.next()
        })
        stream.use { assertEquals(10_000, GameEvents.bonusMap(it).getValue(1).size) }
    }

    @Test fun remainingTimeUsesFlooredDaysOrHoursWithOneHourMinimum() {
        assertEquals(RemainingUnit.Days to 2L, remainingParts(2 * 86_400 + 3600))
        assertEquals(RemainingUnit.Days to 1L, remainingParts(86_400))
        assertEquals(RemainingUnit.Hours to 23L, remainingParts(86_399))
        assertEquals(RemainingUnit.Hours to 2L, remainingParts(2 * 3600 + 30))
        assertEquals(RemainingUnit.Hours to 1L, remainingParts(0))
        assertEquals(RemainingUnit.Hours to 1L, remainingParts(59))
    }
}
