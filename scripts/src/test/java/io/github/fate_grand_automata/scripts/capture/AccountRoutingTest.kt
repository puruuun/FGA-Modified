package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.*
import kotlin.test.*

class AccountRoutingTest {
    private fun route(body: String, target: String = "/home/top", current: String? = "7001") =
        AccountRouting.userId(target, Json.parseToJsonElement(body).jsonObject, current)

    @Test fun loginUsesReplacedUserGame() {
        assertEquals("7002", route("""{"cache":{"replaced":{"userGame":[{"userId":7002}]}}}""", "/login/top?ignored=1"))
    }

    @Test fun loginWithoutUserGameKeepsCurrent() {
        assertEquals("7001", route("""{"cache":{"replaced":{"userItem":[{"userId":7002}]}}}""", "/login/top"))
    }

    @Test fun updatedItemAloneIdentifiesAccount() {
        assertEquals("7002", route("""{"cache":{"updated":{"userItem":[{"userId":"7002","itemId":100}]}}}"""))
    }

    @Test fun noOwnRowsFallsBackToCurrentOrNull() {
        assertEquals("7001", route("""{"cache":{"updated":{"userPresentBox":[{"userId":7002}]}}}"""))
        assertNull(route("{}", current = null))
    }

    @Test fun nestedFollowersNeverIdentifyAccount() {
        assertEquals("7001", route("""{"cache":{"updated":{"userGame":[{"followers":[{"userId":7002}]}],"follower":[{"userId":7003}]}}}"""))
        assertEquals("7002", route("""{"cache":{"updated":{"userGame":[{"userId":7002,"follower":{"userId":7003}}]}}}"""))
    }

    @Test fun distinctIdsAcrossSectionsConflict() {
        assertEquals(AccountRouting.CONFLICT, route("""{"cache":{"replaced":{"userGame":[{"userId":7001}]},"deleted":{"userItem":[{"userId":7002}]}}}"""))
    }

    @Test fun everyOwnTableAndEverySectionIsChecked() {
        for (table in listOf("userGame", "userItem", "userSvt", "userSvtCollection", "userQuest", "userEquip", "userEvent", "userCommandCode")) {
            for (section in listOf("replaced", "updated", "deleted")) {
                assertEquals("7002", route("""{"cache":{"$section":{"$table":[{"userId":7002},{"userId":"7002"}]}}}"""))
            }
        }
    }

    @Test fun malformedShapesNeverThrow() {
        for (body in listOf("{}", """{"cache":[]} """, """{"cache":{"updated":{"userGame":[null,1,{"userId":{}},{"userId":null}]}}}""")) {
            assertEquals("7001", route(body))
        }
    }
}
