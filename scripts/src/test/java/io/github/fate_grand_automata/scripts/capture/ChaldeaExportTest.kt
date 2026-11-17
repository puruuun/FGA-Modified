package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.*
import kotlin.test.*

/** Entirely synthetic: no captured account or login data. */
class ChaldeaExportTest {
    private fun obj(body: String) = Json.parseToJsonElement(body).jsonObject

    private fun state(): AccountState {
        val login = obj("""{"response":[{"nid":"other","resCode":"01"},{"nid":"login","resCode":"00","success":{"synthetic":true}}],
            "cache":{"replaced":{"userGame":[{"userId":1,"name":"Synthetic"}],
            "userItem":[{"itemId":100,"num":3},{"itemId":16,"num":5}],"unknownTable":[]},"serverTime":1000}}""")
        val original = assertNotNull(AccountState.reduce(null, "/login/top", login, 10))
        return assertNotNull(AccountState.reduce(original, "/item/use", obj("""{"response":[{"nid":"item","resCode":"00"}],
            "cache":{"updated":{"userItem":[{"itemId":100,"num":2}]},"deleted":{"userItem":[{"itemId":16}]},
            "replaced":{"extraTable":[{"id":9,"value":"synthetic"}],"userGachaDrawLog":[{"id":1}]},"serverTime":2000}}"""), 20))
    }

    @Test fun exactCompactEnvelopeAndMinimalLoginResponse() {
        val state = state()
        val text = ChaldeaExport.body(state)
        val body = obj(text)
        assertEquals(setOf("response", "cache"), body.keys)
        assertEquals(Json.parseToJsonElement("""[{"nid":"login","resCode":"00"}]"""), body["response"])
        assertNotEquals(state.loginResponse, body["response"])
        val cache = body.getValue("cache").jsonObject
        assertEquals(setOf("updated", "deleted", "replaced", "serverTime"), cache.keys)
        assertEquals(JsonObject(emptyMap()), cache["updated"])
        assertEquals(JsonObject(emptyMap()), cache["deleted"])
        assertEquals(JsonPrimitive(2000), cache["serverTime"])
        assertEquals(body.toString(), text)
        assertFalse(text.contains('\n'))
    }

    @Test fun dropsNonListedTablesAndKeepsListedTablesInStoredOrder() {
        val rawTables = linkedMapOf(
            "userItem" to """[{"itemId":100,"num":2}]""",
            "userGachaDrawLog" to """[{"id":1,"gachaId":99}]""",
            "userGame" to """[{"userId":1,"name":"Synthetic"}]""",
            "unknownTable" to """[]""",
            "userLogin" to """[{"userId":1,"seqLoginCount":3,"totalLoginCount":10,"lastLoginAt":1500}]""",
        )
        val state = AccountState(
            rawTables = rawTables,
            rawLoginResponse = """[{"nid":"login","resCode":"00","success":{"synthetic":true}}]""",
            serverTime = JsonPrimitive(2000),
            snapshotTime = 10,
            lastUpdateTime = 20,
        )
        val text = ChaldeaExport.body(state)
        val replaced = obj(text)["cache"]!!.jsonObject["replaced"]!!.jsonObject
        assertEquals(listOf("userItem", "userGame", "userLogin"), replaced.keys.toList())
        assertFalse("userGachaDrawLog" in replaced)
        assertFalse("unknownTable" in replaced)
        assertTrue("userGachaDrawLog" in state.tables)
        assertTrue(
            text.contains(
                """"replaced":{"userItem":[{"itemId":100,"num":2}],"userGame":[{"userId":1,"name":"Synthetic"}],"userLogin":[{"userId":1,"seqLoginCount":3,"totalLoginCount":10,"lastLoginAt":1500}]}""",
            ),
        )
        assertEquals(45, ChaldeaExport.CHALDEA_TABLES.size)
    }

    @Test fun mergedTablesAndLatestTimeRoundTripAsLogin() {
        val state = state()
        val body = obj(ChaldeaExport.body(state))
        val expectedTables = state.tables.filterKeys { it in ChaldeaExport.CHALDEA_TABLES }
        assertEquals(JsonObject(expectedTables), body["cache"]!!.jsonObject["replaced"])
        assertEquals(JsonPrimitive(2000), body["cache"]!!.jsonObject["serverTime"])
        val imported = assertNotNull(AccountState.reduce(null, "/login/top", body, 30))
        assertEquals(expectedTables, imported.tables)
        assertEquals(Json.parseToJsonElement("""[{"nid":"login","resCode":"00"}]"""), imported.loginResponse)
        assertEquals(state.serverTime, imported.serverTime)
        assertEquals(30, imported.snapshotTime)
    }

    @Test fun importReplacesPreviousAccountAsANewLogin() {
        val previous = state().copy(tables = mapOf("staleTable" to JsonArray(emptyList())))
        val exported = state()
        val imported = assertNotNull(AccountState.reduce(previous, "/login/top", obj(ChaldeaExport.body(exported)), 40))
        assertEquals(exported.tables.filterKeys { it in ChaldeaExport.CHALDEA_TABLES }, imported.tables)
        assertFalse("staleTable" in imported.tables)
        assertEquals(40, imported.snapshotTime)
    }

    @Test fun missingServerTimeIsExplicitNull() {
        val state = state().copy(serverTime = null)
        val body = obj(ChaldeaExport.body(state))
        assertEquals(JsonNull, body["cache"]!!.jsonObject["serverTime"])
        assertNotNull(AccountState.reduce(null, "/login/top", body, 50))
    }
}
