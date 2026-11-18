package io.github.fate_grand_automata.capture

import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val FGA_UID = 10999

class RootRedirectScriptsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val markerRegex = Regex("FGA_[A-Z_]+(=[0-9]+)?")

    @Test
    fun applyBuildsExactUidOnlyRulesForIpv4AndIpv6() {
        val script = RootRedirectScripts.apply(
            fgaUid = FGA_UID,
            fgoUids = listOf(10123, 10456),
            port = 8899,
            ipv6 = true,
        )
        assertTrue(script.contains("IPT=/system/bin/iptables"))
        assertTrue(script.contains("IP6T=/system/bin/ip6tables"))
        assertTrue(
            script.contains(
                "\"\$IPT\" -w 3 -t nat -A FGA_CAP_10999 -p tcp --dport 443 -m owner --uid-owner 10123 -j REDIRECT --to-ports 8899"
            )
        )
        assertTrue(
            script.contains(
                "\"\$IPT\" -w 3 -t nat -A FGA_CAP_10999 -p tcp --dport 443 -m owner --uid-owner 10456 -j REDIRECT --to-ports 8899"
            )
        )
        assertTrue(
            script.contains(
                "\"\$IP6T\" -w 3 -t nat -A FGA_CAP_10999 -p tcp --dport 443 -m owner --uid-owner 10123 -j REDIRECT --to-ports 8899"
            )
        )
        assertTrue(
            script.contains(
                "\"\$IP6T\" -w 3 -t nat -A FGA_CAP_10999 -p tcp --dport 443 -m owner --uid-owner 10456 -j REDIRECT --to-ports 8899"
            )
        )
        assertTrue(script.contains("\"\$IPT\" -w 3 -t nat -C OUTPUT -j FGA_CAP_10999 >/dev/null 2>&1 || \"\$IPT\" -w 3 -t nat -I OUTPUT 1 -j FGA_CAP_10999"))
        assertTrue(script.contains("\"\$IP6T\" -w 3 -t nat -C OUTPUT -j FGA_CAP_10999 >/dev/null 2>&1 || \"\$IP6T\" -w 3 -t nat -I OUTPUT 1 -j FGA_CAP_10999"))
        val redirectLines = script.lines().filter { it.contains("-j REDIRECT") }
        assertEquals(4, redirectLines.size)
        assertTrue(redirectLines.none { it.contains(" -d ") })
        assertTrue(script.contains("echo FGA_APPLY_OK"))
    }

    @Test
    fun applyAddsLoopbackReturnRulesBeforeRedirectsInBothChains() {
        val script = RootRedirectScripts.apply(
            fgaUid = FGA_UID,
            fgoUids = listOf(10123, 10456),
            port = 8899,
            ipv6 = true,
        )
        val lines = script.lines()

        val v4FlushIdx = lines.indexOf("\"\$IPT\" -w 3 -t nat -F FGA_CAP_10999 >/dev/null 2>&1 || { echo FGA_FAIL_STEP=1; exit 1; }")
        val v4Return1 = "\"\$IPT\" -w 3 -t nat -A FGA_CAP_10999 -d 127.0.0.0/8 -j RETURN >/dev/null 2>&1 || { echo FGA_FAIL_STEP=2; exit 1; }"
        val v4Return2 = "\"\$IPT\" -w 3 -t nat -A FGA_CAP_10999 -d 0.0.0.0/32 -j RETURN >/dev/null 2>&1 || { echo FGA_FAIL_STEP=2; exit 1; }"
        val v4RedirectIdx = lines.indexOfFirst { it.startsWith("\"\$IPT\"") && it.contains("-j REDIRECT") }
        assertTrue(v4FlushIdx >= 0)
        assertTrue(lines.indexOf(v4Return1) in (v4FlushIdx + 1) until v4RedirectIdx)
        assertTrue(lines.indexOf(v4Return2) in (v4FlushIdx + 1) until v4RedirectIdx)

        val v6FlushIdx = lines.indexOf("\"\$IP6T\" -w 3 -t nat -F FGA_CAP_10999 >/dev/null 2>&1 || { echo FGA_FAIL_STEP=4; exit 1; }")
        val v6Return1 = "\"\$IP6T\" -w 3 -t nat -A FGA_CAP_10999 -d ::1/128 -j RETURN >/dev/null 2>&1 || { echo FGA_FAIL_STEP=5; exit 1; }"
        val v6Return2 = "\"\$IP6T\" -w 3 -t nat -A FGA_CAP_10999 -d ::/128 -j RETURN >/dev/null 2>&1 || { echo FGA_FAIL_STEP=5; exit 1; }"
        val v6RedirectIdx = lines.indexOfFirst { it.startsWith("\"\$IP6T\"") && it.contains("-j REDIRECT") }
        assertTrue(v6FlushIdx >= 0)
        assertTrue(lines.indexOf(v6Return1) in (v6FlushIdx + 1) until v6RedirectIdx)
        assertTrue(lines.indexOf(v6Return2) in (v6FlushIdx + 1) until v6RedirectIdx)

        val redirectLines = lines.filter { it.contains("-j REDIRECT") }
        assertEquals(
            listOf(
                "\"\$IPT\" -w 3 -t nat -A FGA_CAP_10999 -p tcp --dport 443 -m owner --uid-owner 10123 -j REDIRECT --to-ports 8899 >/dev/null 2>&1 || { echo FGA_FAIL_STEP=2; exit 1; }",
                "\"\$IPT\" -w 3 -t nat -A FGA_CAP_10999 -p tcp --dport 443 -m owner --uid-owner 10456 -j REDIRECT --to-ports 8899 >/dev/null 2>&1 || { echo FGA_FAIL_STEP=2; exit 1; }",
                "\"\$IP6T\" -w 3 -t nat -A FGA_CAP_10999 -p tcp --dport 443 -m owner --uid-owner 10123 -j REDIRECT --to-ports 8899 >/dev/null 2>&1 || { echo FGA_FAIL_STEP=5; exit 1; }",
                "\"\$IP6T\" -w 3 -t nat -A FGA_CAP_10999 -p tcp --dport 443 -m owner --uid-owner 10456 -j REDIRECT --to-ports 8899 >/dev/null 2>&1 || { echo FGA_FAIL_STEP=5; exit 1; }",
            ),
            redirectLines,
        )
        assertTrue(redirectLines.none { it.contains("127.0.0.0/8") || it.contains("0.0.0.0/32") || it.contains("::1/128") || it.contains("::/128") })
    }

    @Test
    fun applyIpv4OnlyAddsNoIpv6RulesAndRemovesOldIpv6Chain() {
        val script = RootRedirectScripts.apply(
            fgaUid = FGA_UID,
            fgoUids = listOf(10123),
            port = 8899,
            ipv6 = false,
        )
        assertTrue(script.contains("IPT=/system/bin/iptables"))
        // H6: no IPv6 rule is added, but an IPv6 jump and chain left by an earlier apply are removed.
        assertFalse(script.contains("\"\$IP6T\" -w 3 -t nat -A FGA_CAP_10999"))
        assertFalse(script.contains("\"\$IP6T\" -w 3 -t nat -N FGA_CAP_10999"))
        assertFalse(script.contains("::1/128"))
        assertFalse(script.contains("::/128"))
        assertTrue(script.contains("\"\$IPT\" -w 3 -t nat -A FGA_CAP_10999 -d 127.0.0.0/8 -j RETURN >/dev/null 2>&1 || { echo FGA_FAIL_STEP=2; exit 1; }"))
        assertTrue(script.contains("\"\$IPT\" -w 3 -t nat -A FGA_CAP_10999 -d 0.0.0.0/32 -j RETURN >/dev/null 2>&1 || { echo FGA_FAIL_STEP=2; exit 1; }"))
        assertTrue(script.contains("\"\$IP6T\" -w 3 -t nat -D OUTPUT -j FGA_CAP_10999"))
        assertTrue(script.contains("\"\$IP6T\" -w 3 -t nat -F FGA_CAP_10999 >/dev/null 2>&1 || true"))
        assertTrue(script.contains("\"\$IP6T\" -w 3 -t nat -X FGA_CAP_10999 >/dev/null 2>&1 || true"))
        assertTrue(
            script.contains(
                "\"\$IPT\" -w 3 -t nat -A FGA_CAP_10999 -p tcp --dport 443 -m owner --uid-owner 10123 -j REDIRECT --to-ports 8899"
            )
        )
        assertTrue(script.contains("echo FGA_APPLY_OK"))
    }

    @Test
    fun applyRejectsInvalidPortOrUids() {
        assertFailsWith<IllegalArgumentException> {
            RootRedirectScripts.apply(FGA_UID, emptyList(), 8899, ipv6 = true)
        }
        assertFailsWith<IllegalArgumentException> {
            RootRedirectScripts.apply(FGA_UID, listOf(0), 8899, ipv6 = true)
        }
        assertFailsWith<IllegalArgumentException> {
            RootRedirectScripts.apply(FGA_UID, listOf(-5), 8899, ipv6 = true)
        }
        assertFailsWith<IllegalArgumentException> {
            RootRedirectScripts.apply(FGA_UID, listOf(10123), 80, ipv6 = true)
        }
        assertFailsWith<IllegalArgumentException> {
            RootRedirectScripts.apply(FGA_UID, listOf(10123), 70000, ipv6 = true)
        }
    }

    @Test
    fun cleanupRemovesBothIpv4AndIpv6ChainsAndKillsWatchdogOnlyAfterChainCheck() {
        val script = RootRedirectScripts.cleanup(FGA_UID, killWatchdog = true)
        assertTrue(script.contains("\"\$IPT\" -w 3 -t nat -D OUTPUT -j FGA_CAP_10999"))
        assertTrue(script.contains("\"\$IPT\" -w 3 -t nat -F FGA_CAP_10999"))
        assertTrue(script.contains("\"\$IPT\" -w 3 -t nat -X FGA_CAP_10999"))
        assertTrue(script.contains("\"\$IP6T\" -w 3 -t nat -D OUTPUT -j FGA_CAP_10999"))
        assertTrue(script.contains("\"\$IP6T\" -w 3 -t nat -F FGA_CAP_10999"))
        assertTrue(script.contains("\"\$IP6T\" -w 3 -t nat -X FGA_CAP_10999"))
        assertTrue(script.contains("[ \"\$WPID\" -gt 1 ]"))
        assertTrue(script.contains("/proc/\$WPID/cmdline"))
        assertTrue(script.contains("fga_cap_watchdog"))
        assertTrue(script.contains("echo FGA_CLEANUP_OK"))

        val chainCheckIdx = script.indexOf("if \"\$IPT\" -w 3 -t nat -S FGA_CAP_10999")
        val failStepIdx = script.indexOf("echo FGA_FAIL_STEP=1")
        val failExitIdx = script.indexOf("exit 1", startIndex = failStepIdx)
        val killLoopIdx = script.indexOf("for PF in ${RootRedirectScripts.watchdogPidPrimary(FGA_UID)}")
        val killCmdIdx = script.indexOf("kill -9 \"\$WPID\"")
        val cleanupOkIdx = script.indexOf("echo FGA_CLEANUP_OK")

        assertTrue(chainCheckIdx >= 0, "Chain check must be present")
        assertTrue(failStepIdx > chainCheckIdx, "Fail marker must be inside chain check")
        assertTrue(failExitIdx > failStepIdx, "Failure branch must exit")
        assertTrue(killLoopIdx > failExitIdx, "Watchdog kill loop must appear after failure exit")
        assertTrue(killCmdIdx > killLoopIdx, "kill -9 must appear inside watchdog kill loop after chain check")
        assertTrue(cleanupOkIdx > killCmdIdx, "echo FGA_CLEANUP_OK must appear after watchdog kill")

        val beforeFailExit = script.substring(0, failExitIdx)
        assertFalse(beforeFailExit.contains("kill -9"), "Failure branch must exit before any kill command")
        assertFalse(beforeFailExit.contains("fga_cap_watchdog"), "Failure branch must exit before touching watchdog")
    }

    @Test
    fun watchdogContainsHexPortFgaUidRootCgroupAndIpv6Tcp6Check() {
        assertEquals("22C3", RootRedirectScripts.portToProcHex(8899))
        val watchdog = RootRedirectScripts.watchdogScript(port = 8899, fgaUid = 10999, ipv6 = true)
        assertTrue(watchdog.contains("/sys/fs/cgroup/cgroup.procs /dev/cg2_bpf/cgroup.procs /acct/cgroup.procs"))
        assertTrue(watchdog.contains("0100007F:22C3"))
        assertTrue(watchdog.contains("0000000000000000FFFF00000100007F:22C3"))
        assertTrue(watchdog.contains("00000000000000000000000001000000:22C3"))
        assertTrue(watchdog.contains("0A"))
        assertTrue(watchdog.contains("10999"))
        assertTrue(watchdog.contains("/proc/net/tcp"))
        assertTrue(watchdog.contains("/proc/net/tcp6"))

        val ipv4OnlyWatchdog = RootRedirectScripts.watchdogScript(port = 8899, fgaUid = 10999, ipv6 = false)
        assertTrue(ipv4OnlyWatchdog.contains("0100007F:22C3"))
        assertTrue(ipv4OnlyWatchdog.contains("0000000000000000FFFF00000100007F:22C3"))
        assertTrue(ipv4OnlyWatchdog.contains("/proc/net/tcp6"))
        assertFalse(ipv4OnlyWatchdog.contains("00000000000000000000000001000000:22C3"))
    }

    @Test
    fun startWatchdogUsesDetachedSetsidNohupAndChecksProbeMarker() {
        val launcher = RootRedirectScripts.startWatchdog(port = 8899, fgaUid = 10999, ipv6 = true)
        assertTrue(launcher.contains(RootRedirectScripts.watchdogScriptPrimary(FGA_UID)))
        assertTrue(launcher.contains(RootRedirectScripts.watchdogScriptFallback(FGA_UID)))
        assertTrue(launcher.contains("setsid nohup sh \"\$WFILE\" >/dev/null 2>&1 </dev/null &"))
        assertTrue(launcher.contains("echo FGA_WATCHDOG_OK"))
        assertTrue(launcher.contains("echo FGA_FAIL_WATCHDOG_PROBE"))
    }

    @Test
    fun applyAndStartWatchdogRunsApplyFirstThenTheWatchdogInOneScript() {
        val script = RootRedirectScripts.applyAndStartWatchdog(listOf(10123), 8899, 10999, ipv6 = true)
        val applyOk = script.indexOf("echo FGA_APPLY_OK")
        val launch = script.indexOf("setsid nohup sh")
        val watchdogOk = script.indexOf("echo FGA_WATCHDOG_OK")
        assertTrue(script.indexOf("-A FGA_CAP_10999") in 0 until applyOk)
        assertTrue(applyOk in 0 until launch)
        assertTrue(launch in 0 until watchdogOk)
        assertTrue(script.contains("echo FGA_FAIL_STEP=1; exit 1"))
        assertTrue(script.contains("echo FGA_FAIL_WATCHDOG_PROBE"))
        assertEquals(
            RootRedirectScripts.apply(FGA_UID, listOf(10123), 8899, true) + RootRedirectScripts.startWatchdog(8899, 10999, true),
            script,
        )
    }

    @Test
    fun precheckRunsReadOnlyNatAndOwnerChecksWithoutModifyingRules() {
        val mutatingFlags = listOf("-A", "-I", "-D", "-N", "-F", "-X")
        val scripts = listOf(
            "IPT" to RootRedirectScripts.precheckIpv4(),
            "IP6T" to RootRedirectScripts.precheckIpv6(),
        )
        for ((toolVar, script) in scripts) {
            val natCheckIdx = script.indexOf("\"\$$toolVar\" -w 3 -t nat -S OUTPUT")
            val ownerCheckIdx = script.indexOf(
                "\"\$$toolVar\" -w 3 -t nat -C OUTPUT -p tcp --dport 443 -m owner --uid-owner 0 -j RETURN"
            )
            assertTrue(natCheckIdx >= 0, "nat OUTPUT check missing for $toolVar")
            assertTrue(ownerCheckIdx > natCheckIdx, "owner -C check must follow nat check for $toolVar")
            assertTrue(script.contains("echo ${RootRedirectScripts.MARKER_FAIL_STEP_PREFIX}1"))
            assertTrue(script.contains("echo ${RootRedirectScripts.MARKER_FAIL_OWNER}"))
            assertTrue(script.contains("echo ${RootRedirectScripts.MARKER_APPLY_OK}"))
            assertTrue(script.contains("[ \"\$rc\" -eq 0 ] || [ \"\$rc\" -eq 1 ]"))
            val ownerMarkerIdx = script.indexOf("echo ${RootRedirectScripts.MARKER_FAIL_OWNER}")
            val rc2Idx = script.indexOf("elif [ \"\$rc\" -eq 2 ]; then")
            assertTrue(rc2Idx in 0 until ownerMarkerIdx, "only exit code 2 may report the owner marker for $toolVar")
            assertTrue(
                script.indexOf("echo ${RootRedirectScripts.MARKER_FAIL_STEP_PREFIX}1", ownerMarkerIdx) > ownerMarkerIdx,
                "other owner-check exit codes must fall back to the generic failure for $toolVar",
            )
            for (flag in mutatingFlags) {
                assertFalse(script.contains(flag), "Precheck for $toolVar must not contain $flag")
            }
        }
    }

    @Test
    fun allProtocolMarkersMatchProcessRootShellFilter() {
        val markers = listOf(
            RootRedirectScripts.MARKER_APPLY_OK,
            RootRedirectScripts.MARKER_CLEANUP_OK,
            RootRedirectScripts.MARKER_WATCHDOG_OK,
            RootRedirectScripts.MARKER_FAIL_WATCHDOG_PROBE,
            RootRedirectScripts.MARKER_FAIL_OWNER,
            "${RootRedirectScripts.MARKER_FAIL_STEP_PREFIX}1",
            "${RootRedirectScripts.MARKER_FAIL_STEP_PREFIX}6",
        )
        for (m in markers) {
            assertTrue(markerRegex.matches(m), "Marker $m did not match ProcessRootShell filter")
        }
    }

    @Test
    fun generatedScriptsPassRealShSyntaxCheckWithoutExecuting() {
        val scripts = mapOf(
            "precheckIpv4" to RootRedirectScripts.precheckIpv4(),
            "precheckIpv6" to RootRedirectScripts.precheckIpv6(),
            "apply_v4" to RootRedirectScripts.apply(FGA_UID, listOf(10123), 8899, ipv6 = false),
            "apply_v4_v6" to RootRedirectScripts.apply(FGA_UID, listOf(10123, 10456), 8899, ipv6 = true),
            "checkChains_v4" to RootRedirectScripts.checkChains(FGA_UID, 8899, ipv6 = false),
            "checkChains_v6" to RootRedirectScripts.checkChains(FGA_UID, 8899, ipv6 = true),
            "cleanup_with_kill" to RootRedirectScripts.cleanup(FGA_UID, killWatchdog = true),
            "cleanup_no_kill" to RootRedirectScripts.cleanup(FGA_UID, killWatchdog = false),
            "killWatchdog" to RootRedirectScripts.killWatchdog(FGA_UID),
            "guard_v4" to RootRedirectScripts.guardOtherApp(FGA_UID, ipv6 = false),
            "guard_v6" to RootRedirectScripts.guardOtherApp(FGA_UID, ipv6 = true),
            "watchdog_v4" to RootRedirectScripts.watchdogScript(8899, 10999, ipv6 = false),
            "watchdog_v6" to RootRedirectScripts.watchdogScript(8899, 10999, ipv6 = true),
            "startWatchdog" to RootRedirectScripts.startWatchdog(8899, 10999, ipv6 = true),
            "applyAndStartWatchdog" to RootRedirectScripts.applyAndStartWatchdog(listOf(10123), 8899, 10999, ipv6 = true),
        )

        for ((name, script) in scripts) {
            val process = try {
                ProcessBuilder("sh", "-n")
                    .redirectErrorStream(true)
                    .start()
            } catch (_: IOException) {
                assumeTrue("sh binary not available on this host; skipping sh -n syntax test", false)
                return
            }
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(script)
            }
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            process.destroyForcibly()
            assertTrue(finished, "sh -n timed out for $name")
            assertEquals(0, process.exitValue(), "sh -n syntax error in $name: $output")
        }
    }

    @Test
    fun watchdogCheckSocketMatchesTcpAndTcp6FixturesWithSh() {
        val tcpHeader = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n"
        val tcp6Header = "  sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n"

        fun tcpRow(
            localAddr: String = "0100007F",
            portHex: String = "22C3",
            state: String = "0A",
            uid: Int = 10062,
        ) = "   0: $localAddr:$portHex 00000000:0000 $state 00000000:00000000 00:00000000 00000000 $uid        0 12345 1 0000000000000000 100 0 0 10 0\n"

        fun tcp6Row(
            slot: Int = 0,
            localAddr: String = "0000000000000000FFFF00000100007F",
            portHex: String = "22C3",
            state: String = "0A",
            uid: Int = 10062,
        ) = "   $slot: $localAddr:$portHex 00000000000000000000000000000000:0000 $state 00000000:00000000 00:00000000 00000000 $uid        0 23456 1 0000000000000000 100 0 0 10 0\n"

        val ipv4MappedAddr = "0000000000000000FFFF00000100007F"
        val ipv6LoopbackAddr = "00000000000000000000000001000000"

        val tcpFile = tempFolder.newFile("proc_net_tcp")
        val tcp6File = tempFolder.newFile("proc_net_tcp6")

        fun runCheck(ipv6: Boolean, tcpContent: String, tcp6Content: String): Int {
            tcpFile.writeText(tcpContent, Charsets.UTF_8)
            tcp6File.writeText(tcp6Content, Charsets.UTF_8)
            val snippet = extractCheckSocketSnippet(
                RootRedirectScripts.watchdogScript(port = 8899, fgaUid = 10062, ipv6 = ipv6),
            )
            val process = try {
                ProcessBuilder("sh").apply {
                    environment()["PROC_NET_TCP"] = tcpFile.absolutePath
                    environment()["PROC_NET_TCP6"] = tcp6File.absolutePath
                    redirectErrorStream(true)
                }.start()
            } catch (_: IOException) {
                assumeTrue("sh binary not available on this host; skipping check_socket execution test", false)
                return -1
            }
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(snippet)
            }
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            process.destroyForcibly()
            assertTrue(finished, "sh check_socket timed out: $output")
            return process.exitValue()
        }

        // IPv4-mapped row only in /proc/net/tcp6 -> pass
        assertEquals(
            0,
            runCheck(
                ipv6 = false,
                tcpContent = tcpHeader,
                tcp6Content = tcp6Header + tcp6Row(localAddr = ipv4MappedAddr),
            ),
            "IPv4-mapped listener in tcp6 should pass",
        )

        // Plain IPv4 row in /proc/net/tcp -> pass
        assertEquals(
            0,
            runCheck(
                ipv6 = false,
                tcpContent = tcpHeader + tcpRow(),
                tcp6Content = tcp6Header,
            ),
            "Plain IPv4 listener in tcp should pass",
        )

        // Wrong UID (both distinct and prefix-matching UID) -> fail
        assertEquals(
            1,
            runCheck(
                ipv6 = false,
                tcpContent = tcpHeader + tcpRow(uid = 10063),
                tcp6Content = tcp6Header + tcp6Row(localAddr = ipv4MappedAddr, uid = 100620),
            ),
            "Wrong UID should fail",
        )

        // Wrong port -> fail
        assertEquals(
            1,
            runCheck(
                ipv6 = false,
                tcpContent = tcpHeader + tcpRow(portHex = "22C4"),
                tcp6Content = tcp6Header + tcp6Row(localAddr = ipv4MappedAddr, portHex = "22C4"),
            ),
            "Wrong port should fail",
        )

        // State 01 (ESTABLISHED) instead of 0A (LISTEN) -> fail
        assertEquals(
            1,
            runCheck(
                ipv6 = false,
                tcpContent = tcpHeader + tcpRow(state = "01"),
                tcp6Content = tcp6Header + tcp6Row(localAddr = ipv4MappedAddr, state = "01"),
            ),
            "Non-LISTEN state 01 should fail",
        )

        // ipv6 = true with ::1 row missing -> fail
        assertEquals(
            1,
            runCheck(
                ipv6 = true,
                tcpContent = tcpHeader,
                tcp6Content = tcp6Header + tcp6Row(localAddr = ipv4MappedAddr),
            ),
            "ipv6=true without ::1 listener should fail",
        )

        // ipv6 = true with IPv4-mapped and ::1 rows in tcp6 -> pass
        assertEquals(
            0,
            runCheck(
                ipv6 = true,
                tcpContent = tcpHeader,
                tcp6Content = tcp6Header +
                    tcp6Row(slot = 0, localAddr = ipv4MappedAddr) +
                    tcp6Row(slot = 1, localAddr = ipv6LoopbackAddr),
            ),
            "ipv6=true with IPv4-mapped and ::1 listeners in tcp6 should pass",
        )

        // ipv6 = true with plain IPv4 in tcp and ::1 in tcp6 -> pass
        assertEquals(
            0,
            runCheck(
                ipv6 = true,
                tcpContent = tcpHeader + tcpRow(),
                tcp6Content = tcp6Header + tcp6Row(localAddr = ipv6LoopbackAddr),
            ),
            "ipv6=true with plain IPv4 in tcp and ::1 in tcp6 should pass",
        )

        // ipv6 = true with only ::1 in tcp6 (no IPv4 or IPv4-mapped listener) -> fail
        assertEquals(
            1,
            runCheck(
                ipv6 = true,
                tcpContent = tcpHeader,
                tcp6Content = tcp6Header + tcp6Row(localAddr = ipv6LoopbackAddr),
            ),
            "ipv6=true with only ::1 listener should fail",
        )
    }

    private fun extractCheckSocketSnippet(watchdogScript: String): String {
        val lines = watchdogScript.lines()
        val start = lines.indexOfFirst { it.startsWith("PROC_NET_TCP=") }
        assertTrue(start >= 0, "PROC_NET_TCP assignment not found")
        val fnStart = lines.indexOfFirst { it == "check_socket() {" }
        assertTrue(fnStart > start, "check_socket() definition not found")
        val fnEnd = (fnStart + 1 until lines.size).firstOrNull { lines[it] == "}" }
            ?: error("Closing brace for check_socket() not found")
        return lines.subList(start, fnEnd + 1).joinToString("\n") + "\ncheck_socket\n"
    }

    @Test
    fun namesCarryTheUidAndTwoUidsNeverShareAChainOrFile() {
        assertEquals("FGA_CAP_1025", RootRedirectScripts.chainName(1025))
        assertEquals("/data/local/tmp/fga_cap_watchdog_1025.sh", RootRedirectScripts.watchdogScriptPrimary(1025))
        assertEquals("/dev/fga_cap_watchdog_1025.sh", RootRedirectScripts.watchdogScriptFallback(1025))
        assertEquals("/data/local/tmp/fga_cap_watchdog_1025.pid", RootRedirectScripts.watchdogPidPrimary(1025))
        assertEquals("/dev/fga_cap_watchdog_1025.pid", RootRedirectScripts.watchdogPidFallback(1025))
        assertEquals("/data/local/tmp/fga_cap_watchdog_1025.ok", RootRedirectScripts.watchdogOkPrimary(1025))
        assertEquals("/dev/fga_cap_watchdog_1025.ok", RootRedirectScripts.watchdogOkFallback(1025))

        val a = allNames(1025)
        val b = allNames(10256)
        assertTrue(a.intersect(b.toSet()).isEmpty(), "uids 1025 and 10256 share a name: ${a.intersect(b.toSet())}")
        assertTrue(RootRedirectScripts.chainName(2147483647).length <= 28)
    }

    private fun allNames(uid: Int) = listOf(
        RootRedirectScripts.chainName(uid),
        RootRedirectScripts.watchdogScriptPrimary(uid),
        RootRedirectScripts.watchdogScriptFallback(uid),
        RootRedirectScripts.watchdogPidPrimary(uid),
        RootRedirectScripts.watchdogPidFallback(uid),
        RootRedirectScripts.watchdogOkPrimary(uid),
        RootRedirectScripts.watchdogOkFallback(uid),
    )

    @Test
    fun everyBuilderRejectsAnInvalidFgaUid() {
        for (uid in listOf(0, -1)) {
            assertFailsWith<IllegalArgumentException> { RootRedirectScripts.chainName(uid) }
            assertFailsWith<IllegalArgumentException> { RootRedirectScripts.apply(uid, listOf(10123), 8899, true) }
            assertFailsWith<IllegalArgumentException> { RootRedirectScripts.checkChains(uid, 8899, true) }
            assertFailsWith<IllegalArgumentException> { RootRedirectScripts.cleanup(uid) }
            assertFailsWith<IllegalArgumentException> { RootRedirectScripts.killWatchdog(uid) }
            assertFailsWith<IllegalArgumentException> { RootRedirectScripts.watchdogScript(8899, uid, true) }
            assertFailsWith<IllegalArgumentException> { RootRedirectScripts.startWatchdog(8899, uid, true) }
            assertFailsWith<IllegalArgumentException> { RootRedirectScripts.guardOtherApp(uid, true) }
        }
    }

    @Test
    fun ownScriptsMentionOnlyThisAppsNames() {
        val scripts = listOf(
            RootRedirectScripts.apply(FGA_UID, listOf(10123), 8899, true),
            RootRedirectScripts.apply(FGA_UID, listOf(10123), 8899, false),
            RootRedirectScripts.checkChains(FGA_UID, 8899, true),
            RootRedirectScripts.cleanup(FGA_UID, killWatchdog = true),
            RootRedirectScripts.killWatchdog(FGA_UID),
            RootRedirectScripts.watchdogScript(8899, FGA_UID, true),
            RootRedirectScripts.startWatchdog(8899, FGA_UID, true),
        )
        val bareChain = Regex("FGA_CAP(?!_$FGA_UID)")
        val bareWatchdog = Regex("fga_cap_watchdog(?!_$FGA_UID\\\\?\\.)")
        for (script in scripts) {
            assertFalse(bareChain.containsMatchIn(script), "bare chain name left in:\n$script")
            assertFalse(bareWatchdog.containsMatchIn(script), "bare watchdog name left in:\n$script")
        }
        val kill = RootRedirectScripts.killWatchdog(FGA_UID)
        assertTrue(kill.contains("grep -q \"fga_cap_watchdog_$FGA_UID\\.sh\""))
        assertTrue(kill.contains("rm -f /data/local/tmp/fga_cap_watchdog_$FGA_UID.ok /dev/fga_cap_watchdog_$FGA_UID.ok"))
    }

    @Test
    fun killWatchdogOfUid1025DoesNotMatchTheWatchdogOf10256() {
        val dir = tempFolder.newFolder("kill")
        val proc = File(dir, "proc/777").apply { mkdirs() }
        File(proc, "cmdline").writeBytes("sh\u0000/data/local/tmp/fga_cap_watchdog_10256.sh\u0000".toByteArray())
        val pidFile = File(dir, "own.pid").apply { writeText("777\n") }
        // kill -9 is replaced by a marker so that nothing real is signalled.
        val script = RootRedirectScripts.killWatchdog(1025)
            .replace("/data/local/tmp/fga_cap_watchdog_1025.pid", pidFile.posix())
            .replace("/dev/fga_cap_watchdog_1025.pid", File(dir, "none.pid").posix())
            .replace("/proc/", dir.posix() + "/proc/")
            .replace("kill -9 \"\$WPID\" >/dev/null 2>&1", "echo KILLED")
        val (rc, out) = runSh(script, emptyMap()) ?: return
        assertEquals(0, rc, out)
        assertFalse(out.contains("KILLED"), "watchdog of 10256 matched uid 1025: $out")

        File(proc, "cmdline").writeBytes("sh\u0000/data/local/tmp/fga_cap_watchdog_1025.sh\u0000".toByteArray())
        pidFile.writeText("777\n")
        val (rc2, out2) = runSh(script, emptyMap()) ?: return
        assertEquals(0, rc2, out2)
        assertTrue(out2.contains("KILLED"), out2)
    }

    @Test
    fun periodicChecksRequireTheRedirectToThePortInBothScripts() {
        val check = RootRedirectScripts.checkChains(FGA_UID, 8899, ipv6 = true)
        assertTrue(check.contains("\"\$IPT\" -w 3 -t nat -S FGA_CAP_$FGA_UID 2>/dev/null | grep -Eq -- \"--to-ports 8899( |\\\$)\""))
        assertTrue(check.contains("\"\$IP6T\" -w 3 -t nat -S FGA_CAP_$FGA_UID 2>/dev/null | grep -Eq -- \"--to-ports 8899( |\\\$)\""))
        assertFalse(RootRedirectScripts.checkChains(FGA_UID, 8899, ipv6 = false).contains("IP6T"))
        val watchdog = RootRedirectScripts.watchdogScript(8899, FGA_UID, ipv6 = true)
        assertEquals(2, watchdog.lines().count { it.contains("-S FGA_CAP_$FGA_UID 2>/dev/null | grep -Eq -- \"--to-ports 8899") })
        assertFailsWith<IllegalArgumentException> { RootRedirectScripts.checkChains(FGA_UID, 80, true) }
    }

    @Test
    fun checkChainsFailsWhenTheChainHasNoRedirectToThePort() {
        val fake = FakeNat()
        val own = "FGA_CAP_$FGA_UID"
        val redirect = "-A $own -p tcp --dport 443 -m owner --uid-owner 10123 -j REDIRECT --to-ports"

        fun run(port: Int, ipv6: Boolean): Pair<Int, String>? =
            runSh(RootRedirectScripts.checkChains(FGA_UID, port, ipv6), fake.env())

        fake.v4 = listOf("-N $own", "-A OUTPUT -j $own", "$redirect 8899")
        fake.v6 = listOf("-N $own", "-A OUTPUT -j $own", "$redirect 8899")
        val ok = run(8899, true) ?: return
        assertEquals(0, ok.first, ok.second)
        assertTrue(ok.second.contains("FGA_APPLY_OK"))

        // Chain and jump exist, but the REDIRECT is for another port or gone.
        fake.v4 = listOf("-N $own", "-A OUTPUT -j $own", "$redirect 9001")
        val otherPort = run(8899, false) ?: return
        assertEquals(1, otherPort.first, otherPort.second)
        assertTrue(otherPort.second.contains("FGA_FAIL_STEP=5"))

        fake.v4 = listOf("-N $own", "-A OUTPUT -j $own", "$redirect 88990")
        val prefixPort = run(8899, false) ?: return
        assertEquals(1, prefixPort.first, "port 8899 must not match 88990")

        fake.v4 = listOf("-N $own", "-A OUTPUT -j $own")
        val empty = run(8899, false) ?: return
        assertEquals(1, empty.first, empty.second)

        fake.v4 = listOf("-N $own", "-A OUTPUT -j $own", "$redirect 8899")
        fake.v6 = listOf("-N $own", "-A OUTPUT -j $own")
        val v6Empty = run(8899, true) ?: return
        assertEquals(1, v6Empty.first, v6Empty.second)
        assertTrue(v6Empty.second.contains("FGA_FAIL_STEP=6"))
    }

    @Test
    fun watchdogCheckChainsFailsWhenTheChainHasNoRedirectToThePort() {
        val fake = FakeNat()
        val own = "FGA_CAP_$FGA_UID"
        val lines = RootRedirectScripts.watchdogScript(8899, FGA_UID, ipv6 = false).lines()
        val start = lines.indexOfFirst { it == "IPT=/system/bin/iptables" }
        val fnStart = lines.indexOfFirst { it == "check_chains() {" }
        val fnEnd = (fnStart + 1 until lines.size).first { lines[it] == "}" }
        val snippet = lines.subList(start, fnEnd + 1).joinToString("\n") + "\ncheck_chains\n"

        fake.v4 = listOf("-N $own", "-A OUTPUT -j $own", "-A $own -j REDIRECT --to-ports 8899")
        val ok = runSh(snippet, fake.env()) ?: return
        assertEquals(0, ok.first, ok.second)

        fake.v4 = listOf("-N $own", "-A OUTPUT -j $own", "-A $own -j REDIRECT --to-ports 9001")
        val bad = runSh(snippet, fake.env()) ?: return
        assertEquals(1, bad.first, "a chain without the REDIRECT to the port must fail the watchdog check")
    }

    @Test
    fun guardReportsAnotherAppWhenAForeignChainHasALiveWatchdog() {
        val fake = FakeNat()
        fake.v4 = listOf("-N FGA_CAP_10256", "-A OUTPUT -j FGA_CAP_10256")
        fake.liveWatchdog("fga_cap_watchdog_10256", pid = 4242)
        val res = fake.runGuard(1025, ipv6 = false) ?: return
        assertEquals(1, res.first, res.second)
        assertTrue(res.second.contains(RootRedirectScripts.MARKER_FAIL_OTHER_APP))
        assertEquals(listOf("-N FGA_CAP_10256", "-A OUTPUT -j FGA_CAP_10256"), fake.v4, "the other app must be left alone")
        assertTrue(fake.pidFile("fga_cap_watchdog_10256").exists(), "the live watchdog's PID file must stay")
    }

    @Test
    fun guardReportsAnotherAppForTheLegacyChainWithALiveLegacyWatchdog() {
        val fake = FakeNat()
        fake.v4 = listOf("-N FGA_CAP", "-A OUTPUT -j FGA_CAP")
        fake.liveWatchdog("fga_cap_watchdog", pid = 4243)
        val res = fake.runGuard(1025, ipv6 = false) ?: return
        assertEquals(1, res.first, res.second)
        assertTrue(res.second.contains(RootRedirectScripts.MARKER_FAIL_OTHER_APP))
        assertEquals(listOf("-N FGA_CAP", "-A OUTPUT -j FGA_CAP"), fake.v4)
    }

    @Test
    fun guardSeesAnIpv6OnlyForeignChainOnlyWhenIpv6WillBeUsed() {
        val fake = FakeNat()
        fake.v6 = listOf("-N FGA_CAP_10256", "-A OUTPUT -j FGA_CAP_10256")
        fake.liveWatchdog("fga_cap_watchdog_10256", pid = 4242, fallbackDir = true)
        val v6 = fake.runGuard(1025, ipv6 = true) ?: return
        assertEquals(1, v6.first, v6.second)
        assertTrue(v6.second.contains(RootRedirectScripts.MARKER_FAIL_OTHER_APP))
        val v4Only = fake.runGuard(1025, ipv6 = false) ?: return
        assertEquals(0, v4Only.first, v4Only.second)
    }

    @Test
    fun guardRemovesAForeignChainWithoutALiveWatchdogAndSucceeds() {
        val cases = listOf(
            "FGA_CAP_10256" to "fga_cap_watchdog_10256",
            "FGA_CAP" to "fga_cap_watchdog",
        )
        for ((chain, base) in cases) {
            for (deadPid in listOf(false, true)) {
                val fake = FakeNat()
                val state = listOf("-N $chain", "-A OUTPUT -j $chain", "-A $chain -j REDIRECT --to-ports 8899")
                fake.v4 = state
                fake.v6 = state
                // No PID file, or a PID file whose process is gone.
                if (deadPid) fake.pidFile(base).writeText("31337\n")
                val res = fake.runGuard(1025, ipv6 = true) ?: return
                assertEquals(0, res.first, "$chain: ${res.second}")
                assertTrue(res.second.contains(RootRedirectScripts.MARKER_GUARD_OK))
                assertEquals(emptyList(), fake.v4, "$chain must be removed from IPv4")
                assertEquals(emptyList(), fake.v6, "$chain must be removed from IPv6")
                assertEquals(deadPid, fake.pidFile(base).exists(), "the guard never deletes another app's files")
            }
        }
    }

    @Test
    fun guardDoesNotTakeAWatchdogOfAnotherUidThatSharesADigitPrefix() {
        val fake = FakeNat()
        fake.v4 = listOf("-N FGA_CAP_10256", "-A OUTPUT -j FGA_CAP_10256")
        // The PID file of chain 10256 points at a live process that runs the watchdog of uid 1025 only.
        fake.pidFile("fga_cap_watchdog_10256").writeText("4242\n")
        fake.process(4242, "/data/local/tmp/fga_cap_watchdog_1025.sh")
        val res = fake.runGuard(1025, ipv6 = false) ?: return
        assertEquals(0, res.first, res.second)
        assertEquals(emptyList(), fake.v4)

        // The other way round: the chain of uid 1025 is foreign, and only the watchdog of 10256 is running.
        val reverse = FakeNat()
        reverse.v4 = listOf("-N FGA_CAP_1025", "-A OUTPUT -j FGA_CAP_1025")
        reverse.pidFile("fga_cap_watchdog_1025").writeText("4242\n")
        reverse.process(4242, "/data/local/tmp/fga_cap_watchdog_10256.sh")
        val res2 = reverse.runGuard(10256, ipv6 = false) ?: return
        assertEquals(0, res2.first, res2.second)
        assertEquals(emptyList(), reverse.v4)
    }

    @Test
    fun guardNeverTreatsTheOwnChainAsForeign() {
        val fake = FakeNat()
        val own = listOf("-N FGA_CAP_1025", "-A OUTPUT -j FGA_CAP_1025")
        fake.v4 = own
        fake.v6 = own
        fake.liveWatchdog("fga_cap_watchdog_1025", pid = 4242)
        val res = fake.runGuard(1025, ipv6 = true) ?: return
        assertEquals(0, res.first, res.second)
        assertEquals(own, fake.v4)
        assertEquals(own, fake.v6)

        // Same with no watchdog: the guard does not clean up this app's own leftovers, apply does.
        fake.pidFile("fga_cap_watchdog_1025").delete()
        val res2 = fake.runGuard(1025, ipv6 = true) ?: return
        assertEquals(0, res2.first, res2.second)
        assertEquals(own, fake.v4)
    }

    @Test
    fun guardIsReadOnlyUntilItFindsAStaleChainAndItsMarkerMatchesTheShellFilter() {
        assertTrue(markerRegex.matches(RootRedirectScripts.MARKER_FAIL_OTHER_APP))
        assertTrue(markerRegex.matches(RootRedirectScripts.MARKER_GUARD_OK))
        val fake = FakeNat()
        fake.v4 = listOf("-A OUTPUT -j SOME_OTHER_CHAIN", "-A OUTPUT -j FGA_CAPX")
        val res = fake.runGuard(1025, ipv6 = true) ?: return
        assertEquals(0, res.first, res.second)
        assertEquals(listOf("-A OUTPUT -j SOME_OTHER_CHAIN", "-A OUTPUT -j FGA_CAPX"), fake.v4)
    }

    private fun File.posix(): String = absolutePath.replace('\\', '/')

    private fun runSh(script: String, env: Map<String, String>): Pair<Int, String>? {
        val process = try {
            ProcessBuilder("sh").apply {
                environment().putAll(env)
                redirectErrorStream(true)
            }.start()
        } catch (_: IOException) {
            assumeTrue("sh binary not available on this host; skipping script execution test", false)
            return null
        }
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(script) }
        val finished = process.waitFor(10, TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        process.destroyForcibly()
        assertTrue(finished, "sh timed out: $output")
        return process.exitValue() to output
    }

    /** A fake `iptables`/`ip6tables` over two state files, plus the PID files and a fake `/proc` of the guard. */
    private inner class FakeNat {
        private val root = tempFolder.newFolder()
        private val bin = File(root, "bin").apply { mkdirs() }
        private val dir = File(root, "files").apply { mkdirs() }
        private val v4File = File(dir, "v4").apply { writeText("") }
        private val v6File = File(dir, "v6").apply { writeText("") }

        init {
            val fake = """
                #!/bin/sh
                case "${'$'}{0##*/}" in ip6tables) STATE="${'$'}FAKE_DIR/v6" ;; *) STATE="${'$'}FAKE_DIR/v4" ;; esac
                [ -f "${'$'}STATE" ] || exit 1
                shift 4
                case "${'$'}1" in
                  -S)
                    if [ "${'$'}2" = OUTPUT ]; then grep '^-A OUTPUT ' "${'$'}STATE"; exit 0; fi
                    grep -qx -- "-N ${'$'}2" "${'$'}STATE" || exit 1
                    grep -- "^-A ${'$'}2 " "${'$'}STATE"
                    exit 0 ;;
                  -C) grep -qx -- "-A OUTPUT -j ${'$'}4" "${'$'}STATE" && exit 0; exit 1 ;;
                  -D)
                    grep -qx -- "-A OUTPUT -j ${'$'}4" "${'$'}STATE" || exit 1
                    grep -vx -- "-A OUTPUT -j ${'$'}4" "${'$'}STATE" > "${'$'}STATE.new"; mv "${'$'}STATE.new" "${'$'}STATE"
                    exit 0 ;;
                  -F)
                    grep -v -- "^-A ${'$'}2 " "${'$'}STATE" > "${'$'}STATE.new"; mv "${'$'}STATE.new" "${'$'}STATE"
                    exit 0 ;;
                  -X)
                    grep -vx -- "-N ${'$'}2" "${'$'}STATE" > "${'$'}STATE.new"; mv "${'$'}STATE.new" "${'$'}STATE"
                    exit 0 ;;
                esac
                exit 1
            """.trimIndent() + "\n"
            for (name in listOf("iptables", "ip6tables")) {
                File(bin, name).apply {
                    writeText(fake)
                    setExecutable(true)
                }
            }
        }

        var v4: List<String>
            get() = v4File.readLines().filter { it.isNotEmpty() }
            set(value) = v4File.writeText(value.joinToString("\n", postfix = if (value.isEmpty()) "" else "\n"))
        var v6: List<String>
            get() = v6File.readLines().filter { it.isNotEmpty() }
            set(value) = v6File.writeText(value.joinToString("\n", postfix = if (value.isEmpty()) "" else "\n"))

        fun env(): Map<String, String> = mapOf(
            "FAKE_DIR" to dir.posix(),
            "PATH" to bin.absolutePath + File.pathSeparator + System.getenv("PATH"),
            "PROC_ROOT" to File(root, "proc").posix(),
        )

        fun pidFile(base: String, fallbackDir: Boolean = false) =
            File(dir, (if (fallbackDir) "dev_" else "") + "$base.pid")

        fun process(pid: Int, scriptPath: String) {
            val p = File(root, "proc/$pid").apply { mkdirs() }
            File(p, "cmdline").writeBytes("sh\u0000$scriptPath\u0000".toByteArray())
        }

        fun liveWatchdog(base: String, pid: Int, fallbackDir: Boolean = false) {
            pidFile(base, fallbackDir).writeText("$pid\n")
            process(pid, "/data/local/tmp/$base.sh")
        }

        fun runGuard(ownUid: Int, ipv6: Boolean): Pair<Int, String>? {
            val script = RootRedirectScripts.guardOtherApp(ownUid, ipv6)
                .replace("/data/local/tmp/", dir.posix() + "/")
                .replace("/dev/\$1", dir.posix() + "/dev_\$1")
            return runSh(script, env())
        }
    }
}
