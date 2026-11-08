package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.AccountState
import io.github.fate_grand_automata.scripts.capture.FriendRoster
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

/** Issue 258: one resident state, summaries from disk, rosters on demand, and files written by today's code. */
class CaptureAccountsLazyTest {
    @TempDir lateinit var directory: File
    private fun body(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun login(id: Int, name: String = "Master $id") = body("""{"response":[{"nid":"login","resCode":"00"}],
        "cache":{"replaced":{"userGame":[{"userId":$id,"name":"$name","actMax":100,"actRecoverAt":5000}],
        "userItem":[{"userId":$id,"itemId":100,"num":5}]}}}""")
    private fun item(id: Int, num: Int) = body("""{"cache":{"updated":{"userItem":[{"userId":$id,"itemId":100,"num":$num}]}}}""")
    private fun top(owner: Int, friend: Int = 11) = body("""{"cache":{
        "replaced":{"tblFriend":[{"userId":$owner,"friendId":$friend},{"userId":12,"friendId":$owner}]},
        "updated":{"otherUserGame":[{"userId":$friend,"userName":"Friend $friend"}]},"serverTime":1000}}""")
    private val followers = body("""{"cache":{"updated":{"userFollower":[{"followerInfo":[{"type":1,"userId":11,"userName":"Renamed"}]}]}}}""")
    private fun store(migrate: Boolean = true) = CaptureAccounts().apply { load(directory, migrate) }

    @Test fun onlyTheCurrentAccountStaysResidentAndOthersLoadOnDemand() {
        val store = store()
        store.apply("login/top", login(7001), 10)
        store.apply("login/top", login(7002), 20)
        // Both wait for their save, so both are kept.
        assertNotNull(store.residentState("7001"))
        store.save()
        assertNull(store.residentState("7001"))
        assertNotNull(store.residentState("7002"))
        val summaries = store.accounts(100)
        assertEquals(setOf("7001", "7002"), summaries.map { it.id }.toSet())
        // A response for the non-current account reads it from disk, switches to it, and drops the other one after saving.
        val response = assertNotNull(store.apply("item/use", item(7001, 2), 30))
        assertEquals(5, response.previous!!.tables.getValue("userItem").first().jsonObject["num"]!!.jsonPrimitive.int)
        assertEquals("7001", store.currentId)
        assertNull(store.residentState("7002"), "the account that stopped being current is dropped, it was already saved")
        assertNotNull(store.residentState("7001"))
        store.save()
        assertNotNull(store.residentState("7001"))
        assertEquals(5, store.stateOf("7002")!!.tables.getValue("userItem").first().jsonObject["num"]!!.jsonPrimitive.int)
        assertNull(store.residentState("7002"), "an on-demand read is not kept")
        assertEquals(2, store.accounts(100).first { it.id == "7001" }.summary.goldenApples)
    }

    @Test fun summariesComeFromSmallFilesAndNeverFromTheState() {
        val first = store()
        first.apply("login/top", login(7001), 10)
        first.apply("login/top", login(7002, ""), 20)
        first.rename("7001", "Farm")
        first.save()
        val expected = first.accounts(1234)
        for (file in directory.walkTopDown().filter { it.name == "account-state.json" }) file.writeText("not json")
        val light = store(migrate = false)
        assertEquals(expected, light.accounts(1234))
        assertEquals("7002", light.currentId)
        assertEquals("#7002", light.displayName("7002"))
        assertEquals("Farm", light.displayName("7001"))
        assertNull(light.residentState("7002"))
        // Time passes: the AP of the saved summary regenerates exactly as a fresh summary would.
        assertEquals(100, light.accounts(5000).first().summary.ap)
    }

    @Test fun filesWrittenByTodaysCodeAreUpgradedOnTheFirstNormalStart() {
        val first = store()
        first.apply("login/top", login(7001, "Old"), 10)
        first.apply("login/top", login(7002, "Newer"), 20)
        first.apply("friend/top", top(7001), 25)
        first.applyFriends("7001", "friend/top", top(7001), 25)
        first.save()
        val expected = first.accounts(100)
        // Today's code wrote only account-state.json, account.json, friends.json and current.
        directory.walkTopDown().filter { it.name == "summary.json" }.toList().forEach { assertTrue(it.delete()) }
        val widget = store(migrate = false)
        assertTrue(widget.accounts(100).isEmpty(), "the widget path never parses a state")
        val upgraded = store()
        assertEquals(expected, upgraded.accounts(100))
        assertEquals(1, upgraded.accounts(100).first { it.id == "7001" }.friendCount)
        assertNull(upgraded.accounts(100).first { it.id == "7002" }.friendCount)
        assertTrue(File(directory, "accounts/7001/summary.json").isFile)
        assertEquals("7002", upgraded.currentId)
        assertEquals(setOf("11"), upgraded.roster("7001")!!.friends.keys)
        assertEquals(first.stateOf("7001"), upgraded.stateOf("7001"))
    }

    @Test fun rostersStayOnDiskAndAllThreePathsUpdateThem() {
        val store = store()
        store.apply("login/top", login(7001), 10)
        store.save()
        assertTrue(store.applyFriends("7001", "friend/top", top(7001), 20))
        assertEquals(1, store.accounts(30).single().friendCount)
        assertEquals(1000, store.accounts(30).single().friendUpdatedAt)
        assertTrue(File(directory, "accounts/7001/friends.json").isFile)
        for (path in listOf("follower/list", "battle/result")) {
            assertTrue(store.applyFriends("7001", path, followers, 40))
            assertEquals("Renamed", store.roster("7001")!!.friends.getValue("11").name)
            store.applyFriends("7001", "friend/top", top(7001), 20)
        }
        // Nothing to apply: no roster change.
        assertFalse(store.applyFriends("7001", "follower/list", body("""{"cache":{"updated":{"userItem":[]}}}"""), 50))
        assertEquals(store.roster("7001"), FriendRoster.fromJson(File(directory, "accounts/7001/friends.json").readText()))
        store.save()
        assertEquals(1, store().accounts(30).single().friendCount)
    }

    @Test fun anUnsavedStateIsWrittenBeforeItsRoster() {
        val store = store()
        store.apply("login/top", login(7001), 10)
        assertTrue(store.applyFriends("7001", "friend/top", top(7001), 20))
        assertTrue(File(directory, "accounts/7001/account-state.json").isFile)
    }

    @Test fun savedStateIsTheSameFileFormatAsBefore() {
        val store = store()
        store.apply("login/top", login(7001), 10)
        store.apply("item/use", item(7001, 3), 20)
        store.save()
        val text = File(directory, "accounts/7001/account-state.json").readText()
        assertEquals(store.stateOf("7001")!!.toJson(), text)
        assertEquals(store.stateOf("7001"), AccountState.fromJson(text))
        assertTrue(text.startsWith("{\"tables\":{\"userGame\":[{"))
    }

    @Test fun outsidePeriodBaselineKeepsItsFileFormat() {
        val tables = mapOf("userItem" to """[{"itemId":1,"num":2,"s":"a\"b"}]""", "userGame" to "[]")
        val old = JsonObject(tables.mapValues { Json.parseToJsonElement(it.value) })
        val serializer = kotlinx.serialization.builtins.MapSerializer(kotlinx.serialization.serializer<String>(), RawTableSerializer)
        val text = Json.encodeToString(serializer, tables.mapValues { RawTable(it.value) })
        assertEquals(old.toString(), text)
        assertEquals(tables, Json.decodeFromString(serializer, text).mapValues { it.value.json })
    }
}
