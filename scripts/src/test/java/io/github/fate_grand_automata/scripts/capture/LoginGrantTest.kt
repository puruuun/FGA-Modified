package io.github.fate_grand_automata.scripts.capture

import kotlin.test.Test
import kotlin.test.assertEquals

class LoginGrantTest {
    private fun login(success: String, resCode: String = "00") =
        """[{"nid":"other","resCode":"00","success":{"addFriendPoint":999}},{"nid":"login","resCode":"$resCode","success":$success}]"""

    @Test fun sumsTheThreeFields() =
        assertEquals(600, LoginGrant.friendPoints(login("""{"addFriendPoint":100,"addFollowFriendPoint":200,"addRecommendSupportFriendPoint":300}""")))

    @Test fun eachFieldAlone() {
        assertEquals(7, LoginGrant.friendPoints(login("""{"addFriendPoint":7}""")))
        assertEquals(8, LoginGrant.friendPoints(login("""{"addFollowFriendPoint":8}""")))
        assertEquals(9, LoginGrant.friendPoints(login("""{"addRecommendSupportFriendPoint":9}""")))
    }

    @Test fun zeroAndMissingFieldsAreZero() {
        assertEquals(0, LoginGrant.friendPoints(login("""{"addFriendPoint":0,"addFollowFriendPoint":0,"addRecommendSupportFriendPoint":0}""")))
        assertEquals(0, LoginGrant.friendPoints(login("{}")))
        assertEquals(0, LoginGrant.friendPoints("""[{"nid":"login","resCode":"00"}]"""))
    }

    @Test fun failedLoginGrantsNothing() =
        assertEquals(0, LoginGrant.friendPoints(login("""{"addFriendPoint":100}""", resCode = "01")))

    @Test fun nonIntegerAndNegativeValuesCountAsZero() {
        assertEquals(0, LoginGrant.friendPoints(login("""{"addFriendPoint":"50","addFollowFriendPoint":1.5,"addRecommendSupportFriendPoint":null}""")))
        assertEquals(30, LoginGrant.friendPoints(login("""{"addFriendPoint":-100,"addFollowFriendPoint":30}""")))
    }

    @Test fun noLoginEntryOrUnreadableTextIsZero() {
        assertEquals(0, LoginGrant.friendPoints("""[{"nid":"other","resCode":"00","success":{"addFriendPoint":5}}]"""))
        assertEquals(0, LoginGrant.friendPoints("[]"))
        assertEquals(0, LoginGrant.friendPoints(""))
        assertEquals(0, LoginGrant.friendPoints("{not json"))
        assertEquals(0, LoginGrant.friendPoints("""{"nid":"login"}"""))
    }
}
