package io.github.fate_grand_automata.scripts.capture.chaldea

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class ChaldeaGameDataTest {
    private class FakeFileAccess(
        private val files: Map<String, ByteArray> = emptyMap(),
        private val sizes: Map<String, Long> = emptyMap(),
        private val unreadable: Set<String> = emptySet(),
    ) : ChaldeaFileAccess {
        val reads = mutableListOf<String>()

        override fun exists(name: String): Boolean = name in files || name in sizes
        override fun length(name: String): Long = sizes[name] ?: files[name]?.size?.toLong() ?: 0L
        override fun lastModified(name: String): Long = 0L
        override fun read(name: String): ByteArray {
            reads += name
            if (name in unreadable) throw ChaldeaIoException(ChaldeaIoException.Kind.Unreadable)
            return files[name] ?: throw ChaldeaIoException(ChaldeaIoException.Kind.NotFound)
        }
        override fun overwrite(name: String, bytes: ByteArray) = error("unused")
    }

    @Test fun parsesCostumesAndTransformsAcrossMultipleFilesAndStopsAtGap() {
        val ver = """{"build":1}""".toByteArray()
        val f1 = """[
            {"id":100100,"collectionNo":2,"profile":{"costume":{"100140":{"id":1,"battleCharaId":100140},"100150":{"id":2,"battleCharaId":100150}}}},
            {"id":505600,"collectionNo":401,"profile":{"costume":{"505630":{"id":30,"battleCharaId":505630}}},"script":{"transformInfo":{"saveTransform":505700}}}
        ]""".toByteArray()
        val f2 = """[
            {"id":505700,"collectionNo":0,"profile":{"costume":{"505740":{"id":40,"battleCharaId":505740}}},"script":{"transformInfo":{"saveTransform":505600}}}
        ]""".toByteArray()
        val f4AfterGap = """[
            {"id":999900,"collectionNo":999,"profile":{"costume":{"999940":{"id":99,"battleCharaId":999940}}}}
        ]""".toByteArray()

        val fa = FakeFileAccess(
            mapOf(
                "version.json" to ver,
                "servants.1.json" to f1,
                "servants.2.json" to f2,
                "servants.4.json" to f4AfterGap,
            ),
        )
        val raw = ChaldeaGameDataReader.readResponseJson(fa, dirExists = true, knownVersion = null)
        val parsed = assertIs<ChaldeaGameDataResult.Loaded>(ChaldeaGameDataReader.parseResponse(raw))
        val data = parsed.data
        assertEquals(sha256Hex(ver), data.version)
        assertEquals(
            mapOf(
                100100L to mapOf(1L to 100140L, 2L to 100150L),
                505600L to mapOf(30L to 505630L),
                505700L to mapOf(40L to 505740L),
            ),
            data.costumes,
        )
        assertEquals(mapOf(505600L to 505700L, 505700L to 505600L), data.transforms)
        // Gap at servants.3.json stops before reading servants.4.json
        assertFalse("servants.4.json" in fa.reads)
    }

    @Test fun unchangedVersionShortcutSkipsReadingServantFiles() {
        val ver = """{"build":2}""".toByteArray()
        val known = sha256Hex(ver)
        val fa = FakeFileAccess(
            mapOf(
                "version.json" to ver,
                "servants.1.json" to """[{"id":100100}]""".toByteArray(),
            ),
        )
        val raw = ChaldeaGameDataReader.readResponseJson(fa, dirExists = true, knownVersion = known)
        assertEquals("""{"version":"$known","unchanged":true}""", raw)
        assertEquals(listOf("version.json"), fa.reads)
        assertEquals(ChaldeaGameDataResult.Unchanged(known), ChaldeaGameDataReader.parseResponse(raw))
    }

    @Test fun gameDataErrorsForMissingOddOversizedFilesAndOversizedResult() {
        val ver = "v1".toByteArray()

        fun err(fa: FakeFileAccess, dirExists: Boolean = true): String {
            val raw = ChaldeaGameDataReader.readResponseJson(fa, dirExists, null)
            return assertIs<ChaldeaGameDataResult.Error>(ChaldeaGameDataReader.parseResponse(raw)).code
        }

        // Missing folder
        assertEquals(ChaldeaSyncCode.NotFound.name, err(FakeFileAccess(), dirExists = false))
        // Missing version.json
        assertEquals(ChaldeaSyncCode.NotFound.name, err(FakeFileAccess()))
        // Missing servants.1.json
        assertEquals(ChaldeaSyncCode.NotFound.name, err(FakeFileAccess(mapOf("version.json" to ver))))
        // Invalid JSON in servants.1.json
        assertEquals(
            ChaldeaSyncCode.InvalidJson.name,
            err(FakeFileAccess(mapOf("version.json" to ver, "servants.1.json" to "{not-array".toByteArray()))),
        )
        // Not an array in servants.1.json
        assertEquals(
            ChaldeaSyncCode.UnexpectedShape.name,
            err(FakeFileAccess(mapOf("version.json" to ver, "servants.1.json" to "{}".toByteArray()))),
        )
        // Oversized servant file (> 16 MB)
        assertEquals(
            ChaldeaSyncCode.TooLarge.name,
            err(
                FakeFileAccess(
                    files = mapOf("version.json" to ver, "servants.1.json" to "[]".toByteArray()),
                    sizes = mapOf("servants.1.json" to ChaldeaUserdataEditor.MAX_FILE_BYTES + 1L),
                ),
            ),
        )
        // Oversized serialized response (> 128,000 chars)
        val hugeServants = buildString {
            append('[')
            for (i in 1..4000) {
                if (i > 1) append(',')
                val svtId = 1000000L + i
                append("""{"id":$svtId,"profile":{"costume":{"$i":{"id":$i,"battleCharaId":${i * 10L}}}},"script":{"transformInfo":{"saveTransform":${svtId + 1L}}}}""")
            }
            append(']')
        }
        assertEquals(
            ChaldeaSyncCode.TooLarge.name,
            err(FakeFileAccess(mapOf("version.json" to ver, "servants.1.json" to hugeServants.toByteArray()))),
        )
    }

    @Test fun gameDataStoreRoundTripAndCorruptFile() {
        val dir = Files.createTempDirectory("chaldea-gd-store").toFile()
        try {
            val store = ChaldeaSyncStore(dir)
            assertNull(store.readGameData())

            val gd = ChaldeaGameData(
                version = "v1",
                costumes = mapOf(100100L to mapOf(12L to 800140L)),
                transforms = mapOf(505600L to 505700L),
            )
            store.writeGameData(gd)
            assertEquals(gd, store.readGameData())
            assertTrue(gd.sameContentAs(gd.copy(version = "v2")))
            assertFalse(gd.sameContentAs(gd.copy(costumes = emptyMap())))

            File(dir, "gamedata.json").writeText("{corrupt")
            assertNull(store.readGameData())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun gameDataResponseJsonHasValidStructure() {
        val data = ChaldeaGameData(
            version = "abc",
            costumes = mapOf(100100L to mapOf(2L to 100150L, 1L to 100140L)),
            transforms = mapOf(505600L to 505700L),
        )
        val round = ChaldeaGameData.fromJson(data.toJson())
        assertEquals(data, round)
        // Numeric keys sorted in JSON
        val obj = Json.parseToJsonElement(data.toJson()).jsonObject
        assertEquals(listOf("1", "2"), obj["costumes"]!!.jsonObject["100100"]!!.jsonObject.keys.toList())
    }
}
