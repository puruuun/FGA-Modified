package io.github.fate_grand_automata.util.chaldea

import io.github.fate_grand_automata.scripts.models.AutoSkillCommand
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChaldeaWaveBoundaryTest {
    private val fixtures = Json.parseToJsonElement(javaClass.getResource("/chaldea/converter-parity.json")!!.readText()).jsonArray
    private fun fixture(name: String) = fixtures.first { it.jsonObject.text("name") == name }.jsonObject

    /** Fixture metadata plus a fake quest source. [quest] null = no quest data, [fail] = the load throws. */
    private fun converter(
        fixture: JsonObject, quest: JsonObject? = null, fail: Boolean = false, requests: MutableList<String> = mutableListOf()
    ) = ChaldeaCommandConverter(object : ChaldeaMetadata {
        val metadata = fixture.getValue("metadata").jsonObject
        override suspend fun servant(id: Int) = metadata.getValue("svt-$id").jsonObject
        override suspend fun mysticCode(id: Int) = metadata.getValue("MC-$id").jsonObject
        override suspend fun quest(id: Int, phase: Int): JsonObject? {
            requests.add("$id/$phase")
            if (fail) throw IOException("offline")
            return quest
        }
    })

    private fun stages(count: Int) =
        JsonObject(mapOf("id" to JsonPrimitive(94150847), "stages" to JsonArray(List(count) { JsonObject(emptyMap()) })))

    /** Share URL of a fixture battle, pointing at the owner's reported quest (94150847, phase 1). */
    private fun url(fixture: JsonObject, id: Int = 94150847, phase: Int = 1): String {
        val data = fixture.getValue("data").jsonObject
        val quest = JsonObject(mapOf("id" to JsonPrimitive(id), "phase" to JsonPrimitive(phase)))
        return encode(JsonObject(data + ("quest" to quest)).toString())
    }

    @Test fun `one wave quest turns every boundary into a same wave turn`() = runBlocking {
        val f = fixture("np-self-death") // two attacks: reference output 4,#,a34
        val link = url(f)
        assertEquals(2, ChaldeaShare.decode(link).array("actions")!!.count { it.jsonObject.text("type") == "attack" })
        val result = converter(f, stages(1)).convertWithBoundaries(link)
        assertEquals(1, result.questWaves)
        assertFalse(result.ambiguous)
        assertEquals(listOf(false), result.preset)
        val command = result.build(result.preset)
        assertEquals("4,a34", command)
        AutoSkillCommand.parse(command)
    }

    @Test fun `attacks not exceeding waves keep the wave separators of the reference converter`() = runBlocking {
        val f = fixture("np-self-death")
        val result = converter(f, stages(2)).convertWithBoundaries(url(f))
        assertFalse(result.ambiguous)
        assertEquals(listOf(true), result.preset)
        assertEquals("4,#,a34", result.build(result.preset))
        // Fewer attacks than waves (the record stops early) is still today's output.
        val early = converter(f, stages(5)).convertWithBoundaries(url(f))
        assertFalse(early.ambiguous)
        assertEquals("4,#,a34", early.build(early.preset))
    }

    @Test fun `more attacks than waves is ambiguous and a user choice is applied`() = runBlocking {
        val f = fixture("np-retreat") // attacks 4 and a34, then a trailing skill: 4,#,a34,#,b
        val result = converter(f, stages(2)).convertWithBoundaries(url(f))
        assertEquals(2, result.questWaves)
        assertEquals(2, result.boundaryCount)
        assertEquals(listOf(true, true), result.preset)
        assertEquals("4,#,a34,#,b", result.build(result.preset))
        val chosen = result.build(listOf(false, true))
        assertEquals("4,a34,#,b", chosen)
        AutoSkillCommand.parse(chosen)
        assertEquals("4,a34,b", result.build(listOf(false, false)))
        // Six attacks in a two wave quest, and exactly six waves is no longer ambiguous.
        val long = fixture("five-turn-death-timer")
        val many = converter(long, stages(2)).convertWithBoundaries(url(long))
        assertTrue(many.ambiguous)
        assertEquals(long.text("expected"), many.build(many.preset))
        assertFalse(converter(long, stages(6)).convertWithBoundaries(url(long)).ambiguous)
    }

    @Test fun `quest load failure or missing data falls back to all waves marked ambiguous`() = runBlocking {
        val f = fixture("np-self-death")
        val link = url(f)
        val failed = converter(f, fail = true).convertWithBoundaries(link)
        val missing = converter(f, quest = null).convertWithBoundaries(link)
        val empty = converter(f, JsonObject(mapOf("stages" to JsonArray(emptyList())))).convertWithBoundaries(link)
        for (result in listOf(failed, missing, empty)) {
            assertTrue(result.ambiguous)
            assertEquals(listOf(true), result.preset)
            assertEquals("4,#,a34", result.build(result.preset))
        }
        assertEquals(null, failed.questWaves)
        // Share data without a usable quest never asks the source.
        val requests = mutableListOf<String>()
        val data = f.getValue("data").jsonObject
        val noQuest = encode(JsonObject(data + ("quest" to JsonPrimitive("x"))).toString())
        assertTrue(converter(f, stages(1), requests = requests).convertWithBoundaries(noQuest).ambiguous)
        assertEquals(emptyList(), requests)
    }

    @Test fun `the quest is requested by the id and phase of the share data`() = runBlocking {
        val f = fixture("np-self-death")
        val requests = mutableListOf<String>()
        converter(f, stages(1), requests = requests).convertWithBoundaries(url(f, id = 94150847, phase = 2))
        assertEquals(listOf("94150847/2"), requests)
    }

    @Test fun `a single attack has no boundary and does not look the quest up`() = runBlocking {
        val f = fixture("normal-turn-without-np")
        val data = f.getValue("data").jsonObject
        val attack = data.array("actions")!!.first { it.jsonObject.text("type") == "attack" }
        val onlyOne = JsonObject(data + ("actions" to JsonArray(listOf(attack))))
        val requests = mutableListOf<String>()
        val result = converter(f, stages(1), requests = requests).convertWithBoundaries(encode(onlyOne.toString()))
        assertEquals(0, result.boundaryCount)
        assertEquals(emptyList(), requests)
    }

    @Test fun `all wave output is byte identical to the reference output for every fixture`() = runBlocking {
        for (entry in fixtures) {
            val f = entry.jsonObject
            val link = encode(f.getValue("data").toString())
            val result = converter(f).convertWithBoundaries(link)
            assertEquals(f.text("expected"), result.build(), f.text("name"))
            assertEquals(f.text("expected"), result.build(List(result.boundaryCount) { true }), f.text("name"))
            assertEquals(f.text("expected"), converter(f).convert(link), f.text("name"))
        }
    }

    private fun encode(json: String): String {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(json.toByteArray()) }
        return "https://link.chaldea.center/laplace/share?data=G${Base64.UrlSafe.encode(output.toByteArray())}"
    }
}
