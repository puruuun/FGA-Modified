# FGA(mod)

[한국어](README.md) | [English](README.en.md) | [日本語](README.ja.md)

**Testing is limited to the KR (Korean) and JP (Japanese) servers. Behavior on other servers has not been verified.**

A modified version of [Fate/Grand Automata (FGA)](https://github.com/Fate-Grand-Automata/FGA), maintained separately from the official upstream distribution.

## Download and updates

- [Latest APK](https://github.com/puruuun/FGA-Modified/releases/latest)
- Stable 1.2.1: [release notes (Korean)](wiki/releases/1.2.1-KR.md)
- [Add to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fapp%2F%257B%2522id%2522%253A%2522io.github.fate_grand_automata.cardcount%2522%252C%2522url%2522%253A%2522https%253A%252F%252Fgithub.com%252Fpuruuun%252FFGA-Modified%2522%252C%2522author%2522%253A%2522puruuun%2522%252C%2522name%2522%253A%2522FGA%2528mod%2529%2522%252C%2522additionalSettings%2522%253A%257B%2522includePrereleases%2522%253Afalse%257D%257D)
- [Details](wiki/scripts/Modified-Features.md#installation)

Supports Android 7+ ARM devices (arm64-v8a / armeabi-v7a). Existing modified builds can be updated in place.

Install source: do not install APKs from anywhere other than GitHub Releases and Obtainium.

## Added and modified features

| Feature | Summary |
|---|---|
| [Import original FGA data](wiki/scripts/Original-FGA-Import.md) | Import scripts, support images, or both in bulk. |
| [Chaldea command import](wiki/scripts/Modified-Features.md#chaldea-import) | Convert a battle share link directly into script commands. |
| [Card-count command start](wiki/scripts/Modified-Features.md#card-start) | Choose between two settings based on a Servant’s face-card count. |
| [First-turn shuffle](wiki/scripts/Modified-Features.md#first-turn-shuffle) | Shuffle and recheck when the start conditions are not met. |
| [Support Append Skills](wiki/scripts/Modified-Features.md#support-append) | Check selected Append slots for level 10. |
| [Support scores](wiki/scripts/Modified-Features.md#support-score) | Filter by Class +62 and Grand +16 requirements. |
| [Support NP level](wiki/scripts/Modified-Features.md#support-np) | Select supports at or above the configured minimum NP level. |
| [Fixed attack order](wiki/scripts/Modified-Features.md#direct-cards) | Specify the order of three face cards and NPs. |
| [Teapots / Storm Pods](wiki/scripts/Modified-Features.md#quest-items) | Opt in to apply ON/OFF choices before Quest Start. |
| [Bond tracking / MAX stop](wiki/scripts/Modified-Features.md#bond-tracking) | Track gains for result characters 1–5 and choose MAX stop exceptions. |

See the [full guide](wiki/scripts/Modified-Features.md) for setup, behavior and validation scope.

## Upstream and license

[Upstream repository](https://github.com/Fate-Grand-Automata/FGA) · [Original documentation](https://github.com/Fate-Grand-Automata/FGA/wiki) · [MIT license](LICENSE)

Based on official FGA v3059 ([source commit](https://github.com/Fate-Grand-Automata/FGA/commit/e138a15273dee9cac342833ee2d8aee93d8ae700)). Upstream copyright notices are retained. Additions and changes are Copyright (c) 2026 puruuun and are distributed under the same MIT license.
