package io.github.fate_grand_automata.capture

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootRedirectScriptsTest {
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
    fun applyIpv4OnlyOmitsIp6tablesCommands() {
        val script = RootRedirectScripts.apply(
            fgoUids = listOf(10123),
            port = 8899,
            ipv6 = false,
        )
        assertTrue(script.contains("IPT=/system/bin/iptables"))
        assertFalse(script.contains("ip6tables"))
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
        assertTrue(watchdog.contains("00000000000000000000000001000000:22C3"))
        assertTrue(watchdog.contains("0A"))
        assertTrue(watchdog.contains("10999"))
        assertTrue(watchdog.contains("/proc/net/tcp"))
        assertTrue(watchdog.contains("/proc/net/tcp6"))

        val ipv4OnlyWatchdog = RootRedirectScripts.watchdogScript(port = 8899, fgaUid = 10999, ipv6 = false)
        assertTrue(ipv4OnlyWatchdog.contains("0100007F:22C3"))
        assertFalse(ipv4OnlyWatchdog.contains("/proc/net/tcp6"))
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
}
