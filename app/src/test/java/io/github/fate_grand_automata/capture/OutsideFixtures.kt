package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.AccountDelta
import io.github.fate_grand_automata.scripts.capture.ChangeSource
import io.github.fate_grand_automata.scripts.capture.OutsidePeriod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Entirely handwritten fictional account/resource data. */
internal fun outsideLogin(id: String = "fictionalA", fruit: Long = 10, svts: String = "[]") = Json.parseToJsonElement("""
    {"response":[{"nid":"login","resCode":"00"}],"cache":{"replaced":{
      "userGame":[{"userId":"$id","name":"Sample Master","qp":20000000,"mana":20,"rarePri":3,"freeStone":90,"chargeStone":10}],
      "tblUserGame":[{"userId":"$id","friendPoint":10000,"createdAt":5,"flag":0}],
      "userItem":[{"userId":"$id","itemId":100,"num":$fruit}],
      "userSvt":$svts
    }}}
""").jsonObject

/** Synthetic KR gacha/draw assumption: cache.updated userGame holdings and new userSvt instance rows. */
internal fun outsideFriendPointSummon(friendPoint: Long = 9800, includeCards: Boolean = true, id: String = "fictionalA") = Json.parseToJsonElement("""
    {"response":[{"nid":"gacha","resCode":"00"}],"cache":{"updated":{
      "userGame":[{"userId":"$id","name":"Sample Master","qp":20000000,"mana":20,"rarePri":3,"freeStone":90,"chargeStone":10}],
      "tblUserGame":[{"userId":"$id","friendPoint":$friendPoint,"createdAt":5,"flag":0}]
      ${if (includeCards) """, "userSvt":[
        {"userId":"$id","id":1002,"svtId":100100,"lv":1},
        {"userId":"$id","id":1003,"svtId":9700010,"lv":1}
      ]""" else ""}
    }}}
""").jsonObject

internal fun outsideFruit(id: String = "fictionalA", num: Long) = Json.parseToJsonElement("""
    {"cache":{"updated":{"userItem":[{"userId":"$id","itemId":100,"num":$num}]}}}
""").jsonObject

internal fun outsideFixture(id: String = "outside-sample", end: Long = 1_793_754_000_000): OutsideLogEntry {
    val period = OutsidePeriod("fictionalA", end / 1000 - 4500)
        .add(ChangeSource.of("present/receive"), AccountDelta(mapOf(6510L to 12L, 6001L to 5L), qp = 1_234_000, mana = 10), end / 1000 - 3000)
        .add(ChangeSource.of("card/combine"), AccountDelta(mapOf(6001L to -20L), qp = -12_000_000), end / 1000 - 2000)
        .add(ChangeSource.of("item/recover"), AccountDelta(mapOf(100L to -2L)), end / 1000 - 1000)
        .add(ChangeSource.of("gacha/draw"), AccountDelta(freeStone = -25, chargeStone = -5), end / 1000 - 500)
        .add(ChangeSource.of("unknown/sample?discard=yes"), AccountDelta(rarePri = 1), end / 1000)
    return OutsideLogEntry(id, period.accountId, "샘플 계정", period.startedAt * 1000, end, period)
}

internal val outsideNames = GameNameMap(items = mapOf(6510L to "무간의 톱니바퀴", 6001L to "검의 휘석", 100L to "황금색 과일"))
