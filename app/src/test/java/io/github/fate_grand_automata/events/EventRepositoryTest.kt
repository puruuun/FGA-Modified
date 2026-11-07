package io.github.fate_grand_automata.events

import io.github.fate_grand_automata.scripts.events.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.InputStream
import kotlin.test.*

class EventRepositoryTest {
    @TempDir lateinit var directory: File
    private var time = 1_000_000L
    private val urls = mutableListOf<String>()
    private var schedule = """[{"id":1,"type":"eventQuest","name":"Sample","startedAt":1,"endedAt":1000000}]"""
    private var equips = """[{"id":10,"skills":[{"functions":[{"funcType":"eventDropUp","svals":[{"EventId":1}]}]}]}]"""
    private var banner = """{"banner":"https://static.atlasacademy.io/KR/banner.png"}"""
    private var fail = false
    private var closed = 0
    private val download: (String) -> InputStream = { url ->
        urls += url
        if (fail) error("offline")
        val text = when {
            "basic_event" in url -> schedule
            "nice_equip" in url -> equips
            else -> banner
        }
        object : java.io.ByteArrayInputStream(text.toByteArray()) {
            override fun close() { closed++; super.close() }
        }
    }
    private fun repository() = EventRepository(directory, download) { time }
    private fun calls(part: String) = urls.count { part in it }

    @Test fun scheduleRefreshesOncePerDayAcrossInstancesAndClosesStreams() = runBlocking {
        val first = repository().refresh()
        assertEquals(3, closed)
        time += EventRepository.DAY_MS - 1
        assertEquals(first, repository().refresh())
        assertEquals(1, calls("basic_event"))
        time++
        repository().refresh()
        assertEquals(2, calls("basic_event"))
        assertEquals(1, calls("nice_equip"))
    }

    @Test fun noLimitedEventsMeansNoBonusOrBannerFetch() = runBlocking {
        schedule = schedule.replace("eventQuest", "questCampaign")
        val data = repository().refresh()
        assertTrue(data.running(time / 1000).visible)
        assertEquals(1, urls.size)
    }

    @Test fun bonusFetchNeedsMissingEventAndItsOwnDailyAttemptBudget() = runBlocking {
        val repository = repository()
        repository.refresh()
        // A newer schedule can introduce another event before the bonus retry budget expires.
        File(directory, "basic_event.attempt").delete()
        schedule = schedule.replace("\"id\":1", "\"id\":2")
        time++
        assertFalse(2L in repository.refresh().bonuses)
        assertEquals(1, calls("nice_equip"))
        time += EventRepository.DAY_MS
        assertEquals(emptyList(), repository.refresh().bonuses[2])
        assertEquals(2, calls("nice_equip"))
        time += EventRepository.DAY_MS
        repository.refresh()
        assertEquals(2, calls("nice_equip"))
    }

    @Test fun bannersAreCachedPerIdIncludingNoBanner() = runBlocking {
        banner = """{"banner":null}"""
        assertTrue(1L in repository().refresh().banners)
        time += EventRepository.DAY_MS
        repository().refresh()
        assertEquals(1, calls("nice/KR/event/1"))
        schedule = schedule.replace("\"id\":1", "\"id\":2")
        time += EventRepository.DAY_MS
        repository().refresh()
        assertEquals(1, calls("nice/KR/event/2"))
    }

    @Test fun rejectsNonAtlasAndNonHttpsBannerAddresses() = runBlocking {
        val unsafe = listOf("https://evil.example/banner.png", "http://static.atlasacademy.io/banner.png",
            "https://static.atlasacademy.io.evil.example/x", "https://user@static.atlasacademy.io/x", "https://static.atlasacademy.io:444/x")
        unsafe.forEach { address ->
            File(directory, "event_banners.json").delete()
            File(directory, "event_banner_1.attempt").delete()
            banner = """{"banner":"$address"}"""
            assertNull(repository().refresh().banners[1])
        }
    }

    @Test fun failedDownloadsRetainOldDataAndDoNotRepeatBeforeADay() = runBlocking {
        val first = repository().refresh()
        fail = true
        time += EventRepository.DAY_MS
        assertEquals(first, repository().refresh())
        assertEquals(2, calls("basic_event"))
        assertEquals(first, repository().refresh())
        assertEquals(2, calls("basic_event"))
        assertEquals(first, repository().load())
    }

    @Test fun failedBonusAndBannerRequestsKeepOldMapAndRetryNextDay() = runBlocking {
        val first = repository().refresh()
        schedule = schedule.replace("\"id\":1", "\"id\":2")
        time += EventRepository.DAY_MS
        // Let the schedule succeed, then fail the remaining requests.
        val cache = EventRepository(directory, { url ->
            if ("basic_event" in url) download(url) else error("offline")
        }) { time }
        val failed = cache.refresh()
        assertEquals(first.bonuses, failed.bonuses)
        assertEquals(first.banners, failed.banners)
        repository().refresh()
        assertEquals(0, calls("nice/KR/event/2"))
        time += EventRepository.DAY_MS
        val retried = repository().refresh()
        assertEquals(emptyList(), retried.bonuses[2])
        assertTrue(2L in retried.banners)
    }

    @Test fun malformedResponsesKeepGoodScheduleAndOnlyCompactMapsAreStored() = runBlocking {
        val first = repository().refresh()
        assertFalse(File(directory, "nice_equip.json").exists())
        assertFalse(File(directory, "event_bonuses.json").readText().contains("functions"))
        schedule = """[{"id":"bad"}]"""
        time += EventRepository.DAY_MS
        assertEquals(first, repository().refresh())
    }

    @Test fun failedFirstDownloadLeavesCardHidden() = runBlocking {
        fail = true
        val data = repository().refresh()
        assertNull(data.events)
        assertFalse(data.running(time / 1000).visible)
        assertEquals(1, urls.size)
    }
}
