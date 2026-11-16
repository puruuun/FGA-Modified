package io.github.fate_grand_automata.capture

import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootRedirectScriptsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val markerRegex = Regex("FGA_[A-Z_]+(=[0-9]+)?")

    @Test
    fun applyBuildsExactUidOnlyRulesForIpv4AndIpv6() {
        val script = RootRedirectScripts.apply(
            fgoUids = listOf(10123, 10456),
            port = 8899,
            ipv6 = true,
        )
        assertTrue(script.contains("IPT=/system/bin/iptables"))
        assertTrue(script.contains("IP6T=/system/bin/ip6tables"))
        assertTrue(
            script.contains(
                "\"\$IPT\" -w 3 -t nat -A FGA_CAP -p tcp --dport 443 -m owner --uid-owner 10123 -j REDIRECT --to-ports 8899"
            )
        )
        assertTrue(
            script.contains(
                "\"\$IPT\" -w 3 -t nat -A FGA_CAP -p tcp --dport 443 -m owner --uid-owner 10456 -j REDIRECT --to-ports 8899"
            )
        )
        assertTrue(
            script.contains(
                "\"\$IP6T\" -w 3 -t nat -A FGA_CAP -p tcp --dport 443 -m owner --uid-owner 10123 -j REDIRECT --to-ports 8899"
            )
        )
        assertTrue(
            script.contains(
                "\"\$IP6T\" -w 3 -t nat -A FGA_CAP -p tcp --dport 443 -m owner --uid-owner 10456 -j REDIRECT --to-ports 8899"
            )
        )
        assertTrue(script.contains("\"\$IPT\" -w 3 -t nat -C OUTPUT -j FGA_CAP >/dev/null 2>&1 || \"\$IPT\" -w 3 -t nat -I OUTPUT 1 -j FGA_CAP"))
        assertTrue(script.contains("\"\$IP6T\" -w 3 -t nat -C OUTPUT -j FGA_CAP >/dev/null 2>&1 || \"\$IP6T\" -w 3 -t nat -I OUTPUT 1 -j FGA_CAP"))
        assertFalse(script.contains(" -d "))
        assertTrue(script.contains("echo FGA_APPLY_OK"))
    }

    @Test
    fun applyIpv4OnlyAddsNoIpv6RulesAndRemovesOldIpv6Chain() {
        val script = RootRedirectScripts.apply(
            fgoUids = listOf(10123),
            port = 8899,
            ipv6 = false,
        )
        assertTrue(script.contains("IPT=/system/bin/iptables"))
        // H6: no IPv6 rule is added, but an IPv6 jump and chain left by an earlier apply are removed.
        assertFalse(script.contains("\"\$IP6T\" -w 3 -t nat -A FGA_CAP"))
        assertFalse(script.contains("\"\$IP6T\" -w 3 -t nat -N FGA_CAP"))
        assertTrue(script.contains("\"\$IP6T\" -w 3 -t nat -D OUTPUT -j FGA_CAP"))
        assertTrue(script.contains("\"\$IP6T\" -w 3 -t nat -F FGA_CAP >/dev/null 2>&1 || true"))
        assertTrue(script.contains("\"\$IP6T\" -w 3 -t nat -X FGA_CAP >/dev/null 2>&1 || true"))
        assertTrue(
            script.contains(
                "\"\$IPT\" -w 3 -t nat -A FGA_CAP -p tcp --dport 443 -m owner --uid-owner 10123 -j REDIRECT --to-ports 8899"
            )
        )
        assertTrue(script.contains("echo FGA_APPLY_OK"))
    }

    @Test
    fun applyRejectsInvalidPortOrUids() {
        assertFailsWith<IllegalArgumentException> {
            RootRedirectScripts.apply(emptyList(), 8899, ipv6 = true)
        }
        assertFailsWith<IllegalArgumentException> {
            RootRedirectScripts.apply(listOf(0), 8899, ipv6 = true)
        }
        assertFailsWith<IllegalArgumentException> {
            RootRedirectScripts.apply(listOf(-5), 8899, ipv6 = true)
        }
        assertFailsWith<IllegalArgumentException> {
            RootRedirectScripts.apply(listOf(10123), 80, ipv6 = true)
        }
        assertFailsWith<IllegalArgumentException> {
            RootRedirectScripts.apply(listOf(10123), 70000, ipv6 = true)
        }
    }

    @Test
    fun cleanupRemovesBothIpv4AndIpv6ChainsAndKillsWatchdogOnlyAfterChainCheck() {
        val script = RootRedirectScripts.cleanup(killWatchdog = true)
        assertTrue(script.contains("\"\$IPT\" -w 3 -t nat -D OUTPUT -j FGA_CAP"))
        assertTrue(script.contains("\"\$IPT\" -w 3 -t nat -F FGA_CAP"))
        assertTrue(script.contains("\"\$IPT\" -w 3 -t nat -X FGA_CAP"))
        assertTrue(script.contains("\"\$IP6T\" -w 3 -t nat -D OUTPUT -j FGA_CAP"))
        assertTrue(script.contains("\"\$IP6T\" -w 3 -t nat -F FGA_CAP"))
        assertTrue(script.contains("\"\$IP6T\" -w 3 -t nat -X FGA_CAP"))
        assertTrue(script.contains("[ \"\$WPID\" -gt 1 ]"))
        assertTrue(script.contains("/proc/\$WPID/cmdline"))
        assertTrue(script.contains("fga_cap_watchdog"))
        assertTrue(script.contains("echo FGA_CLEANUP_OK"))

        val chainCheckIdx = script.indexOf("if \"\$IPT\" -w 3 -t nat -S FGA_CAP")
        val failStepIdx = script.indexOf("echo FGA_FAIL_STEP=1")
        val failExitIdx = script.indexOf("exit 1", startIndex = failStepIdx)
        val killLoopIdx = script.indexOf("for PF in ${RootRedirectScripts.WATCHDOG_PID_PRIMARY}")
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
        assertTrue(launcher.contains(RootRedirectScripts.WATCHDOG_SCRIPT_PRIMARY))
        assertTrue(launcher.contains(RootRedirectScripts.WATCHDOG_SCRIPT_FALLBACK))
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
        assertTrue(script.indexOf("-A FGA_CAP") in 0 until applyOk)
        assertTrue(applyOk in 0 until launch)
        assertTrue(launch in 0 until watchdogOk)
        assertTrue(script.contains("echo FGA_FAIL_STEP=1; exit 1"))
        assertTrue(script.contains("echo FGA_FAIL_WATCHDOG_PROBE"))
        assertEquals(
            RootRedirectScripts.apply(listOf(10123), 8899, true) + RootRedirectScripts.startWatchdog(8899, 10999, true),
            script,
        )
    }

    @Test
    fun allProtocolMarkersMatchProcessRootShellFilter() {
        val markers = listOf(
            RootRedirectScripts.MARKER_APPLY_OK,
            RootRedirectScripts.MARKER_CLEANUP_OK,
            RootRedirectScripts.MARKER_WATCHDOG_OK,
            RootRedirectScripts.MARKER_FAIL_WATCHDOG_PROBE,
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
            "apply_v4" to RootRedirectScripts.apply(listOf(10123), 8899, ipv6 = false),
            "apply_v4_v6" to RootRedirectScripts.apply(listOf(10123, 10456), 8899, ipv6 = true),
            "checkChains_v4" to RootRedirectScripts.checkChains(ipv6 = false),
            "checkChains_v6" to RootRedirectScripts.checkChains(ipv6 = true),
            "cleanup_with_kill" to RootRedirectScripts.cleanup(killWatchdog = true),
            "cleanup_no_kill" to RootRedirectScripts.cleanup(killWatchdog = false),
            "killWatchdog" to RootRedirectScripts.killWatchdog(),
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
}
