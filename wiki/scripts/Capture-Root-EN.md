# Root Capture Mode (Per-App Transparent Redirect + Root Watchdog)

**More options → Additional features → Log collection → Connection mode → Root** is a capture mode for rooted devices that avoids occupying Android's VPN slot by redirecting TCP 443 traffic from the FGO app (UID) into FGA's internal transparent proxy (`127.0.0.1:<port>` and `[::1]:<port>`) **only while reading is turned on**.

While reading is turned off, there are no kernel rules (`FGA_CAP`) and FGA's proxy does not open a listening port, so FGO communicates directly without passing through FGA at all.

---

## 1. Prerequisites and Requirements

- **Root access (`su`)**: Grant root permission to FGA in Magisk, KernelSU, APatch, or similar.
  - Tapping the **Root** chip under Connection mode shows `"Checking root access — please allow the superuser prompt"` and waits up to 30 seconds for root confirmation.
  - If permission is denied or root is unavailable, the `"Could not verify root access"` toast appears and the previous Connection mode is kept.
- **Kernel `iptables -t nat` `REDIRECT` support**:
  - Before starting collection, FGA runs a preflight check via `iptables -w 3 -t nat -S OUTPUT`.
  - If the device kernel does not support `nat REDIRECT`, no rules are added and `"Root rules (nat REDIRECT) are not supported on this device"` is shown.
- **Kernel `iptables` `owner` match (`xt_owner`) support**:
  - Before starting collection, FGA runs a read-only preflight check via `iptables -w 3 -t nat -C OUTPUT -p tcp --dport 443 -m owner --uid-owner 0 -j RETURN`.
  - If the kernel does not support per-app UID matching (`owner`), no rules are added and `"This device does not support per-app rules (iptables owner), so root mode cannot be used. Use FGA VPN or the external app proxy."` is shown.
- **CA certificate setup**:
  - **KR**: The KR game must be installed, and the KR certificate must be created and installed.
  - **JP**: `JP server (root)` must be enabled, the JP game must be installed, and the JP certificate must be ready.
  - Rules apply only to the UIDs of FGO apps whose target is prepared.

---

## 2. How It Works

### Turning Reading On (`Turn reading on` / `Start log collection` on Main Screen)

> Only one FGA app (stable or beta) can run root collection at a time. If another FGA app is already collecting through root, the second app shows an "another FGA app is collecting through root" message and does not start; it never touches the other app's rules. If two apps start at nearly the same moment, or another app starts while this one is collecting, the app that notices shows the same message and stops its own collection.

1. **Start proxy listener**: FGA opens the transparent TLS proxy on `127.0.0.1:<port>` and `[::1]:<port>` first.
2. **Apply kernel rules (`FGA_CAP_<app UID>` chain)**:
   - Creates the `FGA_CAP_<app UID>` chain in `iptables -w 3 -t nat` and `ip6tables -w 3 -t nat`, adds `-p tcp --dport 443 -m owner --uid-owner <UID> -j REDIRECT --to-ports <port>` rules for each prepared FGO app UID, and inserts `-j FGA_CAP_<app UID>` at the top of the `OUTPUT` chain.
   - Before the `REDIRECT` rules, `RETURN` rules are inserted for destinations `127.0.0.0/8`, `0.0.0.0` (IPv4) and `::1`, `::` (IPv6) to exclude loopback traffic from redirection. Thus, connections to hosts returning `127.0.0.1` via DNS blocking fail inside the game just as when capture is off.
   - Because rules match only FGO's UID, FGA's own outbound connections to the game server are never redirected back into itself.
3. **Start root watchdog**:
   - A detached watchdog script running under root moves into the root cgroup and runs in the background.
   - Startup succeeds only after the initial socket check passes (`FGA_WATCHDOG_OK`); if it fails, FGA immediately rolls back the rules and turns reading off.
4. **Reconnection note**:
   - Already-open FGO sockets continue using their existing connections, so capture starts once FGO opens a new connection (such as reconnecting from the title screen or entering a quest).

### Game Traffic vs. Non-Game Traffic (Blind Tunnel)

Because all TCP 443 connections from FGO are redirected, the proxy first inspects the SNI (Server Name Indication, 3 s timeout, max 16 KiB) in the TLS ClientHello.

- **Game server hosts (`yfgo-game.netmarble.com`, `game.fate-go.jp`)**:
  - When a certificate context exists for that server, the proxy decrypts in read-only mode to collect responses and forwards the original bytes unmodified.
- **Non-game hosts (login, in-game notice webviews, resource/data download CDNs, etc.)**:
  - When SNI is present for any non-game host, the proxy connects straight to `SNI:443` as a **blind tunnel** without decrypting or logging.
  - As a result, Netmarble/Aniplex logins, notice webviews, and data downloads all work normally.
- **Connections without SNI or non-TLS connections**:
  - Since the destination cannot be identified, the connection is closed immediately and counted on the card as `Unexpected connections (no SNI / non-TLS closed)`.

### IPv6 Support

- To support FGO JP (CloudFront) and IPv6 mobile data environments (including DNS64/NAT64), identical UID-based TCP 443 redirect rules are applied to both IPv4 (`iptables`) and IPv6 (`ip6tables`).
- If the device kernel does not support `ip6tables -t nat`, only IPv4 rules are applied and the status card displays `"This device does not support IPv6 nat rules; IPv6 connections are not captured (the game works normally)."`. IPv6 connections are not blocked and pass directly, so gameplay is unaffected.

---

## 3. Turning Reading Off and Safety Guards

### Normal Shutdown Sequence

When turning reading off, FGA executes the following steps so in-flight FGO requests are not cut off:

1. Removes the `FGA_CAP` jump and chain from `iptables`/`ip6tables` first and confirms removal (`FGA_CLEANUP_OK`). All new FGO connections immediately bypass FGA and connect directly.
2. Stops the proxy from accepting new connections.
3. Waits up to 30 seconds for any already-open connections to finish, then closes the proxy and stops the watchdog.

If rule removal fails temporarily, FGA keeps the proxy listener open so FGO traffic keeps flowing, retries rule removal every 10 seconds, and displays `"Could not remove root rules. Retrying; if it keeps failing, please reboot."` in the notification and on screen.

### Auto-Retreat

- If **5 client TLS handshake failures occur within 30 seconds without any success**, or **5 consecutive game-server upstream connection failures occur**, FGA automatically turns reading off and removes the rules.
- The toast and notification `"Automatically turned off root capture: <reason>"` is shown, after which FGO connects directly without the proxy.
- Upstream connection failures to non-game hosts (blind tunnels) only increment the counter (`Non-game host connection failures`) on the card without triggering auto-retreat, and reset to 0 each time root capture starts.

### Force Stop, Crash, App Update, Reboot, and Network Switches

- **FGA force stop / crash / app update / swipe from Recents**:
  - The detached **watchdog** running in the root cgroup checks `/proc/net/tcp` (and `/proc/net/tcp6`) every 3 seconds for the listening socket owned by FGA's UID.
  - Once FGA's process exits and the socket closes, the watchdog removes all `FGA_CAP` rules from `iptables` and `ip6tables` within ~3 seconds and exits on its own.
  - Additionally, when FGA restarts or updates, if a leftover rule flag file remains from a previous run, FGA runs one more background cleanup of rules and watchdog (without invoking `su` if no flag file exists).
- **Wi-Fi ↔ mobile data switch / Airplane mode**:
  - Because rules match **FGO app UID + TCP 443** rather than IP addresses, network or IP changes never require recalculating rules; FGA checks every 60 seconds that the chain is intact and reapplies it if missing.
- **Reboot**:
  - Kernel `iptables`/`ip6tables` rules and the watchdog in temporary storage exist only in memory, so **rebooting the device completely clears all rules**.

---

## 4. Using Alongside AdGuard and Personal VPNs

- **Personal VPNs (WireGuard, Surfshark, etc.)**:
  - Because Root mode does not use Android's `VpnService`, log collection can run simultaneously while WireGuard or other personal VPNs are active.
- **AdGuard**:
  - **Set AdGuard to 'Automatic proxy' mode, and exclude both FGO and FGA from AdGuard filtering.**
  - If AdGuard runs in local VPN mode or intercepts FGO/FGA traffic, it can conflict with root redirect rules.
  - Open **AdGuard → App management** for FGO (both KR and JP) and **turn off** **Route traffic through AdGuard**. Do the same for FGA.
    - AdGuard's Automatic proxy also uses root rules, and AdGuard's rules apply before FGA's rules. If routing stays enabled, FGO's game traffic goes to AdGuard instead of FGA, causing empty logs and possible connection failures.
    - This is the **opposite of External proxy app mode**, where FGO routing must be enabled. Update AdGuard's settings whenever switching Connection mode.
  - Turn off any FGA proxy server (`127.0.0.1:<port>`) registered under **AdGuard → Settings → Filtering → Network → Proxy**; that server is used only for External proxy app mode.
  - **How to verify**: If FGO fails to connect with AdGuard on but connects when AdGuard is turned off, FGO's routing in AdGuard is still enabled. Turn off routing, force-close FGO completely, and launch it again.

---

## 5. Troubleshooting (Removing Leftover Rules)

- Tap **Turn reading off** on screen or switch Connection mode to **FGA VPN** to remove rules immediately.
- Even if rule removal keeps failing due to a root manager error, **rebooting the device once removes every `iptables`/`ip6tables` rule and watchdog cleanly**.
