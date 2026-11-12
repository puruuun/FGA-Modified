package io.github.fate_grand_automata.scripts.capture.chaldea

import kotlin.test.*

class ChaldeaAccessTest {
    @Test fun rootGrantedWinsOverShizukuReady() {
        val a = ChaldeaAccess(RootState.Granted, ShizukuState.Ready)
        assertEquals(ChaldeaAccessMode.Root, a.mode)
        assertTrue(a.ready)
    }

    @Test fun shizukuIsUsedWhenRootIsNotGranted() {
        for (root in listOf(RootState.Unchecked, RootState.Unavailable)) {
            val a = ChaldeaAccess(root, ShizukuState.Ready)
            assertEquals(ChaldeaAccessMode.Shizuku, a.mode, "root $root")
            assertTrue(a.ready)
        }
    }

    @Test fun neitherGivesNoMode() {
        for (root in listOf(RootState.Unchecked, RootState.Unavailable)) {
            for (s in ShizukuState.entries.filter { it != ShizukuState.Ready }) {
                val a = ChaldeaAccess(root, s)
                assertNull(a.mode, "root $root shizuku $s")
                assertFalse(a.ready)
            }
        }
    }

    @Test fun everyCombinationFollowsTheRule() {
        for (root in RootState.entries) for (s in ShizukuState.entries) {
            val expected = when {
                root == RootState.Granted -> ChaldeaAccessMode.Root
                s == ShizukuState.Ready -> ChaldeaAccessMode.Shizuku
                else -> null
            }
            assertEquals(expected, ChaldeaAccess(root, s).mode, "root $root shizuku $s")
        }
    }

    // ---- probe parsing ----

    @Test fun uidZeroWithExitZeroIsGranted() {
        assertEquals(RootState.Granted, RootProbe.parse(0, listOf("uid=0(root) gid=0(root) groups=0(root)"), false))
    }

    @Test fun exitZeroWithoutUidZeroIsUnavailable() {
        assertEquals(RootState.Unavailable, RootProbe.parse(0, listOf("uid=2000(shell) gid=2000(shell)"), false))
    }

    @Test fun nonZeroExitIsUnavailable() {
        assertEquals(RootState.Unavailable, RootProbe.parse(1, listOf("uid=0(root)"), false))
    }

    @Test fun timedOutIsUnavailable() {
        assertEquals(RootState.Unavailable, RootProbe.parse(0, listOf("uid=0(root)"), true))
    }

    @Test fun emptyOutputIsUnavailable() {
        assertEquals(RootState.Unavailable, RootProbe.parse(0, emptyList(), false))
    }

    // ---- probe policy ----

    private class Fx(flag: Boolean = false) {
        var stored = flag
        var flagWrites = 0
        val policy = RootProbePolicy({ stored }, { stored = it; flagWrites++ })
    }

    @Test fun userTurnedOnStartsAnInteractiveProbeEvenWithoutTheFlag() {
        val f = Fx(false)
        assertEquals(ProbeKind.Interactive, f.policy.onUserTurnedOn()?.kind)
    }

    @Test fun settingsOnStartsASilentProbeOnlyWithTheFlag() {
        assertNull(Fx(false).policy.onSettingsOn())
        assertEquals(ProbeKind.Silent, Fx(true).policy.onSettingsOn()?.kind)
    }

    @Test fun syncNowProbesInteractivelyUnlessRootIsGranted() {
        val f = Fx(true)
        f.policy.onUserTurnedOn()?.let { f.policy.onProbeResult(it, RootState.Unavailable) }
        assertEquals(ProbeKind.Interactive, f.policy.onSyncNow()?.kind)
        val g = Fx(false)
        g.policy.onProbeResult(g.policy.onUserTurnedOn()!!, RootState.Granted)
        assertNull(g.policy.onSyncNow(), "already Granted in this process")
    }

    @Test fun grantedProbeSetsTheFlagAndTheState() {
        val f = Fx(false)
        val t = f.policy.onUserTurnedOn()!!
        assertTrue(f.policy.onProbeResult(t, RootState.Granted))
        assertEquals(RootState.Granted, f.policy.state)
        assertTrue(f.stored)
    }

    @Test fun interactiveFailureClearsTheFlag() {
        val f = Fx(true)
        val t = f.policy.onUserTurnedOn()!!
        f.policy.onProbeResult(t, RootState.Unavailable)
        assertFalse(f.stored)
        assertEquals(RootState.Unavailable, f.policy.state)
    }

    @Test fun silentFailureLeavesTheFlagAndSetsUnavailable() {
        val f = Fx(true)
        val t = f.policy.onSettingsOn()!!
        f.policy.onProbeResult(t, RootState.Unavailable)
        assertTrue(f.stored, "a silent failure never clears the flag")
        assertEquals(RootState.Unavailable, f.policy.state)
    }

    @Test fun silentGrantedAlsoSetsTheFlag() {
        val f = Fx(true)
        f.stored = false // cannot happen with onSettingsOn, but a grant must always store it
        val t = ProbeTicket(ProbeKind.Silent, 0)
        f.policy.onUserTurnedOn() // running, epoch 0
        f.policy.onProbeResult(t, RootState.Granted)
        assertTrue(f.stored)
    }

    @Test fun noSilentProbeWhileAScriptRunsAndItStartsWhenTheScriptEnds() {
        val f = Fx(true)
        f.policy.onScriptStart()
        assertNull(f.policy.onSettingsOn(), "deferred")
        assertTrue(f.policy.busy)
        assertEquals(RootState.Unchecked, f.policy.state)
        val t = f.policy.onScriptEnd()
        assertEquals(ProbeKind.Silent, t?.kind)
    }

    @Test fun interactiveWishStaysInteractiveWhenDeferred() {
        val f = Fx(true)
        f.policy.onScriptStart()
        assertNull(f.policy.onUserTurnedOn())
        assertNull(f.policy.onSettingsOn())
        assertEquals(ProbeKind.Interactive, f.policy.onScriptEnd()?.kind)
    }

    @Test fun scriptEndWithoutAWishStartsNothing() {
        val f = Fx(true)
        f.policy.onScriptStart()
        assertNull(f.policy.onScriptEnd())
    }

    @Test fun noSecondConcurrentProbe() {
        val f = Fx(true)
        assertNotNull(f.policy.onUserTurnedOn())
        assertNull(f.policy.onUserTurnedOn())
        assertNull(f.policy.onSettingsOn())
        assertNull(f.policy.onSyncNow())
        assertNull(f.policy.onRound())
    }

    @Test fun switchOffResetsToUncheckedAndDropsAStaleResult() {
        val f = Fx(false)
        val granted = f.policy.onUserTurnedOn()!!
        f.policy.onProbeResult(granted, RootState.Granted)
        val running = f.policy.onSyncNow() // Granted: nothing
        assertNull(running)
        f.policy.onSwitchedOff()
        assertEquals(RootState.Unchecked, f.policy.state)

        val t = f.policy.onUserTurnedOn()!!
        f.policy.onSwitchedOff()
        assertFalse(f.policy.onProbeResult(t, RootState.Granted), "the result of a probe from before the switch-off is ignored")
        assertEquals(RootState.Unchecked, f.policy.state)
        assertNotNull(f.policy.onUserTurnedOn(), "and a new probe may start at once")
    }

    @Test fun aFailedRootBindLeadsToOneSilentReprobeOnTheNextRound() {
        val f = Fx(false)
        f.policy.onProbeResult(f.policy.onUserTurnedOn()!!, RootState.Granted)
        assertNull(f.policy.onRound(), "nothing to do while root works")
        f.policy.onRootBindFailed()
        assertEquals(RootState.Unchecked, f.policy.state)
        val t = f.policy.onRound()
        assertEquals(ProbeKind.Silent, t?.kind)
        f.policy.onProbeResult(t!!, RootState.Unavailable)
        assertNull(f.policy.onRound(), "only one re-probe per failed bind")
        assertEquals(RootState.Unavailable, f.policy.state)
        assertTrue(f.stored, "the flag stays")
    }

    @Test fun reprobeWaitsForAScriptToEnd() {
        val f = Fx(false)
        f.policy.onProbeResult(f.policy.onUserTurnedOn()!!, RootState.Granted)
        f.policy.onRootBindFailed()
        f.policy.onScriptStart()
        assertNull(f.policy.onRound())
        assertEquals(ProbeKind.Silent, f.policy.onScriptEnd()?.kind)
    }

    @Test fun twoBindFailuresInARowAfterGrantedEndInUnavailableWithNoFurtherSilentProbe() {
        val f = Fx(false)
        f.policy.onProbeResult(f.policy.onUserTurnedOn()!!, RootState.Granted)

        f.policy.onRootBindFailed()
        assertEquals(RootState.Unchecked, f.policy.state)
        val firstReprobe = f.policy.onRound()
        assertEquals(ProbeKind.Silent, firstReprobe?.kind)
        f.policy.onProbeResult(firstReprobe!!, RootState.Granted)
        assertEquals(RootState.Granted, f.policy.state)

        f.policy.onRootBindFailed()
        assertEquals(RootState.Unavailable, f.policy.state)
        assertNull(f.policy.onRound(), "no more silent re-probes after 2 failures in a row")
        assertNull(f.policy.onSettingsOn(), "remains Unavailable for this process")
        assertTrue(f.stored, "stored flag stays untouched without an interactive refusal")
    }

    @Test fun aSuccessfulRootBindResetsTheBindFailureCount() {
        val f = Fx(false)
        f.policy.onProbeResult(f.policy.onUserTurnedOn()!!, RootState.Granted)

        // First bind failure -> silent re-probe succeeds -> root session opens -> count resets.
        f.policy.onRootBindFailed()
        f.policy.onProbeResult(f.policy.onRound()!!, RootState.Granted)
        f.policy.onRootBindSucceeded()

        // Next failure is failure #1 again, so it still gets a silent re-probe instead of capping.
        f.policy.onRootBindFailed()
        assertEquals(RootState.Unchecked, f.policy.state)
        val reprobe = f.policy.onRound()
        assertEquals(ProbeKind.Silent, reprobe?.kind)
        f.policy.onProbeResult(reprobe!!, RootState.Granted)

        // Second consecutive failure after that re-probe hits the cap.
        f.policy.onRootBindFailed()
        assertEquals(RootState.Unavailable, f.policy.state)
        assertNull(f.policy.onRound())
    }

    @Test fun anInteractiveGrantedProbeReenablesRootAfterTheBindFailureCap() {
        val f = Fx(false)
        f.policy.onProbeResult(f.policy.onUserTurnedOn()!!, RootState.Granted)
        f.policy.onRootBindFailed()
        f.policy.onProbeResult(f.policy.onRound()!!, RootState.Granted)
        f.policy.onRootBindFailed()
        assertEquals(RootState.Unavailable, f.policy.state)
        assertNull(f.policy.onRound())

        val syncNow = f.policy.onSyncNow()
        assertEquals(ProbeKind.Interactive, syncNow?.kind)
        assertTrue(f.policy.onProbeResult(syncNow!!, RootState.Granted))
        assertEquals(RootState.Granted, f.policy.state)

        // Failure count was reset by the interactive grant, so one failure re-probes again.
        f.policy.onRootBindFailed()
        assertEquals(RootState.Unchecked, f.policy.state)
        assertEquals(ProbeKind.Silent, f.policy.onRound()?.kind)
    }

    @Test fun switchOffAndOnResetsTheBindFailureCap() {
        val f = Fx(false)
        f.policy.onProbeResult(f.policy.onUserTurnedOn()!!, RootState.Granted)
        f.policy.onRootBindFailed()
        f.policy.onProbeResult(f.policy.onRound()!!, RootState.Granted)
        f.policy.onRootBindFailed()
        assertEquals(RootState.Unavailable, f.policy.state)

        f.policy.onSwitchedOff()
        assertEquals(RootState.Unchecked, f.policy.state)
        val onAgain = f.policy.onSettingsOn()
        assertEquals(ProbeKind.Silent, onAgain?.kind)
        f.policy.onProbeResult(onAgain!!, RootState.Granted)

        f.policy.onRootBindFailed()
        assertEquals(RootState.Unchecked, f.policy.state)
        assertEquals(ProbeKind.Silent, f.policy.onRound()?.kind)
    }

    @Test fun shizukuPermissionIsNotRequestedWhenRootIsGranted() {
        val f = Fx(false)
        val requester = ShizukuPermissionRequester().also { it.arm() }
        f.policy.onProbeResult(f.policy.onUserTurnedOn()!!, RootState.Granted)
        assertFalse(f.policy.shouldRequestShizukuPermission(requester, ShizukuState.NeedsPermission))
    }

    @Test fun shizukuPermissionWaitsForARunningProbeThenBehavesAsToday() {
        val f = Fx(false)
        val requester = ShizukuPermissionRequester().also { it.arm() }
        val t = f.policy.onUserTurnedOn()!!
        assertFalse(f.policy.shouldRequestShizukuPermission(requester, ShizukuState.NeedsPermission), "the probe may make it unnecessary")
        f.policy.onProbeResult(t, RootState.Unavailable)
        assertTrue(f.policy.shouldRequestShizukuPermission(requester, ShizukuState.NeedsPermission))
        assertFalse(f.policy.shouldRequestShizukuPermission(requester, ShizukuState.NeedsPermission), "no second dialog while one is open")
    }

    // ---- ownership guard ----

    private val owner = ChaldeaFileOwner(10123, 10123, 0x81b0)

    @Test fun equalOwnershipIsNotAChange() {
        assertEquals(emptyList(), ChaldeaOwnership.changed(mapOf("a" to owner), mapOf("a" to owner)))
    }

    @Test fun uidGidOrModeDifferenceIsAChange() {
        assertEquals(listOf("a"), ChaldeaOwnership.changed(mapOf("a" to owner), mapOf("a" to owner.copy(uid = 0))))
        assertEquals(listOf("a"), ChaldeaOwnership.changed(mapOf("a" to owner), mapOf("a" to owner.copy(gid = 0))))
        assertEquals(listOf("a"), ChaldeaOwnership.changed(mapOf("a" to owner), mapOf("a" to owner.copy(mode = 0x8180))))
    }

    @Test fun aFileMissingAfterIsAChangeAndMissingBeforeIsIgnored() {
        assertEquals(listOf("a"), ChaldeaOwnership.changed(mapOf("a" to owner), emptyMap()))
        assertEquals(listOf("a"), ChaldeaOwnership.changed(mapOf("a" to owner), mapOf("a" to null)))
        assertEquals(emptyList(), ChaldeaOwnership.changed(emptyMap(), mapOf("b" to owner.copy(uid = 0))))
    }

    @Test fun onlyTheChangedFilesAreNamed() {
        val changed = ChaldeaOwnership.changed(
            mapOf("a" to owner, "b" to owner),
            mapOf("a" to owner, "b" to owner.copy(uid = 0)),
        )
        assertEquals(listOf("b"), changed)
    }
}
