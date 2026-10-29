# FGA(mod)

[한국어](README.md) | [English](README.en.md)

**Testing is limited to the KR (Korean) and JP (Japanese) servers. Behavior on other servers has not been verified.**

A modified version of [Fate/Grand Automata (FGA)](https://github.com/Fate-Grand-Automata/FGA), maintained separately from the official upstream distribution.

## Download and updates

- [Latest APK](https://github.com/puruuun/FGA-Modified/releases/latest)
- [Release notes (Korean)](wiki/releases/1.6.7-KR.md)
- [Add to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fapp%2F%257B%2522id%2522%253A%2522io.github.fate_grand_automata.cardcount%2522%252C%2522url%2522%253A%2522https%253A%252F%252Fgithub.com%252Fpuruuun%252FFGA-Modified%2522%252C%2522author%2522%253A%2522puruuun%2522%252C%2522name%2522%253A%2522FGA%2528mod%2529%2522%252C%2522additionalSettings%2522%253A%2522%257B%255C%2522includePrereleases%255C%2522%253Afalse%252C%255C%2522apkFilterRegEx%255C%2522%253A%255C%2522%255EFGA-mod-.%252A-arm%255C%255C%255C%255C.apk%2524%255C%2522%257D%2522%257D)
- [Details](wiki/scripts/Modified-Features.md#installation)

Supports Android 7+ ARM devices (arm64-v8a / armeabi-v7a). Existing modified builds can be updated in place.

Install source: do not install APKs from anywhere other than GitHub Releases and Obtainium.

## Added and modified features

| Feature | Summary |
|---|---|
| [Game area and presets](wiki/scripts/Game-Area-Preview-KR.md) | Previews Custom and saved areas, applies capture scaling when settings are finished, and offers Reset and saved presets with a 1280×720 minimum. |
| [App language](wiki/scripts/Hakuno-Shuffle-KR.md#앱-언어) | Saves a supported system language on first launch and shares it with settings and onboarding. |
| [CE enhancement](wiki/scripts/CE-Auto-Enhance-KR.md) | Reminds you to check in-game filters before starting and switches between Servant and CE enhancement on JP/KR. |
| [FP summon auto-sell](wiki/scripts/FP-Auto-Sell-KR.md) | Uses padlock-only selection and fixed sale timing, stops at the upstream scrollbar template end, and leaves dry runs at Confirm. |
| [Skill / Append Skill auto-upgrade](wiki/scripts/Auto-Skill-Level-KR.md) | Upgrades each skill to its target level. |
| [Weekly mission clear](wiki/scripts/Weekly-Mission-Route-KR.md) | Runs weekly missions with content-based navigation and upstream scrollbar template checks; taps map spots as soon as they are clear of UI and edge margins. The feature can be hidden and empty routes do not start. |
| [Servant number images](wiki/scripts/Servant-Number-Portraits-KR.md) | Creates and saves support images by Servant number. |
| [Import original FGA data](wiki/scripts/Original-FGA-Import.md) | Imports scripts and support images from the original FGA. |
| [Chaldea command import](wiki/scripts/Chaldea-Import-KR.md) | Converts Chaldea commands and reuses all 24 built-in Servants and 23 CEs, including U-Olga Marie's costume portrait. |
| [Card-count command start](wiki/scripts/Card-Count-Start-KR.md) | Starts the command only when enough face cards are dealt. |
| [First-turn shuffle](wiki/scripts/Modified-Features-KR.md#first-turn-shuffle) | Rechecks first-turn hands; Hakuno uses `aH{…}` with optional B/A/Q counts, clear count labels and a 0.5-second wait before NP-shortage Close. |
| [Support Append Skills](wiki/scripts/Support-Append-Skills-KR.md) | Picks supports whose Append Skills are level 10. |
| [Support scores](wiki/scripts/Support-Score-62-KR.md) | Picks Class +62 / Grand +16 supports. |
| [Support NP level](wiki/scripts/Support-NP-Level-KR.md) | Picks supports at or above a minimum NP level; the toggle shows its title only. |
| [Post-quest friend request](wiki/scripts/Modified-Features-KR.md#auto-friend-request) | Sends a friend request after using a non-friend support in Preferred mode; the toggle shows its title only. |
| [Ascension select](wiki/scripts/Ascension-Select-KR.md) | Tells ascensions apart by Servant name and NP image. |
| [Fixed attack order](wiki/scripts/Direct-Cards-KR.md) | Sets the order of cards and NPs for a turn. |
| [Card priority: 10 entries, wave or turn, servant style](wiki/scripts/Card-Priority-KR.md) | Up to 10 priority entries that follow the wave or the turn, plus a servant style that picks cards by field position and type. |
| [Teapots / Storm Pods](wiki/scripts/Quest-Items-KR.md) | Sets ON/OFF before the quest starts. |
| [Bond tracking / MAX stop](wiki/scripts/Bond-Tracking-KR.md) | Adds up bond gains and stops at MAX or a confirmed level-up to 10–16, without needing the result column. |

The detailed guides are written in Korean. See the [full guide](wiki/scripts/Modified-Features-KR.md) for setup and behavior.

## Upstream and license

[Upstream repository](https://github.com/Fate-Grand-Automata/FGA) · [Original documentation](https://github.com/Fate-Grand-Automata/FGA/wiki) · [MIT license](LICENSE)

Based on official FGA v3059 ([source commit](https://github.com/Fate-Grand-Automata/FGA/commit/e138a15273dee9cac342833ee2d8aee93d8ae700)). Upstream copyright notices are retained. Additions and changes are Copyright (c) 2026 puruuun and are distributed under the same MIT license.

Credit: the conversion rules behind "Chaldea command import" follow the logic of [Unic31/fgo-converter](https://github.com/Unic31/fgo-converter) (commit `1c95510`), ported to Kotlin.
