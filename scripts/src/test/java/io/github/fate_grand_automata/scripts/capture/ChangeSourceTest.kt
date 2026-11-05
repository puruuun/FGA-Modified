package io.github.fate_grand_automata.scripts.capture

import kotlin.test.*

class ChangeSourceTest {
    @Test fun everyGroupMapsWithoutKeepingQueries() {
        val paths = mapOf(
            "present/receive" to ChangeSource.Group.GiftBox,
            "shop/purchase" to ChangeSource.Group.Shop,
            "gacha/draw" to ChangeSource.Group.Summon,
            "boxGacha/draw" to ChangeSource.Group.Lottery,
            "card/combine" to ChangeSource.Group.Enhancement,
            "svt/skillUp" to ChangeSource.Group.Enhancement,
            "item/recover" to ChangeSource.Group.ApRecovery,
            "battle/result" to ChangeSource.Group.ManualQuest,
            "eventMission/receive" to ChangeSource.Group.Missions,
            "mission/receive" to ChangeSource.Group.Missions,
        )
        paths.forEach { (path, group) ->
            assertEquals(ChangeSource(group), ChangeSource.of("/$path?secret=discard#fragment"))
            assertEquals(ChangeSource(group), ChangeSource.of("https://example.invalid/$path?secret=discard"))
        }
    }

    @Test fun verificationAndUnknownPathsRemainOtherAndLoseTheirQuery() {
        listOf("shop/Verify", "home/top", "item/recovery", "presentExtra/receive").forEach { path ->
            assertEquals(ChangeSource(ChangeSource.Group.Other, path), ChangeSource.of("/$path?discard=yes"))
        }
    }

    @Test fun malformedTargetIsSafe() {
        assertEquals(ChangeSource(ChangeSource.Group.Other, ""), ChangeSource.of("http://[invalid?secret=discard"))
    }
}
