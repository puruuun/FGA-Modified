# Log Collection

**Collects responses exchanged between the game and its servers on the KR and JP servers in read-only mode directly on this device, displaying account summaries (AP, Saint Quartz, fruits, prisms, QP, etc.) and per-run battle gains.**

Supports the KR server (`com.netmarble.fgok`) and the official JP server (`com.aniplex.fategrandorder`) on rooted devices running Android 10 or later. Menus are hidden on Android 9 and earlier. For step-by-step first-time setup, see [Log Collection Setup](Log-Collection-Setup-EN.md). The main-screen account cards, **Storage → Download log icon data**, **Additional features → Export Chaldea file**, and **Friend manager** appear only on Android 10+ with **Enable log collection** turned on, appearing or disappearing immediately as you toggle the switch without leaving the screen. Saved data is not deleted. The **Log collection** settings entry (where the switch lives) and **Main → Log** remain visible regardless of the switch. When a Battle script runs while log collection is active, bond numbers come from server responses, while material counts and bond MAX stops still use screen recognition. See the exact conditions below.

## Connection Modes

Log collection sends FGO traffic to FGA's proxy in one of three ways. KR and JP are collected the same way.

- **FGA VPN** (default): FGA creates a VPN on this device. It cannot run alongside another VPN app. This page describes this mode.
- **External proxy app**: an app you already use, such as AdGuard, passes FGO traffic to FGA through its proxy setting. See [External proxy app mode](Capture-AdGuard-EN.md).
- **Root**: root access sends only FGO traffic to FGA. It can run alongside another VPN. See [Root capture mode](Capture-Root-EN.md).

## JP Server (Root)

On Android 10+ with the official FGO JP app installed, **Log collection → JP server (root)** is shown. Configure it with the **Automatically install the JP CA with root access** toggle in this section. Root access is required; if unavailable or denied, the toggle cannot be turned on. KR and JP are configured separately, and each server is collected only when its certificate is ready. When both are ready, one start of log collection collects both together. Root access is needed to install the JP certificate; any of the three connection modes above can be used. You can also use JP alone without the KR game or KR certificate. The JP toggle must be on, and both the official JP app and JP certificate must be ready.

- Below the **Create certificate / How to install** buttons in the **KR server** section, a separate **JP server (root)** section appears. Turning the toggle on creates a JP-specific certificate and automatically installs it in the system CA store. The **KR server** certificate must still be created and installed separately. **JP certificate: installed** indicates the current installation state of the JP certificate.
- Accounts added from JP responses are labeled **JP** in the main cards, Account dashboard, and Friend manager account selector, while KR accounts are labeled **KR**. Accounts are stored separately by `userId`. JP Chaldea export uses the same response structure, with Chaldea's default region detection set to JP.
- If FGO JP was already running when you first installed the certificate, close and relaunch FGO JP. FGA does not close the game for you.
- Rebooting removes the system CA mount, and FGA automatically reinstalls it the next time log collection starts. Even if JP installation fails, KR collection continues.
- Stopping log collection keeps the CA installed. Turning off the toggle under **JP server (root)** unmounts what it can and displays **Restart the device to remove the CA completely**.
- Names, portraits, icons, and categories use Atlas KR public data first. If the displayed account or logged account is **JP**, or if any ID is missing from the KR list, FGA also downloads JP public lists for Servants, CEs, items, and Mystic Codes to fill in missing entries. Names fetched from JP are displayed in the **original Japanese text**. This applies across Account dashboard, Friend manager, Log, and **Download log icon data**.
- JP lists are stored separately inside the app under `capture/names/jp_*_details.json`. Only when a screen needs a list, FGA checks Atlas's public data version at most once per hour and re-downloads lists only for servers whose Atlas data changed (per-list retries are also capped at once per hour). As a result, when new Servants, CEs, or items are added to the game, their names appear as soon as Atlas updates, and since names are looked up by ID when opening a screen, older logs also update to the new names. When the version cannot be read, the existing fallback rule (every 7 days, at most once per day per list) is used. If an item is absent from both server lists, FGA still queries JP items individually by public ID as before, and this supplemental lookup applies to KR accounts as well. Only public lists and images are requested; no account data is transmitted.

## Safety and Privacy

- FGA VPN and root modes send **only the game app's traffic** to FGA's proxy on this device. In external proxy app mode, traffic comes from the apps you choose in the proxy app (AdGuard and others).
- The proxy forwards requests and responses **unmodified** and only reads responses from the game servers (`yfgo-game.netmarble.com`, and `game.fate-go.jp` when the JP option is enabled). All other addresses pass through untouched.
- Account and login token endpoints are neither read nor saved. Request bodies, tokens, and headers are never logged.
- Collected account summaries are stored solely on this device (in app-private storage) and are **never sent anywhere**.
- The certificate private key is never saved; it is used once to sign the CA certificate during generation and discarded. See below for details.

### Is the installed certificate safe?

Installing a CA certificate is normally something to be careful with: whoever holds that CA's private key could make a **fake certificate for any site**, such as a bank or a search site, and read this device's traffic. FGA's certificate is made so that this risk does not arise.

- **The CA private key is thrown away at once.** When you make the certificate, FGA creates a new CA on this device, uses it to sign **a certificate for one game server address only**, and then discards the CA private key without saving it. Nobody, FGA included, can use this CA to make a certificate for any other site. That is why the certificate name says "single-use".
- **Only the public part of the CA certificate is installed on the device.** The public part cannot make new certificates; it is only used to check signatures.
- **The signed certificate works only for the game server address**: `yfgo-game.netmarble.com` for KR and `game.fate-go.jp` for JP.
- **Each device makes its own.** Users do not share one certificate, so someone else's certificate cannot be used against this device.
- **The game server key stays in FGA's app-private storage only.** It is also left out of cloud backups and device transfers.
- After installing the certificate, Android may show a notice such as "Network may be monitored". It appears for every CA certificate a user installs.
- Pressing **Create new certificate** creates a new CA. You can remove the old CA certificate in the Settings app under **Trusted credentials → User**, and do the same when you stop using log collection.

## KR Server Setup and Certificate File

For the full first-time walkthrough, see [Log Collection Setup](Log-Collection-Setup-EN.md). The **KR server** section does not require root access and appears regardless of whether the KR game is installed.

1. Open **More options → Additional features → Log collection**, turn on **Enable log collection**, and tap **Agree and use**.
2. Tap **KR server → Create certificate**. The certificate is saved to **FGA folder → `capture` → `fga-capture-ca.crt`**, and the saved folder and filename are shown on screen. Any existing file with the same name is overwritten; if that filename cannot be written, a fallback copy is saved as `fga-capture-ca-<date>-<time>.crt`. If the FGA folder is unavailable, the system save dialog opens. Files created in `Download` by older versions are left untouched.
3. Under **How to install → Open security settings**, install `fga-capture-ca.crt` as a **CA certificate**; returning to FGA updates the status to **KR certificate: Installed as a CA certificate.**
4. Start collection under **Connection** according to your chosen mode ([FGA VPN / External proxy app / Root](Log-Collection-Setup-EN.md)), then launch the game fresh to populate the account summary card. Before any snapshot is captured, **Start the game while log collection is on** is shown.

If the game rejects the certificate, the **Connection** section shows the rejection count and guidance; make sure you installed it as a CA certificate.

If a response body is too large, cannot be copied due to low memory, or fails to parse, the screen displays the count of unrecorded responses and the last path while collecting. Game traffic still passes through untouched, so gameplay and auto-farming are completely unaffected—only that response is omitted from the log.

## Notice on First Use

**Before using log collection** explains how it works (three connection modes: FGA VPN, external proxy app, and root) and what KR, JP and each mode need. On small screens, the notice scrolls vertically.

- Game updates may stop this feature from working. If you cannot log in, turn off log collection.
- Changes made while log collection was off are not reflected until you log in again with it on.
- External proxy app: while FGA is closed, FGO cannot connect. Start FGA again, or turn off the proxy in the proxy app. (With AdGuard proxy auto-switch, this applies only while collecting.)
- Root: recording starts when FGO opens a new connection after collection is turned on (for example on the title screen).
- JP: the first time collection starts after the device boots, restart the JP game once.
- **You are responsible for using this feature and for the consequences.**

Your agreement is saved. Users who have already agreed are not asked again. If the consent version of the notice text changes later, FGA may ask for agreement again. You can re-read the notice anytime via **Show notice again** in settings; reviewing it alone does not turn log collection on.

## Using from the Main Screen

When log collection is enabled on Android 10+ and either the KR game or the official JP game (with the JP option on) is installed, a **Start log collection** button appears to the left of the service button. If both buttons do not fit on one row, it stacks above the service button.

- **Blue · Start log collection**: Collection is off. If you haven't agreed yet, the notice opens first. If no certificate exists, a brief prompt opens Log collection settings. In FGA VPN mode, if Android VPN permission is required, the connection dialog appears and starts collection once allowed.
- **Yellow · Stop log collection**: Collection is running. Tapping it stops only log collection while keeping the FGA service running. In external proxy app mode the proxy stays open, so FGO keeps its connection.
- If you stop it from the notification, the system ends collection, or (in FGA VPN mode) another VPN takes over, the button immediately turns blue again.

When log collection is enabled (Android 10+) and at least one login snapshot exists, **profile cards** appear near the top of the main screen. Each card shows the Master name, a small **KR / JP** server badge, current/max AP, and full recovery time (or **Full** if already full). Cards appear in a fixed-width horizontal row below the build/accessibility status line, with the current (last-played) account first; swipe horizontally if they overflow. AP updates every minute while viewing the screen. Tapping a card opens that account's dashboard.

When opening the Battle launcher while log collection is running, the **Track bond point gains** option is hidden, while **Stop when bond MAX is confirmed** and the ignored-position checkboxes (when stop is on) remain visible. The stored value of the hidden tracking preference is preserved.

## Using Multiple Accounts

You do not need to pick an account manually. FGA automatically adds accounts by `userId` in login responses and files subsequent responses under their matching account ID. Logging into another account moves the **last-played account** to the front of the main screen's horizontal card list. Responses whose account ID differs or is mixed are never merged across accounts.

Under **More options → Additional features → Log collection**, per-account cards appear ordered by most recently played. Each card shows the account name, Master level, server (`KR` / `JP`), a **Last played** tag on the active account, snapshot time, and last update time. Current resources and AP can be inspected in the Account dashboard.

- **Rename**: Uses the in-game Master name initially. You can set any custom name; leaving it blank restores the Master name. The main card displays this name too.
- **Delete**: Removes only the captured data and Chaldea export files for the account shown in the confirmation prompt. Deleting the last-played account promotes the next most recent remaining account. Logging back into that account later recreates it.
- Per-account state and names are stored inside the app under `capture/accounts/<userId>/`. Legacy single-account data is migrated automatically on first launch. A new account without a login snapshot populates its summary on the next login.

Battle logs record the name of the account that ran them. A run belongs to the account active at start, or if none is active yet, the first account seen in a response; responses from another account arriving mid-run do not mix into that run's gains. Saved log names persist even if you later rename or delete the account.

**Running multiple game instances simultaneously (Dual Apps, app cloners, etc.) is not supported.** FGA collects responses from the official KR game (`com.netmarble.fgok`) and the official JP game (`com.aniplex.fategrandorder`) when JP is enabled; BetterFGO is excluded.

## Using with the FGA Service

If log collection is enabled and either the KR or JP path is ready when you tap **Start Service** on the main screen, FGA starts the service and then starts log collection too. In FGA VPN mode, if VPN permission has not been granted yet, the Android connection prompt opens on the spot and collection starts once approved. If no certificate exists, a brief notice is shown and the FGA service still starts normally.

When **Stop log collection when the service stops** (off by default; existing saved choices are preserved) is enabled, stopping the FGA service from the main screen, notification, or system shutdown also stops log collection—including collection you started manually. When disabled, log collection keeps running even after the FGA service stops.

When starting a script while log collection is off, if you are on the KR server or on the official JP server with its JP path ready—and log collection is enabled with a certificate (and, in FGA VPN mode, VPN permission) ready—FGA restarts log collection automatically. If consent or a certificate is missing, only a notice is shown and the script still runs. **Ending a script does not stop log collection.** Per-run gain tracking still spans from script start to script end.

## Material Tracking and Bond Stop in Battle

At Battle script start, **if the server is KR and log collection is running**, that run gets its bond **numbers** from log collection responses. If log collection is auto-starting at script launch, FGA waits up to 3 seconds before deciding. This decision does not change mid-run: turning collection on mid-run keeps using screen recognition, and turning it off mid-run keeps the response mode. JP runs and runs started without collection keep the existing behavior.

- **Materials**: Regardless of log collection, selected materials are counted via template matching on the drop screen. Material run limits and automatic deduction continue using screen-recognition results. Battle logs may show both screen-counted materials and log-collection gains together.
- **Bond**: Bond gain numbers come from log collection responses. Check per-Servant points under **Gains (log collection)** in the log rather than per-character bond totals on the exit screen. If only **Track bond point gains** is enabled (without MAX stop), OCR and its read wait are skipped, and result screens advance in the same number of taps as when bond tracking is off.
- **Bond MAX stop**: When bond tracking and MAX stop are on, even during log collection FGA still **reads the result screen (OCR)** just as when collection is off. If a per-character MAX badge (confirmed on two consecutive frames) or a bond level-up popup to Lv.10+ appears on an unignored slot, it stops with the existing MAX message. Screen-advance timing and waits are unchanged. Log collection responses are never used to decide MAX stops—only for bond numbers. Completed quests still count toward runs completed.
- **Missing responses**: On the first result screen, FGA waits up to 3 seconds for that battle's response. If no response arrives or required fields are missing, bond points for that battle are skipped and the script **continues without stopping**. Material counts and bond MAX stops still work regardless of responses.

CE drop checks, screenshots, and all other screen detections, taps, and waits follow your existing settings. For bond setup and screen-reading details, see [Bond Tracking & MAX Stop](Bond-Tracking-EN.md).

## Additional Features and Chaldea File Export

Under **More options → Additional features**, you will find **Search and save support images**, **Import original FGA data**, **Export Chaldea file**, **Log collection**, **Friend manager**, and **Game area (experimental)**.

**Export Chaldea file** appears only on Android 10+ when log collection is enabled and at least one captured account exists. With one account, it exports immediately; with multiple accounts, pick the account to export. You can also export an account from its card in Log collection settings. Check the account card's last-updated timestamp before exporting. The exported file holds only the account tables Chaldea reads, leaving out records Chaldea does not use, such as payment and gacha-time logs.

1. If there is no login snapshot yet, **Start the game while log collection is on** is shown. Launch the game so the account card populates, then retry.
2. The account state is saved as `FGA folder/capture/chaldea-account-<userId>-<yyyyMMdd-HHmm>.json`. Existing files of the same name are overwritten; if unavailable, a filename with seconds appended is tried, and if that also fails or the FGA folder is inaccessible, the system save dialog opens. The dialog shows the actual folder and filename saved. Because the file contains account data, **only the latest file per account is kept**. Latest files of other accounts are preserved; legacy `chaldea-kr-<userId>-<yyyyMMdd-HHmm>.json` files are also cleaned up per account, and old ID-less `chaldea-kr-<yyyyMMdd-HHmm>.json` files created earlier by FGA are removed on export.
3. After saving, **Saved. Select this file in Chaldea.** displays the filename and import instructions. **OK** closes the dialog; **Open Chaldea** launches Chaldea and closes the dialog (it never opens Chaldea automatically). Both Play/GitHub and F-Droid builds are supported.
4. If Chaldea is not installed, installation guidance is shown instead of **Open Chaldea**. Because the file is already saved, install Chaldea and select the file under **Import → HTTPS sniffing → From file**. If saving or cleaning up old files fails, check the error prompt and try again.

**Only data received while log collection was running is reflected in the file.** After playing without log collection, restart the game with log collection on to refresh the snapshot before exporting. FGA never sends account data externally; you import the file directly inside Chaldea.

To browse friends' support Servants in one view, see [Friend Manager](Friend-Manager-EN.md).

## Viewing Gains in the Battle Log

While log collection runs, battle results from scripts are aggregated—whether you started collection manually or let the script start it. When the script ends, **Tracked** appears on the record in **Main → Log**, and **Gains (log collection)** is added below the detail summary.

- **Items**: Aggregates server-reported drops sorted from highest gain downward, including Craft Essences and other drops. Ending holdings are also shown when an account snapshot is available.
- **QP**: Sums quest-clear QP, QP drops, and first-clear QP.
- **Master EXP & Bond**: Shows before/after deltas. Bond is summed per Servant only for Servants in your party (excluding supports and NPCs), hiding zero-gain rows. Any Servant whose bond level newly reached its cap (10 + limit breaks) in that battle displays **MAX** beside its points—across per-battle rows, overall totals, and the log view (older saved logs display without the MAX badge).

  On KR and official JP, even if a formation response omits or reports a Servant ID ≤ 0, FGA resolves the Servant using owned Servants, Second Archive, or pre-battle Servant metadata in the result response so bond is still recorded. If not even one party Servant can be identified, all bond changes present in the response are recorded.

- **Fruits used**: Calculates `start holdings − end holdings + gains` for each of Golden, Silver, Bronzed Cobalt, and Bronze Fruits. Shows **Unknown** if either snapshot is missing.
- **Show battles**: Collapsed by default. Expanding shows each battle's timestamp, quest ID, drops, QP, EXP, and bond.

If you turn log collection on mid-script, or it stops mid-script (for example, in FGA VPN mode, because you switched to another VPN or revoked permission), gains collected so far are preserved. If fewer battles were captured than completed runs, a notice (`%1$s battles were not captured`) is displayed; gains may be partial, and changes after stopping are not included. Ending holdings and fruit usage reflect the last captured point. Even without an account snapshot, drops, QP, EXP, and bond visible in battle responses are recorded; missing values show **Unknown** or are omitted.

Runs that never used log collection and legacy logs show only the standard summary. Paused status checks and 0-run exits do not save a battle log. Battles played manually outside scripts appear under **Changes outside scripts** below. Retention limits for battle logs and outside-script logs can be set to **50 / 100 / 200** under **Log → Filter icon → Records to keep (per type)** (default and maximum 200 each).

Item, Servant, and CE names come from **Atlas Academy's public KR list** first. If the logged account is JP or an ID is missing from KR, names and icons are supplemented from the JP public list in their original Japanese text. When first needed, IDs, names, and item icon URLs are downloaded and cached under `capture/names/` inside the app. Whenever a screen needs a list, FGA checks the Atlas data version at most once per hour and re-downloads only changed server lists, so new Servants, CEs, and items appear even in older logs as soon as Atlas updates. When the version cannot be read, KR retries unknown IDs at most once per day per list, and JP refreshes files older than 7 days at most once per day per file. If downloading fails, placeholder IDs such as `Item #6510`, `Servant #100100`, or `CE #9400010` are shown and logs still save normally.

Gains and uses appear as compact tiles grouped under **Gains (+)** and **Uses (−)**. Items show icon, short name, and count; QP, Mana prisms, Rare prisms, Saint Quartz, fruits, and Saint Quartz Fragments show only icon and count, with Saint Quartz also displaying a small free/paid breakdown. All tiles share a uniform height with the icon on the left and text on the right; ending holdings or free/paid counts sit on the second text line rather than below the icon. Tapping or long-pressing a tile does not open a popup, while screen readers receive the name and count. The icon URL list tracks initial download and failure retries separately from name lists. Missing icons shown on screen are fetched from the public `static.atlasacademy.io` HTTPS server and stored permanently under `capture/icons/<item ID>.png` inside the app without relying on Coil's disk cache or bundling into the APK. If offline or download fails, the item name is shown and retried after at least one hour. Only public lists and images are downloaded; account/game data is never uploaded.

## Account Dashboard

Tapping an account card on the main screen opens the Account dashboard **for that account**. The top-right **Settings icon** opens Log collection settings. The **Export icon** to its left exports a Chaldea file for the viewed account and shows the save dialog above (Android 10+). For accounts that have received a friend list, a **Friends icon** appears to the left of Settings (right of Export), opening Friend manager pre-selected to that account. You can also reach settings via **More options → Additional features → Log collection**.

The dashboard only reads stored capture state on the device and refreshes when new responses arrive. Missing items are hidden rather than zero-filled. The content is split into **sections**, and one section is shown at a time. Sections are listed as **Overview → Servants → Items → Formations → Collection and summons → Present box**; a section with no data is left out of the list (Formations always appears because the support formation card always did). The **Servants** and **Present box** entries show the servant count and the present total next to the name.

- **840 dp wide and above**: a pane about 240 dp wide on the left holds the account header on top and the section list below it. The selected section is shown on the right and scrolls on its own.
- **Below 840 dp**: the account header sits on top, then a horizontally scrollable row of section tabs, then the selected section. Tabs are used instead of a list-first flow so that AP is visible without an extra tap.
- The dashboard opens on **Overview** each time. The chosen section is kept through rotation and app recreation, and falls back to Overview if the chosen section loses its data. Maximum content width is 1280 dp, and long inner lists scroll within a capped height.

The **account header** holds the custom name and in-game name, the server badge (**KR / JP**), `Lv N`, the friend code with a copy button, and **N minutes ago** since the last update (relative to the last update time and recalculated every minute; the exact time is in the accessibility description). Birthday and profile message are not shown. The Export, Friends and Settings icons stay in the top bar.

### Overview

Top to bottom; an item without data is hidden.

- **Headline numbers**: AP is shown large as `current / max` with a thin progress bar and **HH:mm full** (or **Full** when already full), followed by carry-over AP when the game reports it. Saint Quartz shows the total large with free and paid below it, and QP shows the full number. Digits use tabular figures so they line up.
- **Fruit**: the four fruit types in the order golden, silver, bronzed cobalt, bronze, as icon and count on one row.
- **Resources**: Mana prisms, Rare prisms, Saint Quartz Fragments, Bronzed Cobalt Saplings, summon tickets, Storm Pods and the Teapot total, as icon, name and right-aligned count. Summon tickets and Storm Pods show 0 when absent if the item table exists.
- **Storage**: Servants and other (EXP, Fou, etc.) share one bar in two colours with a legend line, and CEs have their own bar, both compared against the standard storage cap. Command codes and Second storage follow as label-over-number pairs. Without Atlas category lists, only totals are shown. Storage expansion offsets are not added to the cap.
- **Recent records**: one latest battle record and one latest outside-script change of this account, each with its time or period, name and a short gains line, and **View all** opens the log. The group is hidden when there is neither.
- **Account**: Friend points, EXP, party cost limit, friends (`143 / 146` once a friend list was received, otherwise the limit only), account creation date, snapshot time and upcoming Command Spell recovery in a two-column grid.

At 840 dp and wider, fruit and resources form the first column and storage, recent records and account the second; narrower screens use one column.

### Servants

- **Servant collection**: Shows owned Servants' name, class, rarity, cost, portrait, bond, NP level, highest skill, and highest level. Sort options are **Bond / Cost / Lv** (Bond default), all descending, with Bond comparing points as well; ties break by remaining fields in `Bond → points → Cost → highest Lv` order and finally by ID. Costs are ★5=16, ★4=12, ★3=7, ★2=4, ★1=3, ★0=4, and Mash=0. Class filters are a horizontally scrolling chip row with **All** first, then the classes in the game's order (Saber, Archer, Lancer, …; Beast classes and unknown ones last); identical display names share one chip, all Beast variants are combined under **Beast**, and U-Olga Marie's Unbeast class is shown separately as **Unbeast**. Unknown classes show their raw name. The top line shows the servant total and the per-rarity counts (for example `316 servants · ★5 41 · ★4 78`) with the sort chips. On wide screens the list is a table with face, name (class · ★ below it), Lv `current/max`, Bond, NP, Skills `a/b/c` and Appends `a/b/c/d/e` columns; on narrow screens each Servant takes three lines (line 1: face, name, class · ★; line 2: `Lv · Bond · NP`; line 3: `Skills · Appends`). Skills and Appends at level 10 are shown in gold, and locked Appends as a muted `–`. There is no 5-row cap and no **Show all**: every matching Servant scrolls inside the list (the sort chips, class chips and table header stay in place). There is no name search.

### Items

- **Item inventory**: Splits positive-count items into **Enhancement materials** (including Pure Prisms and Beast's Footprints), **Gems**, **Pieces and monuments**, **Grails and Lore**, **Event items**, **Servant coins**, and **Other**. **Servant coins** lists Servants with at least 1 coin sorted by highest count, pulling names and icons from the Atlas item list. Event items, Servant coins, and Other start collapsed, and currencies shown above are not duplicated. Summon Tickets and Storm Pods always appear under the Overview resources (even at 0), and all owned Teapots combine into a single **Teapots** row there, so these are omitted from Item inventory.
- **Search by name**: Type part of a name (case is ignored) in the field at the top. Groups with a matching item open automatically and groups without one are hidden. Clearing the field with the **×** puts the groups back in the open or closed state they had before the search. The gem and piece tables highlight the matching cells.
- **Gem and piece tables**: Rows are Gem, Magic Gem and Secret Gem (Piece and Monument in the second table), columns are Saber, Archer, Lancer, Rider, Caster, Assassin and Berserker. An eighth column appears only when the account holds at least one such item, and its header is the Atlas item name. A cell the account does not hold shows a muted "–", and a count **below 10** is drawn in red. Narrow screens use short class labels such as "Sab" and "Arc"; screen readers still read the full name.
- **Lists such as Enhancement materials**: Icon, name and a right-aligned count, in three columns on wide screens and one on narrow ones. Enhancement materials show the first 12 and then **Show more (N)**. Event items, Servant coins and Other start collapsed and show the first 48 when opened. Every group title carries its item count (for example **Enhancement materials 96 items**).

### Formations

- **Support formation**: Read-only view of your own support formations. Includes **Regular / Event** toggles (based on the game's fixed formation list) and **Deck 1, 2, …** chips. Each class row shows a 40 dp Servant portrait on the left, name and **Grand** badge (for Grand Servants only) in the middle with `Lv · NP · Class` underneath. The equipped CE appears on the right without an icon on a single line like `Name · Lv100 · Max` (**Max** appears only at max limit break); long CE names truncate before level and Max. Displays **No CE** if empty. Skills and Appends are not shown. If the formation index list cannot be read, all decks are listed under Regular.
- **Grand formation**: Displays compact Servant rows in Grand seat order. CEs 1 and 3 each occupy a single line, followed below by a small **Bond: NP charge / Base effect / None** label. Hidden when no Grand formation data exists (such as on KR).
- **Mystic codes**: Lists Mystic code names, icons, levels, and equipped state, sorted by level.

### Collection and summons

- **Command code collection**: Displays owned (`status == 2`) vs. total Atlas Command Codes (**Command codes: N / M owned**, or **Command codes: N owned** if Atlas data is unavailable). The **+ Owned (N)** group starts collapsed and expands a grid of owned Command Code icons and names. Hidden if the Command Code collection table is absent.
- **Class score**: Shows per-board node/lock unlock progress from the last login snapshot (**Squares a / b · Locks c / d**, or **Squares a · Locks c** without Atlas summary; blank spaces are excluded and locks are counted separately) plus **Complete** badges, including unstarted boards. Shows the first 3 boards initially, with **Show all (N)** / **Collapse** for the rest. Hidden if the Class score table is absent.
- **Summon history**: Displays total **Friend point summons: N pulls** from the last login snapshot and **Other summons** (top 10 shown initially, with **Show all** / **Collapse**), plus a collapsible **+ Recent summon log** group showing pulls by date. Hidden if all summon history tables are absent.

### Present box

- **Present box**: Displays the count of `userPresentBox` rows in the login snapshot as **Present box (N)**, along with a notice that gifts received after the last login appear after the next login. Identical item types/IDs are summed and tiled with items (`giftType 2`) first, followed by Embers, Fou, Servants, CEs, Command Codes, etc., sorted by highest count (ties by ID); gift messages are omitted. Shows the first 12 types initially, with **Show all (N)** / **Collapse** for the rest. If the table exists but is empty, **No gifts** is shown; if absent entirely, the card is hidden.

Formation cards do not show a separate timestamp; check the account header for last-update time. If support formation data is absent or unreadable, **No formation data** is shown. The dashboard is strictly read-only and has no effect on automation OCR, taps, or capture.

Public Atlas KR lists for items, Servants, CEs, and Mystic Codes are cached per file, with per-file daily retry limits on failure. When the viewed account is JP or an ID is missing from KR, separate JP lists supplement names, portraits, icons, and categories. Class score and summon summaries extract only needed names, periods, and node IDs from the account's region (`KR` or `JP`) into compact summary files. While a script is running, the dashboard does not download Atlas lists and reads only stored local files. Unknown Servants display as `#ID`. Portraits and Mystic Code icons are downloaded on demand from public HTTPS asset servers and stored permanently; they remain even after **Delete log collection data**. No account data is transmitted.

You can also pre-prepare dashboard and log icons via **More options → Storage → Download log icon data** (shown only on Android 10+ with log collection enabled). This is separate from support image extraction. It downloads regular Servant + Mash portraits, skill/NP enhancement materials, currencies/fruits, and Mystic Codes, excluding event items. The initial download is roughly 15 MB (Wi-Fi recommended). Downloads continue even if you leave the screen, and tapping again mid-download does not duplicate work. After an app restart, tapping again downloads only missing files. Only progress or results appear under the item. See [Progress and Retry Guide](Default-Support-Images-EN.md).

## Changes Outside Scripts

While log collection is active and no script is running, account changes are grouped into **one row per period** under **Main → Log**. This covers skill leveling, eating fruits outside quests, claiming presents, shop exchanges, summoning, and quests played by hand. Responses during an active script are handled by per-run battle logs and are not duplicated here.

From the first resource change, an **In progress** entry appears at the top of the list. Its totals, sources, and end timestamp update live with every additional change. The open period is finalized (without creating duplicates) in any of these cases:

- Starting the next script
- Stopping log collection via button, notification, revoked permission, or system stop
- Stopping the FGA service (even when configured to keep log collection running after the service stops)
- Receiving a response for another account (closes the previous account's period without mixing accounts)

Periods with no changes are not saved. **Differences across login (`login/top`) are not recorded** because changes made while log collection was off or on another device could mix in; instead, login updates the baseline snapshot and subsequent responses calculate changes from there.

List cards use two lines: the first line shows the title, a small account name next to it, and the time on the right (end timestamp for battles, start–end range for outside-script changes); the second line shows runs, average turns, Tracked, and stop reason for battles, or the number of changed item/card types and Saint Quartz delta for outside-script changes. Battle cards use gray, while outside-script cards use a distinct color and icon; both share the same border when selected. Tapping opens:

- **Totals**: Sums net changes across items, Servants, CEs, Embers, Fou, and Command Codes during the period, split into **Gains (+)** and **Uses (−)** sorted by magnitude. QP, Mana prisms, Rare prisms, and free/paid Saint Quartz appear as signed numbers.
- **Fruits used**: Sums decreases in Golden, Silver, Bronzed Cobalt, and Bronze Fruits across each response. Receiving fruits later does not erase fruits used earlier.
- **By source**: Groups changes by Gift box, Shop, Summon, Lottery, Enhancement, AP recovery, Quests played by hand, Missions, and Login. Friend points you receive at login (from other players borrowing your support servants) are recorded under **Login**. Collapsed by default; expanding shows the same resource tile layout. Unclassified responses show **Other** and the API path without query strings.

For Servants, CEs, Embers, and Fou, FGA combines regular inventory and Second Storage counts per template ID. Spending 12 Embers on enhancement logs `−12`; selling 3 copies of a CE in the shop logs `−3`. Moving cards between storages or changing level, ascension, or lock state does not alter counts. Command Codes compare counts per ID as well.

Totals and per-source details share the same icon tiles. **Zero-change entries** (unchanged currencies or unused fruits) **are hidden**. Fruit gains and actual uses are tracked separately so uses aren't masked by gains in net totals.

Card names and portraits use Atlas KR's public `basic_svt` (~2 MB, all card types) and `basic_command_code` lists. For JP accounts or Servants/CEs missing from KR, JP's `basic_servant` and `basic_equip` lists fill the gaps. Embers append the class name in parentheses after the shared name, grouping all Beast variants as **Beast** and the ALL class as **All**; unknown entries show `#ID`. Lists are stored inside the app under `capture/names/` and checked against Atlas data versions when needed by a screen (redownloading only if changed; if the version cannot be read, retrying missing or ≥7-day-old files at most once per day per file). Card icons are downloaded on demand—Servants, CEs, Embers, and Fou to `capture/icons/servants/`, Command Codes to `capture/icons/commandCodes/`—and remain even after **Delete log collection data**.

Sources are inferred from API paths. Confirmed KR paths are `present/receive` (Gift box) and `battle/result` (Quests played by hand). Other prefixes (`shop/` excluding `shop/Verify`, `gacha/`, `boxGacha/`, `card/`, `svt*`, `item/recover`, `eventMission/`, `mission/`) are heuristic classifications that may vary with game updates or actual responses. Enhancement's `svt*` matches not only `svt/` but prefix-sharing paths like `svtCombine/` and `svtSkill/`. `shop/Verify` and unknown paths stay under Other. Web-store payment responses (`externalPayment/reflect`) display as **External payment** and count under Other in filters.

Open periods are stored inside the app under `capture/accounts/<userId>/outside-period.json` and resume across process restarts on the next initialization. Finalized records are stored in `outside-log.json` up to the configured retention limit, sorted by end time alongside battle logs below any in-progress entry.

Filtering, retention limits (**50 / 100 / 200**, default 200), and deletion work the same way as described in [Battle Log](Battle-Log-EN.md).

- **Gained resource filter**: Distinguishes items and cards that share a numeric ID; an outside-script record appears if any source gained that resource, even when heavier usage elsewhere makes the net total negative.
- **Deleting an in-progress entry**: Discards the open period so subsequent changes start a fresh period.

Deleting an account discards its open period, and **Delete log collection data** discards all open periods. Already finalized battle logs and outside-script logs remain and must be deleted separately in **Main → Log**.

## Limitations

- FGA VPN mode: Android allows only **one active VPN** at a time. Enabling another VPN app stops log collection and displays the stop reason. To run alongside apps like AdGuard or WireGuard, switch Connection mode to **External proxy app** ([External Proxy App Mode](Capture-AdGuard-EN.md)) or, on rooted devices, **Root** ([Root Capture Mode](Capture-Root-EN.md)).
- While log collection is running, a **Stop** button (**Turn reading off** in external proxy app and root modes) appears in the persistent notification.
- In all three modes FGA sends and receives FGO's traffic, so the game's data usage is counted under FGA. If mobile data is blocked for FGA in the phone settings, the game connects on Wi-Fi but not on cellular (LTE/5G).
- Only responses received while log collection was running are reflected. Changes occurring while the game wasn't tracked update via the next login snapshot.

## Deleting Data and Certificates

- Tapping and confirming **Delete log collection data** removes captured data and open periods for **all accounts**, as well as Chaldea files FGA created inside the FGA folder's `capture` subfolder. Legacy files saved under `Download/FGA/` are untouched—delete them manually if needed. **Delete** on an account card deletes only that account. Saved battle logs and outside-script logs are deleted separately in **Main → Log**. Public name lists and cached icons are kept.
- If a certificate already exists, **Save certificate file** re-exports the same certificate to `capture`. To generate a new one, tap **Create new certificate** separately, which first warns that any previously installed certificate must be replaced. If saving fails, the existing certificate stays intact. After creating a new certificate, remove the old one from Security settings and install the new one.
- **Create new certificate** is available only after stopping log collection, whereas an existing certificate can be re-exported while collection runs. Both buttons lock during saving, and canceling the system save dialog leaves the existing certificate and key unchanged.
- Saving via the system save dialog displays the parent/selected folder name reported by the storage provider along with the actual filename (or **Selected location/filename** if the folder name cannot be resolved).
- Chaldea exports saved via the system save dialog are also tracked per account: after saving a new file, the previous one is removed, and account/full deletions clean them up too. Files already deleted via a file manager are dropped from tracking without error on the next export or deletion. If an old file remains and cannot be deleted (missing permission or provider refusal), **Could not delete the exported Chaldea files** is shown and tracking is kept. The same message appears if FGA folder permissions were revoked while internal app data is cleared; re-grant permission and retry, or delete remaining files manually. Canceling the save dialog keeps the existing Chaldea file.
- To remove the certificate completely, delete `FGA-Modified log collection` (or `FGA-Modified gains tracker` for legacy certs) under **Settings → Security → Encryption & credentials → User credentials** (or the User tab under Trusted credentials). Optionally delete `fga-capture-ca.crt` in `capture` (or `Download/fga-capture-ca.cer` for legacy builds).
