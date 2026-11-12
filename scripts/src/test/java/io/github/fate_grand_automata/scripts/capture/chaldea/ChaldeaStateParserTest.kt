package io.github.fate_grand_automata.scripts.capture.chaldea

import kotlin.test.*

/** Minimal dumps rebuilt from the five tablet situations (REF-0023 11.3.1). Package and ids are synthetic. */
class ChaldeaStateParserTest {
    private val pkg = "cc.narumi.chaldea"
    private val fdroid = "cc.narumi.chaldea.fdroid"

    private fun activity(
        top: String,
        chaldeaVisible: String?,
        mode: String,
        who: String = pkg,
        extra: String = "",
    ) = buildString {
        appendLine("ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)")
        appendLine("  topResumedActivity=ActivityRecord{1a2b u0 $top/.Main t5}")
        appendLine("  ResumedActivity: ActivityRecord{1a2b u0 $top/.Main t5}")
        appendLine("  * Task{aa11 #7 type=standard A=10080:com.other.game U=0 visible=true visibleRequested=true mode=fullscreen sz=1}")
        val v = chaldeaVisible?.let { "visible=$it visibleRequested=$it " } ?: ""
        appendLine("  * Task{bb22 #9 type=standard A=10061:$who U=0 ${v}mode=$mode translucent=false sz=1}")
        appendLine("    mActivityComponent=$who/.MainActivity")
        // Real dumps also reference the task without a visible= field; these must not count as records.
        appendLine("      rootOfTask=true task=Task{bb22 #9 type=standard A=10061:$who}")
        appendLine("        (fullscreen) Task{bb22 #9 type=standard A=10061:$who}")
        if (extra.isNotEmpty()) appendLine(extra)
    }

    private fun window(visible: String?, who: String = pkg) = buildString {
        appendLine("WINDOW MANAGER WINDOWS (dumpsys window windows)")
        appendLine("  Window #1 Window{c1 u0 com.other.game/com.other.game.Main}:")
        appendLine("    isOnScreen=true isVisible=true")
        appendLine("  Window #2 Window{c2 u0 $who/$who.MainActivity}:")
        appendLine("    mHasSurface=true")
        if (visible != null) appendLine("    isOnScreen=$visible isVisible=$visible")
    }

    private fun parse(act: String?, win: String?, exit: Int? = 0, pid: String = "1234\n", p: String = pkg) =
        ChaldeaStateParser.parse(p, exit, pid, act, win)

    @Test fun case1_splitScreenTouchOnOtherAppIsVisible() =
        assertEquals(ChaldeaRunState.Visible, parse(activity("com.other.game", "true", "multi-window"), window("true")))

    @Test fun case2_splitScreenTouchOnChaldeaIsVisible() =
        assertEquals(ChaldeaRunState.Visible, parse(activity(pkg, "true", "multi-window"), window("true")))

    @Test fun case3_freeformWindowIsVisible() =
        assertEquals(ChaldeaRunState.Visible, parse(activity("com.other.game", "true", "freeform"), window("true")))

    @Test fun case4_homeIsBackground() =
        assertEquals(ChaldeaRunState.Background, parse(activity("com.android.launcher", "false", "fullscreen"), window("false")))

    @Test fun case5_screenOffWithChaldeaOnTopIsVisible() =
        assertEquals(ChaldeaRunState.Visible, parse(activity(pkg, "true", "fullscreen"), window("false")))

    @Test fun topResumedAloneIsVisible() =
        assertEquals(ChaldeaRunState.Visible, parse(activity(pkg, "false", "fullscreen"), window("false")))

    @Test fun windowVisibleAloneIsVisible() =
        assertEquals(ChaldeaRunState.Visible, parse(activity("x.y", "false", "fullscreen"), window("true")))

    @Test fun noPidIsNotRunning() {
        assertEquals(ChaldeaRunState.NotRunning, parse(null, null, exit = 1, pid = ""))
        assertEquals(ChaldeaRunState.NotRunning, parse(null, null, exit = 0, pid = "\n"))
    }

    @Test fun otherPackagesVisibleTaskDoesNotCount() {
        // Only the other game is visible; Chaldea's task and window say false.
        val a = activity("com.other.game", "false", "fullscreen")
        assertEquals(ChaldeaRunState.Background, parse(a, window("false")))
    }

    @Test fun fdroidPackageIsMatchedExactly() {
        val a = activity("com.other.game", "true", "multi-window", who = fdroid)
        val w = window("true", who = fdroid)
        assertEquals(ChaldeaRunState.Visible, parse(a, w, p = fdroid))
        // The Play package name must not match the fdroid lines, so only absence is seen: Unknown (pid but no task).
        assertEquals(ChaldeaRunState.Unknown, parse(a, w, p = pkg))
    }

    @Test fun fdroidBackground() {
        val a = activity("com.android.launcher", "false", "fullscreen", who = fdroid)
        assertEquals(ChaldeaRunState.Background, parse(a, window("false", who = fdroid), p = fdroid))
    }

    @Test fun commandFailureIsUnknown() {
        assertEquals(ChaldeaRunState.Unknown, parse(null, null, exit = null))
        assertEquals(ChaldeaRunState.Unknown, parse(null, null, exit = 2, pid = ""))
        assertEquals(ChaldeaRunState.Unknown, parse(null, window("false")))
        assertEquals(ChaldeaRunState.Unknown, parse(activity("x.y", "false", "fullscreen"), null))
    }

    @Test fun emptyOrUnparseableOutputIsUnknown() {
        assertEquals(ChaldeaRunState.Unknown, parse("", ""))
        assertEquals(ChaldeaRunState.Unknown, parse("garbage\nmore garbage", "nonsense"))
    }

    @Test fun pidWithoutChaldeaTaskLineIsUnknown() {
        val a = "ACTIVITY MANAGER\n  * Task{aa11 #7 type=standard A=10080:com.other.game U=0 visible=true mode=fullscreen}\n"
        assertEquals(ChaldeaRunState.Unknown, parse(a, window("false")))
    }

    @Test fun taskLineWithoutVisibleFieldIsUnknown() {
        assertEquals(ChaldeaRunState.Unknown, parse(activity("x.y", null, "fullscreen"), window("false")))
    }

    @Test fun windowWithoutIsVisibleIsUnknown() {
        assertEquals(ChaldeaRunState.Unknown, parse(activity("x.y", "false", "fullscreen"), window(null)))
    }

    @Test fun pidofNonZeroWithPidIsUnknown() {
        assertEquals(ChaldeaRunState.Unknown, parse(activity("x.y", "false", "fullscreen"), window("false"), exit = 3))
    }

    // ---- real MuMu dump (Android 15), trimmed; indentation as in the dump ----

    private val mumuActivities = """
        |ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
        |Display #0 (activities from top to bottom):
        |  * Task{92bade7 #80 type=standard A=10062:io.github.fate_grand_automata.beta U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
        |    topResumedActivity=ActivityRecord{21319590 u0 io.github.fate_grand_automata.beta/io.github.fate_grand_automata.ui.main.MainActivity t80}
        |      rootOfTask=true task=Task{92bade7 #80 type=standard A=10062:io.github.fate_grand_automata.beta}
        |
        |  * Task{38f8115 #84 type=standard A=10060:com.netmarble.fgok nonResizable U=0 visible=false visibleRequested=false mode=fullscreen translucent=true sz=1}
        |      rootOfTask=true task=Task{38f8115 #84 type=standard A=10060:com.netmarble.fgok nonResizable}
        |
        |  * Task{d6486dc #83 type=standard I=cc.narumi.chaldea/.MainActivity U=0 visible=false visibleRequested=false mode=fullscreen translucent=true sz=1}
        |    mLastPausedActivity: ActivityRecord{166252367 u0 cc.narumi.chaldea/.MainActivity t83}
        |    * Hist  #0: ActivityRecord{166252367 u0 cc.narumi.chaldea/.MainActivity t83}
        |      packageName=cc.narumi.chaldea processName=cc.narumi.chaldea
        |      rootOfTask=true task=Task{d6486dc #83 type=standard I=cc.narumi.chaldea/.MainActivity}
        |      mActivityComponent=cc.narumi.chaldea/.MainActivity
        |
        |  * Task{e426fa3 #1 type=home U=0 visible=false visibleRequested=false mode=fullscreen translucent=true sz=1}
        |    * Task{18d10e8 #2 type=home I=app.lawnchair/.LawnchairLauncher U=0 rootTaskId=1 visible=false visibleRequested=false mode=fullscreen translucent=true sz=1}
        |        rootOfTask=true task=Task{18d10e8 #2 type=home I=app.lawnchair/.LawnchairLauncher}
        |  Resumed activities in task display areas (from top to bottom):
        |    Resumed: ActivityRecord{21319590 u0 io.github.fate_grand_automata.beta/io.github.fate_grand_automata.ui.main.MainActivity t80}
        |
        |  ResumedActivity: ActivityRecord{21319590 u0 io.github.fate_grand_automata.beta/io.github.fate_grand_automata.ui.main.MainActivity t80}
        |
        |ActivityTaskSupervisor state:
        |  topDisplayFocusedRootTask=Task{92bade7 #80 type=standard A=10062:io.github.fate_grand_automata.beta}
        |      * Task{92bade7 #80 type=standard A=10062:io.github.fate_grand_automata.beta U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
        |      * Task{d6486dc #83 type=standard I=cc.narumi.chaldea/.MainActivity U=0 visible=false visibleRequested=false mode=fullscreen translucent=true sz=1}
        |        (fullscreen) Task{92bade7 #80 type=standard A=10062:io.github.fate_grand_automata.beta}
        |        (fullscreen) Task{d6486dc #83 type=standard I=cc.narumi.chaldea/.MainActivity}
        |        (fullscreen) Task{38f8115 #84 type=standard A=10060:com.netmarble.fgok nonResizable}
    """.trimMargin() + "\n"

    private val mumuWindows = """
        |WINDOW MANAGER WINDOWS (dumpsys window windows)
        |  Window #6 Window{923c3ec u0 io.github.fate_grand_automata.beta/io.github.fate_grand_automata.ui.main.MainActivity}:
        |    mDisplayId=0 taskId=80
        |    isVisible=true
        |  Window #7 Window{a9e73a8 u0 com.netmarble.fgok/com.netmarble.fgok.lgoUnityActivity}:
        |    mDisplayId=0 taskId=84
        |    isVisible=false
        |  Window #8 Window{9ea6f9c u0 cc.narumi.chaldea/cc.narumi.chaldea.MainActivity}:
        |    mDisplayId=0 taskId=83 mSession=Session{409fc70 18110:u0a10061}
        |    isOnScreen=false
        |    isVisible=false
        |  Window #9 Window{d5e9673 u0 app.lawnchair/app.lawnchair.LawnchairLauncher}:
        |    mDisplayId=0 taskId=2
        |    isVisible=false
    """.trimMargin() + "\n"

    private val mumuPid = "18110\n"
    private val chaldeaTaskHeader = "I=cc.narumi.chaldea/.MainActivity U=0 visible=false visibleRequested=false"

    @Test fun mumuBackgroundWithReferenceLinesIsBackground() =
        assertEquals(ChaldeaRunState.Background, parse(mumuActivities, mumuWindows, pid = mumuPid))

    @Test fun mumuChaldeaTopResumedIsVisible() {
        val a = mumuActivities.replace(
            "topResumedActivity=ActivityRecord{21319590 u0 io.github.fate_grand_automata.beta/io.github.fate_grand_automata.ui.main.MainActivity t80}",
            "topResumedActivity=ActivityRecord{166252367 u0 cc.narumi.chaldea/.MainActivity t83}",
        )
        assertEquals(ChaldeaRunState.Visible, parse(a, mumuWindows, pid = mumuPid))
    }

    @Test fun mumuChaldeaTaskVisibleTrueIsVisible() {
        val a = mumuActivities.replace(chaldeaTaskHeader, "I=cc.narumi.chaldea/.MainActivity U=0 visible=true visibleRequested=true")
        assertEquals(ChaldeaRunState.Visible, parse(a, mumuWindows, pid = mumuPid))
    }

    @Test fun mumuTaskRecordWithoutVisibleIsUnknown() {
        val a = mumuActivities.replace(chaldeaTaskHeader, "I=cc.narumi.chaldea/.MainActivity U=0")
        assertEquals(ChaldeaRunState.Unknown, parse(a, mumuWindows, pid = mumuPid))
    }

    @Test fun onlyReferenceLinesAndNoRecordIsUnknown() {
        val a = mumuActivities.lines()
            .filterNot { it.contains("d6486dc") && it.trimStart().startsWith("* Task{") }
            .joinToString("\n")
        assertEquals(ChaldeaRunState.Unknown, parse(a, mumuWindows, pid = mumuPid))
    }

    @Test fun nestedAndSplitScreenRecordsCount() {
        val a = """
            |ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
            |  * Task{ca05488 #3 type=undefined U=0 visible=true visibleRequested=true mode=fullscreen translucent=true sz=2}
            |    * Task{8d32ca0 #5 type=standard I=cc.narumi.chaldea/.MainActivity U=0 rootTaskId=3 visible=true visibleRequested=true mode=multi-window sz=1}
            |        rootOfTask=true task=Task{8d32ca0 #5 type=standard I=cc.narumi.chaldea/.MainActivity}
            |        (multi-window) Task{8d32ca0 #5 type=standard I=cc.narumi.chaldea/.MainActivity}
        """.trimMargin() + "\n"
        assertEquals(ChaldeaRunState.Visible, parse(a, mumuWindows, pid = mumuPid))
        // Same nested record, not visible: Background.
        assertEquals(ChaldeaRunState.Background, parse(a.replace("visible=true visibleRequested=true mode=multi-window", "visible=false visibleRequested=false mode=multi-window").replace("ca05488 #3 type=undefined U=0 visible=true visibleRequested=true", "ca05488 #3 type=undefined U=0 visible=false visibleRequested=false"), mumuWindows, pid = mumuPid))
    }

    // ---- poll after forceStop ----

    @Test fun pollReturnsAsSoonAsNotRunning() {
        var now = 0L
        val states = ArrayDeque(listOf(ChaldeaRunState.Background, ChaldeaRunState.Background, ChaldeaRunState.NotRunning))
        val r = ChaldeaStateParser.awaitNotRunning({ states.removeFirst() }, { now }, { now += it })
        assertEquals(ChaldeaRunState.NotRunning, r)
        assertEquals(500L, now)
    }

    @Test fun pollGivesUpAfterTimeoutAndReturnsLastState() {
        var now = 0L
        var reads = 0
        val r = ChaldeaStateParser.awaitNotRunning({ reads++; ChaldeaRunState.Background }, { now }, { now += it })
        assertEquals(ChaldeaRunState.Background, r)
        assertTrue(now in 3000L..3250L)
        assertTrue(reads > 1)
    }

    @Test fun pollKeepsUnknownAsNotSafe() {
        var now = 0L
        val r = ChaldeaStateParser.awaitNotRunning({ ChaldeaRunState.Unknown }, { now }, { now += it }, timeoutMs = 1000)
        assertEquals(ChaldeaRunState.Unknown, r)
    }

    // ---- Shizuku state ----

    private fun facts(
        installed: Boolean = true, alive: Boolean = true, outdated: Boolean = false,
        granted: Boolean = false, denied: Boolean = false,
    ) = ShizukuFacts(installed, alive, outdated, granted, denied).state()

    @Test fun shizukuStateMapping() {
        assertEquals(ShizukuState.NotInstalled, facts(installed = false, alive = false))
        assertEquals(ShizukuState.NotRunning, facts(installed = true, alive = false))
        assertEquals(ShizukuState.NotRunning, facts(alive = false, granted = true))
        assertEquals(ShizukuState.Outdated, facts(outdated = true, granted = true))
        assertEquals(ShizukuState.Ready, facts(granted = true))
        assertEquals(ShizukuState.Denied, facts(denied = true))
        assertEquals(ShizukuState.NeedsPermission, facts())
        // A binder without the app (installed=false but alive) is still usable.
        assertEquals(ShizukuState.NeedsPermission, facts(installed = false))
    }

    // ---- package choice ----

    @Test fun packageChoiceTakesNewestFileOrPlayDefault() {
        assertEquals(ChaldeaPackages.PLAY, ChaldeaPackages.choose(mapOf(ChaldeaPackages.PLAY to 5L)))
        assertEquals(ChaldeaPackages.FDROID, ChaldeaPackages.choose(mapOf(ChaldeaPackages.FDROID to 5L)))
        assertEquals(ChaldeaPackages.FDROID, ChaldeaPackages.choose(mapOf(ChaldeaPackages.PLAY to 5L, ChaldeaPackages.FDROID to 9L)))
        assertEquals(ChaldeaPackages.PLAY, ChaldeaPackages.choose(mapOf(ChaldeaPackages.PLAY to 9L, ChaldeaPackages.FDROID to 5L)))
        assertEquals(ChaldeaPackages.PLAY, ChaldeaPackages.choose(emptyMap()))
    }
}
