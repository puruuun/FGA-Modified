# FGA 1.5.0-beta.4 검증 보고서

상태: **자동 검증·APK 확인·BlueStacks 업데이트 설치 완료. 실제 게임 강화 미실행(어펜드 포함).**
베타 `1.5.0-beta.4` / `1005004`, 패키지 `io.github.fate_grand_automata.beta`. 정규판 **1.4.1 / 3085 유지**.

## 변경 사항 — 0008 §13 D-17 / D-18

- 강화 버튼 뒤 대기를 **0.5초 → 0.4초**로 줄였다 (`SkillLevelTiming.AFTER_ENHANCE`).
- 확인창에서 **취소를 누른 뒤 0.4초** 기다린 다음 닫힘 확인과 다음 칸으로 넘어간다 (`SkillLevelTiming.AFTER_CANCEL`). 두 대기 모두 스킬·어펜드가 공유하는 `SkillLevelDevice`의 `pressEnhance()`·`cancel()`에 있다 (`scripts/.../modules/SkillLevelDevice.kt`). 모드는 좌표만 달라지고 흐름은 같다.
- 설정 창의 안내 문구 `skill_level_safety`를 화면과 모든 `values*`에서 지웠다.
- 종료 사유를 `ExitReason.logName`의 고정 이름(`Done`, `NoServantSelected`, `WrongScreen`, `UnsupportedServer`, `NothingSelected`, `Abort`, `Unexpected`)으로 기록한다. 난독화된 클래스 이름(`reason=c73`)이 더는 나오지 않는다. 정지 메시지는 원래 문자열 리소스를 쓴다.
- **목표 값 저장 안 함(D-18):** 설정 창은 스킬·어펜드 모두 열 때마다 모든 칸 0(끔)으로 시작한다. `SkillLevelPrefsCore`와 `skill_level_target_*` 저장을 없앴고 이전은 하지 않는다. 확인 때 값은 같은 실행의 스크립트에 넘기려고 메모리에만 둔다.
- [한국어 설명서](../../../wiki/scripts/Auto-Skill-Level-KR.md)와 설계 문서를 갱신했다.

## 단위 테스트

```text
./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test
```

**546개 통과, 실패·오류·스킵 0개**: app 183, prefs 25, scripts 338 (beta.3의 543개 대비 신규 3개: 취소 뒤 0.4초 순서, 종료 사유 이름 7종, 저장 안 함 확인). 기존 강화 대기 테스트는 0.4초로 고쳤다.

## 전체 기기 QA

`py -3 tools/device-qa/run.py --build --serial 127.0.0.1:5555`, 결과 `build\device-qa\20261019-235457`. 저장 화면 판독 검사이며 게임을 조작하지 않았다.

| suite | 통과 | 실패 | 기존 skip |
|---|---:|---:|---:|
| support | 185 | 0 | 3 |
| bond | 20 | 0 | 0 |
| questItems | 5 | 0 | 8 |
| noblePhantasm | 18 | 0 | 0 |
| fpSell | 736 | 0 | 0 |
| skillLevel | 83 | 0 | 0 |
| 합계 | **1047** | **0** | **11** |

## 커밋과 CI

- 제품·버전·설명서 커밋 `e1cbcd5d29cc027f9bc2a9e3880c7a152366c841`, author/committer puruuun, AI 트레일러 없음.
- Main CI 성공: https://github.com/puruuun/FGA-Modified/actions/runs/39272797239
- 태그·GitHub Release는 만들지 않았다.

## APK와 설치

- `C:\Users\rl\Documents\Codex\FGA-Modified-builds\FGA-beta-1.5.0-beta.4-arm.apk` + `.apk.sha256`.
- SHA-256 **`0a2616d4b087c9707a69893d941d634408e9f160f3e46ca4d7073e0846263ef1`**.
- aapt: `io.github.fate_grand_automata.beta`, `1005004`, `1.5.0-beta.4`, `FGA(beta)`, `arm64-v8a` + `armeabi-v7a`.
- BlueStacks **127.0.0.1:5555**에 `install --no-streaming -r` 성공(처음 한 번은 오프라인이라 `adb connect` 후 재시도). 설치 후 `versionName=1.5.0-beta.4`, `versionCode=1005004`, 최초 설치 시간 **2026-10-15 23:24:59** 보존. 다른 기기는 건드리지 않았다.

## 확인 범위

소유자가 beta.3을 JP·KR 스킬 화면에서 실행해 정상 동작을 확인했다. beta.4의 0.4초 대기와 저장 안 함 동작은 단위 테스트로만 확인했고 실제 게임에서는 실행하지 않았다. 어펜드 강화는 아직 실제 게임에서 실행하지 않았다.
