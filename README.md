<a id="english"></a>

# FGA(mod)

[English](#english) | [한국어](#korean)

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
| [CE enhancement](wiki/scripts/CE-Auto-Enhance-KR.md) | Confirms before starting and switches between Servant and CE enhancement on JP/KR. |
| [FP summon auto-sell](wiki/scripts/FP-Auto-Sell-KR.md) | Sells unlocked cards when the Servant box is full and continues summoning. |
| [Skill / Append Skill auto-upgrade](wiki/scripts/Auto-Skill-Level-KR.md) | Upgrades each skill to its target level. |
| [Weekly mission clear](wiki/scripts/Weekly-Mission-Route-KR.md) | Runs weekly missions with content-based navigation; the feature can be hidden and empty routes do not start. |
| [Servant number images](wiki/scripts/Servant-Number-Portraits-KR.md) | Creates and saves support images by Servant number. |
| [Import original FGA data](wiki/scripts/Original-FGA-Import.md) | Imports scripts and support images from the original FGA. |
| [Chaldea command import](wiki/scripts/Chaldea-Import-KR.md) | Converts a Chaldea share link into script commands. |
| [Card-count command start](wiki/scripts/Card-Count-Start-KR.md) | Starts the command only when enough face cards are dealt. |
| [First-turn shuffle](wiki/scripts/Modified-Features-KR.md#first-turn-shuffle) | Shuffles and rechecks when first-turn conditions are not met. |
| [Support Append Skills](wiki/scripts/Support-Append-Skills-KR.md) | Picks supports whose Append Skills are level 10. |
| [Support scores](wiki/scripts/Support-Score-62-KR.md) | Picks Class +62 / Grand +16 supports. |
| [Support NP level](wiki/scripts/Support-NP-Level-KR.md) | Picks supports at or above a minimum NP level. |
| [Post-quest friend request](wiki/scripts/Modified-Features-KR.md#auto-friend-request) | Sends a friend request after using a non-friend support in Preferred mode. |
| [Ascension select](wiki/scripts/Ascension-Select-KR.md) | Tells ascensions apart by Servant name and NP image. |
| [Fixed attack order](wiki/scripts/Direct-Cards-KR.md) | Sets the order of cards and NPs for a turn. |
| [Teapots / Storm Pods](wiki/scripts/Quest-Items-KR.md) | Sets ON/OFF before the quest starts. |
| [Bond tracking / MAX stop](wiki/scripts/Bond-Tracking-KR.md) | Adds up bond gains and stops at MAX. |

The detailed guides are written in Korean. See the [full guide](wiki/scripts/Modified-Features-KR.md) for setup and behavior.

## Upstream and license

[Upstream repository](https://github.com/Fate-Grand-Automata/FGA) · [Original documentation](https://github.com/Fate-Grand-Automata/FGA/wiki) · [MIT license](LICENSE)

Based on official FGA v3059 ([source commit](https://github.com/Fate-Grand-Automata/FGA/commit/e138a15273dee9cac342833ee2d8aee93d8ae700)). Upstream copyright notices are retained. Additions and changes are Copyright (c) 2026 puruuun and are distributed under the same MIT license.

Credit: the conversion rules behind "Chaldea command import" follow the logic of [Unic31/fgo-converter](https://github.com/Unic31/fgo-converter) (commit `1c95510`), ported to Kotlin.

---

<a id="korean"></a>

# FGA(mod)

[English](#english) | [한국어](#korean)

**KR(한국)·JP(일본) 서버에 한정해 테스트했습니다. 다른 서버의 동작은 확인하지 않았습니다.**

[Fate/Grand Automata(FGA)](https://github.com/Fate-Grand-Automata/FGA)에 기능을 추가한 수정판입니다. 원본 공식 배포판과 별도로 관리합니다.

**📖 [사용 설명서 사이트](https://puruuun.github.io/FGA-Modified/)** — 기능별 사용법을 정리했습니다.

## 다운로드·업데이트

**정규판 1.6.7** — 공격 순서 직접 지정에 카드 부족 시 우선순위 대체를 추가하고, 강화 퀘스트 개방 알림창 정지와 일부 기기의 편성창 정지를 고쳤습니다. [변경 사항](wiki/releases/1.6.7-KR.md).

| 배포판 | APK | Obtainium |
|---|---|---|
| **정규판 FGA(mod)** | [최신 정규판](https://github.com/puruuun/FGA-Modified/releases/latest) | [정규판 추가](https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fapp%2F%257B%2522id%2522%253A%2522io.github.fate_grand_automata.cardcount%2522%252C%2522url%2522%253A%2522https%253A%252F%252Fgithub.com%252Fpuruuun%252FFGA-Modified%2522%252C%2522author%2522%253A%2522puruuun%2522%252C%2522name%2522%253A%2522FGA%2528mod%2529%2522%252C%2522additionalSettings%2522%253A%2522%257B%255C%2522includePrereleases%255C%2522%253Afalse%252C%255C%2522apkFilterRegEx%255C%2522%253A%255C%2522%255EFGA-mod-.%252A-arm%255C%255C%255C%255C.apk%2524%255C%2522%257D%2522%257D) |

Obtainium은 정규판만 제공합니다. [정규판 설정 파일](obtainium.json)을 가져와 등록할 수 있습니다. 개발 베타 APK는 컴퓨터에 저장하거나 블루스택에 직접 설치합니다.

설치 출처: GitHub Releases와 Obtainium 외의 곳에서 받은 APK는 설치하지 마세요.

[설치 안내](wiki/scripts/Modified-Features-KR.md#installation) · [서번트 번호 이미지 생성 안내](wiki/scripts/Servant-Number-Portraits-KR.md)

Android 7 이상 ARM 기기(arm64-v8a / armeabi-v7a)를 지원합니다. 기존 수정판은 업데이트 설치할 수 있습니다.

## 추가·수정된 기능

| 기능 | 설명 |
|---|---|
| [개념예장 자동 강화](wiki/scripts/CE-Auto-Enhance-KR.md) | 시작 창에서 확인하고 JP·KR에서 서번트/예장 강화 모드를 전환합니다. |
| [FP 소환 자동 판매](wiki/scripts/FP-Auto-Sell-KR.md) | 서번트칸이 가득 차면 잠금이 아닌 카드를 판매하고 소환을 이어 갑니다. |
| [스킬·어펜드 스킬 자동 강화](wiki/scripts/Auto-Skill-Level-KR.md) | 칸마다 정한 목표 레벨까지 스킬을 강화합니다. |
| [주간 미션 클리어](wiki/scripts/Weekly-Mission-Route-KR.md) | 목록 내용으로 이동하며 빈 경로는 실행하지 않습니다. 추가기능에서 버튼을 숨길 수 있습니다. |
| [서번트 번호 이미지 생성](wiki/scripts/Servant-Number-Portraits-KR.md) | 도감 번호로 서포트 이미지를 만들어 저장합니다. |
| [원본 FGA 데이터 가져오기](wiki/scripts/Original-FGA-Import-KR.md) | 원본 FGA의 스크립트·서포트 이미지를 가져옵니다. |
| [Chaldea 링크로 커맨드 입력](wiki/scripts/Chaldea-Import-KR.md) | Chaldea 공유 링크를 커맨드로 변환해 입력합니다. |
| [카드 조건으로 커맨드 시작](wiki/scripts/Card-Count-Start-KR.md) | 일반 카드가 충분할 때만 커맨드를 시작합니다. |
| [첫 턴 셔플](wiki/scripts/Modified-Features-KR.md#first-turn-shuffle) | 첫 턴 조건이 부족하면 셔플 후 다시 검사합니다. |
| [서포트 어펜드 스킬](wiki/scripts/Support-Append-Skills-KR.md) | 어펜드 스킬이 레벨 10인 서포트를 고릅니다. |
| [서포트 스코어](wiki/scripts/Support-Score-62-KR.md) | 클래스 +62·그랜드 +16 서포트를 고릅니다. |
| [서포트 보구 레벨](wiki/scripts/Support-NP-Level-KR.md) | 최소 보구 레벨 이상의 서포트를 고릅니다. |
| [퀘스트 후 친구 신청](wiki/scripts/Modified-Features-KR.md#auto-friend-request) | 선호 모드에서 친구가 아닌 서포트를 쓴 뒤 친구 신청을 보냅니다. |
| [재림 구분](wiki/scripts/Ascension-Select-KR.md) | 재림이 다른 서번트를 이름+보구 그림으로 구분해 고릅니다. |
| [공격 순서 직접 지정](wiki/scripts/Direct-Cards-KR.md) | 한 턴의 카드·보구 순서를 직접 정합니다. |
| [티포트·스톰포드](wiki/scripts/Quest-Items-KR.md) | 퀘스트 시작 전에 ON/OFF를 맞춥니다. |
| [인연포인트·MAX 정지](wiki/scripts/Bond-Tracking-KR.md) | 획득 인연포인트를 합산하고 MAX가 되면 멈춥니다. |

설정 방법과 동작은 [사용 설명서](wiki/scripts/Modified-Features-KR.md)에서 확인하세요.

## 원본 및 라이선스

[원본 저장소](https://github.com/Fate-Grand-Automata/FGA) · [원본 사용 안내](https://github.com/Fate-Grand-Automata/FGA/wiki) · [MIT 라이선스](LICENSE)

수정 기준: 공식 FGA v3059 ([기준 커밋](https://github.com/Fate-Grand-Automata/FGA/commit/e138a15273dee9cac342833ee2d8aee93d8ae700)). 원본 개발팀의 저작권 고지를 유지합니다. 추가·수정한 부분의 저작권은 puruuun에게 있으며, 같은 MIT 라이선스로 배포합니다.

참조: Chaldea 링크로 커맨드 입력의 변환 규칙은 [Unic31/fgo-converter](https://github.com/Unic31/fgo-converter)(커밋 `1c95510`)의 변환 로직을 참조해 Kotlin으로 옮겼습니다.
