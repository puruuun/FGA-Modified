package io.github.fate_grand_automata.util.portraits

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ServantPortraitMetadataTest {
    private val example = """{
      "id": 100600, "collectionNo": 90, "name": "Nero Bride",
      "extraAssets": {
        "faces": {
          "ascension": {"1":"https://static.atlasacademy.io/JP/Faces/f_1006000.png", "2":"https://static.atlasacademy.io/JP/Faces/f_1006001.png"},
          "costume": {"100630":"https://static.atlasacademy.io/JP/Faces/f_1006300.png"},
          "transformGroup": {"1":"https://static.atlasacademy.io/JP/Faces/f_1006000_group.png"}
        },
        "facesChange": {"ascension":{"3":{"1":"https://static.atlasacademy.io/JP/Faces/f_1006002_1.png"}}},
        "charaGraph": {"ascension":{"1":"https://static.atlasacademy.io/JP/CharaGraph/100600/100600a.png"}}
      }
    }"""

    @Test fun `collection number resolves all portrait families and excludes full card artwork`() {
        val data = ServantPortraitMetadata.parse(example, 90)
        assertEquals(100600, data.servantId)
        assertEquals("No.90", data.folderName)
        assertEquals(listOf("faces_ascension_1", "faces_ascension_2", "faces_costume_100630", "faces_transformGroup_1", "facesChange_ascension_3_1"), data.portraits.map { it.key })
    }

    @Test fun `shared pictures do not produce duplicate templates`() {
        val data = ServantPortraitMetadata.parse(example.replace("f_1006001.png", "f_1006000.png"), 90)
        assertEquals(4, data.portraits.size)
    }

    @Test fun `a different collection number is never saved under the requested number`() {
        assertFailsWith<PortraitImportException> { ServantPortraitMetadata.parse(example, 91) }
        assertFailsWith<PortraitImportException> { ServantPortraitMetadata.parse(example.replace("\"collectionNo\": 90", "\"collectionNo\": 0"), 0) }
    }

    @Test fun `malformed or empty metadata fails before generating files`() {
        for (text in listOf("{}", "[]", "garbage", example.replace("\"faces\":", "\"unused\":").replace("\"facesChange\":", "\"alsoUnused\":"))) {
            assertFailsWith<Exception> { ServantPortraitMetadata.parse(text, 90) }
        }
    }

    @Test fun `only intended atlas portrait URLs and safe filenames are accepted`() {
        for (url in listOf(
            "http://static.atlasacademy.io/JP/Faces/f_1006000.png",
            "https://static.atlasacademy.io.evil.example/JP/Faces/f_1006000.png",
            "https://static.atlasacademy.io/JP/CharaGraph/f_1006000.png",
            "https://static.atlasacademy.io/JP/Faces/f_1006000.png?redirect=other",
            "https://static.atlasacademy.io/JP/Faces/../../file.png"
        )) assertFalse(ServantPortraitMetadata.validPortraitUrl(url), url)
        assertFailsWith<PortraitImportException> {
            ServantPortraitMetadata.parse(example.replace("\"costume\":", "\"../costume\":"), 90)
        }
    }
}
