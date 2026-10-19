# FGA 1.5.0-beta.1 검증 보고서

상태: **자동 검증·APK 확인·BlueStacks 업데이트 설치·전체 기기 QA 완료. 실제 게임 강화는 확인하지 않았다(소유자 확인 필요).**
버전 `1.5.0-beta.1` / `1005001`, 패키지 `io.github.fate_grand_automata.beta`. 정규 버전은 1.4.1 / 3085 그대로다.

## 기능

설계 `docs/design/0008-auto-skill-level.md` §9~§11(앞 절보다 우선)을 구현했다. 사용법은 `wiki/scripts/Auto-Skill-Level-KR.md`.

- **진입점 두 개, 흐름 하나.** `SkillLevel`(3칸)과 `AppendLevel`(5칸). `AutoDetect`가 제목(KR·JP)으로 고른다. 서번트 선택 여부는 `서번트 재선택` / `サーヴァント再選択` 버튼으로 본다. 선택 전이면 설정 창 없이 "서번트를 먼저 선택하세요"로 끝낸다(`ScriptManager`).
- **설정 창.** 칸마다 목표 레벨 0(끔)~10, 기본은 모두 0, 값은 prefs에 저장. 모든 칸이 0이면 확인 버튼이 비활성이다.
- **칸 반복.** 아이콘 탭 → 1초 → 안내 줄 읽기(강화 / 해방 / 빨간 글씨 / 그 밖) → 강화 버튼 **자리** 탭 → 2초 안에 확인창(`confirmation` 워터마크) → `Lv.현재` OCR(1~9만 유효) → `현재 < 목표`이고 첫 회이거나 `현재 == 직전 + 1`이면 결정 위치 탭, 아니면 취소 → 오른쪽 위 제목 자리를 0.5초 간격으로 최대 20초 눌러 연출·결과 스킵.
- **해방 방지(D-14).** 어펜드 칸 자물쇠 그림, 안내 줄 `해방`, 버튼 글자 `해방`, 확인창 레벨 0·미독, 확인창 제목이 강화 계열이 아님, 버튼 탭 직전 재확인까지 모두 건너뛴다. 해방 버튼은 어떤 경로로도 누르지 않는다.
- **목표 초과 금지.** 결정은 `현재 < 목표`일 때만 누르고, 미독·0·10·직전보다 오르지 않음은 취소한다. 다음 레벨(`Lv.다음`)도 읽어 `현재 + 1`과 어긋나면 취소한다.
- **종료 요약(D-12)** 칸별 `시작 Lv → 끝 Lv (목표) 이유`, 영어·한국어 문자열. 로그 태그 `SkillLevel`.

## 템플릿 (소유자 화면 24장에서 자름, 다른 포크 자료 미사용)

원본 `build/orchestration/inputs/0008/01~24`(BlueStacks 창 캡처 2000×1147)에서 게임 영역(x 0~1950, y 48~1145)을 잘라 1280×720으로 정규화해 `docs/reference-images/{Jp,Kr}/skill-level/NN.png`에 두었다(`tools/device-qa/normalize_skill_level.py`). 템플릿은 `tools/device-qa/cut_skill_level_templates.py`로 자른다(21개 파일).

| 파일 | 서버 | 용도 |
|---|---|---|
| `skill_level_title.png` / `append_level_title.png` | Jp, Kr | 제목으로 스킬·어펜드 구분(KR 스킬 제목은 어펜드 제목의 뒷부분이라 어펜드를 먼저 확인) |
| `skill_level_reselect.png` | Jp, Kr | `서번트 재선택` 버튼 = 서번트 선택됨 |
| `skill_level_band_enhance.png` / `skill_level_band_unlock.png` | Jp, Kr | 안내 줄의 `강화` / `해방` 낱말 |
| `skill_level_unlock_button.png` | Jp, Kr | 버튼 글자 `해방` / `解放する` (해방 방지) |
| `skill_level_max_button.png` | Jp | 빨간 `これ以上強化できません`(최대 레벨 구분) |
| `skill_level_confirmation.png` | En(공통) | 확인창 `confirmation` 워터마크 |
| `skill_level_lock.png` | En(공통) | 어펜드 자물쇠 |
| `skill_level_dialog_title.png` / `append_level_dialog_title.png` | Jp, Kr | 확인창 제목 끝 낱말(강화 계열 확인) |
| `skill_level_result.png` | Jp, Kr | 결과 화면 "화면을 터치" 문구 |

빨간 안내 줄(부족)은 템플릿이 아니라 안내 줄 영역의 평균 색(R ≥ 35이고 R > G)으로 판별한다(부족 R 46~54 / G 33~38, 정상 R 14~22 / G 64~74).

## 확인창 `Lv.현재` OCR (기기, 전체 프레임)

| 프레임 | 화면 | 현재 | 다음 | OCR 시간 |
|---|---|---|---|---|
| JP 04 | 스킬 확인창 | 4 | 5 | 738 ms |
| KR 16 | 스킬 확인창 | 3 | 4 | 481 ms |
| KR 17 | 어펜드 확인창 | 5 | 6 | 436 ms |
| JP 18 | 어펜드 확인창 | 1 | 2 | 266 ms |

정답과 모두 일치한다. 처음 시도한 확대(×3~×5) 프로필은 한 자리 큰 숫자를 못 읽거나(빈 결과 다수) JP 18의 `1`을 `4`로 읽었다. 원본 크기(scale 1)에서 임계값 0.6~0.8 × 가로 0.8·1.0 × 여백 4·12(20개 프로필), 신뢰도 70 이상인 읽기가 서로 어긋남 없이 2개 이상 일치할 때만 값으로 인정한다. 가로 1.2배 이상 늘리면 `1`이 `4`로 읽힌다. **검증한 숫자는 1~6뿐이다.** 7~9와 10은 화면 자료가 없어 확인하지 못했다.

## 테스트

```text
./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test
BUILD SUCCESSFUL
```

**528개 통과**(app 180 + prefs 24 + scripts 324), 실패·오류 0. 새 `SkillLevelFlowTest` 19개(칸 반복 결정):

- 목표에 도달하면 취소하고 목표 도달로 보고한다. 시작·목표 1~10 조합 100가지에서 목표를 한 번도 넘지 않는다.
- 레벨이 오르지 않으면 결정 한 번 뒤 취소하고 그 칸을 멈춘다.
- OCR 실패·0·10·11·음수·`다음`이 어긋난 읽기는 결정하지 않는다.
- 자물쇠·안내 줄·버튼 글자·잠금으로 해방 버튼을 누르지 않는다. 확인창이 강화 제목이 아니면 취소한다.
- 확인창이 안 뜨면 건너뛴다(최대 / 부족 / 불명). 마지막 결정으로 10이 되면 목표 도달로 본다.
- 화면 복귀 실패나 취소 후에도 확인창이 남으면 전체를 정지한다.
- OCR 합의 규칙과 부족 색 판별.

## 전체 기기 QA

명령: `py -3 tools/device-qa/run.py --build --serial 127.0.0.1:5555`. 결과: `build\device-qa\20261019-195947`. 고정 화면 오프라인 판독만 했고 게임은 조작하지 않았다.

| suite | 통과 | 실패 | skip |
|---|---:|---:|---:|
| support | 185 | 0 | 3 |
| bond | 20 | 0 | 0 |
| questItems | 5 | 0 | 8 |
| noblePhantasm | 18 | 0 | 0 |
| fpSell | 736 | 0 | 0 |
| **skillLevel (새 suite)** | **63** | 0 | 0 |
| 합계 | **1027** | **0** | **11** |

`skillLevel` 63개: 제목·모드와 서번트 선택 여부 24(전 프레임), 안내 줄 상태와 해방·최대 버튼 12, 확인창 판별 8(+ 제목), `Lv.현재 / 다음` OCR 4(04·16·17·18), 어펜드 자물쇠 7(프레임 8·13·14·21·22·23·24의 칸별), 결과 화면 8. 기대값은 화면을 보고 손으로 적었고 실행 결과로 바꾸지 않았다. 기존 suite 수치는 1.4.2-beta.1과 같고 새 suite만 늘었다.

## 커밋과 CI

- 제품 코드·문서·버전 커밋 `cd7438601cfd47dfc59972cf12ba01c32d0e759f`.
- [Main CI 성공](https://github.com/puruuun/FGA-Modified/actions/runs/39240986562) (`39240986562`).

## APK·설치

- APK `C:\Users\rl\Documents\Codex\FGA-Modified-builds\FGA-beta-1.5.0-beta.1-arm.apk` 및 같은 경로의 `.sha256`. `./gradlew :app:assembleBeta`로 빌드(Main CI 성공 확인 뒤 같은 커밋 기준).
- SHA-256 `15278b6edda5b75363188f23b34b9a1674c1a2fc158f973ccf77ace53607b70d`.
- aapt: `io.github.fate_grand_automata.beta`, `1005001`, `1.5.0-beta.1`, 앱 이름 `FGA(beta)`, ABI `arm64-v8a` + `armeabi-v7a`, minSdk 24 / targetSdk 36.
- BlueStacks `127.0.0.1:5555`, 모델 `PIXEL 2 XL` 확인 후 `install -r`: **Success**.
- 설치 후 `versionName=1.5.0-beta.1`, `versionCode=1005001`. `firstInstallTime=2026-10-15 23:24:59` 보존.
- 다른 기기(`HA29GCWH`, `emulator-5554`)는 건드리지 않았고 adb 서버를 종료·시작·연결 해제하지 않았다(`adb connect 127.0.0.1:5555`만 사용). 게임을 조작하지 않았다. 태그·GitHub Release는 만들지 않았다.

## 남은 문제

- **실제 게임 강화는 확인하지 못했다.** 권장 순서: 스킬 한 칸, 목표 = 현재 + 1로 첫 확인. 연출 스킵 탭(오른쪽 위 제목 자리)과 확인창 결정 버튼 위치(스킬 y 590, 어펜드 y 565, 720p)는 정지 화면으로만 맞췄다.
- OCR은 숫자 1~6만 검증했다(7~9, 10 미확인). 어긋나면 결정하지 않고 멈추는 쪽으로 실패한다.
- 최대 레벨 구분 템플릿은 JP만 있다. KR은 최대 레벨 버튼 화면이 없어 "강화 불가"로 표시된다.
- 확인창 제목 템플릿은 `강화` / `레벨업`(JP는 `強化`) 낱말 하나뿐이다. 해방 확인창의 실제 제목은 화면 자료가 없어 확인하지 못했다. 해방 방지는 이 검사 이전의 자물쇠·안내 줄·버튼 글자·레벨 0 검사가 주력이다.
- 18:9보다 넓은 화면의 좌표는 확인하지 못했다(16:9 기준, 서번트 강화 화면처럼 세로 오프셋이 다를 수 있다). 결정 버튼 위치는 `xFromCenter`, 강화 버튼은 기존 `enhancementClick`(와이드 값 있음)을 쓴다.
- 스킬 화면에서 서번트가 선택된 직후 안내 줄이 아직 갱신 전이면 "그 밖"으로 읽힐 수 있다. 이 경우에도 버튼을 눌러 확인창 유무로 판단한다(설계 §11).
