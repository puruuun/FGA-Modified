# FGA(mod) — Card-count conditions, Append Skills, score checks and fixed attack order

[한국어](README.md) | [English](README.en.md)

This is a **modified version of [Fate/Grand Automata (FGA)](https://github.com/Fate-Grand-Automata/FGA)**. It extends the original Android battle automation and card recognition to start scripted commands when a Servant has enough face cards. It also adds support Append Skill and score checks, and commands that specify an exact three-card attack order.

This project is maintained separately from the upstream team's official distribution. For the original FGA and its documentation, see:

- **Upstream repository:** https://github.com/Fate-Grand-Automata/FGA
- **Official website:** https://fate-grand-automata.github.io/
- **Official wiki:** https://github.com/Fate-Grand-Automata/FGA/wiki
- **Source base:** official FGA v3059, commit `e138a15273dee9cac342833ee2d8aee93d8ae700`

## Download

- **[Latest modified APK](https://github.com/puruuun/FGA-Modified/releases/latest)**
- **[NP level stable release 0.5.0](https://github.com/puruuun/FGA-Modified/releases/tag/v0.5.0)**
- **[Add to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fapp%2F%257B%2522id%2522%253A%2522io.github.fate_grand_automata.cardcount%2522%252C%2522url%2522%253A%2522https%253A%252F%252Fgithub.com%252Fpuruuun%252FFGA-Modified%2522%252C%2522author%2522%253A%2522puruuun%2522%252C%2522name%2522%253A%2522FGA%2528mod%2529%2522%252C%2522additionalSettings%2522%253A%257B%2522includePrereleases%2522%253Afalse%257D%257D)**
- **[Detailed installation and configuration guide (Korean)](wiki/scripts/Card-Count-Start-KR.md)**

Install `FGA-mod-0.5.0-arm.apk` from the release. It supports Android 7 or later on ARM devices (arm64-v8a / armeabi-v7a).

| Item | Modified build |
|---|---|
| Version | 0.5.0 (stable release) |
| App name | FGA(mod) |
| Icon | Blue Arts |
| Package | `io.github.fate_grand_automata.cardcount` |

It installs separately from official FGA and upstream CI builds. The package and signing certificate are the same as previous Card Count modified releases, allowing an update installation. To transfer settings from the official app, export them as `.fga` files and import them into this app.

## Added and modified features

### 1. Start commands based on face-card count

Enable **Card-count selection mode** and choose a target from the three frontline Servants and a minimum number of face cards (1–5). The script starts from the beginning when the recognized count is **at least** the configured number. NPs and unusable face cards, such as stunned cards, are excluded.

If the condition is not met, the app postpones scripted skills, NPs and automatic use, attacks with face cards, and checks again next turn. You can set a maximum number of waiting turns; 0 means unlimited. Disabling the mode uses the original behavior and preserves your settings.

### 2. Two alternative Servant conditions

You can add a second target Servant with its own minimum card count. Both conditions are checked against the same hand.

| First condition | Second condition | Result |
|---|---|---|
| Met | Not met | Start setting 1 |
| Not met | Met | Start setting 2 |
| Met | Met | Setting 1 takes precedence |
| Not met | Not met | Attack and check again next turn |

Once selected, the setting remains fixed until that quest ends. Conditions are checked again on the next run. Choose **Not used** for the second target to use a single condition.

### 3. Separate commands and card-selection settings

Settings 1 and 2 can each have their own **commands, card priority, Servant-priority toggle and order, Brave Chain and card rearrangement**.

The original basic settings are reused for setting 1 and shown only once. Setting 2 appears below them. Both are included when copying, exporting or importing the battle configuration.

### 4. Shift command waves and turns by the waiting position

The position where the condition is met becomes command wave 1, turn 1. For example, if commands start at actual wave 2, turn 3, command wave 1, turn 2 runs at actual wave 2, turn 4. Command wave 2, turn 1 runs at actual wave 3, turn 1.

After commands start, the card-count condition is no longer checked. Waiting continues even if the wave changes before the condition is met.

### 5. Shared skill/NP Spam and card shuffle

**Automatic skill/NP use (Spam)** and **card shuffle** each have one shared configuration. Both selected settings use it.

The original setting 1 values are retained. Old setting 2 Spam and shuffle values are no longer used. Party, server, support selection and automatic enemy targeting are also shared.

### 6. Shuffle and recheck when the first-turn condition is not met

The shuffle settings include **First-turn command start condition not met**.

1. Check the conditions on the first turn of the first wave.
2. If either qualifies, start the corresponding commands immediately.
3. Otherwise, attempt one shuffle using the third Master Skill.
4. Check both conditions again against the new hand.
5. Start on the same turn if one qualifies. Otherwise, attack with the new hand and wait for the next turn.

Setting 1 also wins ties after shuffling. The shuffle itself does not count as a waiting turn. It is attempted at most once on the first actual turn and is not repeated on later turns, later waves or after commands start. It resets for the next quest.

Use this option with card-count mode and a Mystic Code whose third skill shuffles cards. The existing **None / No effective cards / No cards matching NP** options remain available.

### 7. Settings layout and exception handling

- Mode selection uses an on/off toggle.
- Duplicate basic and setting 1 controls have been removed.
- The start-condition box contains only its toggle, targets, required card counts and waiting limit.
- Selecting the same Servant for both conditions stops the script.
- If one target leaves the frontline, the app can keep waiting for the other. If neither remains, it stops.
- If recognition is unclear, the app checks the same hand up to three times and then stops without attacking. This also applies after a start-condition shuffle.

### 8. Support Append Skill checks

**Check Append Skills** and five `10 / x` selectors appear below the regular max-skill settings under **Support Selection → Preferred Servants**. Every slot marked `10` must be level 10; `x` is ignored. Locked slots do not count as level 10.

The app checks the existing preferred-support and normal-skill conditions first. It then taps the skill-display switch once and reads all five Append Skills from a fresh screenshot. It taps the switch twice to restore normal view before selecting a passing candidate or searching for another. Unconfirmed switching or restoration stops selection.

The feature defaults to off. Five ignored slots skip switching. Settings are included in copies, exports and imports. See the [Append Skill guide (Korean)](wiki/scripts/Support-Append-Skills-KR.md) for configuration and the provided KR/JP screenshot results.

### 9. Support score +62 checks

Enable **Require support score +62** in the preferred Servant settings to accept only a matching +62 score. Other numbers or an unreadable score cause the app to search for another Servant. Version 0.5.0 also moves this setting above Append Skills.

It works independently of Append checks. If both are enabled, both must pass. Score checks do not require switching skill views. You can also use only the +62 filter without choosing Servant or CE images. See the [score guide (Korean)](wiki/scripts/Support-Score-62-KR.md).

### 10. Fixed attack order for a specific turn

Open **Command Editor → Attack → Direct cards**, next to **Cards before NP**, to choose all three face cards and NPs in order. Buster, Arts and Quick ignore affinity. For example, `&a4b` stores Arts → frontline position 1 NP → Buster as one command.

Direct attacks bypass automatic card/Servant priority, Brave Chain selection, rearrangement and added NP Spam. Missing or unrecognized required face cards stop the script before any card is tapped. Other turns keep the original behavior. **Available in stable version 0.4.0.** See the [direct-card guide (Korean)](wiki/scripts/Direct-Cards-KR.md) for syntax and verification limits.

### 11. Support NP level checks — 0.5.0

Open **Support Selection → Preferred Servants → Check support NP level**, then choose a **Minimum NP level** from 1 to 5. A minimum of 3 accepts NP levels 3, 4 and 5. The filter defaults to off; the initial minimum selection is 5.

The app locates `Lv.` inside the candidate's NP name area, then reads its suffix using the existing Tesseract engine. NP name length and changing horizontal positions are supported. Unreadable, uncertain or below-minimum levels reject the candidate. No additional OCR engine or service was added.

**All ordinary filters, including score and NP level → Append Skill checks → selection**. A failed earlier condition skips that candidate's Append view switch entirely. When NP, score and Append conditions are enabled together, all must pass. Settings survive copies, exports and imports. See the [NP guide (Korean)](wiki/scripts/Support-NP-Level-KR.md).

All 12 visible NP levels in the five submitted KR/JP images were correct (12/12, **100% on these submitted images**), also matching in both normalization orders (24/24). The images cover real NP levels 1 and 5. Real levels 2–4 and Android/FGO device operation still need verification.

## Updates through Obtainium

1. Install [Obtainium](https://obtainium.imranr.dev/).
2. Tap **Add to Obtainium** above, or open Obtainium's **Add App** page and enter `https://github.com/puruuun/FGA-Modified`. The source is GitHub.
3. Leave prereleases disabled. Each release has one ARM APK, so no extra APK filter is needed.
4. Check for updates on the app page, or configure background checks in Obtainium. New stable releases provide downloadable APKs.

Version 0.5.0 with NP level checks is a stable release and works with the existing Obtainium configuration.

The configuration is also available in [obtainium.json](obtainium.json). Updates retain the package and signing certificate and use increasing Android version codes. Automatic installation depends on Android and Obtainium permissions and settings. [Adding apps](https://wiki.obtainium.imranr.dev/ui_overview/), [update behavior](https://wiki.obtainium.imranr.dev/app_tracking/).

## Upstream and license

The original FGA developers created the underlying automation and image-recognition features. This project retains the upstream **MIT license and copyright notices**. See [LICENSE](LICENSE) for the full license.
