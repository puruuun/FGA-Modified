package io.github.fate_grand_automata.scripts.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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

    private val full = """{"addFriendPoint":100,"addFollowFriendPoint":200,"addRecommendSupportFriendPoint":300,
        "topAddFriendPointSvt":{"userSvtId":9001,"svtId":100100,"friendPoint":60},
        "topAddFriendPointSvtEQ":{"userSvtId":9002,"svtId":100200,"friendPoint":40},
        "topAddFriendPointSvtGrand":{"userSvtId":9003,"svtId":100300,"friendPoint":20}}"""

    @Test fun breakdownReadsAmountsAndTheThreeTopServants() {
        assertEquals(
            LoginBreakdown(
                at = 77, friend = 100, follow = 200, recommend = 300,
                normal = LoginTopServant(100100, 60), event = LoginTopServant(100200, 40), grand = LoginTopServant(100300, 20),
            ),
            LoginGrant.breakdown(login(full), at = 77),
        )
    }

    @Test fun theTopValuesNeedNotAddUpToTheTotal() {
        val read = assertNotNull(LoginGrant.breakdown(login(full)))
        assertEquals(600, read.total)
        assertEquals(600, LoginGrant.friendPoints(login(full)))
    }

    @Test fun aCategoryWithSvtIdZeroAndPointsZeroIsNone() {
        val read = assertNotNull(LoginGrant.breakdown(login("""{"addFriendPoint":5,
            "topAddFriendPointSvt":{"userSvtId":0,"svtId":0,"friendPoint":0},
            "topAddFriendPointSvtEQ":{"userSvtId":1,"svtId":100200,"friendPoint":0},
            "topAddFriendPointSvtGrand":{"userSvtId":1,"svtId":0,"friendPoint":9}}""")))
        assertNull(read.normal)
        assertNull(read.event)
        assertNull(read.grand)
        assertEquals(5, read.friend)
    }

    @Test fun sameServantCanBeFirstInTwoCategories() {
        val read = assertNotNull(LoginGrant.breakdown(login("""{"addFriendPoint":5,
            "topAddFriendPointSvt":{"svtId":100100,"friendPoint":60},
            "topAddFriendPointSvtGrand":{"svtId":100100,"friendPoint":20}}""")))
        assertEquals(LoginTopServant(100100, 60), read.normal)
        assertEquals(LoginTopServant(100100, 20), read.grand)
    }

    @Test fun missingStringAndFractionalFieldsReadAsMissing() {
        val read = assertNotNull(LoginGrant.breakdown(login("""{"addFriendPoint":"50","addFollowFriendPoint":1.5,
            "topAddFriendPointSvt":{"svtId":"100100","friendPoint":60},
            "topAddFriendPointSvtEQ":{"svtId":100200,"friendPoint":4.5},
            "topAddFriendPointSvtGrand":"nope"}""")))
        assertEquals(LoginBreakdown(), read)
        assertNull(LoginGrant.breakdown(login("""{"addFriendPoint":5,"topAddFriendPointSvt":{"friendPoint":60}}"""))?.normal)
        assertNull(LoginGrant.breakdown(login("""{"addFriendPoint":5,"topAddFriendPointSvt":null}"""))?.normal)
    }

    @Test fun negativeTopValuesAreNone() {
        val read = assertNotNull(LoginGrant.breakdown(login("""{"addFriendPoint":5,
            "topAddFriendPointSvt":{"svtId":-1,"friendPoint":60},"topAddFriendPointSvtEQ":{"svtId":100200,"friendPoint":-4}}""")))
        assertNull(read.normal)
        assertNull(read.event)
    }

    @Test fun failedOrMissingLoginReadsNoBreakdown() {
        assertNull(LoginGrant.breakdown(login(full, resCode = "01")))
        assertNull(LoginGrant.breakdown("[]"))
        assertNull(LoginGrant.breakdown("{not json"))
        assertNull(LoginGrant.breakdown("""[{"nid":"other","resCode":"00","success":$full}]"""))
    }
}