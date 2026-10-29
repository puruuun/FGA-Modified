package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.enums.CardAffinityEnum
import io.github.fate_grand_automata.scripts.enums.CardPriorityStyle
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.scripts.models.CardPriorityPerWave
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.models.FieldSlot
import io.github.fate_grand_automata.scripts.models.NPUsage
import io.github.fate_grand_automata.scripts.models.ParsedCard
import io.github.fate_grand_automata.scripts.models.ServantCardKey
import io.github.fate_grand_automata.scripts.models.ServantCardPriorityPerWave
import io.github.fate_grand_automata.scripts.models.ServantPriorityPerWave
import io.github.fate_grand_automata.scripts.models.TeamSlot
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.models.battle.CommandPosition
import io.github.fate_grand_automata.scripts.modules.ApplyBraveChains
import io.github.fate_grand_automata.scripts.modules.BattleExecution
import io.github.fate_grand_automata.scripts.modules.Card
import io.github.fate_grand_automata.scripts.modules.CardParser
import io.github.fate_grand_automata.scripts.modules.Caster
import io.github.fate_grand_automata.scripts.modules.FaceCardPriority
import io.github.fate_grand_automata.scripts.modules.ServantTracker
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ServantCardPriorityTest {
    private val A = CommandCard.Face.A
    private val B = CommandCard.Face.B
    private val C = CommandCard.Face.C
    private val D = CommandCard.Face.D
    private val E = CommandCard.Face.E

    private fun card(
        face: CommandCard.Face,
        slot: FieldSlot?,
        type: CardTypeEnum,
        affinity: CardAffinityEnum = CardAffinityEnum.Normal,
        stunned: Boolean = false
    ) = ParsedCard(
        card = face,
        servant = when (slot) {
            FieldSlot.A -> TeamSlot.A
            FieldSlot.B -> TeamSlot.B
            FieldSlot.C -> TeamSlot.C
            null -> TeamSlot.Unknown
        },
        fieldSlot = slot,
        type = type,
        affinity = affinity,
        isStunned = stunned
    )

    /** 1B, 2A, 1A, 3Q, 2B */
    private val hand = listOf(
        card(A, FieldSlot.A, CardTypeEnum.Buster),
        card(B, FieldSlot.B, CardTypeEnum.Arts),
        card(C, FieldSlot.A, CardTypeEnum.Arts),
        card(D, FieldSlot.C, CardTypeEnum.Quick),
        card(E, FieldSlot.B, CardTypeEnum.Buster)
    )

    private fun servantPriority(text: String, vararg more: String) =
        ServantCardPriorityPerWave.of((listOf(text) + more).joinToString("\n"))

    private fun sorter(list: ServantCardPriorityPerWave) = FaceCardPriority(
        CardPriorityPerWave.default,
        // The toggle is on but must be ignored.
        ServantPriorityPerWave.default,
        CardPriorityStyle.Servant,
        list
    )

    private val interleaved = "1B,2B,1A,3Q,2A,1Q,3B,3A,2Q"

    @Test
    fun `cards interleave across servants in list order`() {
        val sorted = sorter(servantPriority(interleaved)).sort(hand, 0).map { it.card }

        // 1B, 2B, 1A, 3Q, 2A
        assertThat(sorted).containsExactly(A, E, C, D, B)
    }

    @Test
    fun `affinity and the servant priority toggle do not matter`() {
        val weakHand = hand.map { it.copy(affinity = CardAffinityEnum.Weak) }

        assertThat(sorter(servantPriority(interleaved)).sort(weakHand, 0).map { it.card }).containsExactly(A, E, C, D, B)
    }

    @Test
    fun `cards with the same position and type keep the hand order`() {
        val twins = listOf(
            card(E, FieldSlot.B, CardTypeEnum.Buster),
            card(C, FieldSlot.A, CardTypeEnum.Buster),
            card(A, FieldSlot.B, CardTypeEnum.Buster),
            card(D, FieldSlot.A, CardTypeEnum.Buster, CardAffinityEnum.Weak)
        )

        val sorted = sorter(servantPriority("2B,1B,1A,1Q,2A,2Q,3B,3A,3Q")).sort(twins, 0).map { it.card }

        assertThat(sorted).containsExactly(E, A, C, D)
    }

    @Test
    fun `stunned unknown type and unknown position cards go last in hand order`() {
        val mixed = listOf(
            card(A, FieldSlot.A, CardTypeEnum.Unknown, stunned = true),
            card(B, null, CardTypeEnum.Arts),
            card(C, FieldSlot.C, CardTypeEnum.Quick),
            card(D, FieldSlot.A, CardTypeEnum.Arts, stunned = true),
            card(E, FieldSlot.B, CardTypeEnum.Buster)
        )

        val sorted = sorter(servantPriority("3Q,2B,1A,1B,1Q,2A,2Q,3B,3A")).sort(mixed, 0).map { it.card }

        assertThat(sorted).containsExactly(C, E, A, B, D)
    }

    @Test
    fun `each entry has its own list and the last one repeats`() {
        val list = servantPriority(interleaved, "3Q,3B,3A,2Q,2B,2A,1Q,1B,1A")
        val sorter = sorter(list)

        assertThat(sorter.sort(hand, 0).map { it.card }).containsExactly(A, E, C, D, B)
        // 3Q, 2B, 2A, 1B, 1A
        assertThat(sorter.sort(hand, 1).map { it.card }).containsExactly(D, E, B, A, C)
        assertThat(sorter.sort(hand, 9).map { it.card }).containsExactly(D, E, B, A, C)
    }

    @Test
    fun `affinity style is unchanged by the servant list`() {
        val affinity = FaceCardPriority(
            CardPriorityPerWave.default,
            null,
            CardPriorityStyle.Affinity,
            servantPriority(interleaved)
        )
        val plain = FaceCardPriority(CardPriorityPerWave.default, null)

        assertThat(affinity.sort(FaceCardPriorityTest.lineup1, 0)).isEqualTo(plain.sort(FaceCardPriorityTest.lineup1, 0))
        assertThat(affinity.sort(FaceCardPriorityTest.lineup1, 0).map { it.card })
            .containsExactly(CommandCard.Face.A, CommandCard.Face.E, CommandCard.Face.B, CommandCard.Face.C, CommandCard.Face.D)
    }

    // ---- storage ----

    @Test
    fun `lists round trip through their stored text`() {
        val list = servantPriority(interleaved, "3Q,3B,3A,2Q,2B,2A,1Q,1B,1A")

        val restored = ServantCardPriorityPerWave.of(list.toString())

        assertThat(restored.toString()).isEqualTo(list.toString())
        assertThat(restored.size).isEqualTo(2)
        assertThat(restored.atWave(0).joinToString(",")).isEqualTo(interleaved)
    }

    @Test
    fun `ten entries are stored`() {
        val entries = List(10) { ServantCardKey.all.rotate(it).joinToString(",") }

        val list = ServantCardPriorityPerWave.of(entries.joinToString("\n"))

        assertThat(list.size).isEqualTo(10)
        assertThat(ServantCardPriorityPerWave.of(list.toString()).toString()).isEqualTo(list.toString())
    }

    private fun <T> List<T>.rotate(by: Int) = drop(by) + take(by)

    @Test
    fun `blank settings give the default order`() {
        assertThat(ServantCardPriorityPerWave.of("").toString()).isEqualTo("1B,1A,1Q,2B,2A,2Q,3B,3A,3Q")
        assertThat(ServantCardPriorityPerWave.default.size).isEqualTo(1)
    }

    @Test
    fun `old affinity text is rejected so settings fall back to the default`() {
        // The prefs serializer catches this and uses the default.
        for (text in listOf(
            "WB, WA, WQ, B, A, Q, RB, RA, RQ",
            "1B,1A,1Q,2B,2A,2Q,3B,3A",
            "1B,1B,1Q,2B,2A,2Q,3B,3A,3Q",
            "4B,1A,1Q,2B,2A,2Q,3B,3A,3Q"
        )) {
            assertFailsWith<IllegalArgumentException> { ServantCardPriorityPerWave.of(text) }
        }
    }

    @Test
    fun `keys parse and print`() {
        assertThat(ServantCardKey.parse("2a")).isEqualTo(ServantCardKey(2, CardTypeEnum.Arts))
        assertThat(ServantCardKey.parse("3X")).isNull()
        assertThat(ServantCardKey.parse("0B")).isNull()
        assertThat(ServantCardKey(3, CardTypeEnum.Quick).toString()).isEqualTo("3Q")
        assertThat(ServantCardKey.all.size).isEqualTo(9)
    }

    // ---- ignored settings ----

    private val profile = TestExecutionProfile(
        cardPriorityStyle = CardPriorityStyle.Servant,
        servantCardPriority = servantPriority(interleaved),
        useServantPriority = true,
        servantPriority = ServantPriorityPerWave.of("3,2,1"),
        braveChains = listOf(BraveChainEnum.WithNP, BraveChainEnum.Avoid),
        rearrangeCards = listOf(true, true)
    )

    private fun configOf(profile: TestExecutionProfile) = mockk<IBattleConfig>(relaxed = true).also { config ->
        every { config.cardPriority } returns profile.cardPriority
        every { config.cardPrioritySplit } returns profile.cardPrioritySplit
        every { config.cardPriorityStyle } returns profile.cardPriorityStyle
        every { config.servantCardPriority } returns profile.servantCardPriority
        every { config.useServantPriority } returns profile.useServantPriority
        every { config.servantPriority } returns profile.servantPriority
        every { config.braveChains } returns profile.braveChains
        every { config.rearrangeCards } returns profile.rearrangeCards
        every { config.spam } returns emptyList()
    }

    @Test
    fun `servant style ignores the stored brave chains rearrange and servant priority`() {
        val active = BattleExecution(configOf(profile)).active

        assertThat(active.braveChains).isEmpty()
        assertThat(active.rearrangeCards).isEmpty()
        assertThat(active.priority.sort(hand, 0).map { it.card }).containsExactly(A, E, C, D, B)
    }

    @Test
    fun `affinity style keeps the stored settings`() {
        val affinity = profile.let {
            TestExecutionProfile(
                useServantPriority = true,
                servantPriority = it.servantPriority,
                braveChains = it.braveChains,
                rearrangeCards = it.rearrangeCards
            )
        }
        val active = BattleExecution(configOf(affinity)).active

        assertThat(active.braveChains).isEqualTo(affinity.braveChains)
        assertThat(active.rearrangeCards).isEqualTo(affinity.rearrangeCards)
        // Servant priority 3, 2, 1: the third servant's card first.
        assertThat(active.priority.sort(hand, 0).first().card).isEqualTo(D)
    }

    // ---- click order ----

    private fun clicked(profile: TestExecutionProfile, npUsage: NPUsage = NPUsage.none): List<CommandCard> {
        val taps = mutableListOf<CommandCard>()
        val caster = mockk<Caster>(relaxed = true)
        every { caster.use(any<CommandCard.Face>()) } answers { taps += firstArg<CommandCard.Face>() }
        val tracker = mockk<ServantTracker>()
        every { tracker.deployed } returns mapOf(FieldSlot.A to TeamSlot.A)
        val state = BattleState().also { it.nextStage(); it.commandPosition = CommandPosition(0, 0, 0) }
        val card = Card(
            mockk(relaxed = true), tracker, state, caster, mockk<CardParser>(),
            ApplyBraveChains(), BattleExecution(configOf(profile))
        )

        card.clickCommandCards(hand, npUsage, allowNpSpam = false)
        return taps
    }

    @Test
    fun `servant style clicks exactly the sorted order despite brave chains and rearrange`() {
        for (chain in BraveChainEnum.entries) {
            val p = TestExecutionProfile(
                cardPriorityStyle = CardPriorityStyle.Servant,
                servantCardPriority = servantPriority(interleaved),
                braveChains = listOf(chain),
                rearrangeCards = listOf(true)
            )

            assertThat(clicked(p)).containsExactly(A, E, C)
        }
    }

    @Test
    fun `affinity style still rearranges`() {
        val p = TestExecutionProfile(rearrangeCards = listOf(true))

        // Default priority sorts the hand to 1B, 2B (Buster slot B), then arts; rearrange swaps the last two.
        val plain = clicked(TestExecutionProfile())
        val rearranged = clicked(p)

        assertThat(rearranged).containsExactly(plain[0], plain[2], plain[1])
    }
}
