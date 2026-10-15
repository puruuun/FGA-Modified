package io.github.fate_grand_automata.util.portraits

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServantPortraitFolderTest {
    @Test fun `folder includes the collection number and full JP servant name`() {
        assertEquals("No.444 U－オルガマリー", ServantPortraitFolder.name(444, "U－オルガマリー"))
    }

    @Test fun `names cannot create nested paths or invalid portable filenames`() {
        val folder = ServantPortraitFolder.name(90, " ../Nero\\Bride: *?\"<>|\n.. ")
        assertEquals("No.90 Nero Bride", folder)
        assertFalse(folder.any { it in "\\/:*?\"<>|\n" })
        assertEquals("No.90 Servant", ServantPortraitFolder.name(90, "../\\.."))
    }

    @Test fun `long names stay within a portable folder length and keep unicode intact`() {
        val folder = ServantPortraitFolder.name(9999, "あ".repeat(79) + "😀end")
        assertTrue(folder.toByteArray(Charsets.UTF_8).size <= 255)
        assertFalse(folder.last().isHighSurrogate())
    }

    @Test fun `old number only config resolves renamed folder without confusing nearby numbers`() {
        val folders = listOf("No.44 Other", "No.444 U－オルガマリー", "No.4444 Other", "Arcueid")
        assertEquals("No.444 U－オルガマリー", ServantPortraitFolder.resolve("No.444", folders))
        assertEquals("No.444 U－オルガマリー", ServantPortraitFolder.resolve("No.444 Old name", folders))
        assertEquals("Arcueid", ServantPortraitFolder.resolve("Arcueid", folders))
        assertNull(ServantPortraitFolder.resolve("No.4", folders))
        assertNull(ServantPortraitFolder.resolve("../No.444", folders))
    }

    @Test fun `exact folder wins over another name for the same number`() {
        val folders = listOf("No.444 Old name", "No.444 New name")
        assertEquals("No.444 New name", ServantPortraitFolder.resolve("No.444 New name", folders))
    }

    @Test fun `renaming keeps other preferred servants and merges aliases for the same number`() {
        val selected = setOf("No.444", "No.444 Old name", "No.44 Other", "Arcueid")
        assertEquals(setOf("No.444 U－オルガマリー", "No.44 Other", "Arcueid"),
            ServantPortraitFolder.renameSelections(selected, 444, "No.444 U－オルガマリー"))
    }
}
