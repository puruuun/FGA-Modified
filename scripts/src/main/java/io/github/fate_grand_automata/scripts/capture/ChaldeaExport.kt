package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.*
import java.io.Writer

/** A login-shaped body for Chaldea's HTTPS sniffing file importer. */
object ChaldeaExport {
    private const val MINIMAL_LOGIN_RESPONSE = """[{"nid":"login","resCode":"00"}]"""

    // Chaldea's _$mstMasterSchemes (lib/models/gamedata/mst_tables.dart) at commit 3feb412bab.
    val CHALDEA_TABLES: Set<String> = setOf(
        "battle",
        "mstEventRaid",
        "mstShopDaily",
        "otherUserGame",
        "tblFriend",
        "tblUserGame",
        "totalEventRaid",
        "userAccountLinkage",
        "userBoxGacha",
        "userClassBoardSquare",
        "userCoinRoom",
        "userCommandCode",
        "userCommandCodeCollection",
        "userDeck",
        "userEquip",
        "userEvent",
        "userEventDeck",
        "userEventMission",
        "userEventMissionConditionDetail",
        "userEventMissionFix",
        "userEventPoint",
        "userEventRaid",
        "userEventRandomMission",
        "userEventTrade",
        "userFollower",
        "userGacha",
        "userGame",
        "userItem",
        "userLogin",
        "userPresentBox",
        "userPresentHistory",
        "userQuest",
        "userShop",
        "userShopDaily",
        "userSupportDeck",
        "userSvt",
        "userSvtAppendPassiveSkill",
        "userSvtAppendPassiveSkillLv",
        "userSvtCoin",
        "userSvtCollection",
        "userSvtCommandCard",
        "userSvtCommandCode",
        "userSvtGrand",
        "userSvtLeader",
        "userSvtStorage",
    )

    /** Returns the same JSON text as [write]. */
    fun body(state: AccountState): String = java.io.StringWriter().also { write(state, it) }.toString()

    /**
     * Writes a minimal `response` (`[{"nid":"login","resCode":"00"}]`) and only the `cache.replaced` tables listed in
     * [CHALDEA_TABLES] in stored order, embedding each table's saved text verbatim so nothing is parsed and no whole
     * body String exists (issue #258).
     */
    fun write(state: AccountState, out: Writer) {
        out.write("{\"response\":")
        out.write(MINIMAL_LOGIN_RESPONSE)
        out.write(",\"cache\":{\"updated\":{},\"deleted\":{},\"replaced\":{")
        var first = true
        for ((name, rows) in state.rawTables) {
            if (name !in CHALDEA_TABLES) continue
            if (!first) out.write(",")
            first = false
            out.write(JsonPrimitive(name).toString())
            out.write(":")
            out.write(rows)
        }
        out.write("},\"serverTime\":")
        out.write((state.serverTime ?: JsonNull).toString())
        out.write("}}")
    }
}
