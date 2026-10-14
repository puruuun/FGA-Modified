# FGA(mod) detailed guide

[한국어](Modified-Features-KR.md) | [English](Modified-Features.md) | [日本語](Modified-Features-JA.md)

[Back to README](../../README.en.md)

**Testing is limited to the KR (Korean) and JP (Japanese) servers. Behavior on other servers has not been verified.**

## Added and modified features

<a id="card-start"></a>

### 1. Start commands based on face-card count

Enable **Card-count selection mode** and choose a target from the three frontline Servants and a minimum number of face cards (1–5). The script starts from the beginning when the recognized count is **at least** the configured number. NPs and unusable face cards, such as stunned cards, are excluded.

If the condition is not met, the app postpones scripted skills, NPs and automatic use, attacks with face cards, and checks again next turn. You can set a maximum number of waiting turns; 0 means unlimited. Disabling the mode uses the original behavior and preserves your settings.

<a id="dual-conditions"></a>

### 2. Two alternative Servant conditions

You can add a second target Servant with its own minimum card count. Both conditions are checked against the same hand.

| First condition | Second condition | Result |
|---|---|---|
| Met | Not met | Start setting 1 |
| Not met | Met | Start setting 2 |
| Met | Met | Setting 1 takes precedence |
| Not met | Not met | Attack and check again next turn |

Once selected, the setting remains fixed until that quest ends. Conditions are checked again on the next run. Choose **Not used** for the second target to use a single condition.

<a id="separate-settings"></a>

### 3. Separate commands and card-selection settings

Settings 1 and 2 can each have their own **commands, card priority, Servant-priority toggle and order, Brave Chain and card rearrangement**.

The original basic settings are reused for setting 1 and shown only once. Setting 2 appears below them. Both are included when copying, exporting or importing the battle configuration.

<a id="command-timing"></a>

### 4. Shift command waves and turns by the waiting position

The position where the condition is met becomes command wave 1, turn 1. For example, if commands start at actual wave 2, turn 3, command wave 1, turn 2 runs at actual wave 2, turn 4. Command wave 2, turn 1 runs at actual wave 3, turn 1.

After commands start, the card-count condition is no longer checked. Waiting continues even if the wave changes before the condition is met.

<a id="shared-automatic-use"></a>

### 5. Shared skill/NP Spam and card shuffle

**Automatic skill/NP use (Spam)** and **card shuffle** each have one shared configuration. Both selected settings use it.

The original setting 1 values are retained. Old setting 2 Spam and shuffle values are no longer used. Party, server, support selection and automatic enemy targeting are also shared.

<a id="first-turn-shuffle"></a>

### 6. Shuffle and recheck when the first-turn condition is not met

The shuffle settings include **First-turn command start condition not met**.

1. Check the conditions on the first turn of the first wave.
2. If either qualifies, start the corresponding commands immediately.
3. Otherwise, attempt one shuffle using the third Master Skill.
4. Check both conditions again against the new hand.
5. Start on the same turn if one qualifies. Otherwise, attack with the new hand and wait for the next turn.

Setting 1 also wins ties after shuffling. The shuffle itself does not count as a waiting turn. It is attempted at most once on the first actual turn and is not repeated on later turns, later waves or after commands start. It resets for the next quest.

Use this option with card-count mode and a Mystic Code whose third skill shuffles cards. The existing **None / No effective cards / No cards matching NP** options remain available.

<a id="recognition-and-target-loss"></a>

### 7. Settings layout and exception handling

- Mode selection uses an on/off toggle.
- Duplicate basic and setting 1 controls have been removed.
- The start-condition box contains only its toggle, targets, required card counts and waiting limit.
- Selecting the same Servant for both conditions stops the script.
- If one target leaves the frontline, the app can keep waiting for the other. If neither remains, it stops.
- If recognition is unclear, the app checks the same hand up to three times and then stops without attacking. This also applies after a start-condition shuffle.

<a id="support-append"></a>

### 8. Support Append Skill checks

**Check Append Skills** and five `10 / x` selectors appear below the regular max-skill settings under **Support Selection → Preferred Servants**. Every slot marked `10` must be level 10; `x` is ignored. Locked slots do not count as level 10.

The app checks the existing preferred-support and normal-skill conditions first. It then taps the skill-display switch once and reads all five Append Skills from a fresh screenshot. It taps the switch twice to restore normal view before selecting a passing candidate or searching for another. Unconfirmed switching or restoration stops selection.

The feature defaults to off. Five ignored slots skip switching. Settings are included in copies, exports and imports. See the [Append Skill guide (Korean)](Support-Append-Skills-KR.md) for configuration and the provided KR/JP screenshot results.

<a id="support-score"></a>

### 9. Class score +62 and Grand score +16 — policy updated in 0.7.0

Enable the two switches independently under **Support Selection → Preferred Servants**. The field before `/` is Class; the field after it is Grand. Both switches always require every applicable condition. The combination selector has been removed; previous combination settings are ignored.

| Settings | Ordinary Class-only display (`+62`, `+0`, etc.) | Special Class/Grand display |
|---|---|---|
| Class only | Class +62 | Class +62 |
| Grand only | No score restriction | Grand +16 |
| Both enabled | Class +62 | Class +62 and Grand +16 |
| Both disabled | No score restriction | No score restriction |

A confirmed ordinary display ignores the Grand requirement. With both enabled, ordinary `+62` passes and ordinary `+0` fails. With Grand only, confirmed ordinary displays pass regardless of Class. Special `+0/+16` passes with Grand only and fails with both enabled. An uncertain display format never silently disables the Grand requirement.

Score and NP checks run **before Append Skill checks**. Other preferred-support and Append conditions must also pass. Score-only preferred selection works without Servant/CE images. See the [score guide (Korean)](Support-Score-62-KR.md).

<a id="direct-cards"></a>

### 10. Fixed attack order for a specific turn

Open **Command Editor → Attack → Direct cards**, next to **Cards before NP**, to choose all three face cards and NPs in order. Buster, Arts and Quick ignore affinity. For example, `&a4b` stores Arts → frontline position 1 NP → Buster as one command.

Direct attacks bypass automatic card/Servant priority, Brave Chain selection, rearrangement and added NP Spam. Missing or unrecognized required face cards stop the script before any card is tapped. Other turns keep the original behavior. **Available in stable version 0.4.0.** See the [direct-card guide (Korean)](Direct-Cards-KR.md) for syntax and verification limits.

<a id="support-np"></a>

### 11. Support NP level checks — 0.5.1

Open **Support Selection → Preferred Servants → Check support NP level**, then choose a **Minimum NP level** from 1 to 5. A minimum of 3 accepts NP levels 3, 4 and 5. The filter defaults to off; the initial minimum selection is 5.

The app locates `Lv.` inside the candidate's NP name area, then reads its suffix using the existing Tesseract engine. NP name length and changing horizontal positions are supported. Unreadable, uncertain or below-minimum levels reject the candidate. No additional OCR engine or service was added.

**All ordinary filters, including score and NP level → Append Skill checks → selection**. A failed earlier condition skips that candidate's Append view switch entirely. When NP, score and Append conditions are enabled together, all must pass. Settings survive copies, exports and imports. See the [NP guide (Korean)](Support-NP-Level-KR.md).

In 0.5.1, failed reads trigger a search at several `Lv.` sizes and OCR with alternate preprocessing. Recovery requires at least two high-confidence readings of the same level and rejects conflicting levels. This recovers missed smaller text in long NP names and differences caused by screenshot resizing.

All **44 visible NP levels across 16 submitted KR/JP images were correct (100% on this submitted dataset)**, covering real NP levels 1–5. Both normalization orders passed all 88 reads, 440 minimum-level comparisons and 176 negative checks without the number. Eleven partially hidden top rows used annotated origins. These are desktop image checks; Android/FGO automation and accuracy on other screens remain unverified.

<a id="quest-items"></a>

### 12. Automatic Teapot/Storm Pod ON/OFF — 0.7.0

**Play → launcher**, between **AP refill and Limits**, now has **Use Storm Pods / Use Teapots**. Checked enforces ON; unchecked enforces OFF. Choices are remembered per server and shared across battle configurations.

After support and party selection, the app applies the choices before Quest Start. It handles both direct toggles and the item dialog, whose left control is Storm Pod and right control is Teapot, then closes the dialog. Control defaults off. Opt in under **More options → Additional features → Enable Teapot/Storm Pod control** to apply the launcher choices. Off bypasses inspection and changes. Explicit choices saved in earlier versions retain their behavior.

All **9/9 supplied KR/JP game screenshots** and the enlarged ON-button sample passed recognition tests. Live Android/FGO interaction remains unverified. Fast repeat flows that skip party confirmation retain the game's existing settings. See the [full guide](Quest-Items.md).

<a id="bond-tracking"></a>

### 13. Bond gain tracking and MAX stop — 0.9.2

In each script’s editor, **Bond points** sits between Command start conditions and Card shuffle. Base plus bonus is counted once per quest for result characters 1–5 from left to right. The exit screen shows totals and read/missing counts. Choose MAX exceptions in a horizontal row with names above the checkboxes. Checking character 2 ignores its MAX and keeps farming. A MAX on an unchecked character stops at that cleared quest’s result screen before another quest starts.

Party/support prechecks and original party-position mapping have been removed. The user maintains result-column consistency. Settings are per script and included in copying/export/import. Re-enable them in each script after upgrading from 0.8.0; launcher settings are not migrated to the new numbering.

The first read begins 1.5 seconds after detecting a bond result, using a fresh capture. Faster wait settings do not shorten this delay. Level-up popups skip hidden gains/MAX. A confirmed result column reaching at least 10 applies the MAX stop rule; uncertain popups defer to normal MAX next quest. Gems and NEXT are not read. [Settings and validation scope](Bond-Tracking.md).

### 14. App interface

The home screen shows only the installed-version button, for example **Build: 0.10.1**, linking to modified releases. The repository, troubleshooting, Discord and donation buttons are removed. Bond server/help text and the Append Skill maximum-level heading have also been removed. **More options → Additional features**, next to Advanced, contains Enable Teapot/Storm Pod control, which defaults off.

### 15. GitHub update checks

Enable **More options → Advanced → Check for updates** to compare the installed version with the latest stable FGA-Modified GitHub release when the app starts. A newer release with an uploaded ARM APK opens an update prompt with a link to its release page. Prereleases are excluded. Network failures leave the installed app usable. There is no background polling, closed-app notification or automatic APK installation. The release endpoint follows the [GitHub REST API](https://docs.github.com/en/rest/releases/releases#get-the-latest-release).

<a id="chaldea-import"></a>

### 16. Chaldea command import — 0.10.1

Use the link icon immediately to the left of the terminal icon beside either command. Paste a Chaldea battle share URL and choose **Convert and apply**. The dialog warns that the existing command will be replaced. The unchecked-by-default **Ignore enemy targeting information** box below the URL omits all enemy-selection commands, including unsupported special slots, while retaining skills, ally targets, NP and party-change order. Only a successful conversion that passes FGA syntax validation changes the selected command; cancelling or errors preserve it. Party, support and card settings are not imported. Skill/NP/Mystic Code metadata is loaded from Atlas Academy JP and cached. Conversion follows the default fgo-converter rules, including its `,#,` boundaries and normal-card/gimmick limitations. Two- and three-option skills are supported; unsupported actions fail without replacing the command. There are 35 reference-output parity cases, including the supplied real order-change example. Device UI and live FGO execution still need verification. [Detailed guide (Korean)](Chaldea-Import-KR.md).

<a id="installation"></a>

## Installation

- **[Latest modified APK](https://github.com/puruuun/FGA-Modified/releases/latest)**
- **[Bond tracking / MAX stop stable release 0.10.1](https://github.com/puruuun/FGA-Modified/releases/tag/v0.10.1)**
- **[Add to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fapp%2F%257B%2522id%2522%253A%2522io.github.fate_grand_automata.cardcount%2522%252C%2522url%2522%253A%2522https%253A%252F%252Fgithub.com%252Fpuruuun%252FFGA-Modified%2522%252C%2522author%2522%253A%2522puruuun%2522%252C%2522name%2522%253A%2522FGA%2528mod%2529%2522%252C%2522additionalSettings%2522%253A%257B%2522includePrereleases%2522%253Afalse%257D%257D)**
- **[Detailed installation and configuration guide (Korean)](Card-Count-Start-KR.md)**

Install `FGA-mod-0.10.1-arm.apk` from the release. It supports Android 7 or later on ARM devices (arm64-v8a / armeabi-v7a).

| Item | Modified build |
|---|---|
| Version | 0.10.1 (stable release) |
| App name | FGA(mod) |
| Icon | Blue Arts |
| Package | `io.github.fate_grand_automata.cardcount` |

It installs separately from official FGA and upstream CI builds. The package and signing certificate are the same as previous Card Count modified releases, allowing an update installation. To transfer settings from the official app, export them as `.fga` files and import them into this app.

## Updates through Obtainium

1. Install [Obtainium](https://obtainium.imranr.dev/).
2. Tap **Add to Obtainium** above, or open Obtainium's **Add App** page and enter `https://github.com/puruuun/FGA-Modified`. The source is GitHub.
3. Leave prereleases disabled. Each release has one ARM APK, so no extra APK filter is needed.
4. Check for updates on the app page, or configure background checks in Obtainium. New stable releases provide downloadable APKs.

Version 0.10.1 simplifies bond tracking and updates the interface as a stable release and works with the existing Obtainium configuration.

The configuration is also available in [obtainium.json](../../obtainium.json). Updates retain the package and signing certificate and use increasing Android version codes. Automatic installation depends on Android and Obtainium permissions and settings. [Adding apps](https://wiki.obtainium.imranr.dev/ui_overview/), [update behavior](https://wiki.obtainium.imranr.dev/app_tracking/).
