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
    /** Chain and watchdog name used by 1.8.0 and older, before names carried the app uid (#493). */
    const val LEGACY_CHAIN_NAME = "FGA_CAP"
    private const val CHAIN_PREFIX = "FGA_CAP_"
    private const val WATCHDOG_BASE_PREFIX = "fga_cap_watchdog_"
    private const val LEGACY_WATCHDOG_BASE = "fga_cap_watchdog"
    private const val PRIMARY_DIR = "/data/local/tmp/"
    private const val FALLBACK_DIR = "/dev/"

    const val MARKER_APPLY_OK = "FGA_APPLY_OK"
    const val MARKER_CLEANUP_OK = "FGA_CLEANUP_OK"
    const val MARKER_WATCHDOG_OK = "FGA_WATCHDOG_OK"
    const val MARKER_FAIL_WATCHDOG_PROBE = "FGA_FAIL_WATCHDOG_PROBE"
    const val MARKER_FAIL_OWNER = "FGA_FAIL_OWNER"
    const val MARKER_FAIL_OTHER_APP = "FGA_FAIL_OTHER_APP"
    const val MARKER_GUARD_OK = "FGA_GUARD_OK"
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

    // iptables chain names are limited to 28 characters; FGA_CAP_ plus a 10-digit uid is 18.
    fun chainName(fgaUid: Int): String {
        validateUid(fgaUid, "fgaUid")
        return "$CHAIN_PREFIX$fgaUid"
    }

    private fun watchdogBase(fgaUid: Int): String {
        validateUid(fgaUid, "fgaUid")
        return "$WATCHDOG_BASE_PREFIX$fgaUid"
    }

    fun watchdogScriptPrimary(fgaUid: Int): String = "$PRIMARY_DIR${watchdogBase(fgaUid)}.sh"
    fun watchdogScriptFallback(fgaUid: Int): String = "$FALLBACK_DIR${watchdogBase(fgaUid)}.sh"
    fun watchdogPidPrimary(fgaUid: Int): String = "$PRIMARY_DIR${watchdogBase(fgaUid)}.pid"
    fun watchdogPidFallback(fgaUid: Int): String = "$FALLBACK_DIR${watchdogBase(fgaUid)}.pid"
    fun watchdogOkPrimary(fgaUid: Int): String = "$PRIMARY_DIR${watchdogBase(fgaUid)}.ok"
    fun watchdogOkFallback(fgaUid: Int): String = "$FALLBACK_DIR${watchdogBase(fgaUid)}.ok"

    fun portToProcHex(port: Int): String {
        validatePort(port)
        return "%04X".format(Locale.US, port)
    }

    private fun precheck(toolVar: String, toolName: String): String = buildString {
        appendLine("$toolVar=/system/bin/$toolName")
        appendLine("[ -x \"\$$toolVar\" ] || $toolVar=$toolName")
        appendLine("if ! \"\$$toolVar\" -w 3 -t nat -S OUTPUT >/dev/null 2>&1; then")
        appendLine("  echo ${MARKER_FAIL_STEP_PREFIX}1")
        appendLine("  exit 1")
        appendLine("fi")
        // -C is read-only: 0 (rule exists) or 1 (rule absent) when xt_owner is supported, 2 when iptables cannot
        // load the match ("Couldn't find match 'owner'", measured on a MuMu Android 12 kernel). Other codes, such as
        // 4 for an xtables lock timeout, are not evidence about the kernel and keep the generic failure.
        appendLine("\"\$$toolVar\" -w 3 -t nat -C OUTPUT -p tcp --dport 443 -m owner --uid-owner 0 -j RETURN >/dev/null 2>&1")
        appendLine("rc=\$?")
        appendLine("if [ \"\$rc\" -eq 0 ] || [ \"\$rc\" -eq 1 ]; then")
        appendLine("  echo $MARKER_APPLY_OK")
        appendLine("elif [ \"\$rc\" -eq 2 ]; then")
        appendLine("  echo $MARKER_FAIL_OWNER")
        appendLine("  exit 1")
        appendLine("else")
        appendLine("  echo ${MARKER_FAIL_STEP_PREFIX}1")
        appendLine("  exit 1")
        appendLine("fi")
    }

    fun precheckIpv4(): String = precheck(toolVar = "IPT", toolName = "iptables")

    fun precheckIpv6(): String = precheck(toolVar = "IP6T", toolName = "ip6tables")

    private val IPV4_LOOPBACK_CIDRS = listOf("127.0.0.0/8", "0.0.0.0/32")
    private val IPV6_LOOPBACK_CIDRS = listOf("::1/128", "::/128")

    private fun StringBuilder.appendLoopbackReturnRules(
        toolVar: String,
        chain: String,
        cidrs: List<String>,
        failStep: Int,
    ) {
        for (cidr in cidrs) {
            appendLine(
                "\"\$$toolVar\" -w 3 -t nat -A $chain -d $cidr -j RETURN >/dev/null 2>&1 || { echo $MARKER_FAIL_STEP_PREFIX$failStep; exit 1; }"
            )
        }
    }

    fun apply(fgaUid: Int, fgoUids: List<Int>, port: Int, ipv6: Boolean): String {
        val chain = chainName(fgaUid)
        val uids = validateTargetUids(fgoUids)
        validatePort(port)
        return buildString {
            appendLine("IPT=/system/bin/iptables")
            appendLine("[ -x \"\$IPT\" ] || IPT=iptables")
            appendLine("\"\$IPT\" -w 3 -t nat -N $chain >/dev/null 2>&1 || true")
            appendLine("\"\$IPT\" -w 3 -t nat -F $chain >/dev/null 2>&1 || { echo ${MARKER_FAIL_STEP_PREFIX}1; exit 1; }")
            appendLoopbackReturnRules("IPT", chain, IPV4_LOOPBACK_CIDRS, failStep = 2)
            for (uid in uids) {
                appendLine(
                    "\"\$IPT\" -w 3 -t nat -A $chain -p tcp --dport 443 -m owner --uid-owner $uid -j REDIRECT --to-ports $port >/dev/null 2>&1 || { echo ${MARKER_FAIL_STEP_PREFIX}2; exit 1; }"
                )
            }
            appendLine(
                "\"\$IPT\" -w 3 -t nat -C OUTPUT -j $chain >/dev/null 2>&1 || \"\$IPT\" -w 3 -t nat -I OUTPUT 1 -j $chain >/dev/null 2>&1 || { echo ${MARKER_FAIL_STEP_PREFIX}3; exit 1; }"
            )
            if (ipv6) {
                appendLine("IP6T=/system/bin/ip6tables")
                appendLine("[ -x \"\$IP6T\" ] || IP6T=ip6tables")
                appendLine("\"\$IP6T\" -w 3 -t nat -N $chain >/dev/null 2>&1 || true")
                appendLine("\"\$IP6T\" -w 3 -t nat -F $chain >/dev/null 2>&1 || { echo ${MARKER_FAIL_STEP_PREFIX}4; exit 1; }")
                appendLoopbackReturnRules("IP6T", chain, IPV6_LOOPBACK_CIDRS, failStep = 5)
                for (uid in uids) {
                    appendLine(
                        "\"\$IP6T\" -w 3 -t nat -A $chain -p tcp --dport 443 -m owner --uid-owner $uid -j REDIRECT --to-ports $port >/dev/null 2>&1 || { echo ${MARKER_FAIL_STEP_PREFIX}5; exit 1; }"
                    )
                }
                appendLine(
                    "\"\$IP6T\" -w 3 -t nat -C OUTPUT -j $chain >/dev/null 2>&1 || \"\$IP6T\" -w 3 -t nat -I OUTPUT 1 -j $chain >/dev/null 2>&1 || { echo ${MARKER_FAIL_STEP_PREFIX}6; exit 1; }"
                )
            } else {
                // H6: rules from an earlier IPv6 apply must not stay behind (errors are ignored, e.g. no ip6tables nat).
                appendLine("IP6T=/system/bin/ip6tables")
                appendLine("[ -x \"\$IP6T\" ] || IP6T=ip6tables")
                appendLine("k=0")
                appendLine("while [ \"\$k\" -lt 16 ] && \"\$IP6T\" -w 3 -t nat -D OUTPUT -j $chain >/dev/null 2>&1; do")
                appendLine("  k=\$((k + 1))")
                appendLine("done")
                appendLine("\"\$IP6T\" -w 3 -t nat -F $chain >/dev/null 2>&1 || true")
                appendLine("\"\$IP6T\" -w 3 -t nat -X $chain >/dev/null 2>&1 || true")
            }
            appendLine("echo $MARKER_APPLY_OK")
        }
    }

    /**
     * Apply and watchdog start in one root shell script (K2): apply first, then the watchdog start and its first
     * probe, so both run inside the root process even if FGA dies right after sending it. A failed apply exits
     * before the watchdog starts; output carries [MARKER_APPLY_OK] then [MARKER_WATCHDOG_OK] on success.
     */
    fun applyAndStartWatchdog(fgoUids: List<Int>, port: Int, fgaUid: Int, ipv6: Boolean): String =
        apply(fgaUid, fgoUids, port, ipv6) + startWatchdog(port, fgaUid, ipv6)

    /**
     * Besides the chain and its jump, the chain must still hold the `REDIRECT` to [port]: another FGA app that
     * flushed or rewrote it would leave the chain in place while the capture silently stopped (#493).
     */
    fun checkChains(fgaUid: Int, port: Int, ipv6: Boolean): String {
        val chain = chainName(fgaUid)
        validatePort(port)
        return buildString {
            appendLine("IPT=/system/bin/iptables")
            appendLine("[ -x \"\$IPT\" ] || IPT=iptables")
            appendLine("\"\$IPT\" -w 3 -t nat -S $chain >/dev/null 2>&1 || { echo ${MARKER_FAIL_STEP_PREFIX}1; exit 1; }")
            appendLine("\"\$IPT\" -w 3 -t nat -C OUTPUT -j $chain >/dev/null 2>&1 || { echo ${MARKER_FAIL_STEP_PREFIX}2; exit 1; }")
            appendLine("\"\$IPT\" -w 3 -t nat -S $chain 2>/dev/null | ${redirectGrep(port)} || { echo ${MARKER_FAIL_STEP_PREFIX}5; exit 1; }")
            if (ipv6) {
                appendLine("IP6T=/system/bin/ip6tables")
                appendLine("[ -x \"\$IP6T\" ] || IP6T=ip6tables")
                appendLine("\"\$IP6T\" -w 3 -t nat -S $chain >/dev/null 2>&1 || { echo ${MARKER_FAIL_STEP_PREFIX}3; exit 1; }")
                appendLine("\"\$IP6T\" -w 3 -t nat -C OUTPUT -j $chain >/dev/null 2>&1 || { echo ${MARKER_FAIL_STEP_PREFIX}4; exit 1; }")
                appendLine("\"\$IP6T\" -w 3 -t nat -S $chain 2>/dev/null | ${redirectGrep(port)} || { echo ${MARKER_FAIL_STEP_PREFIX}6; exit 1; }")
            }
            appendLine("echo $MARKER_APPLY_OK")
        }
    }

    private fun redirectGrep(port: Int): String = "grep -Eq -- \"--to-ports $port( |\\\$)\""

    fun cleanup(fgaUid: Int, killWatchdog: Boolean = true): String = buildString {
        val chain = chainName(fgaUid)
        appendLine("IPT=/system/bin/iptables")
        appendLine("[ -x \"\$IPT\" ] || IPT=iptables")
        appendLine("IP6T=/system/bin/ip6tables")
        appendLine("[ -x \"\$IP6T\" ] || IP6T=ip6tables")
        appendLine("i=0")
        appendLine("while [ \"\$i\" -lt 16 ] && \"\$IPT\" -w 3 -t nat -D OUTPUT -j $chain >/dev/null 2>&1; do")
        appendLine("  i=\$((i + 1))")
        appendLine("done")
        appendLine("\"\$IPT\" -w 3 -t nat -F $chain >/dev/null 2>&1 || true")
        appendLine("\"\$IPT\" -w 3 -t nat -X $chain >/dev/null 2>&1 || true")
        appendLine("j=0")
        appendLine("while [ \"\$j\" -lt 16 ] && \"\$IP6T\" -w 3 -t nat -D OUTPUT -j $chain >/dev/null 2>&1; do")
        appendLine("  j=\$((j + 1))")
        appendLine("done")
        appendLine("\"\$IP6T\" -w 3 -t nat -F $chain >/dev/null 2>&1 || true")
        appendLine("\"\$IP6T\" -w 3 -t nat -X $chain >/dev/null 2>&1 || true")
        appendLine("if \"\$IPT\" -w 3 -t nat -S $chain >/dev/null 2>&1 || \"\$IP6T\" -w 3 -t nat -S $chain >/dev/null 2>&1; then")
        appendLine("  echo ${MARKER_FAIL_STEP_PREFIX}1")
        appendLine("  exit 1")
        appendLine("fi")
        if (killWatchdog) {
            append(killWatchdogCommands(fgaUid))
        }
        appendLine("echo $MARKER_CLEANUP_OK")
    }

    fun killWatchdog(fgaUid: Int): String = buildString {
        append(killWatchdogCommands(fgaUid))
        appendLine("echo $MARKER_CLEANUP_OK")
    }

    /**
     * Start guard against another FGA app (stable and beta installs share the device's nat table, #493).
     *
     * Looks at nat `OUTPUT` for jumps to the legacy `FGA_CAP` chain (1.8.0 and older) or to another app's
     * `FGA_CAP_<uid>` chain. A chain whose watchdog is alive belongs to a collecting app: the script prints
     * [MARKER_FAIL_OTHER_APP] and exits 1 without touching anything. Rules never exist without their watchdog
     * (K2), so a chain without a live watchdog is stale and is removed. A chain nothing jumps to is not looked at.
     * Another app's PID and OK files are never removed. `PROC_ROOT` is overridable for tests, as `PROC_NET_TCP` is.
     */
    fun guardOtherApp(fgaUid: Int, ipv6: Boolean): String {
        val own = chainName(fgaUid)
        return buildString {
            appendLine("IPT=/system/bin/iptables")
            appendLine("[ -x \"\$IPT\" ] || IPT=iptables")
            appendLine("IP6T=/system/bin/ip6tables")
            appendLine("[ -x \"\$IP6T\" ] || IP6T=ip6tables")
            appendLine("PROC_ROOT=\"\${PROC_ROOT:-/proc}\"")
            appendLine("OWN=$own")
            appendLine("list_chains() {")
            appendLine("  \"\$1\" -w 3 -t nat -S OUTPUT 2>/dev/null | sed -n 's/^-A OUTPUT .*-j \\(FGA_CAP\\(_[0-9][0-9]*\\)\\{0,1\\}\\)[[:space:]]*\$/\\1/p'")
            appendLine("}")
            appendLine("watchdog_live() {")
            appendLine("  for PF in $PRIMARY_DIR\$1.pid $FALLBACK_DIR\$1.pid; do")
            appendLine("    [ -f \"\$PF\" ] || continue")
            appendLine("    WPID=\$(cat \"\$PF\" 2>/dev/null)")
            appendLine("    case \"\$WPID\" in")
            appendLine("      ''|*[!0-9]*) continue ;;")
            appendLine("    esac")
            appendLine("    [ \"\$WPID\" -gt 1 ] || continue")
            appendLine("    [ -r \"\$PROC_ROOT/\$WPID/cmdline\" ] || continue")
            appendLine("    if tr '\\000' ' ' < \"\$PROC_ROOT/\$WPID/cmdline\" 2>/dev/null | grep -q \"\$1\\.sh\"; then")
            appendLine("      return 0")
            appendLine("    fi")
            appendLine("  done")
            appendLine("  return 1")
            appendLine("}")
            appendLine("watchdog_base() {")
            appendLine("  case \"\$1\" in")
            appendLine("    $LEGACY_CHAIN_NAME) echo $LEGACY_WATCHDOG_BASE ;;")
            appendLine("    *) echo \"$WATCHDOG_BASE_PREFIX\${1#$CHAIN_PREFIX}\" ;;")
            appendLine("  esac")
            appendLine("}")
            if (ipv6) {
                appendLine("CHAINS=\$(list_chains \"\$IPT\"; list_chains \"\$IP6T\")")
            } else {
                appendLine("CHAINS=\$(list_chains \"\$IPT\")")
            }
            appendLine("for C in \$CHAINS; do")
            appendLine("  [ \"\$C\" = \"\$OWN\" ] && continue")
            appendLine("  if watchdog_live \"\$(watchdog_base \"\$C\")\"; then")
            appendLine("    echo $MARKER_FAIL_OTHER_APP")
            appendLine("    exit 1")
            appendLine("  fi")
            appendLine("done")
            appendLine("for C in \$CHAINS; do")
            appendLine("  [ \"\$C\" = \"\$OWN\" ] && continue")
            appendLine("  for T in \"\$IPT\" \"\$IP6T\"; do")
            appendLine("    k=0")
            appendLine("    while [ \"\$k\" -lt 16 ] && \"\$T\" -w 3 -t nat -D OUTPUT -j \"\$C\" >/dev/null 2>&1; do")
            appendLine("      k=\$((k + 1))")
            appendLine("    done")
            appendLine("    \"\$T\" -w 3 -t nat -F \"\$C\" >/dev/null 2>&1 || true")
            appendLine("    \"\$T\" -w 3 -t nat -X \"\$C\" >/dev/null 2>&1 || true")
            appendLine("  done")
            appendLine("  if \"\$IPT\" -w 3 -t nat -C OUTPUT -j \"\$C\" >/dev/null 2>&1 || \"\$IP6T\" -w 3 -t nat -C OUTPUT -j \"\$C\" >/dev/null 2>&1; then")
            appendLine("    echo ${MARKER_FAIL_STEP_PREFIX}1")
            appendLine("    exit 1")
            appendLine("  fi")
            appendLine("done")
            appendLine("echo $MARKER_GUARD_OK")
        }
    }

    private fun killWatchdogCommands(fgaUid: Int): String = buildString {
        val base = watchdogBase(fgaUid)
        appendLine("for PF in ${watchdogPidPrimary(fgaUid)} ${watchdogPidFallback(fgaUid)}; do")
        appendLine("  if [ -f \"\$PF\" ]; then")
        appendLine("    WPID=\$(cat \"\$PF\" 2>/dev/null)")
        appendLine("    case \"\$WPID\" in")
        appendLine("      ''|*[!0-9]*) ;;")
        appendLine("      *)")
        appendLine("        if [ \"\$WPID\" -gt 1 ] && [ -r \"/proc/\$WPID/cmdline\" ]; then")
        appendLine("          if tr '\\000' ' ' < \"/proc/\$WPID/cmdline\" 2>/dev/null | grep -q \"$base\\.sh\"; then")
        appendLine("            kill -9 \"\$WPID\" >/dev/null 2>&1 || true")
        appendLine("          fi")
        appendLine("        fi")
        appendLine("        ;;")
        appendLine("    esac")
        appendLine("    rm -f \"\$PF\" >/dev/null 2>&1 || true")
        appendLine("  fi")
        appendLine("done")
        appendLine("rm -f ${watchdogOkPrimary(fgaUid)} ${watchdogOkFallback(fgaUid)} >/dev/null 2>&1 || true")
    }

    private fun procNetListenGrep(
        localIpHex: String,
        portHex: String,
        fgaUid: Int,
        procPathVar: String,
    ): String =
        "grep -Eq \"^[[:space:]]*[0-9]+:[[:space:]]+$localIpHex:$portHex[[:space:]]+[0-9A-Fa-f]+:[0-9A-Fa-f]+[[:space:]]+0A[[:space:]]+[^[:space:]]+[[:space:]]+[^[:space:]]+[[:space:]]+[^[:space:]]+[[:space:]]+$fgaUid([[:space:]]|\$)\" \"\$$procPathVar\" 2>/dev/null"

    fun watchdogScript(port: Int, fgaUid: Int, ipv6: Boolean): String {
        val portHex = portToProcHex(port)
        val chain = chainName(fgaUid)
        val ipv4Grep = procNetListenGrep("0100007F", portHex, fgaUid, "PROC_NET_TCP")
        val ipv4MappedGrep = procNetListenGrep("0000000000000000FFFF00000100007F", portHex, fgaUid, "PROC_NET_TCP6")
        val ipv6Grep = procNetListenGrep("00000000000000000000000001000000", portHex, fgaUid, "PROC_NET_TCP6")
        return buildString {
            appendLine("#!/system/bin/sh")
            appendLine("for CG in /sys/fs/cgroup/cgroup.procs /dev/cg2_bpf/cgroup.procs /acct/cgroup.procs; do")
            appendLine("  if [ -e \"\$CG\" ]; then")
            appendLine("    echo \$\$ > \"\$CG\" 2>/dev/null || true")
            appendLine("    break")
            appendLine("  fi")
            appendLine("done")
            appendLine("PID_FILE=${watchdogPidPrimary(fgaUid)}")
            appendLine("(echo \$\$ > \"\$PID_FILE\") 2>/dev/null || {")
            appendLine("  PID_FILE=${watchdogPidFallback(fgaUid)}")
            appendLine("  (echo \$\$ > \"\$PID_FILE\") 2>/dev/null || true")
            appendLine("}")
            appendLine("OK_FILE=${watchdogOkPrimary(fgaUid)}")
            appendLine("(: > \"\$OK_FILE\") 2>/dev/null || OK_FILE=${watchdogOkFallback(fgaUid)}")
            appendLine("IPT=/system/bin/iptables")
            appendLine("[ -x \"\$IPT\" ] || IPT=iptables")
            appendLine("IP6T=/system/bin/ip6tables")
            appendLine("[ -x \"\$IP6T\" ] || IP6T=ip6tables")
            appendLine("PROC_NET_TCP=\"\${PROC_NET_TCP:-/proc/net/tcp}\"")
            appendLine("PROC_NET_TCP6=\"\${PROC_NET_TCP6:-/proc/net/tcp6}\"")
            appendLine("check_socket() {")
            appendLine("  $ipv4Grep || $ipv4MappedGrep || return 1")
            if (ipv6) {
                appendLine("  $ipv6Grep || return 1")
            }
            appendLine("  return 0")
            appendLine("}")
            appendLine("check_chains() {")
            appendLine("  \"\$IPT\" -w 3 -t nat -S $chain >/dev/null 2>&1 || return 1")
            appendLine("  \"\$IPT\" -w 3 -t nat -C OUTPUT -j $chain >/dev/null 2>&1 || return 1")
            appendLine("  \"\$IPT\" -w 3 -t nat -S $chain 2>/dev/null | ${redirectGrep(port)} || return 1")
            if (ipv6) {
                appendLine("  \"\$IP6T\" -w 3 -t nat -S $chain >/dev/null 2>&1 || return 1")
                appendLine("  \"\$IP6T\" -w 3 -t nat -C OUTPUT -j $chain >/dev/null 2>&1 || return 1")
                appendLine("  \"\$IP6T\" -w 3 -t nat -S $chain 2>/dev/null | ${redirectGrep(port)} || return 1")
            }
            appendLine("  return 0")
            appendLine("}")
            appendLine("cleanup_once() {")
            appendLine("  i=0")
            appendLine("  while [ \"\$i\" -lt 16 ] && \"\$IPT\" -w 3 -t nat -D OUTPUT -j $chain >/dev/null 2>&1; do")
            appendLine("    i=\$((i + 1))")
            appendLine("  done")
            appendLine("  \"\$IPT\" -w 3 -t nat -F $chain >/dev/null 2>&1 || true")
            appendLine("  \"\$IPT\" -w 3 -t nat -X $chain >/dev/null 2>&1 || true")
            appendLine("  j=0")
            appendLine("  while [ \"\$j\" -lt 16 ] && \"\$IP6T\" -w 3 -t nat -D OUTPUT -j $chain >/dev/null 2>&1; do")
            appendLine("    j=\$((j + 1))")
            appendLine("  done")
            appendLine("  \"\$IP6T\" -w 3 -t nat -F $chain >/dev/null 2>&1 || true")
            appendLine("  \"\$IP6T\" -w 3 -t nat -X $chain >/dev/null 2>&1 || true")
            appendLine("}")
            appendLine("cleanup_until_gone() {")
            appendLine("  while :; do")
            appendLine("    cleanup_once")
            appendLine("    if ! \"\$IPT\" -w 3 -t nat -S $chain >/dev/null 2>&1 && ! \"\$IP6T\" -w 3 -t nat -S $chain >/dev/null 2>&1; then")
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
            append(killWatchdogCommands(fgaUid))
            appendLine("WFILE=${watchdogScriptPrimary(fgaUid)}")
            appendLine("cat <<'FGA_WATCHDOG_EOF' > \"\$WFILE\" 2>/dev/null || {")
            append(watchdogBody)
            appendLine("FGA_WATCHDOG_EOF")
            appendLine("  WFILE=${watchdogScriptFallback(fgaUid)}")
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
            appendLine("  for OF in ${watchdogOkPrimary(fgaUid)} ${watchdogOkFallback(fgaUid)}; do")
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
