package io.github.fate_grand_automata.scripts.capture.chaldea

import io.github.fate_grand_automata.scripts.capture.AccountState
import kotlinx.serialization.json.*
import kotlin.test.*

/** Entirely synthetic data. */
class ChaldeaSyncLogicTest {
    private fun state(vararg tables: Pair<String, String>) =
        AccountState(mapOf(*tables), "[]", null, 1L, 2L)

    private val game = """[{"friendCode":" 123,456 ","name":"Synth","genderType":2,"qp":900,"stone":5,"mana":7,"rarePri":8}]"""
    private val fullState = state(
        "userGame" to game,
        "userItem" to """[{"itemId":100,"num":3},{"itemId":1,"num":1},{"itemId":6503,"num":383}]""",
        "userEquip" to """[{"equipId":1,"lv":10},{"equipId":20,"lv":3}]""",
        "userClassBoardSquare" to """[{"classBoardBaseId":5,"classBoardUnlockSquareIds":[1,2,2],"classBoardSquareIds":[2]}]""",
    )

    private fun patch(
        code: String? = "123,456",
        server: ChaldeaServer = ChaldeaServer.Kr,
        items: Map<Long, Long>? = mapOf(100L to 3L),
        mystic: Map<Long, Long>? = null,
        boards: Map<Long, ChaldeaPatch.Board>? = null,
        isGirl: Boolean? = true,
        nickname: String? = "Synth",
    ) = ChaldeaPatch(code, server, nickname, isGirl, items, mystic, boards)

    private fun file(users: String, extra: String = ""","curUserKey":1""") =
        """{"version":4,"appVer":"2.6.2"$extra,"users":$users,"other":{"a":1.0}}"""

    private fun user(code: String?, region: String = "kr", items: String = "{}", extra: String = "") =
        """{"id":"u-${code ?: "x"}","name":"N","region":"$region","lastImportId":${code?.let { "\"$it\"" } ?: "null"},"items":$items$extra}"""

    private fun ok(r: ChaldeaEditResult) = r as ChaldeaEditResult.Success
    private fun out(r: ChaldeaEditResult) = Json.parseToJsonElement(ok(r).output).jsonObject
    private fun users(r: ChaldeaEditResult) = out(r)["users"]!!.jsonArray

    // ---- patch builder ----

    @Test fun builderReadsAllFourFieldsAndTrimsFriendCode() {
        val p = ChaldeaPatch.from(fullState, ChaldeaServer.Kr)
        assertEquals("123,456", p.friendCode)
        assertEquals("Synth", p.nickname)
        assertEquals(true, p.isGirl)
        assertEquals(383L, p.items!![6503L])
        assertEquals(mapOf(1L to 10L, 20L to 3L), p.mysticCodes)
        assertEquals(ChaldeaPatch.Board(listOf(1L, 2L), listOf(2L)), p.classBoards!![5L])
    }

    @Test fun userGameCurrenciesOverrideUserItemRows() {
        val items = ChaldeaPatch.from(fullState, ChaldeaServer.Kr).items!!
        assertEquals(900L, items[1L]) // userItem said 1, userGame.qp wins
        assertEquals(5L, items[2L])
        assertEquals(7L, items[3L])
        assertEquals(8L, items[18L])
    }

    @Test fun missingTablesLeaveFieldsOut() {
        val p = ChaldeaPatch.from(state("userGame" to game), ChaldeaServer.Jp)
        assertNull(p.mysticCodes)
        assertNull(p.classBoards)
        assertNotNull(p.items) // currencies only
        val none = ChaldeaPatch.from(state("userEquip" to "[]"), ChaldeaServer.Jp)
        assertNull(none.items)
        assertNull(none.friendCode)
        assertEquals(emptyMap(), none.mysticCodes)
    }

    @Test fun jsonKeysAreStringsAndSorted() {
        val o = Json.parseToJsonElement(ChaldeaPatch.from(fullState, ChaldeaServer.Kr).toJson()).jsonObject
        assertEquals(listOf("1", "2", "3", "18", "100", "6503"), o["items"]!!.jsonObject.keys.toList())
        assertEquals(listOf("5"), o["classBoards"]!!.jsonObject.keys.toList())
        assertEquals("kr", o["server"]!!.jsonPrimitive.content)
    }

    @Test fun blankFriendCodeAndMissingGenderAndNickname() {
        val p = ChaldeaPatch.from(state("userGame" to """[{"friendCode":"  ","name":" "}]"""), ChaldeaServer.Kr)
        assertNull(p.friendCode)
        assertNull(p.isGirl)
        assertNull(p.nickname)
        assertEquals("KR account", p.userName)
        assertEquals("Synth (JP)", patch(server = ChaldeaServer.Jp).userName)
        assertEquals(false, ChaldeaPatch.from(state("userGame" to """[{"friendCode":"1","genderType":1}]"""), ChaldeaServer.Kr).isGirl)
    }

    @Test fun hashIsStableAndChangesWithContent() {
        val a = ChaldeaPatch.from(fullState, ChaldeaServer.Kr)
        assertEquals(a.hash(), ChaldeaPatch.from(fullState, ChaldeaServer.Kr).hash())
        assertEquals(64, a.hash().length)
        val reordered = state(
            "userEquip" to """[{"equipId":20,"lv":3},{"equipId":1,"lv":10}]""",
            "userGame" to game,
            "userItem" to """[{"itemId":6503,"num":383},{"itemId":1,"num":1},{"itemId":100,"num":3}]""",
            "userClassBoardSquare" to """[{"classBoardBaseId":5,"classBoardUnlockSquareIds":[1,2],"classBoardSquareIds":[2]}]""",
        )
        assertEquals(a.hash(), ChaldeaPatch.from(reordered, ChaldeaServer.Kr).hash())
        assertNotEquals(a.hash(), a.copy(items = a.items!! + (100L to 4L)).hash())
        assertNotEquals(a.hash(), a.copy(server = ChaldeaServer.Jp).hash())
        assertEquals(a, ChaldeaPatch.fromJson(a.toJson()))
        assertNull(ChaldeaPatch.fromJson("[]"))
    }

    // ---- matcher ----

    private fun arr(s: String) = Json.parseToJsonElement(s).jsonArray

    @Test fun exactFriendCodeMatchWinsAndFirstOfSeveral() {
        val users = arr("[${user("111")},${user("123,456")},${user(" 123,456 ")}]")
        assertEquals(ChaldeaUserMatcher.Match.Existing(1, false), ChaldeaUserMatcher.match(users, patch()))
    }

    @Test fun regionMismatchReported() {
        val users = arr("[${user("123,456", region = "jp")}]")
        assertEquals(ChaldeaUserMatcher.Match.Existing(0, true), ChaldeaUserMatcher.match(users, patch()))
    }

    private val defaultUser = """{"id":"d","name":"Gudako","region":"jp","items":{},"servants":{},"plans":[{"servants":{}},{}],"classBoards":{}}"""

    @Test fun takeoverOfSingleUntouchedDefault() {
        assertEquals(ChaldeaUserMatcher.Match.TakeOver(0), ChaldeaUserMatcher.match(arr("[$defaultUser]"), patch()))
        assertEquals(ChaldeaUserMatcher.Match.TakeOver(0), ChaldeaUserMatcher.match(arr("""[{"id":"d"}]"""), patch()))
    }

    @Test fun takeoverBoundaries() {
        fun m(u: String) = ChaldeaUserMatcher.match(arr("[$u]"), patch())
        val create = ChaldeaUserMatcher.Match.Create
        assertEquals(create, m(user(null, items = """{"1":2}""")))
        assertEquals(create, m("""{"id":"d","plans":[{"servants":{"1":{}}}]}"""))
        assertEquals(create, m("""{"id":"d","classBoards":{"5":{}}}"""))
        assertEquals(create, m("""{"id":"d","lastImportId":"999"}"""))
        assertEquals(create, m("""{"id":"d","name":"Renamed"}"""))
        assertEquals(create, m("""{"id":"d","region":"kr"}"""))
        assertEquals(create, m("""{"id":"d","summons":["a"]}"""))
        assertEquals(create, m("""{"id":"d","dupServantMapping":{"1":2}}"""))
        assertEquals(create, m("""{"id":"d","plans":"x"}"""))
        assertEquals(create, ChaldeaUserMatcher.match(arr("[$defaultUser,$defaultUser]"), patch()))
        assertEquals(create, ChaldeaUserMatcher.match(arr("[]"), patch()))
    }

    @Test fun noMatchAmongSeveralCreates() {
        assertEquals(ChaldeaUserMatcher.Match.Create, ChaldeaUserMatcher.match(arr("[${user("1")},${user("2")}]"), patch()))
    }

    // ---- editor ----

    @Test fun mergeKeepsExistingItemsAndOverwritesPatched() {
        val text = file("[${user("123,456", items = """{"100":1,"999":5}""")}]")
        val r = ChaldeaUserdataEditor.edit(text, patch(items = mapOf(100L to 3L, 101L to 9L)), allowCreate = false)
        val items = users(r)[0].jsonObject["items"]!!.jsonObject
        assertEquals(listOf("100", "999", "101"), items.keys.toList())
        assertEquals(3, items["100"]!!.jsonPrimitive.int)
        assertEquals(5, items["999"]!!.jsonPrimitive.int)
        assertEquals(2, ok(r).itemCount)
    }

    @Test fun boardIsReplacedAsUnitOthersKept() {
        val boards = """{"5":{"unlockedSquares":[1,2,3],"enhancedSquares":[9]},"6":{"unlockedSquares":[4],"enhancedSquares":[]}}"""
        val text = file("[${user("123,456", extra = ""","classBoards":$boards""")}]")
        val p = patch(boards = mapOf(5L to ChaldeaPatch.Board(listOf(1L), listOf(2L))))
        val r = ChaldeaUserdataEditor.edit(text, p, false)
        val b = users(r)[0].jsonObject["classBoards"]!!.jsonObject
        assertEquals(arr("[1]"), b["5"]!!.jsonObject["unlockedSquares"])
        assertEquals(arr("[2]"), b["5"]!!.jsonObject["enhancedSquares"])
        assertEquals(Json.parseToJsonElement("""{"unlockedSquares":[4],"enhancedSquares":[]}"""), b["6"])
    }

    @Test fun unknownFieldsOtherUsersAndLiteralsRoundTrip() {
        val other = """{"id":"o","lastImportId":"1","x":1.0,"big":12345678901234567890,"e":1e3,"s":"é \u00e9 \n \" \\","u":"\u30c6","nested":{"z":[1.50,null,true]},"items":{"1":2}}"""
        val text = """{"version":4,"zz":[1.0,2e2],"curUserKey":0,"users":[$other,${user("123,456", extra = ""","futureField":{"k":1.0}""")}]}"""
        val r = ChaldeaUserdataEditor.edit(text, patch(), false)
        val output = ok(r).output
        assertContains(output, "12345678901234567890")
        assertContains(output, "\"x\":1.0")
        assertContains(output, "\"e\":1e3")
        assertContains(output, "1.50")
        assertContains(output, "[1.0,2e2]")
        val orig = Json.parseToJsonElement(text).jsonObject
        val new = Json.parseToJsonElement(output).jsonObject
        assertEquals(orig["users"]!!.jsonArray[0], new["users"]!!.jsonArray[0])
        assertEquals(orig["zz"], new["zz"])
        assertEquals(orig.keys.toList(), new.keys.toList())
        assertEquals(orig["users"]!!.jsonArray[1].jsonObject["futureField"], new["users"]!!.jsonArray[1].jsonObject["futureField"])
    }

    @Test fun curUserKeyNeverChangedAndMayBeMissing() {
        val withKey = ok(ChaldeaUserdataEditor.edit(file("[${user("1")}]"), patch(), true))
        assertTrue(withKey.created)
        assertEquals("1", out(ChaldeaUserdataEditor.edit(file("[${user("1")}]"), patch(), true))["curUserKey"]!!.jsonPrimitive.content)
        val noKey = file("[${user("1")}]", extra = "")
        val r = ChaldeaUserdataEditor.edit(noKey, patch(), true)
        assertFalse(out(r).containsKey("curUserKey"))
    }

    @Test fun createsNewUserWithFieldsAndKeepsOthers() {
        val text = file("[${user("1", items = """{"5":5}""")}]")
        val r = ChaldeaUserdataEditor.edit(text, patch(mystic = mapOf(1L to 10L)), true) { "fixed-uuid" }
        val s = ok(r)
        assertTrue(s.created)
        assertEquals(1, s.userIndex)
        val list = users(r)
        assertEquals(arr("[${user("1", items = """{"5":5}""")}]")[0], list[0])
        val n = list[1].jsonObject
        assertEquals("fixed-uuid", n["id"]!!.jsonPrimitive.content)
        assertEquals("Synth (KR)", n["name"]!!.jsonPrimitive.content)
        assertEquals("kr", n["region"]!!.jsonPrimitive.content)
        assertEquals(true, n["isGirl"]!!.jsonPrimitive.boolean)
        assertEquals("123,456", n["lastImportId"]!!.jsonPrimitive.content)
        assertEquals(10, n["mysticCodes"]!!.jsonObject["1"]!!.jsonPrimitive.int)
    }

    @Test fun unknownGenderIsLeftOutOfNewUser() {
        val r = ChaldeaUserdataEditor.edit(file("[${user("1")}]"), patch(isGirl = null), true)
        assertFalse(users(r)[1].jsonObject.containsKey("isGirl"))
    }

    @Test fun emptyUsersArrayGetsNewUser() {
        val r = ChaldeaUserdataEditor.edit(file("[]"), patch(), true)
        assertTrue(ok(r).created)
        assertEquals(1, users(r).size)
    }

    @Test fun takeoverSetsIdentityAndKeepsIdAndPlans() {
        val r = ChaldeaUserdataEditor.edit(file("[$defaultUser]"), patch(), true)
        assertTrue(ok(r).tookOverDefault)
        val u = users(r)[0].jsonObject
        assertEquals("d", u["id"]!!.jsonPrimitive.content)
        assertEquals("Synth (KR)", u["name"]!!.jsonPrimitive.content)
        assertEquals("kr", u["region"]!!.jsonPrimitive.content)
        assertEquals("123,456", u["lastImportId"]!!.jsonPrimitive.content)
        assertEquals(arr("""[{"servants":{}},{}]"""), u["plans"])
    }

    @Test fun nonCurrentAccountWithoutMatchIsRefusedAndMatchStillWorks() {
        val text = file("[${user("1")}]")
        assertEquals(ChaldeaEditResult.Refused(ChaldeaRefusal.NoMatchingUser), ChaldeaUserdataEditor.edit(text, patch(), false))
        assertEquals(ChaldeaEditResult.Refused(ChaldeaRefusal.NoMatchingUser), ChaldeaUserdataEditor.edit(file("[$defaultUser]"), patch(), false))
        assertFalse(ok(ChaldeaUserdataEditor.edit(file("[${user("123,456")}]"), patch(), false)).created)
    }

    @Test fun missingOrBlankFriendCodeWritesNothing() {
        for (code in listOf(null, "", "  ")) {
            assertEquals(
                ChaldeaEditResult.Refused(ChaldeaRefusal.AccountNotIdentified),
                ChaldeaUserdataEditor.edit(file("[${user("1")}]"), patch(code = code), true),
            )
        }
    }

    @Test fun regionMismatchSurfaces() {
        val r = ChaldeaUserdataEditor.edit(file("[${user("123,456", region = "jp")}]"), patch(), false)
        assertTrue(ok(r).regionMismatch)
        assertEquals("jp", users(r)[0].jsonObject["region"]!!.jsonPrimitive.content)
    }

    @Test fun refusals() {
        fun reason(text: String) = (ChaldeaUserdataEditor.edit(text, patch(), true) as ChaldeaEditResult.Refused).reason
        assertEquals(ChaldeaRefusal.UnknownVersion, reason("""{"version":3,"users":[]}"""))
        assertEquals(ChaldeaRefusal.UnknownVersion, reason("""{"version":"4","users":[]}"""))
        assertEquals(ChaldeaRefusal.UnknownVersion, reason("""{"version":4.0,"users":[]}"""))
        assertEquals(ChaldeaRefusal.UnknownVersion, reason("""{"users":[]}"""))
        assertEquals(ChaldeaRefusal.NotObject, reason("[]"))
        assertEquals(ChaldeaRefusal.MissingUsers, reason("""{"version":4}"""))
        assertEquals(ChaldeaRefusal.MissingUsers, reason("""{"version":4,"users":{}}"""))
        assertEquals(ChaldeaRefusal.InvalidJson, reason("""{"version":4,"users":["""))
        assertEquals(ChaldeaRefusal.InvalidJson, reason(""))
    }

    @Test fun wrongTypeInPatchedFieldRefuses() {
        val text = file("[${user("123,456", items = "[1]")}]")
        assertEquals(ChaldeaEditResult.Refused(ChaldeaRefusal.UnexpectedShape), ChaldeaUserdataEditor.edit(text, patch(), false))
    }

    // ---- verification ----

    @Test fun verifyAcceptsGoodOutputAndRejectsTampering() {
        val text = file("[${user("1")},${user("123,456", items = """{"7":7}""")}]")
        val root = Json.parseToJsonElement(text).jsonObject
        val good = ok(ChaldeaUserdataEditor.edit(text, patch(), false)).output
        val m = ChaldeaUserMatcher.Match.Existing(1, false)
        assertTrue(ChaldeaUserdataEditor.verify(root, good, patch(), m, 1))
        // another user changed
        assertFalse(ChaldeaUserdataEditor.verify(root, good.replace("\"id\":\"u-1\"", "\"id\":\"u-2\""), patch(), m, 1))
        // curUserKey changed
        assertFalse(ChaldeaUserdataEditor.verify(root, good.replace("\"curUserKey\":1", "\"curUserKey\":0"), patch(), m, 1))
        // version changed
        assertFalse(ChaldeaUserdataEditor.verify(root, good.replace("\"version\":4", "\"version\":5"), patch(), m, 1))
        // patched value missing
        assertFalse(ChaldeaUserdataEditor.verify(root, good.replace("\"100\":3", "\"100\":4"), patch(), m, 1))
        // existing item deleted
        assertFalse(ChaldeaUserdataEditor.verify(root, good.replace("\"7\":7,", ""), patch(), m, 1))
        // user dropped, and unparseable output
        assertFalse(ChaldeaUserdataEditor.verify(root, good.replace(user("1"), ""), patch(), m, 1))
        assertFalse(ChaldeaUserdataEditor.verify(root, good.dropLast(3), patch(), m, 1))
        // unrelated field of the target changed
        assertFalse(ChaldeaUserdataEditor.verify(root, good.replace("\"name\":\"N\",\"region\":\"kr\",\"lastImportId\":\"123,456\"", "\"name\":\"M\",\"region\":\"kr\",\"lastImportId\":\"123,456\""), patch(), m, 1))
    }

    @Test fun verifyCreatedUserCountMustBePlusOne() {
        val text = file("[${user("1")}]")
        val root = Json.parseToJsonElement(text).jsonObject
        val good = ok(ChaldeaUserdataEditor.edit(text, patch(), true)).output
        assertTrue(ChaldeaUserdataEditor.verify(root, good, patch(), ChaldeaUserMatcher.Match.Create, 1))
        assertFalse(ChaldeaUserdataEditor.verify(root, text, patch(), ChaldeaUserMatcher.Match.Create, 1))
    }

    // ---- base selection ----

    private fun bytes(s: String) = s.toByteArray()
    private val valid = file("[${user("1")}]")

    @Test fun baseIsMainFileWhenValid() {
        val b = ChaldeaUserdataEditor.selectBase(bytes(valid), bytes(file("[]"))) as ChaldeaBase.Selected
        assertEquals(ChaldeaBaseSource.UserdataJson, b.source)
        assertEquals(valid, b.text)
    }

    @Test fun truncatedEmptyOrMissingMainFallsBackToBak() {
        for (main in listOf(bytes(valid.take(40)), ByteArray(0), null, byteArrayOf(-1, -2, 0))) {
            val b = ChaldeaUserdataEditor.selectBase(main, bytes(valid)) as ChaldeaBase.Selected
            assertEquals(ChaldeaBaseSource.Bak, b.source)
        }
    }

    @Test fun bothInvalidIsRefused() {
        val refused = ChaldeaBase.Refused(ChaldeaRefusal.Unreadable)
        assertEquals(refused, ChaldeaUserdataEditor.selectBase(bytes("{"), bytes("{")))
        assertEquals(refused, ChaldeaUserdataEditor.selectBase(null, null))
        assertEquals(refused, ChaldeaUserdataEditor.selectBase(bytes("{"), bytes("""{"version":3,"users":[]}""")))
    }

    @Test fun validJsonWithWrongShapeIsNotReplacedByBak() {
        assertEquals(ChaldeaBase.Refused(ChaldeaRefusal.UnknownVersion), ChaldeaUserdataEditor.selectBase(bytes("{}"), bytes(valid)))
    }

    @Test fun oversizedFilesAreRefused() {
        val big = ByteArray((ChaldeaUserdataEditor.MAX_FILE_BYTES + 1).toInt())
        assertEquals(ChaldeaBase.Refused(ChaldeaRefusal.TooLarge), ChaldeaUserdataEditor.selectBase(big, bytes(valid)))
        assertEquals(ChaldeaBase.Refused(ChaldeaRefusal.TooLarge), ChaldeaUserdataEditor.selectBase(bytes(valid), big))
    }

    // ---- stage 2: coins, craft essences, command codes ----

    private val stage2Numbers = ChaldeaCollectionNumbers(
        equips = mapOf(9300010L to 11L, 9300020L to 22L, 9300030L to 33L),
        commandCodes = mapOf(80001L to 1L, 80002L to 2L, 80003L to 3L),
    )

    @Test fun svtCoinsBuildWithCoinIdRuleAndNullWhenTableMissing() {
        val withCoins = ChaldeaPatch.from(
            state("userGame" to game, "userSvtCoin" to """[{"svtId":100100,"num":120},{"svtId":200300,"num":5}]"""),
            ChaldeaServer.Kr,
        )
        assertEquals(mapOf(10010001L to 120L, 20030001L to 5L), withCoins.svtCoins)

        val withoutCoins = ChaldeaPatch.from(state("userGame" to game), ChaldeaServer.Kr)
        assertNull(withoutCoins.svtCoins)
    }

    @Test fun craftEssencesAggregateStatusAndMaxLvLimitCount() {
        val st = state(
            "userGame" to game,
            // 100100 is a servant (not in equips, must be ignored, not counted as unmapped)
            // 9300999 is an unknown CE (not in equips, skipped silently without counting as unmapped)
            // 9300010 has 2 held copies; 9300020 has status 1 and no copy held; 9300030 has status 0 (skipped)
            "userSvtCollection" to """[
                {"svtId":100100,"status":2},
                {"svtId":9300999,"status":2},
                {"svtId":9300010,"status":2},
                {"svtId":9300020,"status":1},
                {"svtId":9300030,"status":0}
            ]""",
            "userSvt" to """[
                {"svtId":100100,"lv":90,"limitCount":4},
                {"svtId":9300999,"lv":80,"limitCount":4},
                {"svtId":9300010,"lv":50,"limitCount":1},
                {"svtId":9300010,"lv":20,"limitCount":4}
            ]""",
        )
        val p = ChaldeaPatch.from(st, ChaldeaServer.Kr, stage2Numbers)
        assertEquals(0, p.unmapped)
        assertEquals(
            mapOf(
                11L to ChaldeaPatch.CeStatus(status = 2L, lv = 50L, limitCount = 4L),
                22L to ChaldeaPatch.CeStatus(status = 1L, lv = null, limitCount = null),
            ),
            p.craftEssences,
        )
    }

    @Test fun craftEssenceHeldWithoutCollectionRowGetsStatus2() {
        val st = state(
            "userGame" to game,
            "userSvtCollection" to "[]",
            "userSvt" to """[{"svtId":9300030,"lv":15,"limitCount":2}]""",
        )
        val p = ChaldeaPatch.from(st, ChaldeaServer.Jp, stage2Numbers)
        assertEquals(mapOf(33L to ChaldeaPatch.CeStatus(status = 2L, lv = 15L, limitCount = 2L)), p.craftEssences)
    }

    @Test fun craftEssenceBestCopyOnlyInStorageUsesStorageValues() {
        val st = state(
            "userGame" to game,
            "userSvtCollection" to """[{"svtId":9300030,"status":2}]""",
            "userSvt" to """[{"svtId":9300030,"lv":1,"limitCount":0}]""",
            "userSvtStorage" to """[{"svtId":9300030,"lv":55,"limitCount":4}]""",
        )
        val p = ChaldeaPatch.from(st, ChaldeaServer.Jp, stage2Numbers)
        assertEquals(mapOf(33L to ChaldeaPatch.CeStatus(status = 2L, lv = 55L, limitCount = 4L)), p.craftEssences)
    }

    @Test fun craftEssenceLvAndLimitCountAreMaxedSeparatelyAcrossBothTables() {
        val st = state(
            "userGame" to game,
            "userSvtCollection" to """[{"svtId":9300030,"status":2}]""",
            "userSvt" to """[{"svtId":9300030,"lv":60,"limitCount":1}]""",
            "userSvtStorage" to """[{"svtId":9300030,"lv":20,"limitCount":4}]""",
        )
        val p = ChaldeaPatch.from(st, ChaldeaServer.Jp, stage2Numbers)
        assertEquals(mapOf(33L to ChaldeaPatch.CeStatus(status = 2L, lv = 60L, limitCount = 4L)), p.craftEssences)
    }

    @Test fun craftEssenceHeldOnlyInStorageAndMissingFromCollectionGetsStatus2() {
        val st = state(
            "userGame" to game,
            "userSvtStorage" to """[{"svtId":9300030,"lv":15,"limitCount":2}]""",
        )
        val p = ChaldeaPatch.from(st, ChaldeaServer.Jp, stage2Numbers)
        assertEquals(mapOf(33L to ChaldeaPatch.CeStatus(status = 2L, lv = 15L, limitCount = 2L)), p.craftEssences)
    }

    @Test fun craftEssenceWithoutStorageTableBehavesAsBefore() {
        val st = state(
            "userGame" to game,
            "userSvtCollection" to """[{"svtId":9300030,"status":2}]""",
            "userSvt" to """[{"svtId":9300030,"lv":15,"limitCount":2},{"svtId":9300030,"lv":30,"limitCount":1}]""",
        )
        val p = ChaldeaPatch.from(st, ChaldeaServer.Jp, stage2Numbers)
        assertEquals(mapOf(33L to ChaldeaPatch.CeStatus(status = 2L, lv = 30L, limitCount = 2L)), p.craftEssences)
    }

    @Test fun commandCodesCountCopiesDefaultStatusAndCountDistinctUnmapped() {
        val st = state(
            "userGame" to game,
            "userCommandCodeCollection" to """[
                {"commandCodeId":80001,"status":1},
                {"commandCodeId":80002,"status":0},
                {"commandCodeId":99991,"status":2}
            ]""",
            "userCommandCode" to """[
                {"commandCodeId":80003},
                {"commandCodeId":80003},
                {"commandCodeId":99991},
                {"commandCodeId":99992}
            ]""",
        )
        val p = ChaldeaPatch.from(st, ChaldeaServer.Kr, stage2Numbers)
        assertEquals(
            mapOf(
                1L to ChaldeaPatch.CcStatus(status = 1L, count = 0L),
                3L to ChaldeaPatch.CcStatus(status = 2L, count = 2L),
            ),
            p.commandCodes,
        )
        // 99991 appears in both collection and held table; 99992 appears once -> 2 distinct ids
        assertEquals(2, p.unmapped)
    }

    @Test fun emptyNumbersOrMissingTablesLeaveStage2FieldsNullAndCountUnmapped() {
        val st = state(
            "userGame" to game,
            "userSvtCollection" to """[{"svtId":9300010,"status":2}]""",
            "userSvt" to """[{"svtId":9300010,"lv":10,"limitCount":0}]""",
            "userCommandCodeCollection" to """[{"commandCodeId":80001,"status":2}]""",
            "userCommandCode" to """[{"commandCodeId":80001},{"commandCodeId":80002}]""",
        )
        val ceOnlyEmptyPatch = ChaldeaPatch.from(
            state(
                "userGame" to game,
                "userSvtCollection" to """[{"svtId":9300010,"status":2}]""",
                "userSvt" to """[{"svtId":9300010,"lv":10,"limitCount":0}]""",
            ),
            ChaldeaServer.Kr,
            ChaldeaCollectionNumbers.EMPTY,
        )
        assertNull(ceOnlyEmptyPatch.craftEssences)
        assertEquals(0, ceOnlyEmptyPatch.unmapped)

        val emptyMapPatch = ChaldeaPatch.from(st, ChaldeaServer.Kr, ChaldeaCollectionNumbers.EMPTY)
        assertNull(emptyMapPatch.craftEssences)
        assertNull(emptyMapPatch.commandCodes)
        // Only the 2 distinct command codes (80001, 80002) are counted; CE 9300010 is skipped silently.
        assertEquals(2, emptyMapPatch.unmapped)

        val missingTables = ChaldeaPatch.from(state("userGame" to game), ChaldeaServer.Kr, stage2Numbers)
        assertNull(missingTables.svtCoins)
        assertNull(missingTables.craftEssences)
        assertNull(missingTables.commandCodes)
        assertEquals(0, missingTables.unmapped)
    }

    @Test fun stage2JsonRoundTripCanonicalAndBackwardCompatibleWithStage1Json() {
        val p = ChaldeaPatch(
            friendCode = "123,456",
            server = ChaldeaServer.Kr,
            nickname = "Synth",
            isGirl = true,
            items = mapOf(2L to 5L),
            mysticCodes = null,
            classBoards = null,
            svtCoins = mapOf(20010001L to 30L, 10010001L to 10L),
            craftEssences = mapOf(
                22L to ChaldeaPatch.CeStatus(1L, null, null),
                11L to ChaldeaPatch.CeStatus(2L, 50L, 4L),
            ),
            commandCodes = mapOf(
                3L to ChaldeaPatch.CcStatus(2L, 2L),
                1L to ChaldeaPatch.CcStatus(1L, 0L),
            ),
            unmapped = 4,
        )
        val json = p.toJson()
        val obj = Json.parseToJsonElement(json).jsonObject
        assertFalse("unmapped" in obj)
        assertEquals(listOf("10010001", "20010001"), obj["svtCoins"]!!.jsonObject.keys.toList())
        assertEquals(listOf("11", "22"), obj["craftEssences"]!!.jsonObject.keys.toList())
        assertEquals(listOf("1", "3"), obj["commandCodes"]!!.jsonObject.keys.toList())

        val decoded = ChaldeaPatch.fromJson(json)!!
        assertEquals(p.copy(unmapped = 0), decoded)
        assertEquals(p.hash(), p.copy(unmapped = 99).hash())
        assertEquals(p.hash(), decoded.hash())
        assertNotEquals(p.hash(), p.copy(svtCoins = mapOf(10010001L to 11L)).hash())
        assertNotEquals(p.hash(), p.copy(craftEssences = mapOf(11L to ChaldeaPatch.CeStatus(2L, 51L, 4L))).hash())
        assertNotEquals(p.hash(), p.copy(commandCodes = mapOf(1L to ChaldeaPatch.CcStatus(2L, 1L))).hash())

        val stage1Json = """{"friendCode":"123,456","server":"kr","items":{"1":100}}"""
        val stage1Decoded = ChaldeaPatch.fromJson(stage1Json)!!
        assertNull(stage1Decoded.svtCoins)
        assertNull(stage1Decoded.craftEssences)
        assertNull(stage1Decoded.commandCodes)
    }

    @Test fun editorMergesCoinsCesAndCommandCodesPerFieldPreservingOtherFields() {
        val existingUser = user(
            "123,456",
            items = """{"7":7,"10010001":5}""",
            extra = ""","itemsLeft":{"7":3},"plans":[1],"servants":{"100100":{"lv":90}},""" +
                """"craftEssences":{"11":{"status":1,"lv":80,"limitCount":4,"fav":true},"99":{"status":2,"lv":100,"limitCount":4}},""" +
                """"cmdCodes":{"1":{"status":1,"count":1,"note":"keep"},"9":{"status":2,"count":3}}""",
        )
        val p = ChaldeaPatch(
            friendCode = "123,456",
            server = ChaldeaServer.Kr,
            nickname = "Synth",
            isGirl = true,
            items = mapOf(100L to 3L),
            mysticCodes = null,
            classBoards = null,
            svtCoins = mapOf(10010001L to 12L, 20010001L to 40L),
            craftEssences = mapOf(
                11L to ChaldeaPatch.CeStatus(2L, null, null), // existing, null lv/limitCount keeps 80/4 and fav
                22L to ChaldeaPatch.CeStatus(1L, null, null), // new entry with nulls gets defaults 1/0
                33L to ChaldeaPatch.CeStatus(2L, 65L, 3L),    // new entry with explicit values
            ),
            commandCodes = mapOf(
                1L to ChaldeaPatch.CcStatus(2L, 4L),          // existing keeps "note"
                5L to ChaldeaPatch.CcStatus(1L, 0L),          // new
            ),
        )
        val res = ok(ChaldeaUserdataEditor.edit(file("[$existingUser]"), p, false))
        assertEquals(1, res.itemCount)
        assertEquals(2, res.coinCount)
        assertEquals(3, res.ceCount)
        assertEquals(2, res.ccCount)

        val u = users(res)[0].jsonObject
        val items = u["items"]!!.jsonObject
        assertEquals("7", items["7"]!!.jsonPrimitive.content)
        assertEquals("3", items["100"]!!.jsonPrimitive.content)
        assertEquals("12", items["10010001"]!!.jsonPrimitive.content)
        assertEquals("40", items["20010001"]!!.jsonPrimitive.content)

        val ces = u["craftEssences"]!!.jsonObject
        assertEquals(
            Json.parseToJsonElement("""{"status":2,"lv":80,"limitCount":4,"fav":true}"""),
            ces["11"],
        )
        assertEquals(
            Json.parseToJsonElement("""{"status":1,"lv":1,"limitCount":0}"""),
            ces["22"],
        )
        assertEquals(
            Json.parseToJsonElement("""{"status":2,"lv":65,"limitCount":3}"""),
            ces["33"],
        )
        assertEquals(
            Json.parseToJsonElement("""{"status":2,"lv":100,"limitCount":4}"""),
            ces["99"],
        )

        val ccs = u["cmdCodes"]!!.jsonObject
        assertEquals(
            Json.parseToJsonElement("""{"status":2,"count":4,"note":"keep"}"""),
            ccs["1"],
        )
        assertEquals(
            Json.parseToJsonElement("""{"status":1,"count":0}"""),
            ccs["5"],
        )
        assertEquals(
            Json.parseToJsonElement("""{"status":2,"count":3}"""),
            ccs["9"],
        )

        // Untouched fields remain unchanged
        assertEquals(Json.parseToJsonElement("""{"7":3}"""), u["itemsLeft"])
        assertEquals(Json.parseToJsonElement("[1]"), u["plans"])
        assertEquals(Json.parseToJsonElement("""{"100100":{"lv":90}}"""), u["servants"])
    }

    @Test fun editorRefusesUnexpectedShapeInStage2FieldsAndEntries() {
        val base = patch(items = null)
        // svtCoins checks items shape
        val badItems = file("[${user("123,456", items = "[]")}]")
        assertEquals(
            ChaldeaEditResult.Refused(ChaldeaRefusal.UnexpectedShape),
            ChaldeaUserdataEditor.edit(badItems, base.copy(svtCoins = mapOf(10010001L to 1L)), false),
        )
        // craftEssences target not object
        val badCes = file("[${user("123,456", extra = ""","craftEssences":[]""")}]")
        assertEquals(
            ChaldeaEditResult.Refused(ChaldeaRefusal.UnexpectedShape),
            ChaldeaUserdataEditor.edit(badCes, base.copy(craftEssences = mapOf(11L to ChaldeaPatch.CeStatus(1L, null, null))), false),
        )
        // craftEssences entry not object
        val badCeEntry = file("[${user("123,456", extra = ""","craftEssences":{"11":123}""")}]")
        assertEquals(
            ChaldeaEditResult.Refused(ChaldeaRefusal.UnexpectedShape),
            ChaldeaUserdataEditor.edit(badCeEntry, base.copy(craftEssences = mapOf(11L to ChaldeaPatch.CeStatus(2L, 10L, 1L))), false),
        )
        // cmdCodes target not object
        val badCcs = file("[${user("123,456", extra = ""","cmdCodes":"nope" """)}]")
        assertEquals(
            ChaldeaEditResult.Refused(ChaldeaRefusal.UnexpectedShape),
            ChaldeaUserdataEditor.edit(badCcs, base.copy(commandCodes = mapOf(1L to ChaldeaPatch.CcStatus(2L, 1L))), false),
        )
        // cmdCodes entry not object
        val badCcEntry = file("[${user("123,456", extra = ""","cmdCodes":{"1":[]}""")}]")
        assertEquals(
            ChaldeaEditResult.Refused(ChaldeaRefusal.UnexpectedShape),
            ChaldeaUserdataEditor.edit(badCcEntry, base.copy(commandCodes = mapOf(1L to ChaldeaPatch.CcStatus(2L, 1L))), false),
        )
    }

    @Test fun verifyDetectsTamperingInStage2Fields() {
        val existingUser = user("123,456", extra = ""","craftEssences":{"11":{"status":1,"lv":20,"limitCount":1}}""")
        val text = file("[$existingUser]")
        val root = Json.parseToJsonElement(text).jsonObject
        val p = patch(items = mapOf(100L to 3L)).copy(
            svtCoins = mapOf(10010001L to 9L),
            craftEssences = mapOf(11L to ChaldeaPatch.CeStatus(2L, null, null)),
            commandCodes = mapOf(5L to ChaldeaPatch.CcStatus(2L, 3L)),
        )
        val good = ok(ChaldeaUserdataEditor.edit(text, p, false)).output
        val m = ChaldeaUserMatcher.Match.Existing(0, false)
        assertTrue(ChaldeaUserdataEditor.verify(root, good, p, m, 0))
        // coin tampered
        assertFalse(ChaldeaUserdataEditor.verify(root, good.replace("\"10010001\":9", "\"10010001\":8"), p, m, 0))
        // CE status tampered
        assertFalse(ChaldeaUserdataEditor.verify(root, good.replace("\"11\":{\"status\":2", "\"11\":{\"status\":1"), p, m, 0))
        // existing CE lv kept from before (20) tampered
        assertFalse(ChaldeaUserdataEditor.verify(root, good.replace("\"lv\":20", "\"lv\":21"), p, m, 0))
        // command code count tampered
        assertFalse(ChaldeaUserdataEditor.verify(root, good.replace("\"count\":3", "\"count\":2"), p, m, 0))
    }

    @Test fun verifyRejectsEveryUnintendedChangeInStage2Edit() {
        val otherUser = user("1", items = """{"5":5}""")
        val targetUser = user(
            "123,456",
            items = """{"7":7,"100":1}""",
            extra = ""","itemsLeft":{"7":3},"plans":[1],"servants":{"100100":{"lv":90}},""" +
                """"craftEssences":{"11":{"status":1,"lv":20,"limitCount":1,"fav":true},"99":{"status":2,"lv":100,"limitCount":4}},""" +
                """"cmdCodes":{"5":{"status":1,"count":1,"note":"keep"},"9":{"status":2,"count":3}}""",
        )
        val text = file("[$otherUser,$targetUser]")
        val root = Json.parseToJsonElement(text).jsonObject
        val p = patch(items = mapOf(100L to 3L)).copy(
            svtCoins = mapOf(10010001L to 9L),
            craftEssences = mapOf(11L to ChaldeaPatch.CeStatus(2L, null, null)),
            commandCodes = mapOf(5L to ChaldeaPatch.CcStatus(2L, 3L)),
        )
        val good = ok(ChaldeaUserdataEditor.edit(text, p, false)).output
        val m = ChaldeaUserMatcher.Match.Existing(1, false)
        assertTrue(ChaldeaUserdataEditor.verify(root, good, p, m, 1))

        fun reject(tampered: String) {
            assertNotEquals(good, tampered)
            assertFalse(ChaldeaUserdataEditor.verify(root, tampered, p, m, 1))
        }

        // changed itemsLeft in target user
        reject(good.replace(""""itemsLeft":{"7":3}""", """"itemsLeft":{"7":4}"""))
        // changed plans in target user
        reject(good.replace(""""plans":[1]""", """"plans":[2]"""))
        // changed field in another (non-target) user
        reject(good.replace(""""5":5""", """"5":6"""))
        // uncovered CE entry in craftEssences: changed value
        reject(good.replace(""""99":{"status":2,"lv":100,"limitCount":4}""", """"99":{"status":2,"lv":99,"limitCount":4}"""))
        // uncovered CE entry in craftEssences: newly added entry
        reject(good.replace(""""99":{"status":2,"lv":100,"limitCount":4}""", """"99":{"status":2,"lv":100,"limitCount":4},"88":{"status":1,"lv":1,"limitCount":0}"""))
        // unknown field (fav) of patched CE entry changed
        reject(good.replace(""""fav":true""", """"fav":false"""))
        // unknown field (fav) of patched CE entry removed
        reject(good.replace(""","fav":true""", ""))
        // uncovered cmdCodes entry changed
        reject(good.replace(""""9":{"status":2,"count":3}""", """"9":{"status":2,"count":4}"""))
        // uncovered items key changed
        reject(good.replace(""""7":7""", """"7":8"""))
        // top-level field (version) changed
        reject(good.replace(""""version":4""", """"version":5"""))
    }

    @Test fun fromJsonReturnsNullForMalformedStage2FieldsWhileStage1StillParses() {
        val malformed = listOf(
            """{"friendCode":"123,456","server":"kr","craftEssences":"bad"}""",
            """{"friendCode":"123,456","server":"kr","craftEssences":{"11":"bad"}}""",
            """{"friendCode":"123,456","server":"kr","craftEssences":{"11":{"lv":10}}}""",
            """{"friendCode":"123,456","server":"kr","commandCodes":"bad"}""",
            """{"friendCode":"123,456","server":"kr","commandCodes":{"1":"bad"}}""",
            """{"friendCode":"123,456","server":"kr","commandCodes":{"1":{"count":1}}}""",
            """{"friendCode":"123,456","server":"kr","commandCodes":{"1":{"status":2}}}""",
            """{"friendCode":"123,456","server":"kr","svtCoins":"bad"}""",
        )
        for (json in malformed) {
            assertNull(ChaldeaPatch.fromJson(json), "Expected null for malformed patch: $json")
        }

        val stage1 = ChaldeaPatch.fromJson("""{"friendCode":"123,456","server":"kr","items":{"1":100}}""")
        assertNotNull(stage1)
        assertEquals(mapOf(1L to 100L), stage1.items)
        assertNull(stage1.svtCoins)
        assertNull(stage1.craftEssences)
        assertNull(stage1.commandCodes)
        assertNull(stage1.servants)
    }

    // ---- stage 3: R3 CE alignment, R2/R4 servants & costumes, R5 editor, R6 size, R7 result ----

    @Test fun craftEssenceOwnedWithoutCopyUsesCollectionMaxWhileMetStaysNull() {
        val st = state(
            "userGame" to game,
            "userSvtCollection" to """[
                {"svtId":9300010,"status":2,"maxLv":80,"maxLimitCount":4},
                {"svtId":9300020,"status":1,"maxLv":80,"maxLimitCount":4},
                {"svtId":9300030,"status":2,"maxLv":80,"maxLimitCount":4}
            ]""",
            "userSvt" to """[{"svtId":9300030,"lv":50,"limitCount":2}]""",
        )
        val p = ChaldeaPatch.from(st, ChaldeaServer.Kr, stage2Numbers)
        assertEquals(
            mapOf(
                // Owned (2), no held copy -> uses maxLv/maxLimitCount
                11L to ChaldeaPatch.CeStatus(status = 2L, lv = 80L, limitCount = 4L),
                // Met (1), no held copy -> keeps null even when collection has maxLv/maxLimitCount
                22L to ChaldeaPatch.CeStatus(status = 1L, lv = null, limitCount = null),
                // Held copy wins over collection max
                33L to ChaldeaPatch.CeStatus(status = 2L, lv = 50L, limitCount = 2L),
            ),
            p.craftEssences,
        )
    }

    private val stage3Numbers = ChaldeaCollectionNumbers(
        equips = mapOf(9300010L to 11L),
        commandCodes = mapOf(80001L to 1L, 80002L to 2L),
        servants = mapOf(
            100100L to 2L,
            800100L to 1L,
            200100L to 11L,
            300100L to 20L,
            505600L to 401L,
        ),
    )

    @Test fun servantsCopySelectionOrderStorageAndLockWithdrawnRules() {
        // Order: lv desc -> skill sum desc -> createdAt asc -> id desc. Storage included.
        val st = state(
            "userGame" to game,
            "userSvtCollection" to """[
                {"svtId":100100,"status":2,"friendshipRank":5,"friendshipExceedCount":0,"costumeIds":[]},
                {"svtId":200100,"status":2,"friendshipRank":5,"friendshipExceedCount":0,"costumeIds":[]},
                {"svtId":300100,"status":2,"friendshipRank":5,"friendshipExceedCount":0,"costumeIds":[]},
                {"svtId":800100,"status":2,"friendshipRank":8,"friendshipExceedCount":3,"costumeIds":[]}
            ]""",
            "userSvt" to """[
                {"id":10,"svtId":100100,"lv":90,"skillLv1":10,"skillLv2":1,"skillLv3":1,"createdAt":100,"status":1,"treasureDeviceLv1":1},
                {"id":20,"svtId":100100,"lv":90,"skillLv1":5,"skillLv2":5,"skillLv3":5,"createdAt":200,"status":1,"treasureDeviceLv1":2},
                {"id":30,"svtId":100100,"lv":90,"skillLv1":5,"skillLv2":5,"skillLv3":5,"createdAt":100,"status":1,"treasureDeviceLv1":3},
                {"id":40,"svtId":200100,"lv":80,"skillLv1":10,"skillLv2":10,"skillLv3":10,"createdAt":50,"status":1,"isLock":0,"treasureDeviceLv1":2},
                {"id":41,"svtId":200100,"lv":70,"skillLv1":10,"skillLv2":10,"skillLv3":10,"createdAt":50,"status":1,"isLock":1,"treasureDeviceLv1":1},
                {"id":50,"svtId":300100,"lv":90,"skillLv1":10,"skillLv2":10,"skillLv3":10,"createdAt":50,"status":5,"treasureDeviceLv1":5},
                {"id":51,"svtId":300100,"lv":80,"skillLv1":1,"skillLv2":1,"skillLv3":1,"createdAt":50,"status":1,"treasureDeviceLv1":1}
            ]""",
            "userSvtStorage" to """[
                {"id":35,"svtId":100100,"lv":90,"skillLv1":5,"skillLv2":5,"skillLv3":5,"createdAt":100,"status":0,"isLock":1,"treasureDeviceLv1":5},
                {"id":60,"svtId":800100,"lv":80,"skillLv1":10,"skillLv2":10,"skillLv3":10,"createdAt":1,"status":1,"treasureDeviceLv1":3}
            ]""",
        )
        val p = ChaldeaPatch.from(st, ChaldeaServer.Kr, stage3Numbers)
        val svts = p.servants!!
        // 100100 (colNo 2): tie on lv=90, skillSum=15, createdAt=100 -> largest id=35 in storage (isLock=1 overrides status=0)
        assertEquals(5L, svts[2L]!!.npLv)
        // 200100 (colNo 11): best copy id=40 has isLock=0 (overrides status bit 1) -> servant skipped even though id=41 is locked
        assertFalse(11L in svts)
        // 300100 (colNo 20): best copy id=50 is withdrawn (status & 4 != 0) -> servant skipped even though id=51 is valid
        assertFalse(20L in svts)
        // 800100 (colNo 1, Mash in storage): status bit 1 (no isLock field) -> locked, written; bondLimit = 5 + 3 clamped max(8,10)..16 = 10
        assertEquals(3L, svts[1L]!!.npLv)
        assertEquals(10L, svts[1L]!!.bondLimit)
    }

    @Test fun servantsFieldFormulasClampsFouAppendsGrandCmdCodesAndCmdCard() {
        // Test Fou boundaries: adjustHp 0, 100, 150, 200, 300, 400 -> (0,0,0), (20,0,0), (20,25,0), (20,50,0), (20,50,10), (20,50,10)
        val expectedFou = listOf(
            0L to Triple(0L, 0L, 0L),
            100L to Triple(20L, 0L, 0L),
            150L to Triple(20L, 25L, 0L),
            200L to Triple(20L, 50L, 0L),
            300L to Triple(20L, 50L, 10L),
            400L to Triple(20L, 50L, 10L),
        )
        for ((adj, triple) in expectedFou) {
            val st = state(
                "userGame" to game,
                "userSvtCollection" to """[{"svtId":100100,"status":2,"friendshipRank":17,"friendshipExceedCount":10}]""",
                "userSvt" to """[{"id":1,"svtId":100100,"lv":120,"limitCount":5,"exceedCount":-2,"skillLv1":0,"skillLv2":11,"skillLv3":5,"adjustHp":$adj,"adjustAtk":$adj,"status":1,"treasureDeviceLv1":5}]""",
                "userSvtAppendPassiveSkill" to """[{"svtId":100100,"unlockNums":[100,101,104]}]""",
                "userSvtAppendPassiveSkillLv" to """[{"userSvtId":1,"appendPassiveSkillNums":[101,103],"appendPassiveSkillLvs":[11,6]},{"userSvtId":999,"appendPassiveSkillNums":[100],"appendPassiveSkillLvs":[10]}]""",
                "userSvtGrand" to """[{"userSvtId":1}]""",
                "userCommandCode" to """[{"id":10,"commandCodeId":80001},{"id":11,"commandCodeId":99999}]""",
                // 3-element list: [10 (-> cc 1), -1 (-> null), 11 (unmapped -> null)], slots 3 & 4 missing -> null
                "userSvtCommandCode" to """[{"svtId":100100,"userCommandCodeIds":[10,-1,11]}]""",
                // 3-element commandCardParam: [0, 39, 500, 600, ...] missing index -> 0, >500 clamped 25
                "userSvtCommandCard" to """[{"svtId":100100,"commandCardParam":[0,39,500,600]}]""",
            )
            val s = ChaldeaPatch.from(st, ChaldeaServer.Kr, stage3Numbers).servants!![2L]!!
            assertEquals(true, s.favorite)
            assertEquals(16L, s.bond)
            assertEquals(16L, s.bondLimit) // 10+10 clamped to 16
            assertEquals(4L, s.ascension) // 5 clamped to 4
            assertEquals(0L, s.grail) // -2 clamped to 0
            assertEquals(listOf(1L, 10L, 5L), s.skills)
            assertEquals(triple.first, s.fouHp3)
            assertEquals(triple.second, s.fouHp)
            assertEquals(triple.third, s.fouHp5)
            assertEquals(triple.first, s.fouAtk3)
            assertEquals(triple.second, s.fouAtk)
            assertEquals(triple.third, s.fouAtk5)
            assertEquals(listOf(1L, 10L, 0L, 6L, 1L), s.appendSkills)
            assertEquals(true, s.grandSvt)
            assertNull(s.costumes) // no gameData -> null
            assertEquals(listOf(1L, null, null, null, null), s.equipCmdCodes)
            assertEquals(listOf(0L, 1L, 25L, 25L, 0L), s.cmdCardStrengthen)
        }

        // Mash bondLimit lower bound max(bond, 10), grandSvt false when table present without match, null when absent
        val mashState = state(
            "userGame" to game,
            "userSvtCollection" to """[{"svtId":800100,"status":2,"friendshipRank":12,"friendshipExceedCount":3},{"svtId":100100,"status":2,"friendshipRank":3,"friendshipExceedCount":0}]""",
            "userSvt" to """[{"id":1,"svtId":800100,"lv":80,"status":1},{"id":2,"svtId":100100,"lv":90,"status":1}]""",
            "userSvtGrand" to """[{"userSvtId":999}]""",
        )
        val mashPatch = ChaldeaPatch.from(mashState, ChaldeaServer.Kr, stage3Numbers)
        // Mash: 5 + 3 = 8, clamped max(bond=12, 10)..16 = 12
        assertEquals(12L, mashPatch.servants!![1L]!!.bondLimit)
        assertEquals(false, mashPatch.servants!![1L]!!.grandSvt)
        assertNull(mashPatch.servants!![1L]!!.appendSkills) // both append tables absent -> null
    }

    @Test fun servantsCmdCodeAndCmdCardOnlyForSkippedServantAndNullConditions() {
        // Unlocked servant copy skipped for growth, still gets equipCmdCodes & cmdCardStrengthen; unknown svtId skipped
        val st = state(
            "userGame" to game,
            "userSvtCollection" to """[{"svtId":100100,"status":2}]""",
            "userSvt" to """[{"id":1,"svtId":100100,"lv":90,"status":0}]""",
            "userCommandCode" to """[{"id":10,"commandCodeId":80002}]""",
            "userSvtCommandCode" to """[{"svtId":100100,"userCommandCodeIds":[10]},{"svtId":999999,"userCommandCodeIds":[10]}]""",
            "userSvtCommandCard" to """[{"svtId":100100,"commandCardParam":[200]},{"svtId":999999,"commandCardParam":[200]}]""",
        )
        val p = ChaldeaPatch.from(st, ChaldeaServer.Kr, stage3Numbers)
        val entry = p.servants!![2L]!!
        assertNull(entry.favorite)
        assertNull(entry.bond)
        assertNull(entry.skills)
        assertEquals(listOf(2L, null, null, null, null), entry.equipCmdCodes)
        assertEquals(listOf(10L, 0L, 0L, 0L, 0L), entry.cmdCardStrengthen)
        assertEquals(setOf(2L), p.servants!!.keys)

        // Missing numbers.servants, missing userSvtCollection, or missing both card tables -> servants == null
        assertNull(ChaldeaPatch.from(st, ChaldeaServer.Kr, stage2Numbers).servants)
        assertNull(ChaldeaPatch.from(state("userGame" to game, "userSvt" to """[{"svtId":100100}]"""), ChaldeaServer.Kr, stage3Numbers).servants)
        assertNull(ChaldeaPatch.from(state("userGame" to game, "userSvtCollection" to """[{"svtId":100100}]"""), ChaldeaServer.Kr, stage3Numbers).servants)
    }

    @Test fun servantsEquipCmdCodesIsNullAndSkippedServantHasNoEntryWhenMappingDataMissing() {
        val baseTables = mapOf(
            "userGame" to game,
            "userSvtCollection" to """[{"svtId":100100,"status":2,"friendshipRank":5},{"svtId":200100,"status":2,"friendshipRank":3}]""",
            "userSvt" to """[{"id":1,"svtId":100100,"lv":90,"status":1},{"id":2,"svtId":200100,"lv":80,"status":0}]""",
            "userSvtCommandCode" to """[{"svtId":100100,"userCommandCodeIds":[10,-1]},{"svtId":200100,"userCommandCodeIds":[10]}]""",
        )

        // Case 1: numbers.commandCodes is empty even though userCommandCode table is present
        val withEmptyCcNumbers = ChaldeaPatch.from(
            AccountState(baseTables + ("userCommandCode" to """[{"id":10,"commandCodeId":80001}]"""), "[]", null, 1L, 2L),
            ChaldeaServer.Kr,
            stage3Numbers.copy(commandCodes = emptyMap()),
        )
        val svtsEmptyNum = withEmptyCcNumbers.servants!!
        assertEquals(true, svtsEmptyNum[2L]!!.favorite)
        assertEquals(5L, svtsEmptyNum[2L]!!.bond)
        assertNull(svtsEmptyNum[2L]!!.equipCmdCodes)
        assertFalse(11L in svtsEmptyNum, "skipped servant with only command codes must not get an entry")

        // Case 2: userCommandCode table is absent even though numbers.commandCodes is present
        val withoutUserCcTable = ChaldeaPatch.from(
            AccountState(baseTables, "[]", null, 1L, 2L),
            ChaldeaServer.Kr,
            stage3Numbers,
        )
        val svtsNoTable = withoutUserCcTable.servants!!
        assertEquals(true, svtsNoTable[2L]!!.favorite)
        assertEquals(5L, svtsNoTable[2L]!!.bond)
        assertNull(svtsNoTable[2L]!!.equipCmdCodes)
        assertFalse(11L in svtsNoTable, "skipped servant with only command codes must not get an entry")
    }

    @Test fun servantsBigTablesAreParsedAtMostOncePerFromCall() {
        val parseCounts = LinkedHashMap<String, Int>()
        val st = state(
            "userGame" to game,
            "userSvtCollection" to """[{"svtId":100100,"status":2,"friendshipRank":5}]""",
            "userSvt" to """[{"id":1,"svtId":100100,"lv":90,"status":1}]""",
            "userSvtStorage" to """[{"id":2,"svtId":9300010,"lv":50,"limitCount":4}]""",
            "userCommandCode" to """[{"id":10,"commandCodeId":80001}]""",
            "userSvtCommandCode" to """[{"svtId":100100,"userCommandCodeIds":[10]}]""",
        )
        val reverseKeyByValue = st.rawTables.entries.associate { it.value to it.key }
        val p = ChaldeaPatch.fromWithParser(st, ChaldeaServer.Kr, stage3Numbers) { raw ->
            val tableName = reverseKeyByValue[raw] ?: "unknown"
            parseCounts[tableName] = (parseCounts[tableName] ?: 0) + 1
            Json.parseToJsonElement(raw)
        }
        assertNotNull(p.servants)
        assertNotNull(p.craftEssences)
        assertNotNull(p.commandCodes)
        assertEquals(1, parseCounts["userSvtCollection"])
        assertEquals(1, parseCounts["userSvt"])
        assertEquals(1, parseCounts["userSvtStorage"])
        assertEquals(1, parseCounts["userCommandCode"])
    }

    @Test fun servantsCostumesMappingAndTransformPartnerMergeOrder() {
        val gd = ChaldeaGameData(
            version = "v1",
            costumes = mapOf(
                505600L to mapOf(1L to 505630L, 99L to 505699L),
                505700L to mapOf(2L to 505740L, 99L to 505799L),
                100100L to mapOf(12L to 800140L),
            ),
            transforms = mapOf(505600L to 505700L, 100100L to 200100L),
        )
        // Case 1: full mapping, unknown costumeId 888 dropped, transform partner 505700 (not in numbers.servants!) has collection row -> merged;
        // partner 200100 for 100100 has NO collection row -> ignored without failing.
        val st = state(
            "userGame" to game,
            "userSvtCollection" to """[
                {"svtId":505600,"status":2,"costumeIds":[1,888]},
                {"svtId":505700,"status":2,"costumeIds":[2,777]},
                {"svtId":100100,"status":2,"costumeIds":[12]}
            ]""",
            "userSvt" to """[
                {"id":1,"svtId":505600,"lv":90,"status":1},
                {"id":2,"svtId":100100,"lv":90,"status":1}
            ]""",
        )
        val p = ChaldeaPatch.from(st, ChaldeaServer.Jp, stage3Numbers, gd)
        assertEquals(mapOf(505740L to 1L, 505630L to 1L), p.servants!![401L]!!.costumes)
        // Verify partner inserted before own in key order when serialized
        val pJson = Json.parseToJsonElement(p.toJson()).jsonObject
        assertEquals(
            JsonObject(mapOf("505630" to JsonPrimitive(1), "505740" to JsonPrimitive(1))),
            pJson["servants"]!!.jsonObject["401"]!!.jsonObject["costumes"]!!.jsonObject,
        )
        assertEquals(mapOf(800140L to 1L), p.servants!![2L]!!.costumes)

        // Verify merge order explicitly when partner and own share a battleCharaId key
        val sameBcGd = ChaldeaGameData(
            version = "v2",
            costumes = mapOf(505600L to mapOf(1L to 900L, 2L to 902L), 505700L to mapOf(3L to 901L)),
            transforms = mapOf(505600L to 505700L),
        )
        val pOrder = ChaldeaPatch.from(
            state(
                "userGame" to game,
                "userSvtCollection" to """[{"svtId":505600,"costumeIds":[1,2]},{"svtId":505700,"costumeIds":[3]}]""",
                "userSvt" to """[{"id":1,"svtId":505600,"lv":90,"status":1}]""",
            ),
            ChaldeaServer.Jp,
            stage3Numbers,
            sameBcGd,
        )
        assertEquals(listOf(901L, 900L, 902L), pOrder.servants!![401L]!!.costumes!!.keys.toList())
    }

    @Test fun servantsRoundTripToJsonAndFromJson() {
        val full = ChaldeaPatch(
            friendCode = "123,456",
            server = ChaldeaServer.Kr,
            nickname = "Synth",
            isGirl = true,
            items = mapOf(1L to 100L),
            mysticCodes = null,
            classBoards = null,
            servants = mapOf(
                2L to ChaldeaPatch.SvtStatus(
                    favorite = true,
                    ascension = 4L,
                    skills = listOf(10L, 10L, 10L),
                    appendSkills = listOf(0L, 10L, 0L, 0L, 10L),
                    costumes = mapOf(800150L to 1L, 800140L to 1L),
                    grail = 5L,
                    fouHp5 = 10L,
                    fouAtk5 = 10L,
                    fouHp = 50L,
                    fouAtk = 50L,
                    fouHp3 = 20L,
                    fouAtk3 = 20L,
                    bondLimit = 15L,
                    npLv = 3L,
                    bond = 12L,
                    equipCmdCodes = listOf(null, 5L, null, null, 9L),
                    cmdCardStrengthen = listOf(25L, 0L, 0L, 10L, 5L),
                    grandSvt = true,
                ),
                1L to ChaldeaPatch.SvtStatus(equipCmdCodes = listOf(null, null, null, null, null)),
            ),
        )
        val json = full.toJson()
        assertEquals(full, ChaldeaPatch.fromJson(json))
        // Canonical sorted servant keys & field keys
        val svtsObj = Json.parseToJsonElement(json).jsonObject["servants"]!!.jsonObject
        assertEquals(listOf("1", "2"), svtsObj.keys.toList())
        assertEquals(listOf("equipCmdCodes"), svtsObj["1"]!!.jsonObject.keys.toList())
        assertEquals(listOf("800140", "800150"), svtsObj["2"]!!.jsonObject["costumes"]!!.jsonObject.keys.toList())
        assertNull(ChaldeaPatch.fromJson("""{"friendCode":"123,456","server":"kr","servants":"bad"}"""))
    }

    @Test fun editorMergesServantsPreservesPriorityAndDefaultsAndVerifiesStrictly() {
        val otherUser = user("1", items = """{"5":5}""")
        val existingSvt2 = """"2":{"cur":{"favorite":false,"ascension":1,"skills":[4,4,4],"appendSkills":[0,0,0,0,0],"costumes":{"999":1},"grail":0,"fouHp5":0,"fouAtk5":0,"fouHp":0,"fouAtk":0,"fouHp3":20,"fouAtk3":20,"bondLimit":10,"npLv":1,"extraCur":"keep"},"priority":5,"bond":3,"equipCmdCodes":[null,null,null,null,null],"cmdCardStrengthen":null,"grandSvt":false,"extraTop":"keep"}"""
        val untouchedSvt9 = """"9":{"cur":{"favorite":true,"ascension":4,"skills":[10,10,10],"appendSkills":[0,0,0,0,0],"costumes":{},"grail":0,"fouHp5":0,"fouAtk5":0,"fouHp":0,"fouAtk":0,"fouHp3":20,"fouAtk3":20,"bondLimit":10,"npLv":5},"priority":2,"bond":10}"""
        val existingMissingCur = """"3":{"priority":4,"bond":2}"""
        val targetUser = user(
            "123,456",
            extra = ""","plans":[1,2],"dupServantMapping":{"1":[2]},"servants":{$existingSvt2,$existingMissingCur,$untouchedSvt9}""",
        )
        val text = file("[$otherUser,$targetUser]")
        val root = Json.parseToJsonElement(text).jsonObject

        val p = patch().copy(
            servants = mapOf(
                // Updates growth + replaces costumes on existing servant 2; priority & unknowns must remain untouched
                2L to ChaldeaPatch.SvtStatus(favorite = true, ascension = 4L, costumes = mapOf(800140L to 1L), bond = 11L),
                // Existing servant 3 with missing cur gets default cur + patched bond
                3L to ChaldeaPatch.SvtStatus(npLv = 2L, bond = 7L),
                // Brand new servant 10 with only npLv gets Chaldea defaults for everything else (incl. fouHp3/fouAtk3 = 20)
                10L to ChaldeaPatch.SvtStatus(npLv = 3L),
            ),
        )
        val res = ok(ChaldeaUserdataEditor.edit(text, p, false))
        assertEquals(3, res.servantCount)
        val m = ChaldeaUserMatcher.Match.Existing(1, false)
        assertTrue(ChaldeaUserdataEditor.verify(root, res.output, p, m, 1))

        val outTarget = Json.parseToJsonElement(res.output).jsonObject["users"]!!.jsonArray[1].jsonObject
        val svt2 = outTarget["servants"]!!.jsonObject["2"]!!.jsonObject
        assertEquals(5, svt2["priority"]!!.jsonPrimitive.int)
        assertEquals("keep", svt2["extraTop"]!!.jsonPrimitive.content)
        assertEquals("keep", svt2["cur"]!!.jsonObject["extraCur"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(mapOf("800140" to JsonPrimitive(1))), svt2["cur"]!!.jsonObject["costumes"]!!.jsonObject)

        val svt10 = outTarget["servants"]!!.jsonObject["10"]!!.jsonObject
        assertEquals(1, svt10["priority"]!!.jsonPrimitive.int)
        assertEquals(0, svt10["bond"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, svt10["cmdCardStrengthen"])
        assertEquals(false, svt10["grandSvt"]!!.jsonPrimitive.boolean)
        assertEquals(20, svt10["cur"]!!.jsonObject["fouHp3"]!!.jsonPrimitive.int)
        assertEquals(20, svt10["cur"]!!.jsonObject["fouAtk3"]!!.jsonPrimitive.int)
        assertEquals(3, svt10["cur"]!!.jsonObject["npLv"]!!.jsonPrimitive.int)

        // verify rejects any tampering
        fun reject(tampered: String) {
            assertNotEquals(res.output, tampered)
            assertFalse(ChaldeaUserdataEditor.verify(root, tampered, p, m, 1))
        }
        reject(res.output.replace("\"priority\":5", "\"priority\":4"))
        reject(res.output.replace("\"extraCur\":\"keep\"", "\"extraCur\":\"bad\""))
        reject(res.output.replace("\"extraTop\":\"keep\"", "\"extraTop\":\"bad\""))
        reject(res.output.replace("\"npLv\":5", "\"npLv\":4")) // unpatched servant 9
        reject(res.output.replace("\"plans\":[1,2]", "\"plans\":[1,3]"))
        reject(res.output.replace("\"dupServantMapping\":{\"1\":[2]}", "\"dupServantMapping\":{\"1\":[3]}"))
        reject(res.output.replace("\"5\":5", "\"5\":6")) // other user
        reject(res.output.replace("\"version\":4", "\"version\":5")) // top level

        // Missing or null servants starts empty; unexpected shapes refuse with UnexpectedShape
        val nullSvts = file("[${user("123,456", extra = ""","servants":null""")}]")
        assertTrue(ChaldeaUserdataEditor.edit(nullSvts, patch().copy(servants = mapOf(10L to ChaldeaPatch.SvtStatus(npLv = 2L))), false) is ChaldeaEditResult.Success)
        for (badExtra in listOf(
            ""","servants":[]""",
            ""","servants":{"2":[]}""",
            ""","servants":{"2":{"cur":[]}}""",
            ""","servants":{"2":{"cur":{"costumes":[]}}}""",
        )) {
            val bad = file("[${user("123,456", extra = badExtra)}]")
            assertEquals(
                ChaldeaEditResult.Refused(ChaldeaRefusal.UnexpectedShape),
                ChaldeaUserdataEditor.edit(bad, patch().copy(servants = mapOf(2L to ChaldeaPatch.SvtStatus(bond = 1L))), false),
            )
        }
    }

    @Test fun realisticStage3PatchSizeFitsWellUnderBinderLimit() {
        val svtNumbers = (1L..330L).associate { (100000L + it * 100L) to it }
        val equipNumbers = (1L..1600L).associate { (9300000L + it) to it }
        val items = (1L..1300L).associate { it to (it * 10L) }
        val servants = svtNumbers.values.associateWith { colNo ->
            ChaldeaPatch.SvtStatus(
                favorite = true,
                ascension = 4L,
                skills = listOf(10L, 10L, 10L),
                appendSkills = listOf(10L, 10L, 0L, 0L, 10L),
                costumes = mapOf(800100L + colNo to 1L),
                grail = 5L,
                fouHp5 = 10L,
                fouAtk5 = 10L,
                fouHp = 50L,
                fouAtk = 50L,
                fouHp3 = 20L,
                fouAtk3 = 20L,
                bondLimit = 15L,
                npLv = 5L,
                bond = 15L,
                equipCmdCodes = listOf(1L, 2L, 3L, null, null),
                cmdCardStrengthen = listOf(25L, 25L, 25L, 25L, 25L),
                grandSvt = colNo <= 7L,
            )
        }
        val ces = equipNumbers.values.associateWith { colNo ->
            ChaldeaPatch.CeStatus(status = 2L, lv = 100L, limitCount = 4L)
        }
        val realistic = ChaldeaPatch(
            friendCode = "123,456,789",
            server = ChaldeaServer.Kr,
            nickname = "Master",
            isGirl = false,
            items = items,
            mysticCodes = (1L..20L).associateWith { 10L },
            classBoards = (1L..9L).associateWith { ChaldeaPatch.Board(listOf(1L, 2L, 3L), listOf(2L)) },
            svtCoins = (1L..330L).associate { ((100000L + it * 100L) * 100L + 1L) to 120L },
            craftEssences = ces,
            commandCodes = (1L..150L).associateWith { ChaldeaPatch.CcStatus(2L, 1L) },
            servants = servants,
        )
        val json = realistic.toJson()
        val chars = json.length
        val parcelBytes = chars * 2
        println("R6_REALISTIC_SIZE chars=$chars parcelBytes=$parcelBytes")
        assertTrue(chars < ChaldeaPatch.MAX_JSON_CHARS, "Expected chars ($chars) < ${ChaldeaPatch.MAX_JSON_CHARS}")
    }

    @Test fun syncResultSerializesServantsAndOldJsonDefaultsToZero() {
        val r = ChaldeaSyncResult(ChaldeaSyncCode.Ok, packageName = "cc.narumi.chaldea", itemCount = 3, servantCount = 42)
        val parsed = ChaldeaSyncResult.fromJson(r.toJson())
        assertEquals(42, parsed.servantCount)

        val oldJson = """{"code":"Ok","items":3}"""
        assertEquals(0, ChaldeaSyncResult.fromJson(oldJson).servantCount)
    }
}
