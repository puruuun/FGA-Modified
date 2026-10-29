package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.weekly.WarInfo
import kotlin.test.*
import io.github.fate_grand_automata.scripts.weekly.*

/** Atlas war data keeps the map size and the gimmick layers; issue #111 uses only the size. */
class WeeklyMapCacheTest {
    @Test fun `map size and gimmicks decode from war data and survive the compact model`() {
        val war = AtlasWeekly.wars("""[{"id":306,"maps":[{"id":30601,"mapImage":"base","mapImageW":2048,"mapImageH":1440,
            "mapGimmicks":[{"image":"layer","x":1024,"y":720,"depthOffset":-100,"scale":1000}]}],
            "spots":[{"id":1,"quests":[{"id":1,"name":"quest","type":"free","consumeType":"ap","consume":21,
            "afterClear":"repeatLast","phases":[1]}]}]}]""", 100).wars.getValue(306)
        assertEquals(MapGimmick("layer", 1024.0, 720.0, -100, 1000), war.maps.single().mapGimmicks.single())
        val json = kotlinx.serialization.json.Json
        val saved = json.encodeToString(WarInfo.serializer(), war)
        assertEquals(war, json.decodeFromString(WarInfo.serializer(), saved))
    }
}
