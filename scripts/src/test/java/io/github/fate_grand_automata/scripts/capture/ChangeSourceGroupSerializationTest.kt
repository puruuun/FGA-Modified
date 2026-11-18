package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChangeSourceGroupSerializationTest {
    @Test fun everyGroupIsWrittenAsItsPlainName() {
        ChangeSource.Group.entries.forEach {
            assertEquals("\"${it.name}\"", Json.encodeToString(it))
            assertEquals("""{"group":"${it.name}"}""", Json.encodeToString(ChangeSource(it)))
        }
        assertEquals("""{"group":"Other","path":"x/y","script":true}""", Json.encodeToString(ChangeSource(ChangeSource.Group.Other, "x/y", true)))
    }

    @Test fun everyKnownGroupRoundTrips() {
        ChangeSource.Group.entries.forEach {
            val source = ChangeSource(it, "p", true)
            assertEquals(source, Json.decodeFromString<ChangeSource>(Json.encodeToString(source)))
        }
    }

    @Test fun unknownGroupNameReadsAsOtherAndKeepsPathAndScript() {
        val read = Json.decodeFromString<ChangeSource>("""{"group":"FutureGroup","path":"a/b","script":true}""")
        assertEquals(ChangeSource(ChangeSource.Group.Other, "a/b", true), read)
    }

    @Test fun nonStringGroupIsStillMalformed() {
        listOf("5", "null", "{}", "[]", "true").forEach {
            assertFailsWith<SerializationException>(it) { Json.decodeFromString<ChangeSource>("""{"group":$it}""") }
        }
    }

    @Test fun twoUnknownGroupsInOnePeriodAddUp() {
        val stored = """{"accountId":"a","startedAt":1,"sources":[
            {"source":{"group":"FutureA"},"delta":{"qp":5},"fruitUsed":{"100":2}},
            {"source":{"group":"FutureB"},"delta":{"qp":7},"fruitUsed":{"100":3}}],"total":{"qp":12}}"""
        val period = Json.decodeFromString<OutsidePeriod>(stored)
        val next = period.add(ChangeSource(ChangeSource.Group.Other), AccountDelta(qp = 1), 5)
        val other = next.sources.single { it.source == ChangeSource(ChangeSource.Group.Other) }
        assertEquals(13, other.delta.qp)
        assertEquals(5L, other.fruitUsed[100L])
        assertEquals(13, next.total.qp)
        assertEquals(1, next.sources.size)
    }

    private val duplicated = """{"accountId":"a","startedAt":1,"sources":[
        {"source":{"group":"Shop"},"delta":{"qp":1}},
        {"source":{"group":"FutureA"},"delta":{"qp":5},"fruitUsed":{"100":2}},
        {"source":{"group":"FutureB"},"delta":{"qp":7},"fruitUsed":{"100":3}}],"total":{"qp":13}}"""

    @Test fun duplicateSourcesMergeKeepingFirstAppearanceOrder() {
        val merged = Json.decodeFromString<OutsidePeriod>(duplicated).mergeDuplicateSources()
        assertEquals(listOf(ChangeSource.Group.Shop, ChangeSource.Group.Other), merged.sources.map { it.source.group })
        assertEquals(12, merged.sources[1].delta.qp)
        assertEquals(5L, merged.sources[1].fruitUsed[100L])
        assertEquals(13, merged.total.qp)
    }

    @Test fun periodWithoutDuplicatesIsUnchanged() {
        val period = OutsidePeriod("a", 1).add(ChangeSource(ChangeSource.Group.Shop), AccountDelta(qp = 1), 2)
            .add(ChangeSource(ChangeSource.Group.Other, "x"), AccountDelta(qp = 2), 3)
        val before = Json.encodeToString(period)
        assertEquals(before, Json.encodeToString(period.mergeDuplicateSources()))
        assertEquals(period, period.mergeDuplicateSources())
    }
}
