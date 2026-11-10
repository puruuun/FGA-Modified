package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.*
import kotlin.test.*

/** Synthetic account ids and values, using the KR and official JP capture field structure. */
class BondDeckResolutionTest {
    private data class Fixture(val body: JsonObject, val state: AccountState)

    private fun fixture(name: String): Fixture {
        val json = Json.parseToJsonElement(checkNotNull(javaClass.getResource("/capture/bond-svtid-zero-$name.json"))
            .readText()).jsonObject
        return Fixture(json.getValue("body").jsonObject, state(json.getValue("state").jsonObject))
    }

    private fun state(tables: JsonObject) = AccountState(tables.mapValues { it.value.jsonArray },
        JsonArray(emptyList()), snapshotTime = 1, lastUpdateTime = 1)

    private fun result(
        deck: String = """{"id":11,"userSvtId":10010011001,"svtId":0}""",
        setup: String = deck,
        userSvts: String = "[]",
        oldUserSvts: String = "[]",
        storage: String = "[]",
    ): Fixture {
        val tables = Json.parseToJsonElement("""{"battle":[{"battleInfo":{"myDeck":{"svts":[$setup]}}}],
            "userSvt":$userSvts,"userSvtStorage":$storage}""").jsonObject
        val body = Json.parseToJsonElement("""{"response":[{"success":{
            "myDeck":{"svts":[$deck]},"oldUserSvt":$oldUserSvts,
            "oldUserSvtCollection":[{"svtId":901,"friendship":100},{"svtId":902,"friendship":500}]}}],
            "cache":{"updated":{"userSvtCollection":[{"svtId":901,"friendship":300},{"svtId":902,"friendship":570}]}}}""").jsonObject
        return Fixture(body, state(tables))
    }

    private fun assertResolved(fixture: Fixture, expected: Map<Long, Long> = mapOf(901L to 200L)): BattleGains {
        val gains = assertNotNull(BattleGains.parse(fixture.body, fixture.state))
        assertEquals(expected, gains.bond)
        val captured = assertNotNull(CapturedBattle.parse(fixture.body, fixture.state))
        assertEquals(expected, assertNotNull(captured.bond).associate { assertNotNull(it.svtId) to assertNotNull(it.gained) })
        return gains
    }

    private fun assertRealShape(name: String) {
        val fixture = fixture(name)
        val gains = assertResolved(fixture, mapOf(901L to 200L, 903L to 50L))
        assertEquals(setOf(901L, 903L), gains.bondMax)
        val captured = assertNotNull(CapturedBattle.parse(fixture.body, fixture.state))
        assertEquals(listOf(CapturedBond(1, 901, 9, 10, 10, 200, 1),
            CapturedBond(2, 903, 11, 12, 12, 50, 3)), captured.bond)
        val summary = RunGains.start(fixture.state).add(gains).finish(null).summarized()
        assertEquals(gains.bond, summary.bond)
        assertEquals(gains.bondMax, summary.bondMax)
        assertEquals(setOf(901L, 903L), summary.displayBond().map { it.svtId }.toSet())
    }

    @Test fun krZeroDeckIdsResolveOwnServantsAndExcludeSupport() = assertRealShape("kr")

    @Test fun officialJpZeroDeckIdsResolveOwnServantsAndExcludeSupport() = assertRealShape("jp")

    @Test fun servantFoundOnlyInStorageIsIncluded() {
        assertResolved(result(storage = """[{"id":10010011001,"svtId":901}]"""))
    }

    @Test fun servantFoundOnlyInResultOldUserSvtIsIncluded() {
        assertResolved(result(oldUserSvts = """[{"id":10010011001,"svtId":901}]"""))
    }

    private val unknownIds = listOf("", """, "svtId":0""", """, "svtId":-1""")

    @Test fun unknownResultIdFallsThroughToSetup() {
        for (id in unknownIds) {
            assertResolved(result(deck = """{"id":11,"userSvtId":10010011001$id}""",
                setup = """{"id":11,"userSvtId":10010011001,"svtId":901}"""))
        }
    }

    @Test fun unknownResultAndSetupIdsFallThroughToUserSvt() {
        for (resultId in unknownIds) {
            for (setupId in unknownIds) {
                assertResolved(result(deck = """{"id":11,"userSvtId":10010011001$resultId}""",
                    setup = """{"id":11,"userSvtId":10010011001$setupId}""",
                    userSvts = """[{"id":10010011001,"svtId":901}]"""))
            }
        }
    }

    @Test fun unknownUserSvtIdFallsThroughToResultOldUserSvt() {
        for (id in unknownIds) {
            assertResolved(result(userSvts = """[{"id":10010011001$id}]""",
                oldUserSvts = """[{"id":10010011001,"svtId":901}]"""))
        }
    }

    @Test fun unknownUserSvtAndOldUserSvtIdsFallThroughToStorage() {
        for (id in unknownIds) {
            assertResolved(result(userSvts = """[{"id":10010011001$id}]""",
                oldUserSvts = """[{"id":10010011001$id}]""",
                storage = """[{"id":10010011001$id},{"id":10010011001,"svtId":901}]"""))
        }
    }

    @Test fun unresolvedServantsKeepAllGainsRowsAndUnknownDiagnosticSlots() {
        for (id in unknownIds) {
            val fixture = result(deck = """{"id":11,"userSvtId":10010011001$id}""",
                userSvts = """[{"id":10010011001$id}]""",
                oldUserSvts = """[{"id":10010011001$id}]""",
                storage = """[{"id":10010011001$id}]""")
            val gains = assertNotNull(BattleGains.parse(fixture.body, fixture.state))
            assertEquals(mapOf(901L to 200L, 902L to 70L), gains.bond)
            val captured = assertNotNull(CapturedBattle.parse(fixture.body, fixture.state))
            assertEquals(listOf(CapturedBond(1, null, null, null, null, null)), captured.bond)
        }
    }

    @Test fun positiveResultIdKeepsPrecedenceOverSetupAndUserSvt() {
        assertResolved(result(deck = """{"id":11,"userSvtId":10010011001,"svtId":901}""",
            setup = """{"id":11,"userSvtId":10010011001,"svtId":902}""",
            userSvts = """[{"id":10010011001,"svtId":902}]"""))
    }

    @Test fun positiveUserSvtIdKeepsPrecedenceOverOldUserSvtAndStorage() {
        assertResolved(result(userSvts = """[{"id":10010011001,"svtId":901}]""",
            oldUserSvts = """[{"id":10010011001,"svtId":902}]""",
            storage = """[{"id":10010011001,"svtId":902}]"""))
    }
}
