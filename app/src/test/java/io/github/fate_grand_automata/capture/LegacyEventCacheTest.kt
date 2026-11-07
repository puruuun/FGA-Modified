package io.github.fate_grand_automata.capture

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class LegacyEventCacheTest {
    @TempDir lateinit var directory: File

    private val removed = listOf("basic_event.json", "event_bonuses.json", "event_banners.json",
        "basic_event.attempt", "event_bonuses.attempt", "event_banner_1.attempt", "event_banner_20261107.attempt")
    private val kept = listOf("basic_servant.json", "basic_equip.json", "basic_servant.attempt", "item_icons.attempt",
        "basic_event_details.attempt", "event_notes.json", "mystic.json")

    @Test fun deletesExactlyTheEventCardFilesAndKeepsTheOthers() {
        (removed + kept).forEach { File(directory, it).writeText("x") }
        File(directory, "event_banner_dir.attempt").mkdir()
        deleteLegacyEventCache(directory)
        removed.forEach { assertFalse(File(directory, it).exists(), it) }
        kept.forEach { assertTrue(File(directory, it).exists(), it) }
        assertTrue(File(directory, "event_banner_dir.attempt").isDirectory)
    }

    @Test fun isHarmlessWhenFilesOrDirectoryAreAbsent() {
        deleteLegacyEventCache(directory)
        deleteLegacyEventCache(File(directory, "missing"))
        File(directory, "basic_servant.json").writeText("x")
        deleteLegacyEventCache(directory)
        deleteLegacyEventCache(directory)
        assertEquals(listOf("basic_servant.json"), directory.list()!!.toList())
    }
}
