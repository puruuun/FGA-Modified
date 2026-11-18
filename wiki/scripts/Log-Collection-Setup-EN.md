# Log Collection Setup

This guide walks through enabling Log Collection for the first time and capturing your first KR or JP login data. For detailed behavior, the Account dashboard, Chaldea export, and data deletion, see [Log Collection](Gains-Tracker-EN.md).

!!! note "Requirements and privacy"
    - Requires **Android 10 or later** with the official KR app (`com.netmarble.fgok`) or the official JP app on a rooted device (`com.aniplex.fategrandorder`). BetterFGO and dual/cloned apps are not supported.
    - All traffic stays on-device inside a read-only local proxy; account credentials and login tokens are never stored or sent anywhere.
    - Set your FGA storage folder under **More options → Storage** beforehand so certificate files can be saved easily ([Installation](Installation-EN.md)).

## 1. Enable Log Collection and Accept the Notice

1. Open **More options → Additional features → Log collection**.
2. Turn on **Enable log collection**.
3. Read the **Log collection notice** and tap **Agree and use** (tapping **Cancel** leaves it off).

## 2. Prepare Certificates per Server

Prepare a certificate for each server you want to collect. When both servers are ready, a single capture start collects both.

### KR Server (No Root Required)

1. Tap **KR server → Create certificate**.
   - Saved to `capture/fga-capture-ca.crt` inside your chosen FGA folder (if that folder is unavailable, a system save picker opens).
2. Tap **How to install → Open security settings** (or search **CA certificate** in your device settings).
3. Go to **Security → Encryption & credentials → Install a certificate → CA certificate** and choose `fga-capture-ca.crt`.
4. Return to FGA and confirm the status reads **KR certificate: installed as CA certificate**.

!!! warning "Install as a CA certificate"
    Installing it as a Wi-Fi certificate or another type causes the game to reject the certificate and blocks collection.

### JP Server (Root Required)

When the official JP app is installed on Android 10+, the **JP server (root)** section appears.

1. Turn on **Automatically install the JP CA with root access** and grant root permission when prompted.
2. Confirm the status reads **JP certificate: installed**.
3. If the JP game was already running, close it completely and launch it again.

!!! note "JP certificate after reboot"
    Rebooting clears the system CA mount, and FGA automatically reinstalls it the next time you start log collection.

## 3. Choose a Connection Mode

Under **Connection → Connection mode**, pick the mode that fits your setup:

| Connection mode | Best for | Setup needed |
|---|---|---|
| **FGA VPN** (default) | Devices without another VPN or ad-blocker | No extra app or root needed. Allow Android's VPN prompt on first start. Cannot run at the same time as another VPN app. |
| **External proxy app** | Running alongside AdGuard or another local-VPN app | Exclude FGA from battery optimization, point the external app to `127.0.0.1:<port>` (default `8899`) for FGO only, exclude FGA, and disable HTTPS filtering for FGO. See [External Proxy App Mode](Capture-AdGuard-EN.md). |
| **Root** | Rooted devices running a personal VPN (WireGuard, etc.) | Grant root access; requires kernel support for `nat REDIRECT` and `iptables owner`. See [Root Capture Mode](Capture-Root-EN.md). |

## 4. Start Collecting and Verify

Start log collection from any of these four places:

- **Log collection screen**: Tap **Start** (FGA VPN) or **Turn reading on** (External proxy app / Root) under **Connection**.
- **Main screen**: Tap the blue **Start log collection** button at the bottom right (turns yellow as **Stop log collection** while active).
- **Start with FGA service**: Once setup is ready, tapping **Start service** on the main screen also starts log collection.
- **Home-screen widget**: Tap the log collection button on a widget ([Home-Screen Widgets](Widgets-EN.md)).

With collection active, **launch FGO fresh** (if it was already open, close and reopen it; in Root mode, new connections such as reconnecting from the title screen are captured). Once a login response arrives, a **profile card** showing your Master name and AP appears near the top of the main screen.

## Common Pitfalls

- **Certificate rejected warning**: Confirm the KR certificate was installed as a **CA certificate** (not Wi-Fi), or—in External proxy mode—that **HTTPS filtering (decryption) is disabled for FGO** in the proxy app.
- **Works on Wi-Fi but FGO fails on cellular data (LTE/5G)**: Because FGO traffic goes through FGA during collection, check Android settings to make sure **mobile data is allowed for FGA**.
- **Where collected data appears**: Inspect collected data in the main screen **profile card (Account dashboard)**, **[Battle Log](Battle-Log-EN.md)**, **[Friend Manager](Friend-Manager-EN.md)**, and **[Chaldea Auto Sync](Chaldea-Sync-EN.md)**.
