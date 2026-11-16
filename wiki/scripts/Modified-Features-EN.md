# FGA(mod) Detailed Guide

[Back to README](../../README.en.md)

**Testing is limited to the KR (Korean) and JP (Japanese) servers. Behavior on other servers has not been verified.**

Check each feature's dedicated page for detailed settings. This page provides an overall overview.

## Game Area

Under **More options → Additional features → Game area**, you can adjust custom offsets while previewing the red configured area and green actual recognition area. In split screen, align the green border with the edges of the game image. [Details](Game-Area-Preview-EN.md)

## Hakuno Shuffle

Repeats the NP-consuming first skill until the card condition is met, with configurable min/max presses and stop-on-unmet options. Turn off the in-game skill use confirmation. [Details](Hakuno-Shuffle-EN.md)

## CE Auto-Enhance

Turns on auto-select using the game's recommended-selection filters on JP and KR and repeats enhancement. [Details](CE-Auto-Enhance-EN.md)

## Battle Start Conditions

<a id="card-start"></a>

### Command Start Based on Face-Card Count

Enable **Command card count mode** and choose a target Servant from frontline slots 1–3 along with the required face-card count (1–5). Once the hand contains **at least** the configured number of usable cards, the reserved command starts from the beginning. Unusable cards (such as stunned cards) are excluded.

If the condition is not met, skills and NPs are held and the turn advances with normal attacks before rechecking next turn. You can also set a maximum number of skipped turns (0 means unlimited). Turning the toggle off restores the original behavior while keeping your settings. [Details](Card-Count-Start-EN.md)

<a id="turn-limit-retreat"></a>

### Retreat and Restart

Retreats and restarts the quest when the party is wiped out (all servers), and when N is 1 or higher, also retreats upon reaching turn N (KR/JP). Retreats do not count as runs, and you can stop after 3 retreats in a row. Replaces the previous "Enable withdraw" toggle. [Details](Turn-Limit-Retreat-EN.md)

<a id="dual-conditions"></a>

### Two Servant Conditions

You can add a second target Servant and required card count. Both conditions are evaluated against the same hand.

| First condition | Second condition | Execution |
|---|---|---|
| Met | Not met | Setting 1 |
| Not met | Met | Setting 2 |
| Met | Met | Setting 1 takes precedence |
| Not met | Not met | Normal attack and recheck next turn |

Once a setting is chosen, it stays active until that battle ends; the next run rechecks conditions from scratch. Set the second target to **Not used** for single-condition mode. Selecting the same Servant for both conditions prevents starting.

When the second condition is active, you can also add a third condition (checked in `1 → 2 → 3` order, starting the first setting met). The third condition has its own command and card priorities; see [Card-Count Start](Card-Count-Start-EN.md) for full details.

<a id="separate-settings"></a>

### Per-Condition Command and Card Settings

Setting 1 and Setting 2 store separate **Command, Card Priority, Use Servant Priority, Brave Chains, and Rearrange Cards**. The default config and Setting 1 share the same values, and both settings are included in copy, export, and import.

<a id="command-timing"></a>

### Shifting Command Position by Skipped Turns

The point where the condition is met is treated as command wave 1, turn 1. For instance, if commands start at actual wave 2, turn 3, command wave 1, turn 2 executes at actual wave 2, turn 4. Once started, start conditions are not checked again.

<a id="shared-automatic-use"></a>

### Shared Spam Mode and Card Shuffle

**Automatic skill/NP use (Spam mode)**, **Shuffle cards**, Party, Server, Support, and Auto choose target are managed in one shared place rather than split per condition.

<a id="first-turn-shuffle"></a>

### Shuffle When First-Turn Start Condition Is Unmet

Choosing **Shuffle cards → When → Start condition not met on first turn** uses the third Master Skill once on wave 1, turn 1 if conditions are unmet, then rechecks the new hand. If satisfied, commands start on the same turn; otherwise, the turn proceeds with normal attacks and waits for the next turn. This shuffle happens at most once on the first turn of battle and is not counted toward skipped turns.

Used only in card-count mode and requires a Mystic Code whose third skill shuffles cards. If card recognition is unclear, the same hand is checked up to three times before stopping without attacking.

## Support Selection

<a id="auto-friend-request"></a>

### Send Friend Request After Quest

Only the setting title is shown in the UI.

Enabling **Support → Preferred → Friend → Send friend request after quest** in a battle config sends a friend request instead of declining after using a non-friend support. Saved per battle config and off by default. Works in **Preferred** mode, including supports picked via fallback First or Manual selection. Enabling **Friends Only** turns off and disables this option.

If the other player's friend list is full and the request prompt remains on screen, FGA checks again 1 second later and declines.

<a id="support-append"></a>

### Downloading Preferred Servant / CE Images

In 1.8.0 beta builds, **Download images** at the bottom-left of the **Preferred Servants / Preferred CEs** pickers under Battle Config → Support opens the search-and-save dialog directly. Newly saved folders are automatically added to your checked selection while keeping existing checks. Tap **OK** to save into the config; downloaded images remain even if you tap **Cancel**. [Details](Servant-Number-Portraits-EN.md)

### Support Append Skills

Enable **Support → Preferred → Preferred Servants → Check Append Skills** and set Append slots 1–5 to `10 / x`. Only supports where every slot set to `10` is level 10 qualify; locked slots do not count as 10. [Details](Support-Append-Skills-EN.md)

<a id="support-score"></a>

### Support Score (Class +62 / Grand +16)

Toggle **Require Class score +62** and **Require Grand score +16** independently under **Support → Preferred → Preferred Servants**. Every enabled condition must pass. [Details](Support-Score-62-EN.md)

<a id="support-np"></a>

### Support NP Level

Enable **Support → Preferred → Preferred Servants → Check support NP level** and choose a **Minimum NP level** (1–5). Only supports at or above that NP level are selected. [Details](Support-NP-Level-EN.md)

Check order is: Servant → ordinary conditions / scores / NP level → Append Skills → selection. If an earlier check fails, FGA skips switching the display to inspect Append Skills.

### Re-Ascension Distinction

Distinguishes different ascensions of the same Servant using Servant-name + NP-name images created with Support Image Maker. [Details](Ascension-Select-EN.md)

## During Battle

<a id="direct-cards"></a>

### Direct Cards (Fixed Attack Order)

Under **Command → Attack → Direct cards** (next to **Cards before NP**), specify the exact order of three face cards and NPs. On a turn using direct cards, Brave Chain selection, Rearrange Cards, and added NP Spam do not apply.

If a required card is missing, the script stops before tapping. Selecting **Unselected** for a slot or checking **Fill missing and unselected cards by priority** fills empty face-card slots using card priority (checked automatically when any slot is Unselected). [Details](Direct-Cards-EN.md)

<a id="quest-items"></a>

### Automatic Teapot / Storm Pod ON/OFF

Turning on **More options → Battle → Enable Teapot/Storm Pod control** sets Storm Pods and Teapots ON or OFF before Quest Start to match the **Use Storm Pods / Use Teapots** checkboxes in the Battle launcher. Choices are remembered per server. [Details](Quest-Items-EN.md)

<a id="bond-tracking"></a>

### Bond Point Tracking & MAX Stop

Configured under **Battle launcher → Bond points**. Sums bond gains for result characters 1–5 to show on the exit screen, and stops at that quest's result screen if any unignored character hits MAX. You can check **Ignore MAX for checked characters**. [Details](Bond-Tracking-EN.md)

<a id="command-spell-tap"></a>

### Fixed Command Spell Button Tap

When using a Command Spell in battle, instead of template-matching the `"Command Spell"` text, FGA taps the fixed coordinates of the Command Spell diamond to the left of the Master portrait for 16:9 and wide screens. Subsequent steps behave as before, and if no confirmation dialog appears, FGA treats Command Spells as depleted and stops the battle. [Details](Command-Spell-Tap-EN.md)

## Other Features

App language selection moved to **More options → Advanced → App Language**. Under **More options → Advanced → Dark mode**, you can pick **Follow system** (default), **Light**, or **Dark**, taking effect immediately across the app. In-battle overlays always stay dark, and the AP widget follows Widget settings.

View and delete finished battle records under **Log** on the main screen (stores the newest 200 entries). [Details](Battle-Log-EN.md)

<a id="chaldea-import"></a>

### Chaldea Command Import

Paste a Chaldea share URL into **Import from link** on the Command line and tap **Convert and apply** to convert battle actions into commands. Existing commands are replaced only when conversion succeeds. Check **Ignore enemy targeting information** to omit enemy-target commands. [Details](Chaldea-Import-EN.md)

<a id="original-fga-import"></a>

### Import Original FGA Data

Import scripts, support images, or both from the original FGA under **More options → Additional features**. [Details](Original-FGA-Import-EN.md)

### FP Auto-Sell · Auto Skill Level · Weekly Missions · Servant Number Images

[Log Collection](Gains-Tracker-EN.md) · [Friend Manager](Friend-Manager-EN.md) · [FP Auto-Sell](FP-Auto-Sell-EN.md) · [Auto Skill / Append Level](Auto-Skill-Level-EN.md) · [Weekly Mission Clear](Weekly-Mission-Route-EN.md) · [Servant Number Images](Servant-Number-Portraits-EN.md)

### Check for Updates

Enabling **More options → Advanced → Check for updates** compares the installed version against the latest stable release when opening the app. If a newer version exists, tapping **Open release** opens its GitHub page. Prereleases are excluded, and APKs are never installed automatically.

### Two-Column Battle Config Layout

When the screen width is 600 dp or wider (tablets, landscape mode, BlueStacks), the Battle Config edit screen automatically uses a two-column layout: Name/Notes, Command, and summary cards on the left; Support, Command start, and Shuffle cards on the right. Narrower screens keep the single-column layout. There is no toggle; only screen layout changes while automation behavior and saved settings remain identical.

When the window is wider than 1280 dp, content width on Main, Battle Configs, Card Priority, More options, Weekly route settings, License, Fine-tune, Added features fine tune, Preferred Support, and Spam screens is capped at 1280 dp and centered automatically without any setting.

### Wide Screens (21:9, 2560×1080)

Edge button positions were adjusted so FGA works on wide screens such as BlueStacks `1680×720` and `2560×1080`. Screens wider than 21:9 compute coordinates as 21:9 just like the original FGA. Small digits (`+62/+16`, etc.) may fail to read at low or blurry resolutions; unreadable supports are treated as unmet and skipped.

### Post-Battle Close Prompt

When a centered bottom **Close** button appears after battle—such as `"Unlocked!"` notifications on Rank Up Quests—FGA closes it and returns to the quest list to continue. When Storm Pods run out, FGA detects the insufficient-consumables prompt and stops.

<a id="mission-popup-clear"></a>

### Clear Mission Popups Before Repeat

Enabling **More options → Battle → Clear mission popups before Repeat** rapidly taps the top center of the Repeat dialog for a set duration (1–60 s, default 10 s) before pressing Repeat to dismiss stacked mission popups. [Details](Mission-Popup-Clear-EN.md)

### Re-Acquiring Screen Capture After Screen Off

Turning the screen off makes Android end screen capture. Tapping Play afterward opens the screen-capture permission prompt again and starts immediately once allowed (without restarting the service); canceling does not start. If the screen turns off while a script is running, the script pauses and stops when resumed because capture was lost—simply tap Play again. Not applicable to root screenshots.

<a id="installation"></a>

## Installation and Updates

- **[Latest stable APK](https://github.com/puruuun/FGA-Modified/releases/latest)**: Supports Android 7+ ARM devices (`arm64-v8a` / `armeabi-v7a`).
- **Obtainium**: Add `https://github.com/puruuun/FGA-Modified` (GitHub) as the source or import [obtainium.json](../../obtainium.json). Keep prereleases turned off.
- Do not install APKs downloaded from anywhere other than GitHub Releases and Obtainium.

The app is named **FGA(mod)** with package `io.github.fate_grand_automata.cardcount`. It installs separately from the official FGA and can update in place over earlier modified builds. Export settings from the official app as `.fga` files and [import them in FGA(mod)](Original-FGA-Import-EN.md).

The full MIT License text is viewable in **More options → Advanced → License**.

### Weekly Mission Route Settings

Turning off **More options → Battle → Use weekly mission automation** hides the button in the launcher (on by default). Missions appear in their original order without checkboxes on manual missions, while unchecked missions stay grayed out. Empty routes do not execute. Treasure Vault runs last via `Main → Chaldea Gate → Daily Quest list`, verified by the left-hand button and list contents.

### More Options and Account Dashboard

At the top of the **Advanced** tab are **Check for updates**, beta-only **Save support score diagnostics**, and **Weekly quest tracking settings**. At the bottom of the **Battle** tab are **Use weekly mission automation**, **Enable bond tracking**, and **Enable Teapot/Storm Pod control**. Under **Additional features**, items appear in the order: **Search and save support images → Import original FGA data → Export Chaldea file → Log collection → Game area**. Existing values and defaults are preserved.

Tapping **More options → Advanced → Disable battery optimization** (directly below Auto-start service) opens the system battery optimization list; select FGA and set it to **Not optimized / Unrestricted**.

Tapping an account card on the main screen opens that account's read-only dashboard showing Profile, Resources and AP, Storage, Servant collection (sortable by Bond, Cost, Lv), grouped Item inventory, Mystic codes, Recent activity, Support formation, and Grand formation (hiding missing sections). Wide screens display summary and list cards in two independent columns. The export icon left of Settings creates a Chaldea file for the current account, displays the saved filename and import instructions, and can be dismissed with **OK** or launch Chaldea directly via **Open Chaldea** if installed. [Details](Gains-Tracker-EN.md)

## Home-Screen Widgets

You can add a 1×1 widget to start/stop the FGA service and an Account info widget showing AP for captured accounts on your home screen. [Details](Widgets-EN.md)
