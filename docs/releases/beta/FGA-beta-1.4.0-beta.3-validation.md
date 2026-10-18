# FGA 1.4.0-beta.3 검증 보고서

상태: 선택 판별·확인 방식·정지 사유 수정 완료. 전체 단위 테스트, 기기 QA 전 스위트, Main CI 통과. BlueStacks에 업데이트 설치.

- 베타: `1.4.0-beta.3` / `1004003`. 정규판 `1.2.1` / `3083` 유지. `FpSellPolicy.DEFAULT_DRY_RUN = true` 유지.
- 근거: 설계 0007 §10 (D-19, D-20, D-21). 1.4.0-beta.2를 일섭 BlueStacks에서 실행하니 첫 칸을 눌러 게임에서는 선택됐지만 스크립트가 `fp_sell_screen_unknown`으로 멈췄습니다.

## 변경

- **D-19 선택 판별**: 선택 템플릿(카드 그림 픽셀 포함)을 버리고, 칸 왼쪽 테두리의 청록색만 읽습니다. 카드 왼쪽 가장자리(클래스 배지 아래, 번호 배지 위)의 3×40 px(720p) 띠 7곳(가장자리에서 -2..4 px)의 평균색과 청록 기준색(RGB 92/245/190)의 거리 최솟값을 구합니다. 25 이하면 선택됨(true), 45 이상이면 선택 안 됨(false), 사이는 판별 불가(null)입니다. null이면 칸을 누르지 않고, 누른 뒤 확인에서는 선택으로 세지 않습니다. 새 `Pattern.meanColor()`(libautomata)와 `FpSellLocations.selectionStrip`을 사용합니다. 색 판별에는 색 캡처가 필요하므로 `useColor` 안의 새 프레임에서 읽고 `useSameSnapIn` 안에서는 부르지 않습니다(`FpSellReader.selection`/`confirmed`).
- **D-20 확인**: 칸을 한 번 누른 뒤 0.15초 간격으로 최대 10번(약 1.5초) 새 프레임을 읽어 한 번이라도 확실히 선택이면 성공입니다. 끝까지 확인되지 않으면 멈추며, **같은 칸을 다시 누르지 않습니다.**
- **D-21 정지 사유**: `SellScreenUnknown`(소환 루프 120프레임 알 수 없는 화면)은 유지하고, 판매 단계 정지를 `SellEntryFailed`(판매 화면 진입), `ColumnSwitchFailed`(7열 전환), `SelectionNotConfirmed`(선택 확인), `SellFlowFailed`(결정·변환·닫기)로 나눴습니다. 모두 시험 모드 여부와 선택된 칸 수를 담습니다. `ScriptManager`가 문자열 `fp_sell_stop_*`로 표시합니다(영어 `values/localized.xml`, 한국어 `values-ko`).
- **기기 QA 케이스**: `docs/reference-images/cases/fpSell.json`에 JP 45(`Jp/fp-sell/45-jp-selected.png`, 원본 webp를 (0,48)-(1936,1137)로 잘라 1280×720으로 줄임) 28건을 추가했습니다. 선택된 15칸(1·2줄 전부와 3줄 첫 칸)은 `selected: true`, 나머지 보이는 13칸은 `false`입니다. 기존 142건은 바꾸지 않았습니다. 판독 스위트는 판별 불가를 `"unknown"`으로 내보내 기대값과 어긋나게 하고, `selectionDistance`를 함께 기록합니다.
- 위키 `wiki/scripts/FP-Auto-Sell-KR.md`: 확인 방식과 새 종료 안내를 반영했습니다.

## 선택 판별 점수 분리 (청록 거리, 작을수록 청록)

| 화면 | 선택됨 | 선택 안 됨 |
|---|---|---|
| KR 20 | 16칸, 2.9~5.0 | 5칸, 88.2~138.2 |
| JP 45 | 15칸, 3.3~6.0 | 13칸, 86.6~141.9 |

- 기존 템플릿 상관 점수(참고): KR 20 선택 칸 0.93~1.0, JP 45 선택 칸 0.86~0.90(기준 0.88의 경계).
- 기준 25/45 대비 여유: 선택 칸 최대 6.0, 선택 안 된 칸 최소 53.4(전체 fpSell 셀 케이스 기준, JP 29의 하늘색 그림 칸). 판별 불가(null)로 읽힌 케이스는 0건입니다.

## 검증

- `./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test` 통과. 새 단위 테스트: 재탭 없음과 약 1.5초 확인(D-20), 단계별 정지 사유 매핑과 진입·열 전환 실패(D-21), 색 판별 3상태.
- 기기 QA `py -3 tools/device-qa/run.py --build --serial 127.0.0.1:5555` 전 스위트 통과(빌드는 변경 코드와 동일한 커밋 내용):

| 스위트 | 이전 | 이번 |
|---|---|---|
| support | 185 통과 / skip 3 | 185 통과 / skip 3 |
| bond | 20 | 20 |
| questItems | 5 / skip 8 | 5 / skip 8 |
| noblePhantasm | 18 | 18 |
| fpSell | 142 | 170 (기존 142 + JP 45 28) |

## 배포 상태

- 커밋: `4216210b` (`main`에 푸시). Main CI #13 성공.
- APK: `C:\Users\rl\Documents\Codex\FGA-Modified-builds\FGA-beta-1.4.0-beta.3-arm.apk`, aapt: package `io.github.fate_grand_automata.beta`, versionCode 1004003, versionName 1.4.0-beta.3.
- SHA-256: `1a491f186ca3f6a04c37ad32d1de2ad7428cbb37c9e9b4ed2e4051d4e75a7ca5`
- 설치: BlueStacks `127.0.0.1:5555`(PIXEL 2 XL)에 `adb install -r` **Success**. 설치 후 versionCode 1004003 / versionName 1.4.0-beta.3, firstInstallTime 2026-10-15 23:24:59 유지(업데이트 설치). 소유자 태블릿(HA29GCWH)은 건드리지 않았고 게임은 조작하지 않았습니다.
- GitHub 릴리스는 게시하지 않았습니다.

## 남은 일

- 소유자의 일섭 BlueStacks 실사용 확인(시험 모드): 첫 칸을 누른 뒤 선택이 확인되어 선택 수가 올라가는지 확인이 필요합니다.
- 실제 판매 흐름(결정 → 변환 → 닫기)은 여전히 게임에서 검증되지 않았습니다. 시험 모드 기본 켬을 유지합니다.
