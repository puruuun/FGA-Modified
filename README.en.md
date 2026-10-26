# FGA(mod)

[한국어](README.md) | [English](README.en.md)

**Testing is limited to the KR (Korean) and JP (Japanese) servers. Behavior on other servers has not been verified.**

A modified version of [Fate/Grand Automata (FGA)](https://github.com/Fate-Grand-Automata/FGA), maintained separately from the official upstream distribution.

## Download and updates

- [Latest APK](https://github.com/puruuun/FGA-Modified/releases/latest)
- [Release notes (Korean)](wiki/releases/1.6.7-KR.md)
- [Add to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fapp%2F%257B%2522id%2522%253A%2522io.github.fate_grand_automata.cardcount%2522%252C%2522url%2522%253A%2522https%253A%252F%252Fgithub.com%252Fpuruuun%252FFGA-Modified%2522%252C%2522author%2522%253A%2522puruuun%2522%252C%2522name%2522%253A%2522FGA%2528mod%2529%2522%252C%2522additionalSettings%2522%253A%257B%2522includePrereleases%2522%253Afalse%257D%257D)
- [Details](wiki/scripts/Modified-Features.md#installation)

Supports Android 7+ ARM devices (arm64-v8a / armeabi-v7a). Existing modified builds can be updated in place.

Install source: do not install APKs from anywhere other than GitHub Releases and Obtainium.

## Added and modified features

| Feature | Summary |
|---|---|
| [FP summon auto-sell](wiki/scripts/FP-Auto-Sell-KR.md) | Sells unlocked cards when the Servant box is full and continues summoning. |
| [Skill / Append Skill auto-upgrade](wiki/scripts/Auto-Skill-Level-KR.md) | Upgrades each skill to its target level. |
| [Weekly mission clear](wiki/scripts/Weekly-Mission-Route-KR.md) | Clears this week's missions on the lowest-AP route. |
| [Servant number images](wiki/scripts/Servant-Number-Portraits-KR.md) | Creates and saves support images by Servant number. |
| [Import original FGA data](wiki/scripts/Original-FGA-Import.md) | Imports scripts and support images from the original FGA. |
| [Chaldea command import](wiki/scripts/Chaldea-Import-KR.md) | Converts a Chaldea share link into script commands. |
| [Card-count command start](wiki/scripts/Card-Count-Start-KR.md) | Starts the command only when enough face cards are dealt. |
| [First-turn shuffle](wiki/scripts/Modified-Features-KR.md#first-turn-shuffle) | Shuffles and rechecks when first-turn conditions are not met. |
| [Support Append Skills](wiki/scripts/Support-Append-Skills-KR.md) | Picks supports whose Append Skills are level 10. |
| [Support scores](wiki/scripts/Support-Score-62-KR.md) | Picks Class +62 / Grand +16 supports. |
| [Support NP level](wiki/scripts/Support-NP-Level-KR.md) | Picks supports at or above a minimum NP level. |
| [Ascension select](wiki/scripts/Ascension-Select-KR.md) | Tells ascensions apart by Servant name and NP image. |
| [Fixed attack order](wiki/scripts/Direct-Cards-KR.md) | Sets the order of cards and NPs for a turn. |
| [Teapots / Storm Pods](wiki/scripts/Quest-Items-KR.md) | Sets ON/OFF before the quest starts. |
| [Bond tracking / MAX stop](wiki/scripts/Bond-Tracking-KR.md) | Adds up bond gains and stops at MAX. |

The detailed guides are written in Korean. See the [full guide](wiki/scripts/Modified-Features-KR.md) for setup and behavior.

## Upstream and license

[Upstream repository](https://github.com/Fate-Grand-Automata/FGA) · [Original documentation](https://github.com/Fate-Grand-Automata/FGA/wiki) · [MIT license](LICENSE)

Based on official FGA v3059 ([source commit](https://github.com/Fate-Grand-Automata/FGA/commit/e138a15273dee9cac342833ee2d8aee93d8ae700)). Upstream copyright notices are retained. Additions and changes are Copyright (c) 2026 puruuun and are distributed under the same MIT license.

Credit: the conversion rules behind "Chaldea command import" follow the logic of [Unic31/fgo-converter](https://github.com/Unic31/fgo-converter) (commit `1c95510`), ported to Kotlin.
