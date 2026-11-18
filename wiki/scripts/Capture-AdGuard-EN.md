# External Proxy App Mode

Because Android allows only one active VPN at a time, local VPN-based apps (such as AdGuard) cannot run alongside FGA's default VPN capture mode. Selecting **External proxy app** keeps FGA from opening a VPN; instead, FGA opens a persistent local proxy on the loopback address (`127.0.0.1`) inside the device, and your external proxy or firewall app forwards only FGO's traffic to that proxy.

The default Connection mode is **FGA VPN**; if you do not use another VPN or proxy app alongside FGA, you do not need to change it.

!!! note "On-device processing"
    All traffic is processed solely inside this device and is never sent to external servers.

## Prerequisites

1. Under **More options → Additional features → Log collection**, turn on **Enable log collection** and agree to the notice ([Log Collection Setup](Log-Collection-Setup-EN.md)).
2. Just as in VPN mode, generate the FGA certificate and install it as a **user CA certificate** on the device. (If using JP server root auto-injection, certificate and module setup remain the same.)
3. Under **Connection mode**, select **External proxy app**.
4. Check the **Host** (`127.0.0.1`) and **Port** (e.g., `8899`) at the top of the card. Tap **Copy** beside each field to copy host or port separately, or enter a different port in the `1024–65535` range and tap **Apply** (or Done on the keyboard) to change it.
5. Exclude FGA from **battery optimization** so the system does not kill it in the background. Tap **Exclude from battery optimization** on the card (shown as "excluded" when already excluded) to open the system prompt.
6. Keeping FGA's accessibility service enabled lets FGA reopen the proxy in standby state if the proxy went down when FGO comes to the foreground (checked at most once every 30 seconds). It does not turn reading on automatically.

## Common Requirements (Any External App)

Regardless of which external app you use, all three conditions below must be met:

1. **Route only FGO through `127.0.0.1:<port>` (SOCKS5 or HTTP, no authentication)** (include JP as well if you play both KR and JP).
2. **Exclude FGA from the external app.** Because FGA connects directly to the game servers, if the external app intercepts or proxies FGA's own HTTPS traffic, FGA cannot reach the game servers.
3. **Do not let the external app decrypt FGO's HTTPS.** If the external app decrypts FGO traffic first, the game may show connection errors or logs may remain empty.

!!! note "Other VPN / proxy apps"
    Other VPN or proxy apps **can work in principle** if they satisfy all three requirements above, though actual operation **must be verified on your device**.

## Setup with AdGuard

Using **AdGuard** as a typical example, follow these steps:

1. In **AdGuard → Settings → Filtering → Network → Proxy → Proxy server**, add a server with Host and Port entered in their separate fields (do not put `127.0.0.1:8899` together in the Host field):
   - **Proxy type**: `SOCKS5` or `HTTP`
   - **Proxy host**: `127.0.0.1`
   - **Proxy port**: the port shown in FGA (default `8899`)
   - **Proxy authentication**: leave **OFF**.
2. Under **"Apps operating through proxy"**, select **only FGO** (also select JP if you use both KR and JP).
3. In **AdGuard's app management**, **exclude FGA from AdGuard**.
4. In **AdGuard's app management**, **turn off HTTPS filtering for FGO**.
5. Turn **on** the proxy in AdGuard.

### AdGuard Proxy Auto-Switch

When AdGuard (`com.adguard.android`) is installed on your device, the **AdGuard proxy auto-switch** setting appears below the **When using AdGuard** section. Turning it on lets FGA automatically enable and disable AdGuard's outbound proxy whenever you turn log reading on or off.

- **Setup**:
  1. On **AdGuard's Automation settings screen**, turn on **Automation** and copy or note the displayed automation password.
  2. Enter that password in FGA's **AdGuard automation password** field and turn on the **AdGuard proxy auto-switch** switch. (Auto-switch does nothing if the password is empty.)
- **How it behaves**:
  - **Turn reading on**: After the FGA proxy opens, FGA sets AdGuard's default proxy to `127.0.0.1:<port>` (SOCKS5) and turns the proxy on.
  - **Turn reading off**: Right before turning reading off, FGA turns AdGuard's proxy off.
  - **Service starts in waiting state**: The first time FGA's proxy service starts with reading off (app launch, boot, restart, etc.), it sends a command to turn AdGuard's proxy off once per service instance.
  - **Switching modes or disabling the feature**: When auto-switch is active (toggle on + non-empty password), FGA skips the "Turn off external proxy first" dialog, disables AdGuard's proxy, and exits cleanly.
  - **Turning off the auto-switch toggle**: Turning the toggle off sends no command to AdGuard and leaves its current state unchanged.
- **How to tell if the password is wrong**:
  - AdGuard's automation broadcast does not return an error to FGA when the password is wrong—it **fails silently**.
  - If **Last FGO connection** on the card does not update and FGO connection is not confirmed after turning reading on, verify that the password in AdGuard's Automation screen matches the password entered in FGA.

## Proxy Standby and Turning Reading On/Off

- **Proxy waiting**: To keep external proxy apps able to pass FGO connections through, FGA's local proxy service stays open persistently even when **reading is off**. In this state, it forwards traffic untouched without reading or decrypting game responses. (With AdGuard auto-switch enabled, AdGuard's proxy is on only while collecting.)
- **Turn reading on / Turn reading off**: Toggle via the persistent notification action, **More options → Additional features → Log collection**, the main-screen button, or the home-screen widget. No FGA VPN permission prompt appears, and turning reading off keeps the proxy open so FGO's connection does not drop.
- **Changing the port**: If collecting is active, FGA turns reading off first, opens the new port, and closes the old port, though FGO's active connection may drop briefly during the switch. If not using auto-switch, update the port number in your external app to match.

## Checking Status and Troubleshooting

- **Last FGO connection**: Shows in minutes when FGO last connected to a game server through the proxy. If nothing is recorded yet, verify that the proxy is enabled in your external app and FGO is selected as a proxied app (or that the automation password matches when using auto-switch).
- **Port in use error**: If another app is already using the chosen port, the proxy cannot bind to it. Switch to another port, update the external app to the same port, close the conflicting app if needed, and retry via the collect button or reopening the app.
- **TLS / upstream failure warnings**:
  - **Game rejected FGA's certificate**: **Turn off HTTPS filtering (HTTPS decryption) for FGO in your external app**, and verify that FGA's certificate is installed as a user CA certificate on the device.
  - **FGA could not reach the game server**: Verify that **FGA is excluded from your external app**, and check the device's network connection.
- **Networks without IPv6 (e.g., certain Wi-Fi or emulators)**: Even if an external app passes an IPv6 address to the game and sends an IPv6 request, when the device network lacks IPv6 support FGA immediately returns a connection-failure response so the game reconnects over IPv4 (no extra configuration required).

## Known Limitations

- **Force-stopping FGA while collecting**: If FGA is terminated immediately (such as via Android Settings **Force stop**) while reading is on and AdGuard auto-switch enabled AdGuard's proxy, FGA cannot send the disable command, leaving AdGuard's proxy on and blocking FGO's connection. Reopen FGA or turn off the proxy manually inside AdGuard.
- **Overwriting an existing AdGuard default proxy**: When AdGuard auto-switch starts reading, FGA configures `127.0.0.1:<port>` as AdGuard's default proxy server, overwriting any separate default proxy server you previously configured in AdGuard.
- **Swiping away from Recents vs. Force stop**: Swiping FGA away from the recent apps list keeps the proxy running. Only pressing **Force stop** in Android Settings stops the proxy.
- **Battery optimization**: If FGA is subject to battery optimization, Android may kill it in the background and cut FGO's connection. Use the **Exclude from battery optimization** button on the card.
- **App updates and reboots**: While FGA is updating, FGO goes offline for a few seconds. Once the update finishes or the device reboots, the proxy restarts automatically.
- **Dropped responses during network switches**: Responses in flight while switching between Wi-Fi and mobile data may be missed from the log.

## Switching Back or Turning Off the Feature

If you switch Connection mode back to **FGA VPN** or turn off **Enable log collection** while an external app is still routing FGO traffic to FGA's proxy (`127.0.0.1`), shutting down the proxy will cut FGO's connection.

- **Manual setup (or auto-switch off)**: Always **turn off the proxy setting in AdGuard or your external proxy app first**, then confirm the prompt in FGA before switching modes or disabling log collection.
- **With AdGuard auto-switch active**: FGA turns off AdGuard's proxy automatically before switching or shutting down, so it proceeds immediately without prompting.
