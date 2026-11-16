package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.fate_grand_automata.scripts.enums.CardAffinityEnum
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.locations.AttackScreenLocations
import io.github.fate_grand_automata.scripts.locations.IScriptAreaTransforms
import io.github.fate_grand_automata.scripts.locations.Locations
import io.github.fate_grand_automata.scripts.models.CardPriority
import io.github.fate_grand_automata.scripts.models.CardPriorityPerWave
import io.github.fate_grand_automata.scripts.models.CardScore
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.models.FieldSlot
import io.github.fate_grand_automata.scripts.models.ParsedCard
import io.github.fate_grand_automata.scripts.models.TeamSlot
import io.github.fate_grand_automata.scripts.modules.CardParser
import io.github.fate_grand_automata.scripts.modules.CardSwapIcon
import io.github.fate_grand_automata.scripts.modules.FaceCardPriority
import io.github.fate_grand_automata.scripts.modules.ServantTracker
import io.github.fate_grand_automata.scripts.modules.swapType
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.lib_automata.Location
import io.github.lib_automata.Match
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test

class CardSwapTest {
    @Test
    fun `swapType converts Buster and Quick only when enabled, not stunned, and icon present`() {
        var calls = 0
        assertThat(swapType(CardTypeEnum.Buster, stunned = false, enabled = true) { calls++; true })
            .isEqualTo(CardTypeEnum.Arts)
        assertThat(swapType(CardTypeEnum.Quick, stunned = false, enabled = true) { calls++; true })
            .isEqualTo(CardTypeEnum.Arts)
        assertThat(swapType(CardTypeEnum.Buster, stunned = false, enabled = true) { calls++; false })
            .isEqualTo(CardTypeEnum.Buster)
        assertThat(swapType(CardTypeEnum.Quick, stunned = false, enabled = true) { calls++; false })
            .isEqualTo(CardTypeEnum.Quick)
        assertThat(calls).isEqualTo(4)

        // Arts, Unknown, stunned, or disabled never invoke the detector.
        assertThat(swapType(CardTypeEnum.Arts, stunned = false, enabled = true) { calls++; true })
            .isEqualTo(CardTypeEnum.Arts)
        assertThat(swapType(CardTypeEnum.Unknown, stunned = false, enabled = true) { calls++; true })
            .isEqualTo(CardTypeEnum.Unknown)
        assertThat(swapType(CardTypeEnum.Buster, stunned = true, enabled = true) { calls++; true })
            .isEqualTo(CardTypeEnum.Buster)
        assertThat(swapType(CardTypeEnum.Buster, stunned = false, enabled = false) { calls++; true })
            .isEqualTo(CardTypeEnum.Buster)
        assertThat(swapType(CardTypeEnum.Quick, stunned = false, enabled = false) { calls++; true })
            .isEqualTo(CardTypeEnum.Quick)
        assertThat(calls).isEqualTo(4)
    }

    private fun transforms(): IScriptAreaTransforms = object : IScriptAreaTransforms {
        override val scriptArea = Region(0, 0, 2560, 1440)
        override val isWide = false
        override val gameServer = GameServer.Jp.Original
        override val canLongSwipe = false
        override fun Location.xFromCenter() = this + Location(scriptArea.center.x, 0)
        override fun Region.xFromCenter() = this + Location(scriptArea.center.x, 0)
        override fun Location.xFromRight() = this + Location(scriptArea.right, 0)
        override fun Region.xFromRight() = this + Location(scriptArea.right, 0)
        override fun Location.yFromBottom() = this + Location(0, scriptArea.bottom)
        override fun Region.yFromBottom() = this + Location(0, scriptArea.bottom)
    }

    private fun setupParser(
        swapEnabled: Boolean,
        types: Map<CommandCard.Face, CardTypeEnum>,
        stunnedCards: Set<CommandCard.Face> = emptySet(),
        swapIcons: Set<CommandCard.Face> = emptySet()
    ): Triple<CardParser, CardSwapIcon, List<ScriptLog>> {
        val transforms = transforms()
        val attackLocations = AttackScreenLocations(transforms)
        val locations = mockk<Locations> {
            every { attack } returns attackLocations
        }

        val patterns = Images.entries.associateWith { mockk<Pattern>(relaxed = true) }
        val images = mockk<IImageLoader> {
            every { this@mockk[any()] } answers { patterns.getValue(firstArg()) }
        }
        val logs = mutableListOf<ScriptLog>()
        val messages = mockk<IScriptMessages>(relaxed = true) {
            every { log(capture(logs)) } returns Unit
        }
        val api = mockk<IFgoAutomataApi>(relaxed = true) {
            every { this@mockk.locations } returns locations
            every { this@mockk.images } returns images
            every { this@mockk.messages } returns messages
        }

        for (card in CommandCard.Face.list) {
            val typeRegion = attackLocations.typeRegion(card)
            val stunRegion = typeRegion.copy(y = 930, width = 248, height = 188)
            val isStunned = card in stunnedCards
            every {
                with(api) {
                    stunRegion.contains(any<Collection<Pattern>>())
                }
            } returns isStunned

            val cardType = types[card] ?: CardTypeEnum.Unknown
            for ((img, enumVal) in listOf(
                Images.Buster to CardTypeEnum.Buster,
                Images.Arts to CardTypeEnum.Arts,
                Images.Quick to CardTypeEnum.Quick
            )) {
                every {
                    with(api) {
                        typeRegion.contains(patterns.getValue(img))
                    }
                } returns (cardType == enumVal)
            }

            val affinityRegion = attackLocations.affinityRegion(card)
            every {
                with(api) {
                    affinityRegion.contains(any<Pattern>())
                }
            } returns false
        }

        val servantTracker = mockk<ServantTracker>(relaxed = true) {
            every { faceCardsGroupedByServant() } returns emptyMap()
            every { deployed } returns emptyMap()
            every { faceCardCheckEnabled } returns false
        }
        val battleConfig = mockk<IBattleConfig> {
            every { cardSwapDetection } returns swapEnabled
        }
        val cardSwapIcon = mockk<CardSwapIcon> {
            every { isPresent(any()) } answers { firstArg<CommandCard.Face>() in swapIcons }
        }

        return Triple(CardParser(api, servantTracker, battleConfig, cardSwapIcon), cardSwapIcon, logs)
    }

    @Test
    fun `CardParser with switch on converts Buster and Quick with icon, skips Arts, stunned and Unknown`() {
        val (parser, cardSwapIcon, logs) = setupParser(
            swapEnabled = true,
            types = mapOf(
                CommandCard.Face.A to CardTypeEnum.Buster,
                CommandCard.Face.B to CardTypeEnum.Quick,
                CommandCard.Face.C to CardTypeEnum.Buster,
                CommandCard.Face.D to CardTypeEnum.Arts,
                CommandCard.Face.E to CardTypeEnum.Unknown
            ),
            swapIcons = setOf(CommandCard.Face.A, CommandCard.Face.B, CommandCard.Face.D, CommandCard.Face.E)
        )

        val cards = parser.parse()

        assertThat(cards[0].type).isEqualTo(CardTypeEnum.Arts)
        assertThat(cards[0].swappedFrom).isEqualTo(CardTypeEnum.Buster)
        assertThat(cards[1].type).isEqualTo(CardTypeEnum.Arts)
        assertThat(cards[1].swappedFrom).isEqualTo(CardTypeEnum.Quick)
        assertThat(cards[2].type).isEqualTo(CardTypeEnum.Buster)
        assertThat(cards[2].swappedFrom).isNull()
        assertThat(cards[3].type).isEqualTo(CardTypeEnum.Arts)
        assertThat(cards[3].swappedFrom).isNull()
        assertThat(cards[4].type).isEqualTo(CardTypeEnum.Unknown)
        assertThat(cards[4].swappedFrom).isNull()

        verify(exactly = 1) { cardSwapIcon.isPresent(CommandCard.Face.A) }
        verify(exactly = 1) { cardSwapIcon.isPresent(CommandCard.Face.B) }
        verify(exactly = 1) { cardSwapIcon.isPresent(CommandCard.Face.C) }
        verify(exactly = 0) { cardSwapIcon.isPresent(CommandCard.Face.D) }
        verify(exactly = 0) { cardSwapIcon.isPresent(CommandCard.Face.E) }

        val swapLogs = logs.filterIsInstance<ScriptLog.FaceCardCheck>().map { it.message }
        assertThat(swapLogs).containsExactly(
            "A: Buster -> Arts (swap icon)",
            "B: Quick -> Arts (swap icon)"
        )
    }

    @Test
    fun `CardParser with switch on never checks stunned cards`() {
        val (parser, cardSwapIcon, logs) = setupParser(
            swapEnabled = true,
            types = mapOf(
                CommandCard.Face.A to CardTypeEnum.Buster
            ),
            stunnedCards = setOf(CommandCard.Face.A),
            swapIcons = setOf(CommandCard.Face.A)
        )

        val cards = parser.parse()

        assertThat(cards[0].isStunned).isTrue()
        assertThat(cards[0].type).isEqualTo(CardTypeEnum.Unknown)
        assertThat(cards[0].swappedFrom).isNull()
        verify(exactly = 0) { cardSwapIcon.isPresent(CommandCard.Face.A) }
        assertThat(logs.filterIsInstance<ScriptLog.FaceCardCheck>()).isEqualTo(emptyList())
    }

    @Test
    fun `CardParser with switch off never calls detector and leaves types unchanged`() {
        val (parser, cardSwapIcon, logs) = setupParser(
            swapEnabled = false,
            types = mapOf(
                CommandCard.Face.A to CardTypeEnum.Buster,
                CommandCard.Face.B to CardTypeEnum.Quick,
                CommandCard.Face.C to CardTypeEnum.Arts,
                CommandCard.Face.D to CardTypeEnum.Unknown,
                CommandCard.Face.E to CardTypeEnum.Buster
            ),
            stunnedCards = setOf(CommandCard.Face.E),
            swapIcons = setOf(CommandCard.Face.A, CommandCard.Face.B)
        )

        val cards = parser.parse()

        assertThat(cards.map { it.type }).containsExactly(
            CardTypeEnum.Buster,
            CardTypeEnum.Quick,
            CardTypeEnum.Arts,
            CardTypeEnum.Unknown,
            CardTypeEnum.Unknown
        )
        assertThat(cards.all { it.swappedFrom == null }).isTrue()
        verify(exactly = 0) { cardSwapIcon.isPresent(any()) }
        confirmVerified(cardSwapIcon)
        assertThat(logs.filterIsInstance<ScriptLog.FaceCardCheck>()).isEqualTo(emptyList())
    }

    @Test
    fun `converted card is ranked in the Arts group by existing priority`() {
        val swappedWeakArts = ParsedCard(
            card = CommandCard.Face.A,
            servant = TeamSlot.A,
            fieldSlot = FieldSlot.A,
            type = CardTypeEnum.Arts,
            affinity = CardAffinityEnum.Weak,
            swappedFrom = CardTypeEnum.Buster
        )
        val weakBuster = ParsedCard(
            card = CommandCard.Face.B,
            servant = TeamSlot.B,
            fieldSlot = FieldSlot.B,
            type = CardTypeEnum.Buster,
            affinity = CardAffinityEnum.Weak
        )
        val priorityOrder = CardPriorityPerWave.from(
            listOf(
                CardPriority.from(
                    listOf(
                        CardScore(CardTypeEnum.Arts, CardAffinityEnum.Weak),
                        CardScore(CardTypeEnum.Buster, CardAffinityEnum.Weak),
                        CardScore(CardTypeEnum.Arts, CardAffinityEnum.Normal),
                        CardScore(CardTypeEnum.Buster, CardAffinityEnum.Normal),
                        CardScore(CardTypeEnum.Quick, CardAffinityEnum.Weak),
                        CardScore(CardTypeEnum.Quick, CardAffinityEnum.Normal),
                        CardScore(CardTypeEnum.Arts, CardAffinityEnum.Resist),
                        CardScore(CardTypeEnum.Buster, CardAffinityEnum.Resist),
                        CardScore(CardTypeEnum.Quick, CardAffinityEnum.Resist)
                    )
                )
            )
        )
        val priority = FaceCardPriority(priorityOrder, null)

        val sorted = priority.sort(listOf(weakBuster, swappedWeakArts), 0).map { it.card }

        assertThat(sorted).containsExactly(CommandCard.Face.A, CommandCard.Face.B)
    }

    @Test
    fun `CardSwapIcon uses explicit 0_70 similarity for isPresent and 0_01 for score`() {
        val transforms = transforms()
        val attackLocations = AttackScreenLocations(transforms)
        val locations = mockk<Locations> {
            every { attack } returns attackLocations
        }
        val pattern = mockk<Pattern>()
        val images = mockk<IImageLoader> {
            every { this@mockk[Images.CardSwap] } returns pattern
        }
        val api = mockk<IFgoAutomataApi>(relaxed = true) {
            every { this@mockk.locations } returns locations
            every { this@mockk.images } returns images
        }
        val regionA = attackLocations.swapIconRegion(CommandCard.Face.A)
        val regionB = attackLocations.swapIconRegion(CommandCard.Face.B)
        every { with(api) { regionA.findAll(pattern, CardSwapIcon.SIMILARITY) } } returns
            listOf(Match(regionA, 0.92))
        every { with(api) { regionB.findAll(pattern, CardSwapIcon.SIMILARITY) } } returns emptyList()
        every { with(api) { regionA.findAll(pattern, 0.01) } } returns
            listOf(Match(regionA, 0.92), Match(regionA, 0.45))
        every { with(api) { regionB.findAll(pattern, 0.01) } } returns emptyList()

        val detector = CardSwapIcon(api)
        assertThat(CardSwapIcon.SIMILARITY).isEqualTo(0.70)
        assertThat(detector.isPresent(CommandCard.Face.A)).isTrue()
        assertThat(detector.isPresent(CommandCard.Face.B)).isFalse()
        assertThat(detector.score(CommandCard.Face.A)).isEqualTo(0.92)
        assertThat(detector.score(CommandCard.Face.B)).isEqualTo(0.0)
    }
}
