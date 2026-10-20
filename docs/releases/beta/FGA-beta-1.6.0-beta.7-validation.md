# FGA-beta-1.6.0-beta.7 검증

- 베타 **1.6.0-beta.7 / 1006007**, 정규판 **1.5.0 / 3086** 유지.
- 설계 0009 §14 **D-24**(마지막 전투 뒤 5초 대기)와 **D-25**(탐색 시험 모드).

## 변경

- **D-24.** 마지막 전투 뒤 연속 출격 창을 닫으면 **5초 더 기다린 뒤** 지도와 퀘스트 판을 확인합니다(`settleOnMap`).
- **D-25 탐색 시험 모드.** 주간 패널의 "탐색 시험(AP 소모 없음)" 체크를 켜면 경로 대신 시험 옵션이 보입니다.
  - 대상: 모든 메인 스토리 프리 퀘스트 / 선택한 장 / 주간 퀘 추적 설정에서 켠 퀘스트. 믹틀란 제외. JP·KR.
  - 주간 경로와 같은 이동을 하고 판 판정까지만 합니다. `RouteDriver.probeSpot`은 Match여도 **퀘스트 판을 누르지 않고** 판을 열어 둔 채 돌려주며, 상태기계는 판을 닫고 다음 퀘스트로 갑니다. 시험 모드의 상태기계는 `openSpot`/`battle`(판 탭·전투·연속 출격 창) 경로를 부르지 않습니다. 방문 순서는 장 → 지도 → 지점 순입니다.
  - 퀘스트별 결과: 성공 / 지도 맞추기 실패 / 지점 탭 후 판 없음 / 판 불일치 / 장 못 찾음 / 미실행 + 걸린 시간(ms).
  - 끝나면 요약 창(상태별 개수, 실패 목록 25줄)과 CSV `diagnostics/weeklyRoute/probe/probe-<서버>-<시각>.csv`. 열: `quest_id,war_id,chapter,spot,quest,ap,status,elapsed_ms,note`.
  - 실패한 퀘스트마다 진단 PNG 1장(지도 또는 열린 판)을 `diagnostics/weeklyRoute/<실행번호>/`에 저장, 시험 모드에서는 빌드 종류와 관계없이 **최대 100장**. 일반 경로는 기존대로 beta 8장.
  - 수동 정지 시 그때까지의 결과를 유지하고 가지 못한 퀘스트는 미실행으로 기록합니다.
  - "이미 성공한 퀘스트 건너뛰기": 서버별로 성공 ID를 `filesDir/weekly-probe-succeeded-<서버>.txt`에 누적하고 다시 시작할 때 제외합니다.
  - 로그는 `WeeklyRoute` 태그, 모든 줄에 `mode=probe`.

## 단위 테스트·컴파일

`./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test` 성공.

**606개 통과, 실패0**(app184, prefs26, scripts396, libautomata0). 새 `WeeklyProbeTest` 7개:
- `openSpot`·`battle`이 호출되면 실패하는 가짜 드라이버로, 시험 모드가 판 탭·전투·연속 출격 경로를 절대 부르지 않고 성공한 판만 닫는지.
- 판 없음·판 불일치·지도 맞추기 실패 분류 후 다음 퀘스트 계속, 장 못 찾음, 축소 실패, 중단 시 부분 결과.
- 범위 선택(전체·장·켠 퀘스트, 믹틀란·비프리 제외)과 CSV 이스케이프.

## 전체 기기 QA

`py -3 tools/device-qa/run.py --build --serial 127.0.0.1:5555` 성공. 결과 `build\device-qa\20261021-032745`.

| suite | 통과 | 실패 | 기존 skip |
|---|---:|---:|---:|
| support | 185 | 0 | 3 |
| bond | 20 | 0 | 0 |
| questItems | 5 | 0 | 8 |
| noblePhantasm | 18 | 0 | 0 |
| fpSell | 736 | 0 | 0 |
| skillLevel | 83 | 0 | 0 |
| weeklyRoute | 101 | 0 | 0 |
| 합계 | **1148** | **0** | **11** |

beta.6과 같은 케이스·기대값이며 변경하지 않았습니다.

## 커밋·CI

- 제품 커밋 `e7bd087a`, author/committer puruuun, AI 트레일러 없음.
- [Main CI 성공](https://github.com/puruuun/FGA-Modified/actions/runs/39473741204). 성공 후 베타 APK를 빌드했습니다.
- 태그·GitHub Release 없음.

## APK·설치

- `C:\Users\rl\Documents\Codex\FGA-Modified-builds\FGA-beta-1.6.0-beta.7-arm.apk` + `.sha256`
- SHA-256 **`d07145f1dffd53baf05cecbde523c84b3e1f1508aa7315c3c4da55ca5c1e91cd`**
- aapt: `io.github.fate_grand_automata.beta`, `1006007`, `1.6.0-beta.7`, `FGA(beta)`, armeabi-v7a + arm64-v8a.
- BlueStacks **127.0.0.1:5555**에 베타만 `install --no-streaming -r` 성공. 설치 후 1006007/1.6.0-beta.7 확인. 최초 설치 시간 **2026-10-15 23:24:59** 보존. 다른 기기와 게임은 조작하지 않았고 앱도 실행하지 않았습니다.

## 확인 범위

시험 모드의 실제 게임 이동(전 장 순회, 판 닫기, 진단 PNG·CSV 저장, 요약 창)과 5초 대기는 소유자의 실사용 확인이 남아 있습니다. 단위 테스트는 상태기계의 안전 규칙(판을 누르지 않음, 전투 없음)과 분류·CSV를 검증합니다. 시험 중 생성되는 CSV·PNG 위치는 FGA 저장소의 `diagnostics/weeklyRoute/`입니다.
