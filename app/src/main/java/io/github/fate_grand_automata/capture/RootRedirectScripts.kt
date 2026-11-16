package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.prefs.core.CaptureConnectionMode
import java.util.Locale

/**
 * Pure shell script builders for Root capture mode (`iptables` / `ip6tables` nat `REDIRECT` and root watchdog).
 *
 * Only validated integer UIDs and port numbers are interpolated into shell commands.
 * Markers match [ProcessRootShell]'s output filter (`FGA_[A-Z_]+(=[0-9]+)?`).
 */
internal object RootRedirectScripts {
    const val CHAIN_NAME = "FGA_CAP"
    const val WATCHDOG_SCRIPT_PRIMARY = "/data/local/tmp/fga_cap_watchdog.sh"
    const val WATCHDOG_SCRIPT_FALLBACK = "/dev/fga_cap_watchdog.sh"
    const val WATCHDOG_PID_PRIMARY = "/data/local/tmp/fga_cap_watchdog.pid"
    const val WATCHDOG_PID_FALLBACK = "/dev/fga_cap_watchdog.pid"
    const val WATCHDOG_OK_PRIMARY = "/data/local/tmp/fga_cap_watchdog.ok"
    const val WATCHDOG_OK_FALLBACK = "/dev/fga_cap_watchdog.ok"

    const val MARKER_APPLY_OK = "FGA_APPLY_OK"
    const val MARKER_CLEANUP_OK = "FGA_CLEANUP_OK"
    const val MARKER_WATCHDOG_OK = "FGA_WATCHDOG_OK"
    const val MARKER_FAIL_WATCHDOG_PROBE = "FGA_FAIL_WATCHDOG_PROBE"
    const val MARKER_FAIL_STEP_PREFIX = "FGA_FAIL_STEP="

    private fun validatePort(port: Int) {
        require(CaptureConnectionMode.isValidProxyPort(port)) {
            "Port out of range: $port"
        }
    }

    private fun validateUid(uid: Int, name: String) {
        require(uid > 0) {
            "Invalid $name: $uid"
        }
    }

    private fun validateTargetUids(fgoUids: List<Int>): List<Int> {
        val distinct = fgoUids.distinct()
        require(distinct.isNotEmpty()) { "At least one FGO UID is required" }
        distinct.forEach { validateUid(it, "fgoUid") }
        return distinct
    }

    fun portToProcHex(port: Int): String {
        validatePort(port)
        return "%04X".format(Locale.US, port)
    }

    fun precheckIpv4(): String = buildString {
        appendLine("IPT=/system/bin/iptables")
        appendLine("[ -x \"\$IPT\" ] || IPT=iptables")
        appendLine("if \"\$IPT\" -w 3 -t nat -S OUTPUT >/dev/null 2>&1; then")
        appendLine("  echo $MARKER_APPLY_OK")
        appendLine("else")
        appendLine("  echo ${MARKER_FAIL_STEP_PREFIX}1")
        appendLine("  exit 1")
        appendLine("fi")
    }

    fun precheckIpv6(): String = buildString {
        appendLine("IP6T=/system/bin/ip6tables")
        appendLine("[ -x \"\$IP6T\" ] || IP6T=ip6tables")
        appendLine("if \"\$IP6T\" -w 3 -t nat -S OUTPUT >/dev/null 2>&1; then")
        appendLine("  echo $MARKER_APPLY_OK")
        appendLine("else")
        appendLine("  echo ${MARKER_FAIL_STEP_PREFIX}1")
        appendLine("  exit 1")
        appendLine("fi")
    }

    fun apply(fgoUids: List<Int>, port: Int, ipv6: Boolean): String {
        val uids = validateTargetUids(fgoUids)
        validatePort(port)
        return buildString {
            appendLine("IPT=/system/bin/iptables")
            appendLine("[ -x \"\$IPT\" ] || IPT=iptables")
            appendLine("\"\$IPT\" -w 3 -t nat -N $CHAIN_NAME >/dev/null 2>&1 || true")
            appendLine("\"\$IPT\" -w 3 -t nat -F $CHAIN_NAME >/dev/null 2>&1 || { echo ${MARKER_FAIL_STEP_PREFIX}1; exit 1; }")
            for (uid in uids) {
                appendLine(
                    "\"\$IPT\" -w 3 -t nat -A $CHAIN_NAME -p tcp --dport 443 -m owner --uid-owner $uid -j REDIRECT --to-ports $port >/dev/null 2>&1 || { echo ${MARKER_FAIL_STEP_PREFIX}2; exit 1; }"
                )
            }
            appendLine(
                "\"\$IPT\" -w 3 -t nat -C OUTPUT -j $CHAIN_NAME >/dev/null 2>&1 || \"\$IPT\" -w 3 -t nat -I OUTPUT 1 -j $CHAIN_NAME >/dev/null 2>&1 || { echo ${MARKER_FAIL_STEP_PREFIX}3; exit 1; }"
            )
            if (ipv6) {
                appendLine("IP6T=/system/bin/ip6tables")
                appendLine("[ -x \"\$IP6T\" ] || IP6T=ip6tables")
                appendLine("\"\$IP6T\" -w 3 -t nat -N $CHAIN_NAME >/dev/null 2>&1 || true")
                appendLine("\"\$IP6T\" -w 3 -t nat -F $CHAIN_NAME >/dev/null 2>&1 || { echo ${MARKER_FAIL_STEP_PREFIX}4; exit 1; }")
                for (uid in uids) {
                    appendLine(
                        "\"\$IP6T\" -w 3 -t nat -A $CHAIN_NAME -p tcp --dport 443 -m owner --uid-owner $uid -j REDIRECT --to-ports $port >/dev/null 2>&1 || { echo ${MARKER_FAIL_STEP_PREFIX}5; exit 1; }"
                    )
                }
                appendLine(
                    "\"\$IP6T\" -w 3 -t nat -C OUTPUT -j $CHAIN_NAME >/dev/null 2>&1 || \"\$IP6T\" -w 3 -t nat -I OUTPUT 1 -j $CHAIN_NAME >/dev/null 2>&1 || { echo ${MARKER_FAIL_STEP_PREFIX}6; exit 1; }"
                )
            }
            appendLine("echo $MARKER_APPLY_OK")
        }
    }

    fun checkChains(ipv6: Boolean): String = buildString {
        appendLine("IPT=/system/bin/iptables")
        appendLine("[ -x \"\$IPT\" ] || IPT=iptables")
        appendLine("\"\$IPT\" -w 3 -t nat -S $CHAIN_NAME >/dev/null 2>&1 || { echo ${MARKER_FAIL_STEP_PREFIX}1; exit 1; }")
        appendLine("\"\$IPT\" -w 3 -t nat -C OUTPUT -j $CHAIN_NAME >/dev/null 2>&1 || { echo ${MARKER_FAIL_STEP_PREFIX}2; exit 1; }")
        if (ipv6) {
            appendLine("IP6T=/system/bin/ip6tables")
            appendLine("[ -x \"\$IP6T\" ] || IP6T=ip6tables")
            appendLine("\"\$IP6T\" -w 3 -t nat -S $CHAIN_NAME >/dev/null 2>&1 || { echo ${MARKER_FAIL_STEP_PREFIX}3; exit 1; }")
            appendLine("\"\$IP6T\" -w 3 -t nat -C OUTPUT -j $CHAIN_NAME >/dev/null 2>&1 || { echo ${MARKER_FAIL_STEP_PREFIX}4; exit 1; }")
        }
        appendLine("echo $MARKER_APPLY_OK")
    }

    fun cleanup(killWatchdog: Boolean = true): String = buildString {
        appendLine("IPT=/system/bin/iptables")
        appendLine("[ -x \"\$IPT\" ] || IPT=iptables")
        appendLine("IP6T=/system/bin/ip6tables")
        appendLine("[ -x \"\$IP6T\" ] || IP6T=ip6tables")
        appendLine("i=0")
        appendLine("while [ \"\$i\" -lt 16 ] && \"\$IPT\" -w 3 -t nat -D OUTPUT -j $CHAIN_NAME >/dev/null 2>&1; do")
        appendLine("  i=\$((i + 1))")
        appendLine("done")
        appendLine("\"\$IPT\" -w 3 -t nat -F $CHAIN_NAME >/dev/null 2>&1 || true")
        appendLine("\"\$IPT\" -w 3 -t nat -X $CHAIN_NAME >/dev/null 2>&1 || true")
        appendLine("j=0")
        appendLine("while [ \"\$j\" -lt 16 ] && \"\$IP6T\" -w 3 -t nat -D OUTPUT -j $CHAIN_NAME >/dev/null 2>&1; do")
        appendLine("  j=\$((j + 1))")
        appendLine("done")
        appendLine("\"\$IP6T\" -w 3 -t nat -F $CHAIN_NAME >/dev/null 2>&1 || true")
        appendLine("\"\$IP6T\" -w 3 -t nat -X $CHAIN_NAME >/dev/null 2>&1 || true")
        appendLine("if \"\$IPT\" -w 3 -t nat -S $CHAIN_NAME >/dev/null 2>&1 || \"\$IP6T\" -w 3 -t nat -S $CHAIN_NAME >/dev/null 2>&1; then")
        appendLine("  echo ${MARKER_FAIL_STEP_PREFIX}1")
        appendLine("  exit 1")
        appendLine("fi")
        if (killWatchdog) {
            append(killWatchdogCommands())
        }
        appendLine("echo $MARKER_CLEANUP_OK")
    }

    fun killWatchdog(): String = buildString {
        append(killWatchdogCommands())
        appendLine("echo $MARKER_CLEANUP_OK")
    }

    private fun killWatchdogCommands(): String = buildString {
        appendLine("for PF in $WATCHDOG_PID_PRIMARY $WATCHDOG_PID_FALLBACK; do")
        appendLine("  if [ -f \"\$PF\" ]; then")
        appendLine("    WPID=\$(cat \"\$PF\" 2>/dev/null)")
        appendLine("    case \"\$WPID\" in")
        appendLine("      ''|*[!0-9]*) ;;")
        appendLine("      *)")
        appendLine("        if [ \"\$WPID\" -gt 1 ] && [ -r \"/proc/\$WPID/cmdline\" ]; then")
        appendLine("          if tr '\\000' ' ' < \"/proc/\$WPID/cmdline\" 2>/dev/null | grep -q \"fga_cap_watchdog\"; then")
        appendLine("            kill -9 \"\$WPID\" >/dev/null 2>&1 || true")
        appendLine("          fi")
        appendLine("        fi")
        appendLine("        ;;")
        appendLine("    esac")
        appendLine("    rm -f \"\$PF\" >/dev/null 2>&1 || true")
        appendLine("  fi")
        appendLine("done")
        appendLine("rm -f $WATCHDOG_OK_PRIMARY $WATCHDOG_OK_FALLBACK >/dev/null 2>&1 || true")
    }

    fun watchdogScript(port: Int, fgaUid: Int, ipv6: Boolean): String {
        val portHex = portToProcHex(port)
        validateUid(fgaUid, "fgaUid")
        return buildString {
            appendLine("#!/system/bin/sh")
            appendLine("for CG in /sys/fs/cgroup/cgroup.procs /dev/cg2_bpf/cgroup.procs /acct/cgroup.procs; do")
            appendLine("  if [ -e \"\$CG\" ]; then")
            appendLine("    echo \$\$ > \"\$CG\" 2>/dev/null || true")
            appendLine("    break")
            appendLine("  fi")
            appendLine("done")
            appendLine("PID_FILE=$WATCHDOG_PID_PRIMARY")
            appendLine("(echo \$\$ > \"\$PID_FILE\") 2>/dev/null || {")
            appendLine("  PID_FILE=$WATCHDOG_PID_FALLBACK")
            appendLine("  (echo \$\$ > \"\$PID_FILE\") 2>/dev/null || true")
            appendLine("}")
            appendLine("OK_FILE=$WATCHDOG_OK_PRIMARY")
            appendLine("(: > \"\$OK_FILE\") 2>/dev/null || OK_FILE=$WATCHDOG_OK_FALLBACK")
            appendLine("IPT=/system/bin/iptables")
            appendLine("[ -x \"\$IPT\" ] || IPT=iptables")
            appendLine("IP6T=/system/bin/ip6tables")
            appendLine("[ -x \"\$IP6T\" ] || IP6T=ip6tables")
            appendLine("check_socket() {")
            appendLine("  grep -Eq \"^[[:space:]]*[0-9]+:[[:space:]]+0100007F:${portHex}[[:space:]]+[0-9A-Fa-f]+:[0-9A-Fa-f]+[[:space:]]+0A[[:space:]]+[^[:space:]]+[[:space:]]+[^[:space:]]+[[:space:]]+[^[:space:]]+[[:space:]]+${fgaUid}([[:space:]]|\$)\" /proc/net/tcp 2>/dev/null || return 1")
            if (ipv6) {
                appendLine("  grep -Eq \"^[[:space:]]*[0-9]+:[[:space:]]+00000000000000000000000001000000:${portHex}[[:space:]]+[0-9A-Fa-f]+:[0-9A-Fa-f]+[[:space:]]+0A[[:space:]]+[^[:space:]]+[[:space:]]+[^[:space:]]+[[:space:]]+[^[:space:]]+[[:space:]]+${fgaUid}([[:space:]]|\$)\" /proc/net/tcp6 2>/dev/null || return 1")
            }
            appendLine("  return 0")
            appendLine("}")
            appendLine("check_chains() {")
            appendLine("  \"\$IPT\" -w 3 -t nat -S $CHAIN_NAME >/dev/null 2>&1 || return 1")
            appendLine("  \"\$IPT\" -w 3 -t nat -C OUTPUT -j $CHAIN_NAME >/dev/null 2>&1 || return 1")
            if (ipv6) {
                appendLine("  \"\$IP6T\" -w 3 -t nat -S $CHAIN_NAME >/dev/null 2>&1 || return 1")
                appendLine("  \"\$IP6T\" -w 3 -t nat -C OUTPUT -j $CHAIN_NAME >/dev/null 2>&1 || return 1")
            }
            appendLine("  return 0")
            appendLine("}")
            appendLine("cleanup_once() {")
            appendLine("  i=0")
            appendLine("  while [ \"\$i\" -lt 16 ] && \"\$IPT\" -w 3 -t nat -D OUTPUT -j $CHAIN_NAME >/dev/null 2>&1; do")
            appendLine("    i=\$((i + 1))")
            appendLine("  done")
            appendLine("  \"\$IPT\" -w 3 -t nat -F $CHAIN_NAME >/dev/null 2>&1 || true")
            appendLine("  \"\$IPT\" -w 3 -t nat -X $CHAIN_NAME >/dev/null 2>&1 || true")
            appendLine("  j=0")
            appendLine("  while [ \"\$j\" -lt 16 ] && \"\$IP6T\" -w 3 -t nat -D OUTPUT -j $CHAIN_NAME >/dev/null 2>&1; do")
            appendLine("    j=\$((j + 1))")
            appendLine("  done")
            appendLine("  \"\$IP6T\" -w 3 -t nat -F $CHAIN_NAME >/dev/null 2>&1 || true")
            appendLine("  \"\$IP6T\" -w 3 -t nat -X $CHAIN_NAME >/dev/null 2>&1 || true")
            appendLine("}")
            appendLine("cleanup_until_gone() {")
            appendLine("  while :; do")
            appendLine("    cleanup_once")
            appendLine("    if ! \"\$IPT\" -w 3 -t nat -S $CHAIN_NAME >/dev/null 2>&1 && ! \"\$IP6T\" -w 3 -t nat -S $CHAIN_NAME >/dev/null 2>&1; then")
            appendLine("      break")
            appendLine("    fi")
            appendLine("    sleep 2")
            appendLine("  done")
            appendLine("}")
            appendLine("if check_socket; then")
            appendLine("  echo OK > \"\$OK_FILE\" 2>/dev/null || true")
            appendLine("else")
            appendLine("  echo FAIL > \"\$OK_FILE\" 2>/dev/null || true")
            appendLine("  cleanup_until_gone")
            appendLine("  rm -f \"\$PID_FILE\" >/dev/null 2>&1 || true")
            appendLine("  exit 1")
            appendLine("fi")
            appendLine("tick=0")
            appendLine("while :; do")
            appendLine("  sleep 3")
            appendLine("  tick=\$((tick + 1))")
            appendLine("  if ! check_socket; then")
            appendLine("    cleanup_until_gone")
            appendLine("    rm -f \"\$PID_FILE\" \"\$OK_FILE\" >/dev/null 2>&1 || true")
            appendLine("    exit 0")
            appendLine("  fi")
            appendLine("  if [ \"\$tick\" -ge 5 ]; then")
            appendLine("    tick=0")
            appendLine("    if ! check_chains; then")
            appendLine("      cleanup_until_gone")
            appendLine("      rm -f \"\$PID_FILE\" \"\$OK_FILE\" >/dev/null 2>&1 || true")
            appendLine("      exit 0")
            appendLine("    fi")
            appendLine("  fi")
            appendLine("done")
        }
    }

    fun startWatchdog(port: Int, fgaUid: Int, ipv6: Boolean): String {
        val watchdogBody = watchdogScript(port, fgaUid, ipv6)
        return buildString {
            append(killWatchdogCommands())
            appendLine("WFILE=$WATCHDOG_SCRIPT_PRIMARY")
            appendLine("cat <<'FGA_WATCHDOG_EOF' > \"\$WFILE\" 2>/dev/null || {")
            append(watchdogBody)
            appendLine("FGA_WATCHDOG_EOF")
            appendLine("  WFILE=$WATCHDOG_SCRIPT_FALLBACK")
            appendLine("  cat <<'FGA_WATCHDOG_EOF' > \"\$WFILE\" || { echo $MARKER_FAIL_WATCHDOG_PROBE; exit 1; }")
            append(watchdogBody)
            appendLine("FGA_WATCHDOG_EOF")
            appendLine("}")
            appendLine("chmod 700 \"\$WFILE\" >/dev/null 2>&1 || true")
            appendLine("if command -v setsid >/dev/null 2>&1; then")
            appendLine("  setsid nohup sh \"\$WFILE\" >/dev/null 2>&1 </dev/null &")
            appendLine("else")
            appendLine("  nohup sh \"\$WFILE\" >/dev/null 2>&1 </dev/null &")
            appendLine("fi")
            appendLine("p=0")
            appendLine("while [ \"\$p\" -lt 30 ]; do")
            appendLine("  for OF in $WATCHDOG_OK_PRIMARY $WATCHDOG_OK_FALLBACK; do")
            appendLine("    if [ -f \"\$OF\" ]; then")
            appendLine("      ST=\$(cat \"\$OF\" 2>/dev/null)")
            appendLine("      if [ \"\$ST\" = \"OK\" ]; then")
            appendLine("        echo $MARKER_WATCHDOG_OK")
            appendLine("        exit 0")
            appendLine("      elif [ \"\$ST\" = \"FAIL\" ]; then")
            appendLine("        echo $MARKER_FAIL_WATCHDOG_PROBE")
            appendLine("        exit 1")
            appendLine("      fi")
            appendLine("    fi")
            appendLine("  done")
            appendLine("  sleep 0.1 2>/dev/null || sleep 1")
            appendLine("  p=\$((p + 1))")
            appendLine("done")
            appendLine("echo $MARKER_FAIL_WATCHDOG_PROBE")
            appendLine("exit 1")
        }
    }
}
