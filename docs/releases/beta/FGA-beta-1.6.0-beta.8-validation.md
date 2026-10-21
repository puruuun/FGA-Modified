# FGA-beta-1.6.0-beta.8 검증

- 베타 **1.6.0-beta.8 / 1006008**, 정규판 **1.5.0 / 3086** 유지.
- 설계 0009 §15 D-26~D-28.

## 변경

- **D-26:** Free Quest 표시와 전체 서버 퀘스트명 사전으로 투표한 이름의 일치만 판정합니다. AP는 계속 읽고 로그에 남기며, AP가 null이거나 달라도 이름이 일치하면 Match입니다. AP 또는 글자 폭만 일치하는 판은 거절합니다. NotFree 및 기존 4회 판독·대기는 유지합니다. 생산 코드와 기존 기기 QA의 `verdict` 호출부 모두 같은 이름 판정을 사용합니다.
- **D-27:** 판이 한 번이라도 열린 뒤 실패하면 `panel_mismatch last=<마지막 판정> recovery=<복구 사유>`로 기록합니다. 재중앙화 중 지도 맞추기 실패가 나도 원래 판 불일치를 지도 실패로 덮어쓰지 않습니다. 스크롤 뒤 새 지도 맞춤 결과의 원점 이동이 4px 미만이고 현재 지점이 안전하면 그 자리에서 한 번 더 엽니다.
- **D-28:** 720p UI 영역을 §15의 초상화·레벨/AP·선물/미션·MENU 상자로 줄였습니다. 왼쪽/오른쪽 기준과 `height / 720` 배율을 유지하며, 아이콘 중심이 UI 밖이고 둘레 12px가 화면 안이면 탭할 수 있습니다. 원점이 4px 미만 움직인 스크롤은 즉시 중단하며 같은 드래그를 반복하지 않습니다.
- 탐색 시험의 판 탭·전투 금지는 유지합니다. 한국어 설명서의 판정·지도 가장자리·실패 사유 안내를 갱신했습니다.

## 단위 테스트·컴파일·설명서

`./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test :app:compileCiKotlin :app:compileBetaKotlin` 성공.

**612개 통과, 실패 0**: app184, prefs26, scripts402, libautomata0. beta.7의 606개에 새 테스트 6개를 추가했습니다. 기존 지도 가장자리 단위 테스트의 화면 밖 예시는 새 12px 규칙에 맞게 25px에서 11px로 변경했습니다.

새 테스트: AP 없는 정확한 이름, AP·글자 폭만 일치하는 판 거부, NotFree, 판이 열린 뒤 복구 실패 분류, 화면 비율별 UI 영역 및 X-G (243,485), 움직이지 않는 스크롤 정지와 재중앙화 현재 위치 재사용.

계측 Kotlin 컴파일과 MkDocs `build --strict`도 성공했습니다.

## 전체 기기 QA

`py -3 tools/device-qa/run.py --build --serial 127.0.0.1:5555` 성공. 결과: `build\device-qa\20261021-134239`.

| suite | 통과 | 실패 | 기존 skip |
|---|---:|---:|---:|
| support | 185 | 0 | 3 |
| bond | 20 | 0 | 0 |
| questItems | 5 | 0 | 8 |
| noblePhantasm | 18 | 0 | 0 |
| fpSell | 736 | 0 | 0 |
| skillLevel | 83 | 0 | 0 |
| weeklyRoute | 117 | 0 | 0 |
| 합계 | **1164** | **0** | **11** |

- 기존 **1148개 전부 통과**, 신규 **16개 전부 통과**. weeklyRoute는 기존101 + 신규16입니다.
- 기존 기대값 중 **JP-01-wrong-ap / KR-30-wrong-ap의 verdict만 ApMismatch → Match**로 변경했습니다. 이유는 설계 0009 **D-26**: AP는 판정 조건이 아니므로 이름이 맞으면 다른 AP라도 Match입니다. 소유자가 이 두 변경을 승인했으며 다른 기존 기대값은 변경하지 않았습니다.
- 소유자 원본 PNG 14장을 `docs/reference-images/Jp/weekly-route/probe-beta7/`에 바이트 그대로 추가했습니다(1280×720, 리사이즈 없음). 최종 로그 지점의 탭 가능 여부·스크롤 필요 여부·화면 판별 14개와 판이 보이는 8·11번의 실제 이름 판독 2개를 검사합니다.
- X-G 프레임4의 **(243,485)는 tappable=true, needsScroll=false**입니다. 프레임8은 `ロワールの畔`(AP7), 프레임11은 `刃物の町`(AP7)로 읽혀 Match입니다. 이 저장 프레임에서는 AP도 읽혔으므로, AP=null일 때의 Match는 별도의 단위 테스트로 검증했습니다.
- 최초 실행에서는 구 규칙의 두 AP 기대값만 실패했습니다. 승인된 두 기대값을 갱신한 뒤 전체 suite를 다시 실행해 실패 0을 확인했습니다.

## 커밋·CI

- 제품 커밋 `e419a7d8` (author/committer puruuun, AI 트레일러 없음).
- [Main CI 성공](https://github.com/puruuun/FGA-Modified/actions/runs/39546059831). 성공 확인 후 APK를 빌드했습니다.
- 태그·GitHub Release를 생성하지 않았습니다.

## APK·업데이트 설치

- `C:\Users\rl\Documents\Codex\FGA-Modified-builds\FGA-beta-1.6.0-beta.8-arm.apk` 및 `.sha256`.
- SHA-256: **`ad0f334b9a91b7e6b64a9786c5872ad5cbe6bea5289ab134a8e70674108af656`**.
- aapt: 패키지 `io.github.fate_grand_automata.beta`, versionCode **1006008**, versionName **1.6.0-beta.8**, 이름 **FGA(beta)**, ABI **armeabi-v7a + arm64-v8a** 확인.
- apksigner 검증 성공. 저장소 debug 키의 SHA-256 인증서 `5acce47e71373dcfe3a6f6400ed3b3f687bd454aab91c7562c8327a3a083ac9d`와 일치.
- BlueStacks **127.0.0.1:5555**, 모델 **PIXEL 2 XL**, 베타만 `install --no-streaming -r` 결과 **Success**.
- 설치 후 **1.6.0-beta.8 / 1006008** 확인. 최초 설치 시간 **2026-10-15 23:24:59**은 업데이트 전과 동일합니다.
- 다른 기기를 사용하거나 게임을 조작하지 않았습니다. 배포 APK를 설치한 뒤 앱·게임을 실행하지 않았습니다. 기기 QA는 별도 테스트 앱에서 저장된 화면만 읽었습니다.

## 확인 범위

소유자의 beta.7 시험에서 후유키·오를레앙 등 17개 퀘스트 중 드러난 실패를 저장된 프레임과 단위 테스트로 재현해 검증했습니다. beta.8의 실제 재탐색 및 16:10·20:9 기기에서의 UI 경계 확인은 소유자의 실사용 검증이 남아 있습니다.
