package io.github.fate_grand_automata.util.chaldea

import io.github.fate_grand_automata.ui.skill_maker.SkillMakerModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChaldeaCommandConverterTest {
    private val fixtures = Json.parseToJsonElement(javaClass.getResource("/chaldea/converter-parity.json")!!.readText()).jsonArray
    private val user = fixtures.first().jsonObject
    private val userUrl = javaClass.getResource("/chaldea/user-share-url.txt")!!.readText()
    private val reported = Json.parseToJsonElement(javaClass.getResource("/chaldea/reported-target-battle.json")!!.readText()).jsonObject
    private val reportedUrl = javaClass.getResource("/chaldea/reported-target-share-url.txt")!!.readText()

    private fun converter(fixture: JsonObject = user) = ChaldeaCommandConverter(object : ChaldeaMetadata {
        val metadata = fixture.getValue("metadata").jsonObject
        override suspend fun servant(id: Int) = metadata.getValue("svt-$id").jsonObject
        override suspend fun mysticCode(id: Int) = metadata.getValue("MC-$id").jsonObject
    })

    @TestFactory
    fun `matches reference converter and remains editable by the FGA skill maker`() = fixtures.map { entry ->
        val fixture = entry.jsonObject
        dynamicTest(fixture.text("name")!!) {
            runBlocking {
                val actual = converter(fixture).convert(encode(fixture.getValue("data").toString()))
                assertEquals(fixture.text("expected"), actual)
                // Checks the actual parser and graphical editor rather than a duplicate grammar.
                assertEquals(actual, SkillMakerModel(actual).toString())
            }
        }
    }

    @Test fun `user supplied link produces exactly the supplied command`() = runBlocking {
        assertEquals(15, ChaldeaShare.decode(userUrl).array("actions")!!.size)
        assertEquals("ghi1x31ih3gt2acbdefj654", converter().convert(userUrl))
    }

    @TestFactory
    fun `ignoring targets removes every enemy command while preserving other actions`() = fixtures.map { entry ->
        val fixture = entry.jsonObject
        dynamicTest("ignore targets: ${fixture.text("name")}") {
            runBlocking {
                val actual = converter(fixture).convert(encode(fixture.getValue("data").toString()), ignoreEnemyTargeting = true)
                assertEquals(fixture.text("expected")!!.replace(Regex("t[123]"), ""), actual)
                assertTrue('t' !in actual)
                assertEquals(actual, SkillMakerModel(actual).toString())
            }
        }
    }

    @Test fun `reported special enemy slot is rejected when targeting is enabled`() = runBlocking {
        assertEquals(13, ChaldeaShare.decode(reportedUrl).array("actions")!!.size)
        assertEquals(ChaldeaImportError.TARGETING, assertFailsWith<ChaldeaImportException> {
            converter(reported).convert(reportedUrl)
        }.error)
    }

    @Test fun `reported link imports skills and attacks successfully with targeting ignored`() = runBlocking {
        val command = converter(reported).convert(reportedUrl, ignoreEnemyTargeting = true)
        assertEquals("defab1cgix31hgj54", command)
        assertEquals(reported.text("expectedIgnoringTargets"), command)
        assertTrue('t' !in command)
        assertEquals(command, SkillMakerModel(command).toString())
    }

    @Test fun `ignore option is per import and does not leak into later conversions`() = runBlocking {
        val converter = converter()
        assertEquals("ghi1x31ih3gacbdefj654", converter.convert(userUrl, ignoreEnemyTargeting = true))
        assertEquals("ghi1x31ih3gt2acbdefj654", converter.convert(userUrl))
    }

    @Test fun `all special enemy target values are omitted without dropping enemy skills`() = runBlocking {
        val fixture = fixtures.first { it.jsonObject.text("name") == "enemy-target-reuse-and-change" }.jsonObject
        val data = fixture.getValue("data").jsonObject
        for (target in listOf(-10, -1, 0, 1, 2, 3, 4, 5, 99)) {
            val actions = data.array("actions")!!.map { element ->
                val action = element.jsonObject
                JsonObject(action + ("options" to JsonObject(action.obj("options")!! + ("enemyTarget" to JsonPrimitive(target)))))
            }
            val changed = JsonObject(data + ("actions" to JsonArray(actions)))
            assertEquals("ccc4", converter(fixture).convert(encode(changed.toString()), ignoreEnemyTargeting = true))
        }
    }

    @Test fun `ignoring targets still rejects corrupt links and missing order change data`() = runBlocking {
        assertEquals(ChaldeaImportError.DATA, assertFailsWith<ChaldeaImportException> {
            converter().convert("https://link.chaldea.center/?data=Gbad", ignoreEnemyTargeting = true)
        }.error)
        val data = JsonObject(user.getValue("data").jsonObject - "delegate")
        assertEquals(ChaldeaImportError.DATA, assertFailsWith<ChaldeaImportException> {
            converter().convert(encode(data.toString()), ignoreEnemyTargeting = true)
        }.error)
    }

    @Test fun `old gzip unpadded URL safe and zlib links preserve the command`() = runBlocking {
        val json = user.getValue("data").toString()
        for ((prefix, gzip) in listOf("G" to true, "" to true, "D" to false, "Z" to false)) {
            assertEquals(user.text("expected"), converter().convert(encode(json, prefix, gzip).trimEnd('=')), prefix)
        }
    }

    @Test fun `unrelated quest stats equipment and result fields do not affect commands`() = runBlocking {
        val data = user.getValue("data").jsonObject
        val changed = JsonObject(data + mapOf(
            "quest" to JsonPrimitive("ignored"), "options" to JsonArray(emptyList()),
            "isCritTeam" to JsonPrimitive(true), "unrelated" to JsonPrimitive("ignored")
        ))
        assertEquals(user.text("expected"), converter().convert(encode(changed.toString())))
    }

    @Test fun `invalid URLs and missing or ambiguous data are rejected`() {
        for (link in listOf("", "not a link", "https://link.chaldea.center/laplace/share", "file:///tmp/x?data=abc",
            "https://link.chaldea.center/?data=x&data=y", "https://link.chaldea.center/?data=%invalid")) {
            assertEquals(ChaldeaImportError.LINK, assertFailsWith<ChaldeaImportException> { ChaldeaShare.decode(link) }.error)
        }
    }

    @Test fun `corrupt compressed data and incomplete battle structures are rejected`() {
        for (link in listOf("https://link.chaldea.center/?data=Gbad", encode("[]"), encode("{}"),
            encode("{\"actions\":[{}],\"team\":{}}"))) {
            assertEquals(ChaldeaImportError.DATA, assertFailsWith<ChaldeaImportException> { ChaldeaShare.decode(link) }.error)
        }
    }

    @Test fun `formation only links fail before requesting game data`() = runBlocking {
        val data = JsonObject(user.getValue("data").jsonObject + ("actions" to JsonArray(emptyList())))
        val neverFetch = object : ChaldeaMetadata {
            override suspend fun servant(id: Int): JsonObject = error("Must not fetch")
            override suspend fun mysticCode(id: Int): JsonObject = error("Must not fetch")
        }
        assertEquals(ChaldeaImportError.NO_ACTIONS, assertFailsWith<ChaldeaImportException> {
            ChaldeaCommandConverter(neverFetch).convert(encode(data.toString()))
        }.error)
    }

    @Test fun `decompression has a fixed output limit`() {
        val oversized = " ".repeat(ChaldeaShare.MAX_JSON_BYTES + 1)
        assertEquals(ChaldeaImportError.DATA, assertFailsWith<ChaldeaImportException> {
            ChaldeaShare.decode(encode(oversized))
        }.error)
    }

    @Test fun `unknown actions are rejected rather than silently discarded`() = runBlocking {
        val data = user.getValue("data").jsonObject
        val changed = JsonObject(data + ("actions" to JsonArray(data.array("actions")!! +
            JsonObject(mapOf("type" to JsonPrimitive("new-action"))))))
        assertEquals(ChaldeaImportError.UNSUPPORTED, assertFailsWith<ChaldeaImportException> {
            converter().convert(encode(changed.toString()))
        }.error)
    }

    @Test fun `missing exact skill metadata does not produce a partial command`() = runBlocking {
        val metadata = user.getValue("metadata").jsonObject
        val broken = JsonObject(metadata.mapValues { (key, value) ->
            if (key == "svt-101300") JsonObject(value.jsonObject + ("skills" to JsonArray(emptyList()))) else value
        })
        val fixture = JsonObject(user + ("metadata" to broken))
        assertEquals(ChaldeaImportError.METADATA, assertFailsWith<ChaldeaImportException> {
            converter(fixture).convert(userUrl)
        }.error)
    }

    @Test fun `unsupported choice counts are rejected before saving a command`() = runBlocking {
        val fixture = fixtures.first { it.jsonObject.text("name") == "choice-2-with-target" }.jsonObject
        val metadata = fixture.getValue("metadata").toString().replace("\"btn\":[{},{}]", "\"btn\":[{},{},{},{}]")
        val changed = JsonObject(fixture + ("metadata" to Json.parseToJsonElement(metadata)))
        assertEquals(ChaldeaImportError.UNSUPPORTED, assertFailsWith<ChaldeaImportException> {
            converter(changed).convert(encode(changed.getValue("data").toString()))
        }.error)
    }

    @Test fun `missing order change choices do not silently turn into a master skill`() = runBlocking {
        val data = JsonObject(user.getValue("data").jsonObject - "delegate")
        assertEquals(ChaldeaImportError.DATA, assertFailsWith<ChaldeaImportException> {
            converter().convert(encode(data.toString()))
        }.error)
    }

    @Test fun `out of range skill positions do not produce commands for another servant`() = runBlocking {
        val data = user.getValue("data").jsonObject
        val action = JsonObject(data.array("actions")!!.first().jsonObject + ("svt" to JsonPrimitive(6)))
        val changed = JsonObject(data + ("actions" to JsonArray(listOf(action))))
        assertEquals(ChaldeaImportError.DATA, assertFailsWith<ChaldeaImportException> {
            converter().convert(encode(changed.toString()))
        }.error)
    }

    @Test fun `repeated imports have no party state left over from previous conversions`() = runBlocking {
        val converter = converter()
        repeat(3) { assertEquals(user.text("expected"), converter.convert(userUrl)) }
    }

    @Test fun `only the relevant metadata IDs are requested once per import`() = runBlocking {
        val ids = mutableListOf<String>()
        val provider = object : ChaldeaMetadata {
            override suspend fun servant(id: Int): JsonObject {
                ids.add("svt-$id")
                return user.getValue("metadata").jsonObject.getValue("svt-$id").jsonObject
            }
            override suspend fun mysticCode(id: Int): JsonObject {
                ids.add("MC-$id")
                return user.getValue("metadata").jsonObject.getValue("MC-$id").jsonObject
            }
        }
        assertEquals(user.text("expected"), ChaldeaCommandConverter(provider).convert(userUrl))
        assertEquals(setOf("svt-105600", "svt-106000", "svt-101300", "svt-103600", "MC-210"), ids.toSet())
        assertEquals(5, ids.size)
        assertTrue(ids.none { "equip" in it })
    }

    private fun encode(json: String, prefix: String = "G", gzip: Boolean = true): String {
        val output = ByteArrayOutputStream()
        val zip = if (gzip) GZIPOutputStream(output) else DeflaterOutputStream(output)
        zip.use { it.write(json.toByteArray()) }
        return "https://link.chaldea.center/laplace/share?data=$prefix${Base64.UrlSafe.encode(output.toByteArray())}"
    }
}
