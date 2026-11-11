package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.AccountDelta
import io.github.fate_grand_automata.scripts.capture.AccountState
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

/** Handwritten fixtures; no captures or real ids. Exercises the actual filesystem and reducer. */
class CaptureAccountsTest {
    @Test fun krFriendPointSummonMergesNewServantAndCeRowsAndKeepsExistingCards() {
        val store = store()
        val target = "https://yfgo-game.netmarble.com/gacha/draw"
        store.apply("https://yfgo-game.netmarble.com/login/top",
            outsideLogin(svts = """[{"userId":"fictionalA","id":1001,"svtId":100100,"lv":1}]"""), 1)
        val response = assertNotNull(store.apply(target, outsideFriendPointSummon(), 2))
        val state = assertNotNull(response.next)
        assertEquals(3, state.tables.getValue("userSvt").size)
        assertEquals(setOf(1001L, 1002L, 1003L), state.tables.getValue("userSvt").map { it.jsonObject.getValue("id").jsonPrimitive.long }.toSet())
        assertEquals(AccountDelta(
            svts = mapOf(100100L to 1L, 9700010L to 1L), friendPoint = -200),
            AccountDelta.between(response.previous, state))
        store.save()
        assertEquals(state, store().state)
        val repeated = assertNotNull(store.apply(target, outsideFriendPointSummon(), 3))
        assertSame(repeated.previous, repeated.next)
    }

    @TempDir lateinit var directory: File
    private fun kr(path: String) = "https://yfgo-game.netmarble.com/$path"
    private fun jp(path: String) = "https://game.fate-go.jp/$path"
    private fun body(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun login(id: Int, name: String = "Master $id") = body("""{"response":[{"nid":"login","resCode":"00"}],
        "cache":{"replaced":{"userGame":[{"userId":$id,"name":"$name"}],"userItem":[{"userId":$id,"itemId":100,"num":5}]}}}""")
    private fun item(id: Int, num: Int) = body("""{"cache":{"updated":{"userItem":[{"userId":$id,"itemId":100,"num":$num}]}}}""")
    private fun idLessItem(num: Int) = body("""{"cache":{"updated":{"userItem":[{"itemId":100,"num":$num}]}}}""")
    private fun store() = CaptureAccounts().apply { load(directory) }
    private fun fruit(store: CaptureAccounts, id: String) = store.accounts(100).first { it.id == id }.summary.goldenApples

    @Test fun jpAndKrMetadataStaySeparateAfterSaveAndReload() {
        val store = store()
        store.apply(kr("login/top"), login(7001, "Synthetic KR"), 10)
        store.apply(jp("login/top"), login(7002, "Synthetic JP"), 20)
        store.save()
        val restored = store().accounts(100).associateBy { it.id }
        assertEquals("KR", restored.getValue("7001").server)
        assertEquals("JP", restored.getValue("7002").server)
        assertEquals("Synthetic KR", restored.getValue("7001").displayName)
        assertEquals("Synthetic JP", restored.getValue("7002").displayName)
    }

    @Test fun interleavedAccountsStaySeparateAndLastChangedResponseBecomesCurrent() {
        val store = store()
        store.apply(kr("login/top"), login(7001), 10)
        store.apply(kr("login/top"), login(7002), 20)
        assertEquals("7002", store.currentId)
        store.apply(kr("item/use"), item(7001, 2), 30)
        assertEquals("7001", store.currentId)
        assertEquals(listOf("7001", "7002"), store.accounts(100).map { it.id })
        assertEquals(2, fruit(store, "7001"))
        assertEquals(5, fruit(store, "7002"))
        store.apply(kr("item/use"), item(7002, 5), 40)
        assertEquals("7001", store.currentId) // An unchanged response does not count as play.
        store.apply(kr("login/top"), login(7002), 50)
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
        store.apply(kr("login/top"), login(7001), 10)
        val previous = store.states.toMap()
        val conflict = body("""{"cache":{"updated":{"userItem":[{"userId":7001,"itemId":100,"num":1}],"userGame":[{"userId":7002}]}}}""")
        assertNull(store.apply(kr("battle/result"), conflict, 20))
        assertEquals(previous, store.states)
        assertEquals("7001", store.currentId)
        assertEquals(1, warnings.size)
        assertFalse(warnings.single().contains("7001"))
    }

    @Test fun unknownAccountDeltaDoesNotCreateSnapshotOrSwitchCurrent() {
        val store = store()
        store.apply(kr("login/top"), login(7001), 10)
        store.apply(kr("item/use"), item(7002, 1), 20)
        assertEquals(setOf("7001"), store.states.keys)
        assertEquals("7001", store.currentId)
    }

    @Test fun readableLegacyMigratesOnceAndNeverOverwritesNewerState() {
        val legacy = AccountState.reduce(null, kr("login/top"), login(7001), 10)!!
        val file = File(directory, "account-state.json").apply { writeText(legacy.toJson()) }
        val migrated = store()
        assertFalse(file.exists())
        assertEquals(legacy, migrated.state)
        assertEquals("7001", File(directory, "accounts/current").readText())
        migrated.apply(kr("item/use"), item(7001, 3), 20)
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
        store.apply(kr("login/top"), login(7001, "First"), 10)
        store.apply(kr("login/top"), login(7002, "Second"), 20)
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
        store.apply(kr("login/top"), login(7001, ""), 10)
        assertEquals("#7001", store.displayName("7001"))
    }

    @Test fun deleteCurrentSelectsNextNewestAndPendingSaveDoesNotResurrectDeletedFolder() {
        val store = store()
        for (id in 7001..7003) store.apply(kr("login/top"), login(id), id.toLong())
        store.save()
        store.rename("7003", "Pending")
        store.delete("7003")
        store.save()
        assertFalse(File(directory, "accounts/7003").exists())
        assertEquals("7002", store().currentId)
        assertNull(store().currentId("KR"))
        assertFalse(File(directory, "accounts/current-KR").exists())
        store.delete("7001")
        assertEquals("7002", store.currentId)
        store.delete("7002")
        assertNull(store.currentId)
        assertFalse(File(directory, "accounts/current").exists())
        assertTrue(store().states.isEmpty())
    }

    @Test fun clearRemovesEveryAccountButLeavesCertificatesAndPublicNamesAlone() {
        val store = store()
        store.apply(kr("login/top"), login(7001), 10)
        store.apply(jp("login/top"), login(7002), 20)
        store.save()
        File(directory, "certificate").writeText("fixture")
        store.clear()
        store.save()
        assertFalse(File(directory, "accounts").exists())
        assertTrue(File(directory, "certificate").exists())
        assertTrue(store.states.isEmpty())
        assertNull(store.state)
        assertNull(store.currentId("KR"))
        assertNull(store.currentId("JP"))
    }

    @Test fun unsafeIdCannotEscapeAccountsDirectory() {
        val store = store()
        val unsafe = body("""{"cache":{"updated":{"userItem":[{"userId":"../outside","itemId":100,"num":1}]}}}""")
        assertNull(store.apply(kr("item/use"), unsafe, 10))
        assertTrue(store.states.isEmpty())
    }

    @Test fun accountSaveDoesNotWriteAnotherAccountsPendingStateOrDanglingCurrentMarker() {
        val store = store()
        store.apply(kr("login/top"), login(7001), 10)
        store.apply(jp("login/top"), login(7002), 20)
        store.save("7001")
        assertTrue(File(directory, "accounts/7001/account-state.json").isFile)
        assertTrue(File(directory, "accounts/current-KR").isFile)
        assertFalse(File(directory, "accounts/7002/account-state.json").exists())
        assertFalse(File(directory, "accounts/current").exists())
        assertFalse(File(directory, "accounts/current-JP").exists())
        store.save("7002")
        val reloaded = store()
        assertEquals("7002", reloaded.currentId)
        assertEquals("7001", reloaded.currentId("KR"))
        assertEquals("7002", reloaded.currentId("JP"))
        assertEquals(store.states, reloaded.states)
    }

    @Test fun jpLoginThenKrPreLoginIdLessResponsesThenKrLoginKeepsJpUnchangedAndReverse() {
        val store = store()
        store.apply(jp("login/top"), login(7002, "JP Master"), 10)
        val jpBefore = store.stateOf("7002")
        // Pre-login KR responses have no own-account id; they must not relabel or mutate the JP account.
        assertNull(store.apply(kr("gamedata/top"), idLessItem(99), 15))
        assertNull(store.apply(kr("item/use"), idLessItem(1), 16))
        assertSame(jpBefore, store.stateOf("7002"))
        assertEquals("JP", store.serverOf("7002"))
        assertEquals("7002", store.currentId)
        assertEquals("7002", store.currentId("JP"))
        assertNull(store.currentId("KR"))
        store.apply(kr("login/top"), login(7001, "KR Master"), 20)
        val byId = store.accounts(100).associateBy { it.id }
        assertEquals("JP", byId.getValue("7002").server)
        assertEquals("KR", byId.getValue("7001").server)
        assertEquals(5, byId.getValue("7002").summary.goldenApples)
        assertEquals(5, byId.getValue("7001").summary.goldenApples)

        // Reverse order: KR login first, then JP pre-login id-less responses, then JP login.
        val reverseDir = File(directory, "reverse")
        val reverse = CaptureAccounts().apply { load(reverseDir) }
        reverse.apply(kr("login/top"), login(7001, "KR Master"), 10)
        val krBefore = reverse.stateOf("7001")
        assertNull(reverse.apply(jp("gamedata/top"), idLessItem(99), 15))
        assertNull(reverse.apply(jp("item/use"), idLessItem(1), 16))
        assertSame(krBefore, reverse.stateOf("7001"))
        assertEquals("KR", reverse.serverOf("7001"))
        assertEquals("7001", reverse.currentId)
        assertEquals("7001", reverse.currentId("KR"))
        assertNull(reverse.currentId("JP"))
        reverse.apply(jp("login/top"), login(7002, "JP Master"), 20)
        val revById = reverse.accounts(100).associateBy { it.id }
        assertEquals("KR", revById.getValue("7001").server)
        assertEquals("JP", revById.getValue("7002").server)
        assertEquals(5, revById.getValue("7001").summary.goldenApples)
        assertEquals(5, revById.getValue("7002").summary.goldenApples)
    }

    @Test fun idLessResponseReachesSameServerCurrentAccountEvenAfterOtherServerWasActive() {
        val store = store()
        store.apply(kr("login/top"), login(7001, "KR"), 10)
        store.apply(jp("login/top"), login(7002, "JP"), 20)
        assertEquals("7002", store.currentId)
        // KR id-less response routes to KR's current account (7001), leaving JP (7002) untouched.
        assertNotNull(store.apply(kr("item/use"), idLessItem(2), 30))
        assertEquals(2, fruit(store, "7001"))
        assertEquals(5, fruit(store, "7002"))
        assertEquals("KR", store.serverOf("7001"))
        assertEquals("JP", store.serverOf("7002"))
        assertEquals("7001", store.currentId)
        // JP id-less response routes to JP's current account (7002), leaving KR (7001) untouched.
        assertNotNull(store.apply(jp("item/use"), idLessItem(4), 40))
        assertEquals(2, fruit(store, "7001"))
        assertEquals(4, fruit(store, "7002"))
        assertEquals("7002", store.currentId)
    }

    @Test fun upgradeFromSingleCurrentFileWithWrongLabelKeepsDataAndFixesLabelOnNextIdentifiedResponse() {
        val first = store()
        first.apply(jp("login/top"), login(7002, "JP Master"), 10)
        first.save()
        // Simulate an upgraded folder from the old bug: wrong label "KR", only the single `current` file exists.
        File(directory, "accounts/7002/account.json").writeText("""{"server":"KR"}""")
        File(directory, "accounts/current-JP").delete()
        File(directory, "accounts/current-KR").delete()

        val upgraded = store()
        assertEquals("7002", upgraded.currentId)
        // Per-server markers start empty on upgrade and are not derived from the wrong stored label.
        assertNull(upgraded.currentId("KR"))
        assertNull(upgraded.currentId("JP"))
        assertEquals(5, fruit(upgraded, "7002"))
        // An id-less KR response must be dropped rather than routed into 7002.
        assertNull(upgraded.apply(kr("item/use"), idLessItem(1), 15))
        assertEquals(5, fruit(upgraded, "7002"))
        // An identified JP response corrects the label and sets the JP marker without deleting data.
        assertNotNull(upgraded.apply(jp("item/use"), item(7002, 4), 20))
        assertEquals("JP", upgraded.serverOf("7002"))
        assertEquals("7002", upgraded.currentId("JP"))
        assertNull(upgraded.currentId("KR"))
        assertEquals(4, fruit(upgraded, "7002"))
        upgraded.save()
        val reloaded = store()
        assertEquals("JP", reloaded.serverOf("7002"))
        assertEquals("7002", reloaded.currentId("JP"))
    }

    @Test fun identifiedResponseChangingLabelRemovesAccountFromOldServerMarker() {
        val store = store()
        store.apply(kr("login/top"), login(7002, "Mislabelled"), 10)
        assertEquals("7002", store.currentId("KR"))
        // Identified response from JP host wins and clears 7002 from the KR marker.
        assertNotNull(store.apply(jp("item/use"), item(7002, 3), 20))
        assertEquals("JP", store.serverOf("7002"))
        assertEquals("7002", store.currentId("JP"))
        assertNull(store.currentId("KR"))
        assertNull(store.apply(kr("item/use"), idLessItem(1), 30))
        assertEquals(3, fruit(store, "7002"))
    }

    @Test fun unknownHostAndRelativePathAreDroppedIdentifiedOrNot() {
        val store = store()
        store.apply(kr("login/top"), login(7001), 10)
        for (bad in listOf("login/top", "/login/top", "https://example.com/login/top", "https://example.com/item/use")) {
            assertNull(store.apply(bad, login(7002), 20))
            assertNull(store.apply(bad, item(7001, 1), 20))
            assertNull(store.apply(bad, idLessItem(1), 20))
        }
        assertEquals(setOf("7001"), store.ids)
        assertEquals(5, fruit(store, "7001"))
    }
}
