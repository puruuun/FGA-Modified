package io.github.fate_grand_automata.util.portraits

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupportFolderRenameTest {
    private val source = "No.444"
    private val destination = "No.444 Ｕ－オルガマリー"

    @Test fun `provider failure after moving a folder still completes migration`() {
        for (throws in listOf(false, true)) {
            val folders = mutableSetOf(source)
            assertTrue(SupportFolderRename.confirmed(source, destination, folders::contains) {
                folders.remove(source)
                folders.add(destination)
                if (throws) throw java.io.FileNotFoundException(source)
                false
            })
            assertEquals(setOf(destination), folders)
        }
    }

    @Test fun `successful response without an actual move never migrates selections`() {
        val folders = mutableSetOf(source)
        assertFalse(SupportFolderRename.confirmed(source, destination, folders::contains) { true })
        assertEquals(setOf(source), folders)
    }

    @Test fun `failed and ambiguous moves never count as completed`() {
        val folders = mutableSetOf(source)
        assertFalse(SupportFolderRename.confirmed(source, destination, folders::contains) { false })
        assertFalse(SupportFolderRename.confirmed(source, destination, folders::contains) {
            folders.add(destination)
            true
        })
        assertEquals(setOf(source, destination), folders)
    }

    @Test fun `an existing destination or missing source never invokes rename`() {
        for (folders in listOf(setOf(source, destination), setOf(destination), emptySet())) {
            assertFalse(SupportFolderRename.confirmed(source, destination, folders::contains) {
                error("Must not rename over an existing document")
            })
        }
    }
}
