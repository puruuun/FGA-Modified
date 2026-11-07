package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.AccountState
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

/** Handwritten fixtures; no captures or real ids. Exercises the actual filesystem and reducer. */
class CaptureAccountsTest {
    @TempDir lateinit var directory: File
    private fun body(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun login(id: Int, name: String = "Master $id") = body("""{"response":[{"nid":"login","resCode":"00"}],
        "cache":{"replaced":{"userGame":[{"userId":$id,"name":"$name"}],"userItem":[{"userId":$id,"itemId":100,"num":5}]}}}""")
    private fun item(id: Int, num: Int) = body("""{"cache":{"updated":{"userItem":[{"userId":$id,"itemId":100,"num":$num}]}}}""")
    private fun store() = CaptureAccounts().apply { load(directory) }
    private fun fruit(store: CaptureAccounts, id: String) = store.accounts(100).first { it.id == id }.summary.goldenApples

    @Test fun jpAndKrMetadataStaySeparateAfterSaveAndReload() {
        val store = store()
        store.apply("https://yfgo-game.netmarble.com/login/top", login(7001, "Synthetic KR"), 10)
        store.apply("https://game.fate-go.jp/login/top", login(7002, "Synthetic JP"), 20)
        store.save()
        val restored = store().accounts(100).associateBy { it.id }
        assertEquals("KR", restored.getValue("7001").server)
        assertEquals("JP", restored.getValue("7002").server)
        assertEquals("Synthetic KR", restored.getValue("7001").displayName)
        assertEquals("Synthetic JP", restored.getValue("7002").displayName)
    }

    @Test fun interleavedAccountsStaySeparateAndLastChangedResponseBecomesCurrent() {
        val store = store()
        store.apply("login/top", login(7001), 10)
        store.apply("login/top", login(7002), 20)
        assertEquals("7002", store.currentId)
        store.apply("item/use", item(7001, 2), 30)
        assertEquals("7001", store.currentId)
        assertEquals(listOf("7001", "7002"), store.accounts(100).map { it.id })
        assertEquals(2, fruit(store, "7001"))
        assertEquals(5, fruit(store, "7002"))
        store.apply("item/use", item(7002, 5), 40)
        assertEquals("7001", store.currentId) // An unchanged response does not count as play.
        store.apply("login/top", login(7002), 50)
        assertEquals("7002", store.currentId)
        store.save()
        val restored = store()
        assertEquals(store.states, restored.states)
        assertEquals("7002", restored.currentId)
        assertTrue(File(directory, "accounts/7001/account.json").isFile)
        assertFalse(File(directory, "account-state.json").exists())
    }

    @Test fun conflictChangesNoStateAndReportsWarningWithoutIdsOrBodies() {
        val warnings = mutableListOf<String>()
        val store = CaptureAccounts(warnings::add)
        store.apply("login/top", login(7001), 10)
        val previous = store.states.toMap()
        val conflict = body("""{"cache":{"updated":{"userItem":[{"userId":7001,"itemId":100,"num":1}],"userGame":[{"userId":7002}]}}}""")
        assertNull(store.apply("battle/result", conflict, 20))
        assertEquals(previous, store.states)
        assertEquals("7001", store.currentId)
        assertEquals(1, warnings.size)
        assertFalse(warnings.single().contains("7001"))
    }

    @Test fun unknownAccountDeltaDoesNotCreateSnapshotOrSwitchCurrent() {
        val store = store()
        store.apply("login/top", login(7001), 10)
        store.apply("item/use", item(7002, 1), 20)
        assertEquals(setOf("7001"), store.states.keys)
        assertEquals("7001", store.currentId)
    }

    @Test fun readableLegacyMigratesOnceAndNeverOverwritesNewerState() {
        val legacy = AccountState.reduce(null, "login/top", login(7001), 10)!!
        val file = File(directory, "account-state.json").apply { writeText(legacy.toJson()) }
        val migrated = store()
        assertFalse(file.exists())
        assertEquals(legacy, migrated.state)
        assertEquals("7001", File(directory, "accounts/current").readText())
        migrated.apply("item/use", item(7001, 3), 20)
        migrated.save()
        file.writeText(legacy.toJson()) // Retry after a crash before removing the source.
        assertEquals(3, fruit(store(), "7001"))
        assertFalse(file.exists())
        assertEquals(3, fruit(store(), "7001"))
    }

    @Test fun unreadableLegacyIdIsDeletedAndWarned() {
        val warnings = mutableListOf<String>()
        val file = File(directory, "account-state.json")
        for (text in listOf("broken", AccountState(emptyMap(), JsonArray(emptyList()), snapshotTime = 1, lastUpdateTime = 1).toJson())) {
            file.writeText(text)
            val store = CaptureAccounts(warnings::add).apply { load(directory) }
            assertTrue(store.states.isEmpty())
            assertNull(store.currentId)
            assertFalse(file.exists())
        }
        assertEquals(2, warnings.size)
    }

    @Test fun renamePersistsAndBlankRestoresMasterNameWithoutSwitchingAccount() {
        val store = store()
        store.apply("login/top", login(7001, "First"), 10)
        store.apply("login/top", login(7002, "Second"), 20)
        store.rename("7001", "  Farming  ")
        store.save()
        assertEquals("Farming", store().displayName("7001"))
        assertEquals("7002", store.currentId)
        store.rename("7001", "  ")
        store.save()
        assertEquals("First", store().displayName("7001"))
        store.rename("missing", "Ignored")
        assertEquals(2, store.states.size)
    }

    @Test fun missingMasterNameUsesId() {
        val store = store()
        store.apply("login/top", login(7001, ""), 10)
        assertEquals("#7001", store.displayName("7001"))
    }

    @Test fun deleteCurrentSelectsNextNewestAndPendingSaveDoesNotResurrectDeletedFolder() {
        val store = store()
        for (id in 7001..7003) store.apply("login/top", login(id), id.toLong())
        store.save()
        store.rename("7003", "Pending")
        store.delete("7003")
        store.save()
        assertFalse(File(directory, "accounts/7003").exists())
        assertEquals("7002", store().currentId)
        store.delete("7001")
        assertEquals("7002", store.currentId)
        store.delete("7002")
        assertNull(store.currentId)
        assertFalse(File(directory, "accounts/current").exists())
        assertTrue(store().states.isEmpty())
    }

    @Test fun clearRemovesEveryAccountButLeavesCertificatesAndPublicNamesAlone() {
        val store = store()
        store.apply("login/top", login(7001), 10)
        store.apply("login/top", login(7002), 20)
        store.save()
        File(directory, "certificate").writeText("fixture")
        store.clear()
        store.save()
        assertFalse(File(directory, "accounts").exists())
        assertTrue(File(directory, "certificate").exists())
        assertTrue(store.states.isEmpty())
        assertNull(store.state)
    }

    @Test fun unsafeIdCannotEscapeAccountsDirectory() {
        val store = store()
        val unsafe = body("""{"cache":{"updated":{"userItem":[{"userId":"../outside","itemId":100,"num":1}]}}}""")
        assertNull(store.apply("item/use", unsafe, 10))
        assertTrue(store.states.isEmpty())
    }

    @Test fun accountSaveDoesNotWriteAnotherAccountsPendingStateOrDanglingCurrentMarker() {
        val store = store()
        store.apply("login/top", login(7001), 10)
        store.apply("login/top", login(7002), 20)
        store.save("7001")
        assertTrue(File(directory, "accounts/7001/account-state.json").isFile)
        assertFalse(File(directory, "accounts/7002/account-state.json").exists())
        assertFalse(File(directory, "accounts/current").exists())
        store.save("7002")
        assertEquals("7002", store().currentId)
        assertEquals(store.states, store().states)
    }
}
