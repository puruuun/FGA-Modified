package io.github.fate_grand_automata.capture

import java.time.LocalDateTime
import java.util.Locale
import kotlin.test.*

class ChaldeaFilesTest {
    private val old = "chaldea-kr-20261104-0901.json"
    private val latest = "chaldea-kr-20261104-0902.json"

    @Test fun filenameHasZeroPaddedLocalMinute() {
        assertEquals(old, ChaldeaFiles.name(LocalDateTime.of(2026, 11, 4, 9, 1, 59)))
    }

    @Test fun filenameDoesNotDependOnDefaultLocale() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar"))
            assertEquals(old, ChaldeaFiles.name(LocalDateTime.of(2026, 11, 4, 9, 1)))
        } finally { Locale.setDefault(before) }
    }

    @Test fun keepsNewestOfUnsortedNames() {
        assertEquals(listOf(old), ChaldeaFiles.toDelete(listOf(latest, old)))
    }

    @Test fun neverDeletesForeignNames() {
        val foreign = listOf("account.json", "fga-capture-ca.cer", "chaldea-jp-20261104-0901.json",
            "chaldea-kr-20261104-0901.json.bak", "chaldea-kr-20261104-0901 (1).json", "chaldea-kr-bad.json")
        assertEquals(listOf(old), ChaldeaFiles.toDelete(foreign + old + latest))
    }

    @Test fun emptyAndSingleExportNeedNoDeletion() {
        assertTrue(ChaldeaFiles.toDelete(emptyList()).isEmpty())
        assertTrue(ChaldeaFiles.toDelete(listOf(latest)).isEmpty())
    }

    @Test fun keepsFreshExportWhenClockMovedBackwards() {
        assertEquals(listOf(latest), ChaldeaFiles.toDelete(listOf(latest, old), newest = old))
    }

    @Test fun minuteCollisionIsRetainedByNameForStoreToResolveById() {
        assertEquals(listOf(old), ChaldeaFiles.toDelete(listOf(latest, old, latest), latest))
    }
}
