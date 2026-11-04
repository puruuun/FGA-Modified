package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.battlelog.BattleLogEntry
import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.battlelog.gainsFixture
import kotlinx.serialization.json.Json
import kotlin.test.*

class CaptureLogAccountTest {
    @Test fun oldJsonWithOrWithoutGainsLoadsWithoutAccountFields() {
        for (entry in listOf(battleLogFixture(), battleLogFixture().copy(gains = gainsFixture()))) {
            val json = Json.encodeToString(entry)
            assertFalse(json.contains("accountId"))
            assertFalse(json.contains("accountName"))
            val restored = Json.decodeFromString<BattleLogEntry>(json)
            assertNull(restored.accountId)
            assertNull(restored.accountName)
            assertEquals(entry.gains, restored.gains)
        }
    }

    @Test fun accountFieldsRoundTripAndHistoricalNameWinsOverRenameOrDeletion() {
        val entry = battleLogFixture().copy(gains = gainsFixture(), accountId = "7001", accountName = "First")
        assertEquals(entry, Json.decodeFromString<BattleLogEntry>(Json.encodeToString(entry)))
        assertEquals("First", entry.captureAccountName(listOf(captureAccountFixture(name = "Later"))))
        assertEquals("First", entry.captureAccountName(emptyList()))
    }

    @Test fun idOnlyEntryUsesSurvivingAccountNameButNeverUnrelatedAccount() {
        val entry = battleLogFixture().copy(accountId = "7001")
        assertEquals("Current name", entry.captureAccountName(listOf(captureAccountFixture(name = "Current name"))))
        assertNull(entry.captureAccountName(listOf(captureAccountFixture(id = "7002"))))
        assertNull(entry.captureAccountName(emptyList()))
        assertNull(battleLogFixture().captureAccountName(listOf(captureAccountFixture())))
    }
}
