package io.github.fate_grand_automata.capture

import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class SupportListShapeDiagnosticsTest {
    @TempDir lateinit var directory: File
    private fun body(text: String) = Json.parseToJsonElement(text).jsonObject
    private val list = body(
        """{"cache":{"updated":{"userFollower":[{"followerInfo":[{"type":1,"userId":"PRIVATE-ID","userName":"PRIVATE-NAME",
        "userSvtLeaderHash":[{"svtId":4321,"lv":80,"script":{}},{"svtId":0,"lv":1}]}]}]}}}""",
    )
    private fun file(server: String) = File(directory, "diagnostics/support-list-shape-$server.json")

    @Test fun followerListWritesOneFilePerServerAndReplacesIt() {
        val store = CaptureAccounts().apply { load(directory) }
        store.recordSupportListShape("https://yfgo-game.netmarble.com/follower/list", list, 1)
        store.recordSupportListShape("https://game.fate-go.jp/follower/list", list, 2)
        store.recordSupportListShape("https://game.fate-go.jp/follower/list", list, 3)
        assertEquals(JsonPrimitive(1), body(file("kr").readText())["time"])
        assertEquals(JsonPrimitive(3), body(file("jp").readText())["time"])
        for (name in listOf("kr", "jp")) {
            val text = file(name).readText()
            assertTrue("PRIVATE" !in text && "4321" !in text)
        }
    }

    @Test fun otherPathsAndMalformedResponsesWriteNothing() {
        val warnings = mutableListOf<String>()
        val store = CaptureAccounts(warn = warnings::add).apply { load(directory) }
        for (path in listOf("battle/result", "friend/top", "login/top")) {
            store.recordSupportListShape("https://yfgo-game.netmarble.com/$path", list, 1)
        }
        store.recordSupportListShape("https://yfgo-game.netmarble.com/follower/list", body("""{"cache":{"updated":{"userFollower":7}}}"""), 1)
        assertFalse(File(directory, "diagnostics").exists() && File(directory, "diagnostics").listFiles()!!.any { it.name.startsWith("support-list") })
        assertTrue(warnings.isEmpty())
    }

    @Test fun writeFailureIsSwallowedAndLogged() {
        val warnings = mutableListOf<String>()
        val store = CaptureAccounts(warn = warnings::add).apply { load(directory) }
        File(directory, "diagnostics").writeText("blocked")
        store.recordSupportListShape("https://yfgo-game.netmarble.com/follower/list", list, 1)
        assertEquals(listOf("writing support list shape diagnostics failed"), warnings)
    }

    @Test fun friendFieldsFileIsUnchangedByTheShapeDiagnostics() {
        val store = CaptureAccounts().apply { load(directory) }
        store.recordSupportListShape("https://yfgo-game.netmarble.com/follower/list", list, 1)
        assertFalse(File(directory, "diagnostics/friend-fields.json").exists())
    }
}
