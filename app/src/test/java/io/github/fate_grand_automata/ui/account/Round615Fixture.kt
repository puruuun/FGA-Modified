package io.github.fate_grand_automata.ui.account

import io.github.fate_grand_automata.capture.*

/**
 * Round2Fixture plus what #615 adds: the in-progress block, login counts, parties, support formation names, and
 * command codes and card boost on a few servants. Everything is synthetic; the sample names say so.
 */
internal class Round615Fixture(private val now: Long = System.currentTimeMillis() / 1000) {
    val base = Round2Fixture()
    val case = FormationCase(grand = true)

    val names: GameNameMap = base.uiNames(case).let {
        it.copy(mysticData = it.mysticData + (1L to AtlasBasic(1, "샘플 전투복", "mysticCode")))
    }

    private fun member(position: Int, svt: Long, level: Long, np: Long, ce: Triple<Long, Long, Long>? = null) =
        DashboardPartySlot(position, svt, level, np, listOfNotNull(ce?.let { DashboardPartyCe(it.first, it.second, it.third) }))

    private fun support(position: Int) = DashboardPartySlot(position, null, null, null, emptyList())

    val parties = listOf(
        DashboardParty(1, "주력 파티", listOf(
            member(1, 10, 90, 3, Triple(9000, 100, 4)), member(2, 32, 80, 2, Triple(9001, 40, 1)), member(3, 43, 70, 1),
            member(4, 20, 80, 5, Triple(9002, 60, 2)), member(5, 31, 60, 1), support(6),
        ), mysticCodeId = 1),
        DashboardParty(2, null, listOf(member(1, 10, 90, 3, Triple(9000, 100, 4)), member(2, 32, 80, 2), member(3, 43, 70, 1), support(4)), mysticCodeId = 1),
        DashboardParty(5, "농장", listOf(member(1, 20, 80, 5, Triple(9001, 40, 1)), member(2, 31, 60, 1)), mysticCodeId = null),
    )

    val login = DashboardLogin(total = 2774, seq = 1672, nextRewardDay = 2800)

    val progress = DashboardProgress(
        weekly = WeeklyProgress(start = now - 3 * 86400, end = now + 4 * 86400, done = 5, total = 7),
        events = listOf(
            EventProgress(80500, "샘플 여름 이벤트", 1_234_567, 2_000_000, now - 10 * 86400, now + 10 * 86400),
            EventProgress(80501, "샘플 복각 이벤트", 3_000_000, 3_000_000, now - 10 * 86400, now + 5 * 86400),
        ),
    )

    private fun withData(servant: DashboardServant) = when (servant.id) {
        10L -> servant.copy(commandCodes = DashboardServantCodes(2, 5), cardBoost = DashboardCardBoost(1000, 2))
        32L -> servant.copy(commandCodes = DashboardServantCodes(5, 5))
        43L -> servant.copy(cardBoost = DashboardCardBoost(500, 1))
        20L -> servant.copy(commandCodes = DashboardServantCodes(0, 3))
        else -> servant
    }

    fun data(): DashboardData = base.allSections(case).let { d ->
        d.copy(
            login = login,
            parties = parties,
            servants = d.servants!!.map(::withData),
            formations = d.formations?.let { f -> f.copy(main = f.main.mapIndexed { i, deck -> if (i == 0) deck.copy(name = "샘플 서포트") else deck }) },
        )
    }
}
