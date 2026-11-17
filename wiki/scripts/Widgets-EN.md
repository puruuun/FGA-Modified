# Home-Screen Widgets

You can place two FGA widgets on your home screen: a 1x1 widget to start and stop the FGA service, and an Account info widget that displays AP for your collected accounts. Both widgets default to a dark navy card style, and you can choose a light theme and background opacity in [Widget settings](#widget-settings).

**Targets the KR (Korean) and official JP (Japanese) servers.** Account AP data appears on Android 10 and later once you enable [Log collection](Gains-Tracker-EN.md) and capture at least one account.

## Adding a Widget

1. Long-press an empty space on your home screen and open **Widgets**.
2. Find **FGA** in the list and drag **FGA service** or **Account info** onto your home screen.

The Account info widget is added immediately without an account-picking step. Widgets placed in earlier versions also display all collected accounts.

## FGA service Widget (1x1)

Starts at the same 1x1 size as before. You can resize it horizontally up to roughly two columns (2x1), and when **Show log collection button** is turned on, a two-column widget shows the log-collection button on the left and the service button on the right side by side. At one column wide, only the service button is shown.

When the service is stopped, a circle with ▶ and **OFF** are shown; when running, ■ and an accented **ON** are shown. Whenever the service starts or stops, both widget types update their status.

- **Tapping from OFF** opens FGA and runs the exact same sequence as the **Start Service** button on the main screen once: Storage folder → notification permission → Display over other apps permission → Accessibility Service → screen capture permission → Start Service, and if log collection is enabled, also starts the log-collection VPN at the end.
- Even if FGA was left on the Account dashboard, Log, or More options screen, it navigates to the main screen first to run the start flow. If initial setup is needed, the start request is dropped; if the service is already running, a duplicate start request is not run.
- If any permission is missing, the usual prompt dialog appears. Once the service starts, the FGA screen closes automatically and returns you to what you were viewing. If starting fails or you cancel screen capture permission, the FGA screen stays open.
- **Tapping from ON** stops the service without opening FGA. Log collection stops along with it according to the existing rule (**Stop log collection when the service stops** in settings).

## Account info Widget (Default 4x1, Resizable Down to 3x1)

When **Show log collection button** is enabled, the right side displays **Account info | ▲▼ | Log collection | Service**. The log button is the same size as the service button and uses a key icon. To prevent truncating account info, the log button is hidden when width is below 250 dp (so it is hidden on a typical 3x1 around 200 dp). Horizontal content padding is about 22 dp, with spacing between account info and ▲▼ as well.

One account card is shown on the left. Switch accounts using the **▲ / ▼ buttons** between the card and the start/stop button: **▼ goes to the next account, ▲ goes to the previous account**, wrapping around at either end. Each widget remembers which account it is viewing, keeping that same account across 15-minute refreshes or new data saves. If the viewed account is removed, it falls back to the first account. The current (most recently played) account appears first, followed by others ordered by most recent data collection time. The start/stop button on the right stays fixed.

Only one account is shown at a time. If you have only one account, the ▲ / ▼ buttons are hidden. Left padding keeps the name, AP, and bar clear of the card's rounded corners.

| Display | Meaning |
| --- | --- |
| Account name | Your custom-renamed name takes priority. |
| Gray text on the right: `Just now` / `Nm ago` / `Nh ago` / `Nd ago` | Time elapsed since data was last collected for this account. Shown only in single-account view. |
| `57 / 142` | Current AP and max AP calculated from saved data for the current time. |
| `Full 8:23 PM` | Time when AP will be full. Shows **AP full** if already full. |
| Progress bar | Current AP / max AP ratio. |

- Time formatting follows your device's language and 12/24-hour setting. Colors follow the theme in [Widget settings](#widget-settings) rather than the system theme; the default is dark navy.
- Font sizes shrink on narrower widths so AP and full time stay visible together. Because the button area takes space, narrower widths use smaller text.
- Tapping the visible account card's info or empty space opens that account's dashboard. Even after switching accounts with ▲ / ▼, tapping opens the currently displayed account.
- AP regenerates at 1 AP per 5 minutes, matching the account cards on the main screen.

### All Accounts in a Row

Changing **Widget settings → Display style** to **All accounts in a row** removes the ▲ / ▼ buttons and displays accounts side by side in equal-width columns. Column order matches single-account view (current account first, then most recently collected).

- Each column shows, from top to bottom, the **name** (bold, ellipsized if long), **current / max AP** (large and bold), and green **full time** below it (**AP full** if already full). There is no progress bar.
- Only as many columns as fit in the widget width are shown; remaining accounts are omitted. With only the service button on, a 4x1 phone widget (~272 dp) shows two accounts and 5x1 (~350 dp) shows three. With both log and service buttons on, 4x1 shows two accounts starting around 292 dp and one account if narrower. Each column reserves at least ~76 dp. Unlike single-account view, elapsed time (`Just now` / `Nm ago`) is not shown.
- On shorter heights, padding and text size shrink so all three lines still fit. At the minimum height of 40 dp, the right-side buttons show icons only, while button size and circular shape remain the same at every height.
- Tapping an account column opens that account's dashboard.

### Refresh Timing

- Calculations rerun every 15 minutes while at least one Account info widget is placed. Removing all Account info widgets stops periodic updates.
- Updates immediately whenever log collection saves new data for an account.
- Placed widgets redraw on device reboot or app update. **If your emulator or phone blocks auto-start and a loading indicator remains, open FGA once.** Placed widgets also redraw when the app starts.
- Battery-saving restrictions that block FGA's background work may delay the 15-minute refresh.

### When Data Is Out of Date

AP values are calculated purely by advancing time from the last captured snapshot. If you logged into the game without log collection enabled, AP spent or recovered in the meantime is not reflected. For accurate values, check the **data age** (`Just now` / `Nm ago`) on the single-account widget and open the game again with log collection running to capture fresh data.

### No Account Data

If no accounts have been captured, log collection is turned off, or the device runs below Android 10, the left side displays **No account data**, and tapping it opens FGA. The start/stop button on the right remains usable. Capturing an account again or re-enabling log collection restores the account info display.

The widget does not send notifications when AP reaches full.

<a id="widget-settings"></a>

## Widget settings

Customize the appearance of both widgets (the 1x1 service widget and the Account info widget) under **More options → Advanced → Widget settings**. Changes immediately redraw widgets placed on your home screen.

| Setting | Options | Default |
| --- | --- | --- |
| **Background opacity** | 0% to 100% in 10% steps | 0% (opaque, original style) |
| **Theme** | Dark theme / Light theme | Dark theme (original navy) |
| **Display style** | One account (switch with up and down) / All accounts in a row | One account (original style) |
| **Show server (KR/JP) next to the name** | On / Off | Off |
| **Show service start/stop button** | On / Off | On |
| **Show log collection button** | On / Off | On |

- **Show log collection button** now defaults to On. Any previous manual On/Off setting is preserved.
- Turning off **Show service start/stop button** removes the service button from the Account info widget and expands the account area into that space.
- Turning on **Show server (KR/JP) next to the name** adds a small `KR` / `JP` badge next to the account name in both display styles.
- Opacity affects only the card background; text, AP bar, ▲ / ▼ buttons, and start/stop buttons remain fully visible regardless of opacity. On the 1x1 service widget, the button tile itself is the card, so its background becomes transparent while the icon and ON/OFF text remain visible.
- Light theme uses dark text on a white card, with a darker green for the AP full time so it stays legible against white. The progress bar color stays the same, and the start/stop button area also uses light styling.
- Corners stay rounded on Android 10 and later regardless of opacity.

## Starting and Stopping Log Collection from a Widget

Available on Android 10+ devices with **More options → Advanced → Widget settings → Show log collection button** and Log collection enabled. While log collection is active, a key icon (matching **Start log collection** on the main screen) and **ON** appear on a yellow background. Starting or stopping log collection refreshes both widgets.

- **Tapping from ON** stops log collection immediately without opening the FGA screen.
- **Tapping from OFF** starts log collection without opening FGA if current-version consent, a certificate, and VPN permission are all already ready.
- If consent or a permission is missing, FGA opens and runs the same sequence as the main screen's log-collection button: consent → certificate check → VPN permission (FGA VPN mode only) → start. If no certificate exists, it opens the certificate guide screen in Log collection settings.
- Once collection starts successfully, the FGA screen closes automatically. If you cancel consent or VPN permission, or if no certificate exists, the prompted screen stays open.
