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
            "replaced":{"extraTable":[{"id":9,"value":"synthetic"}]},"serverTime":2000}}"""), 20))
    }

    @Test fun exactCompactEnvelopeAndOriginalLoginResponse() {
        val state = state()
        val text = ChaldeaExport.body(state)
        val body = obj(text)
        assertEquals(setOf("response", "cache"), body.keys)
        assertEquals(state.loginResponse, body["response"])
        assertTrue(body["response"]!!.jsonArray.any {
            it.jsonObject["nid"]?.jsonPrimitive?.content == "login" && it.jsonObject["resCode"]?.jsonPrimitive?.content == "00"
        })
        val cache = body.getValue("cache").jsonObject
        assertEquals(setOf("updated", "deleted", "replaced", "serverTime"), cache.keys)
        assertEquals(JsonObject(emptyMap()), cache["updated"])
        assertEquals(JsonObject(emptyMap()), cache["deleted"])
        assertEquals(body.toString(), text)
        assertFalse(text.contains('\n'))
    }

    @Test fun mergedTablesAndLatestTimeRoundTripAsLogin() {
        val state = state()
        val body = obj(ChaldeaExport.body(state))
        assertEquals(JsonObject(state.tables), body["cache"]!!.jsonObject["replaced"])
        assertEquals(JsonPrimitive(2000), body["cache"]!!.jsonObject["serverTime"])
        val imported = assertNotNull(AccountState.reduce(null, "/login/top", body, 30))
        assertEquals(state.tables, imported.tables)
        assertEquals(state.loginResponse, imported.loginResponse)
        assertEquals(state.serverTime, imported.serverTime)
        assertEquals(30, imported.snapshotTime)
    }

    @Test fun importReplacesPreviousAccountAsANewLogin() {
        val previous = state().copy(tables = mapOf("staleTable" to JsonArray(emptyList())))
        val exported = state()
        val imported = assertNotNull(AccountState.reduce(previous, "/login/top", obj(ChaldeaExport.body(exported)), 40))
        assertEquals(exported.tables, imported.tables)
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
