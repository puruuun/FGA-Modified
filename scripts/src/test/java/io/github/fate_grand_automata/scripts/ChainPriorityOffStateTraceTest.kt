package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.GameServers
import io.github.fate_grand_automata.scripts.locations.BattleScreenLocations
import io.github.fate_grand_automata.scripts.locations.IScriptAreaTransforms
import io.github.fate_grand_automata.scripts.locations.Locations
import io.github.fate_grand_automata.scripts.locations.MasterLocations
import io.github.fate_grand_automata.scripts.models.AutoSkillAction
import io.github.fate_grand_automata.scripts.models.FieldSlot
import io.github.fate_grand_automata.scripts.models.OrderChangeMember
import io.github.fate_grand_automata.scripts.models.ServantTarget
import io.github.fate_grand_automata.scripts.models.Skill
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.modules.Caster
import io.github.fate_grand_automata.scripts.modules.ServantTracker
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import io.github.lib_automata.AutomataApi
import io.github.lib_automata.Location
import io.github.lib_automata.Match
import io.github.lib_automata.OcrService
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Issue #654: with Chain Priority off, a battle taps, reads the screen, waits and logs exactly as before.
 * The trace was recorded from `origin/main` before any production change (FGA_RECORD_CHAIN654=1 re-records,
 * which is only correct on that code).
 */
class ChainPriorityOffStateTraceTest {
    private val fixturePath = "src/test/resources/chain654/off-state-trace.txt"

    private val transforms = object : IScriptAreaTransforms {
        override val scriptArea = Region(0, 0, 2560, 1440)
        override val isWide = scriptArea.size.isWide()
        override val gameServer: GameServer = GameServers.default
        override val canLongSwipe = false
        override fun Location.xFromCenter() = this + Location(scriptArea.center.x, 0)
        override fun Region.xFromCenter() = this + Location(scriptArea.center.x, 0)
        override fun Location.xFromRight() = this + Location(scriptArea.right, 0)
        override fun Region.xFromRight() = this + Location(scriptArea.right, 0)
        override fun Location.yFromBottom() = this + Location(0, scriptArea.bottom)
        override fun Region.yFromBottom() = this + Location(0, scriptArea.bottom)
    }

    /** Records every tap, screen read, wait and log; the screen answers "same as before" to every check. */
    class TraceApi(
        override val prefs: IPreferences,
        override val locations: Locations
    ) : IFgoAutomataApi {
        val trace = mutableListOf<String>()
        private val names = mutableMapOf<Pattern, String>()
        private var captures = 0
        private var depth = 0

        /** Regions where the SUPPORT label of a servant is shown. */
        var supportRegions = emptySet<Region>()

        /** Regions where a captured servant portrait is no longer found (a different servant entered). */
        var changedRegions = emptySet<Region>()

        private fun named(name: String): Pattern = mockk<Pattern>(relaxed = true).also { names[it] = name }
        private fun Pattern.name() = names[this] ?: "?"

        override val images: IImageLoader = mockk<IImageLoader>().also { loader ->
            val cache = mutableMapOf<Images, Pattern>()
            every { loader[any(), any()] } answers { cache.getOrPut(firstArg()) { named(firstArg<Images>().name) } }
        }
        override val messages: IScriptMessages = object : IScriptMessages {
            override fun log(item: ScriptLog) {
                trace += "log ${item::class.simpleName}"
            }

            override fun notify(action: ScriptNotify) {
                trace += "notify ${action::class.simpleName}"
            }
        }

        private fun Region.answer(image: Pattern) = when {
            image.name() == Images.ServantCheckSupport.name -> this in supportRegions
            image.name().startsWith("capture") -> this !in changedRegions
            else -> true
        }

        override fun Region.getPattern(tag: String): Pattern {
            trace += "capture $this '$tag'"
            return named("capture${++captures}")
        }

        override fun <T> useSameSnapIn(block: () -> T): T {
            trace += "snap{"
            depth++
            return try { block() } finally { depth--; trace += "}" }
        }

        override fun <T> useColor(block: () -> T): T = block()
        override fun Duration.wait(applyMultiplier: Boolean) {
            trace += "wait $this $applyMultiplier"
        }

        override fun Location.click(times: Int) {
            trace += "click $this x$times"
        }

        override fun Region.click(times: Int) {
            trace += "click $this x$times"
        }

        override fun Region.exists(image: Pattern, timeout: Duration, similarity: Double?): Boolean {
            trace += "exists $this ${image.name()} $timeout $similarity"
            return answer(image)
        }

        override fun Region.waitVanish(image: Pattern, timeout: Duration, similarity: Double?): Boolean {
            trace += "waitVanish $this ${image.name()} $timeout $similarity"
            return true
        }

        override fun Region.findAll(pattern: Pattern, similarity: Double?): List<Match> {
            trace += "findAll $this ${pattern.name()} $similarity"
            return if (answer(pattern)) listOf(Match(this, 0.9)) else emptyList()
        }

        override fun Region.contains(image: Pattern) = exists(image)
        override fun Region.contains(images: Collection<Pattern>) = images.any { contains(it) }
        override fun Region.find(pattern: Pattern, similarity: Double?): Match? = findAll(pattern, similarity).firstOrNull()
        override fun Region.find(patterns: Collection<Pattern>, similarity: Double?): Match? =
            patterns.firstNotNullOfOrNull { find(it, similarity) }

        override fun Region.isWhite(): Boolean {
            trace += "isWhite $this"
            return false
        }

        override fun Region.isBlack(): Boolean {
            trace += "isBlack $this"
            return false
        }

        override fun Region.detectText(outlinedText: Boolean): String {
            trace += "detectText $this $outlinedText"
            return ""
        }

        override fun Map<Pattern, Region>.exists(timeout: Duration, similarity: Double?, requireAll: Boolean): Boolean {
            trace += "existsMap ${entries.joinToString { "${it.key.name()}@${it.value}" }} $timeout"
            return true
        }
    }

    private fun prefs() = mockk<IPreferences>(relaxed = true).also {
        every { it.skillDelay } returns 500.milliseconds
        every { it.masterSkillMenuDelay } returns 1500.milliseconds
        stubAddedPrefs(it)
    }

    private fun locations(): Locations {
        val master = MasterLocations(transforms, mockk(relaxed = true), mockk<AutomataApi>(relaxed = true), mockk(relaxed = true))
        return Locations(
            transforms,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            BattleScreenLocations(transforms, master),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        )
    }

    /** Brave chains turn the face-card check on, so the details dialog is opened for every servant. */
    private fun config(
        chainPriorities: List<Boolean> = emptyList(),
        braveChains: List<BraveChainEnum> = listOf(BraveChainEnum.WithNP)
    ): IBattleConfig = FaceCardCheckPolicyTest.config(
        primary = FaceCardCheckPolicyTest.profile(braveChains = braveChains, chainPriorities = chainPriorities)
    )

    private fun run(makeTracker: (IFgoAutomataApi, IBattleConfig) -> ServantTracker, config: IBattleConfig = config()): List<String> {
        val api = TraceApi(prefs(), locations())
        val tracker = makeTracker(api, config)
        val caster = Caster(api, BattleState(), tracker)
        fun step(name: String, block: () -> Unit) {
            api.trace += "== $name"
            block()
        }
        step("servants enter") { tracker.beginTurn() }
        step("skill with a choice") { caster.castServantSkill(Skill.Servant.A1, ServantTarget.Left) }
        step("skill with three choices") { caster.castServantSkill(Skill.Servant.C2, ServantTarget.SpecialTarget.Choice3OptionB) }
        step("transformation") { caster.castServantSkill(Skill.Servant.B3, ServantTarget.Transform) }
        step("transformation again") { caster.castServantSkill(Skill.Servant.B3, ServantTarget.Transform) }
        step("next turn") { tracker.beginTurn() }
        step("order change") { caster.orderChange(AutoSkillAction.OrderChange(OrderChangeMember.Starting.A, OrderChangeMember.Sub.A)) }
        step("turn after order change") { tracker.beginTurn() }
        step("next run") { tracker.nextRun(); tracker.beginTurn() }
        return api.trace
    }

    /** Servant 3 is a support; the next run brings a different support into the same slot. */
    private fun runSupportChange(makeTracker: (IFgoAutomataApi, IBattleConfig) -> ServantTracker): List<String> {
        val api = TraceApi(prefs(), locations())
        val tracker = makeTracker(api, config())
        val battle = api.locations.battle
        fun step(name: String, block: () -> Unit) {
            api.trace += "== $name"
            block()
        }
        api.supportRegions = setOf(battle.servantChangeSupportCheckRegion(FieldSlot.C))
        step("servants and support enter") { tracker.beginTurn() }
        step("next turn") { tracker.beginTurn() }
        api.changedRegions = setOf(battle.servantChangeCheckRegion(FieldSlot.C))
        step("next run with a different support") { tracker.nextRun(); tracker.beginTurn() }
        api.changedRegions = emptySet()
        step("turn after the support change") { tracker.beginTurn() }
        return api.trace
    }

    private fun assertMatchesFixture(actual: List<String>, path: String = fixturePath) {
        val file = File(path)
        if (System.getenv("FGA_RECORD_CHAIN654") == "1") {
            file.parentFile.mkdirs()
            file.writeText(actual.joinToString("\n") + "\n")
        }
        val expected = file.readLines().filter { it.isNotEmpty() }
        actual.zip(expected).forEachIndexed { index, (a, e) ->
            assertThat(a, "line ${index + 1}").isEqualTo(e)
        }
        assertThat(actual.size).isEqualTo(expected.size)
    }

    @Test
    fun `taps reads and waits of a config that never touched chain priority match the recorded trace`() {
        assertMatchesFixture(run({ api, config -> ServantTracker(api, config) }))
    }

    @Test
    fun `a support change with chain priority off matches the recorded trace`() {
        assertMatchesFixture(runSupportChange { api, config -> ServantTracker(api, config) }, SUPPORT_FIXTURE)
    }

    @Test
    fun `a support change with chain priority shown but off matches the recorded trace`() {
        assertMatchesFixture(
            runSupportChange { api, config -> ServantTracker(api, config, mockk<OcrService>(), chainShown = true) },
            SUPPORT_FIXTURE
        )
    }

    // Recorded from main, which reads a stored Always as None: no face card check, so no dialog.
    @Test
    fun `a stored Always while the feature is hidden matches the recorded trace`() {
        assertMatchesFixture(
            run(
                { api, config -> ServantTracker(api, config, mockk<OcrService>(), chainShown = false) },
                config(braveChains = listOf(BraveChainEnum.Always))
            ),
            ALWAYS_FIXTURE
        )
    }

    @Test
    fun `a stored chain priority on changes nothing while the feature is hidden`() {
        assertMatchesFixture(
            run({ api, config -> ServantTracker(api, config, mockk<OcrService>(), chainShown = false) }, config(listOf(true)))
        )
    }

    @Test
    fun `shown chain priority that is off in every entry changes nothing`() {
        assertMatchesFixture(
            run({ api, config -> ServantTracker(api, config, mockk<OcrService>(), chainShown = true) }, config(listOf(false, false)))
        )
    }

    @Test
    fun `shown chain priority that is on reads the NP type in the same frame as the face`() {
        val trace = run({ api, config -> ServantTracker(api, config, mockk<OcrService>(relaxed = true), chainShown = true) }, config(listOf(false, true)))

        val face = trace.indexOf("capture Region(x=595, y=410, width=110, height=60) 'Face [1]'")
        assertThat(trace.subList(face - 1, face + 4)).isEqualTo(
            listOf(
                "snap{",
                "capture Region(x=595, y=410, width=110, height=60) 'Face [1]'",
                "capture Region(x=545, y=760, width=100, height=50) 'NP type [1]'",
                "log NpCardTypeReading",
                "}"
            )
        )
        // The skill with a choice opens the dialog again; the plain skills do not.
        val choice = trace.indexOf("== skill with a choice")
        val nextStep = trace.indexOf("== skill with three choices")
        assertThat(trace.subList(choice, nextStep).count { it == "click Location(x=324, y=810) x1" }).isEqualTo(1)
    }

    private companion object {
        const val SUPPORT_FIXTURE = "src/test/resources/chain654/off-state-trace-support.txt"
        const val ALWAYS_FIXTURE = "src/test/resources/chain654/off-state-trace-always.txt"
    }
}

// The settings that did not exist when the trace was recorded, at their defaults: the waits they replace were fixed then.
private fun stubAddedPrefs(prefs: IPreferences) {
    every { prefs.servantDetailsWait } returns 250.milliseconds
    every { prefs.waitAfterOrderChange } returns 1000.milliseconds
    every { prefs.npCardTypeSimilarity } returns 0.5
    every { prefs.npCardDetectionSimilarity } returns 0.8
}
