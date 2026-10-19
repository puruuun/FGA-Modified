# FGA 1.5.0-beta.3 검증 보고서

상태: **자동 검증·APK 확인·BlueStacks 업데이트 설치 완료. 실제 게임 강화 미실행.**
베타 `1.5.0-beta.3` / `1005003`, 패키지 `io.github.fate_grand_automata.beta`. 정규판 **1.4.1 / 3085 유지**.

## 변경 사항 — 0008 §12 D-15 / D-16

- 강화 버튼을 누른 뒤 **0.5초** 기다리고 기존 최대 2초 확인창 탐색을 시작한다. `SkillLevelTiming.AFTER_ENHANCE`에 모았다. 소유자 정정에 따라 **결정 뒤 +0.3초 대기·상수는 없다.** 결정 뒤 별도 대기 없이 기존 0.5초 간격 스킵 절차로 이어진다. 기존 선택 안정화·탐색 상한·스킵 상한은 유지했다.
- 비디버그 베타의 `FgaTree` INFO 허용 목록에 `SKILL_LEVEL_TAG`를 추가했다. 기존 `FpSell`·지원 점수·WARN 이상 정책을 유지하며, 실제 Tree가 호출하는 정책 함수를 분리해 비디버그 조건으로 테스트했다.
- `SkillLevel` 로그에 슬롯 선택, 밴드 상태, 강화 탭, 확인창 유무, current/next, 결정/취소, 스킵 복귀 결과를 남긴다. 정지 시 `stop slot=N step=... reason=...`, 슬롯별 `summary`를 기록한다.
- 예외·수동 정지 때도 진행 중인 슬롯의 알려진 시작/끝 레벨과 실패 단계를 보존한다. 단계는 `select`, `enhance`, `dialog`, `ocr`, `confirm`, `skip`이다. 취소 실패는 confirm, 스킵 복귀 실패는 skip으로 구분한다. 시작 전 정지는 slot=0이다.
- 영어·한국어 정지 메시지와 슬롯별 요약에 실패 단계를 표시한다. 실행 전 서번트 미선택 메시지도 선택 단계가 표시된다. 해방 삼중 보호와 목표 초과·OCR 불확실 시 결정 금지 규칙은 유지했다.
- [한국어 설명서](../../../wiki/scripts/Auto-Skill-Level-KR.md)를 갱신했다. 템플릿·좌표·OCR 판독기·기기 QA 기대값은 변경하지 않았다.

## 단위 테스트·컴파일·문서

JDK 21에서 다음 명령 성공:

```text
./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test :app:compileCiKotlin
```

**543개 통과, 실패·오류·스킵 0개**: app 183, prefs 24, scripts 336, libautomata 테스트 소스 없음. beta.2의 533개 대비 신규 10개다.

- 로그 정책 3개: 비디버그 INFO 허용/DEBUG 억제, 기존 태그·WARN 정책, 디버그 정책.
- 기기 어댑터 3개: 강화 탭→0.5초→탐색 순서와 기존 상한, 결정 단일 탭·추가 대기 없음, 마지막 해방 보호.
- 흐름 4개: 단계별 로그, 6단계 예외의 슬롯/단계 보존, 스킵 중 수동 정지 요약, 취소 실패/스킵 실패 구분.
- `mkdocs build --strict` 성공.

## 전체 기기 QA

```text
py -3 tools/device-qa/run.py --build --serial 127.0.0.1:5555
```

저장된 화면을 읽는 오프라인 판독 검사이며 게임을 조작하지 않았다. 결과 `build\device-qa\20261019-231749`.

| suite | 통과 | 실패 | 기존 skip |
|---|---:|---:|---:|
| support | 185 | 0 | 3 |
| bond | 20 | 0 | 0 |
| questItems | 5 | 0 | 8 |
| noblePhantasm | 18 | 0 | 0 |
| fpSell | 736 | 0 | 0 |
| skillLevel | 83 | 0 | 0 |
| 합계 | **1047** | **0** | **11** |

기존 1047건 모두 통과. 신규 화면 사례 없음. 기존 사례와 기대값은 변경하지 않았다. beta.2에서 확인한 14개 current/next 판독 사례도 그대로 통과했다.

## 커밋과 CI

- 제품·버전·설명서 커밋 `d72d876c238e28cd5bc0b3f67f1f6134e9d6714d`.
- [Main CI 성공](https://github.com/puruuun/FGA-Modified/actions/runs/39267532821). 최종 검증 문서 전용 후속 커밋은 Main CI paths-ignore 대상이다.
- author/committer 모두 puruuun, AI 트레일러 없음. 태그·GitHub Release는 만들지 않았다.

## APK와 설치

- APK `C:\Users\rl\Documents\Codex\FGA-Modified-builds\FGA-beta-1.5.0-beta.3-arm.apk`, 같은 경로 `.apk.sha256`.
- SHA-256 **`a3c154df51df400f2921becf77cb97ac07a7e9ca665d075ee33d830fbc7576d4`**. 원본·보관 APK·체크섬 파일 일치 확인.
- aapt: `io.github.fate_grand_automata.beta`, `1005003`, `1.5.0-beta.3`, `FGA(beta)`, `arm64-v8a` + `armeabi-v7a`.
- apksigner 검증 성공. 저장소 debug 키 인증서 SHA-256 `5acce47e71373dcfe3a6f6400ed3b3f687bd454aab91c7562c8327a3a083ac9d` 일치. 기존 숫자 자산 12개 포함 및 ZIP 무결성 확인.
- BlueStacks **127.0.0.1:5555 / PIXEL 2 XL**에 `install --no-streaming -r`: **Performing Push Install
Success**.
- 설치 후 `versionName=1.5.0-beta.3`, `versionCode=1005003 minSdk=24 targetSdk=36`.
- 최초 설치 시간 **2026-10-15 23:24:59** 보존. 지정 외 기기 접근·게임 조작 없음.

## 확인 범위와 로그 수집

소유자는 beta.2 JP에서 한 슬롯을 현재+1 목표로 실제 강화했고 강화 성공과 도중 오류를 보고했다. 당시 로그 필터 때문에 정확한 원인은 확인되지 않았다. 이번 수정은 로그와 단계 식별을 보완한 것이며 **beta.3의 실제 게임 오류 해결을 검증한 것은 아니다.** 이번에는 게임을 실행·조작하지 않았다.

```text
adb -s 127.0.0.1:5555 logcat -s SkillLevel
```

current 2 / next 3 확인창 자료, 실제 게임 연출·와이드 화면 좌표 등 beta.2의 남은 검증 범위는 유지된다.
