package io.github.fate_grand_automata.capture

import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

/** Synthetic fixtures only. Exercises the real filesystem and tracker-facing storage rules. */
class FriendRosterStoreTest {
    @TempDir lateinit var directory: File
    private fun kr(path: String) = "https://yfgo-game.netmarble.com/$path"
    private fun body(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun login(id: Int) = body("""{"response":[{"nid":"login","resCode":"00"}],
        "cache":{"replaced":{"userGame":[{"userId":$id,"name":"Master $id"}]}}}""")
    private fun top(owner: Int = 7001) = body("""{"cache":{"updated":{"otherUserGame":[{"userId":"11","userName":"Friend","userLv":100,
        "friendCode":"111222333","message":"PRIVATE-MESSAGE","mainSupportDeckIds":[101],"eventSupportDeckIds":[],
        "userSvtLeaderHash":[{"supportDeckId":101,"classId":1,"svtId":500,"updatedAt":900}],"eventUserSvtLeaderHash":[]}]},
        "replaced":{"tblFriend":[{"userId":$owner,"friendId":11},{"userId":12,"friendId":$owner}]},"serverTime":1000}}""")
    private fun store() = CaptureAccounts().apply { load(directory) }
    private fun rosterFile(id: String) = File(directory, "accounts/$id/friends.json")

    @Test fun rosterIsSavedBesideTheAccountAndLoadedBack() {
        val store = store()
        store.apply(kr("login/top"), login(7001), 10)
        assertTrue(store.applyFriends("7001", kr("friend/top"), top(), 20))
        assertFalse(store.applyFriends("7001", kr("friend/top"), top(), 21)) // identical: nothing to save
        store.save("7001")
        assertTrue(rosterFile("7001").isFile)
        assertFalse(rosterFile("7001").readText().contains("PRIVATE-MESSAGE"))
        assertEquals(store.rosters, store().rosters)
        assertEquals(setOf("11"), store().rosters.getValue("7001").friends.keys)
    }

    @Test fun noRosterWithoutAnAccountState() {
        val store = store()
        assertFalse(store.applyFriends("7001", kr("friend/top"), top(), 20))
        assertTrue(store.rosters.isEmpty())
    }

    @Test fun deletingAnAccountOrAllDataRemovesTheRoster() {
        val store = store()
        for (id in listOf(7001, 7002)) {
            store.apply(kr("login/top"), login(id), 10)
            store.applyFriends(id.toString(), kr("friend/top"), top(id), 20)
        }
        store.save()
        store.delete("7001")
        assertFalse(rosterFile("7001").exists())
        assertEquals(setOf("7002"), store.rosters.keys)
        store.clear()
        assertTrue(store.rosters.isEmpty())
        assertFalse(rosterFile("7002").exists())
        assertTrue(store().rosters.isEmpty())
    }

    @Test fun rosterNeverEntersTheAccountState() {
        val store = store()
        store.apply(kr("login/top"), login(7001), 10)
        store.apply(kr("friend/top"), top(), 20)
        store.applyFriends("7001", kr("friend/top"), top(), 20)
        store.save()
        assertFalse(File(directory, "accounts/7001/account-state.json").readText().contains("otherUserGame"))
        assertFalse(File(directory, "accounts/7001/account-state.json").readText().contains("Friend"))
    }

    @Test fun oldSavedStateIsRewrittenWithoutOtherPlayersTables() {
        val first = store()
        first.apply(kr("login/top"), login(7001), 10)
        first.save()
        val file = File(directory, "accounts/7001/account-state.json")
        val old = Json.parseToJsonElement(file.readText()).jsonObject.let { state ->
            val tables = state["tables"]!!.jsonObject + ("otherUserGame" to JsonArray(listOf(body("""{"userId":"11"}"""))))
            JsonObject(state + ("tables" to JsonObject(tables)))
        }
        file.writeText(old.toString())
        assertTrue(file.readText().contains("otherUserGame"))
        // Files written before summary.json existed are upgraded on the first normal start.
        assertTrue(File(file.parentFile, "summary.json").delete())
        val loaded = store()
        assertFalse("otherUserGame" in loaded.states.getValue("7001").tables)
        assertFalse(file.readText().contains("otherUserGame"))
    }

    @Test fun malformedBodyKeepsTheStoredRoster() {
        val store = store()
        store.apply(kr("login/top"), login(7001), 10)
        store.applyFriends("7001", kr("friend/top"), top(), 20)
        val before = store.rosters.getValue("7001")
        assertFalse(store.applyFriends("7001", kr("friend/top"), body("""{"cache":{"updated":{"otherUserGame":"x"}}}"""), 30))
        assertEquals(before, store.rosters.getValue("7001"))
    }

    @Test fun dashboardFriendsTextUsesTheRosterOnlyOnceKnown() {
        assertEquals("145", friendsText("145", null))
        assertEquals("139/145", friendsText("145", 139))
        assertEquals("0/145", friendsText("145", 0))
    }

    @Test fun hostServerIsSavedAndLegacyMetadataDefaultsToKr() {
        val first = store()
        first.apply("https://yfgo-game.netmarble.com/login/top", login(7001), 10)
        first.save()
        assertEquals("KR", first.accounts(10).single().server)
        val file = File(directory, "accounts/7001/account.json")
        assertTrue(file.readText().contains("\"server\":\"KR\""))
        assertEquals("KR", store().accounts(10).single().server)
        file.writeText("""{"customName":"Legacy"}""")
        val old = store()
        assertEquals("KR", old.accounts(10).single().server)
        old.rename("7001", "Renamed"); old.save()
        assertEquals("KR", store().accounts(10).single().server)
        file.delete()
        assertEquals("KR", store().accounts(10).single().server)
    }
}
