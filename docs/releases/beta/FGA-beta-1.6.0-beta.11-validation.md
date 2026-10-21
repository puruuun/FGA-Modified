# FGA-beta-1.6.0-beta.11 검증

- 버전 **1.6.0-beta.11 / 1006011**, 설계 0009 §20.
- 변경: `Kr/weekly_mi.png`, 원본·Atlas 배너 출처 기록, 기기 QA 27건, 한국어 매뉴얼 안내, 버전 번호. 자동화 로직은 변경하지 않았습니다.
- 소유자 캡처 37·38(2000×1147)에서 게임 영역 `(0,48)–(1950,1144)`를 잘라 LANCZOS로 1280×720 정규화했습니다. 무손실 WebP와 SHA-256은 `docs/reference-images/Kr/weekly-route/sources.json`에 기록했습니다.
- 버튼은 JP와 같은 내부 영역 크기 **525×70**입니다. KR 캡처의 목록 위치가 61px 낮아 JP `(665,448)` 대신 **`(665,509)`**에서 잘랐습니다. 절대 Y=448은 버튼 위 배경을 포함합니다. 스킬의 `crop_template.py`로 실제 검색 영역 `Region(-1500,200,1500,1140).xFromRight()`에서 재현한 크롭과 픽셀 일치를 확인했습니다.

## KR 버튼·배너 점수

제품과 같은 컬러 매칭·검색 영역·기준을 사용한 BlueStacks 오프라인 기기 QA 결과입니다. 버튼은 캡처 37에서만 true, 배너는 캡처 38에서만 true입니다.

| 캡처 | 버튼 | 배너 307 | 배너 310 |
|---|---:|---:|---:|
| 24 | 0.331409 | 0.378380 | 0.228134 |
| 25 | 0.306901 | 0.221179 | 0.148671 |
| 26 | 0.314306 | 0.295390 | 0.182153 |
| 27 | 0.371964 | 0.350660 | 0.224811 |
| 28 | 0.272877 | 0.127056 | 0.134339 |
| 29 | 0.292678 | 0.307760 | 0.217559 |
| 30 | 0.359255 | 0.250143 | 0.215425 |
| 37 | **1.000000** | 0.341445 | 0.208915 |
| 38 | 0.342812 | **0.958826** | **0.935160** |

Atlas KR 원본 `questboard_cap309.png`(307), `questboard_cap315.png`(310)을 JP와 같은 `BANNER_BODY` `(14,36,420,63)`로 크롭하고 1.25배 확대했습니다. 배너 기준 **0.85**를 통과하며 음성 16건은 모두 기준 미만입니다. Atlas URL·원본 해시는 `docs/reference-images/atlas/weekly-route/sources.json`에 기록했습니다.

## 테스트

- 전체 단위 테스트 `./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test`: **635개 통과, 실패 0**(app184, prefs26, scripts425, libautomata0).
- 전체 기기 QA `tools/device-qa/run.py --build --serial 127.0.0.1:5555`: **1,310개 통과, 실패 0, 기존 skip 11개**.

| suite | 통과 | 실패 | 기존 skip |
|---|---:|---:|---:|
| support | 185 | 0 | 3 |
| bond | 20 | 0 | 0 |
| questItems | 5 | 0 | 8 |
| noblePhantasm | 18 | 0 | 0 |
| fpSell | 736 | 0 | 0 |
| skillLevel | 83 | 0 | 0 |
| weeklyRoute | 263 | 0 | 0 |

- 기존 weeklyRoute 236건의 기대값은 유지했습니다. 신규 27건은 버튼 9건과 배너 18건입니다.
- 기기 QA 보고서: `build/device-qa/20261021-191350/`. 단위 테스트·빌드·서명·설치 증거: `build/orchestration/kr-beta11-*`.
- 한국어 매뉴얼 수정 후 `mkdocs build --strict` 성공.

## 커밋·CI·APK·설치

- 제품 커밋 **`07af13b6`**, author/committer 모두 `puruuun <188972188+puruuun@users.noreply.github.com>`, AI 트레일러 없음. main 직접 푸시.
- [Main CI 성공](https://github.com/puruuun/FGA-Modified/actions/runs/39583033109) 확인 후 `:app:assembleBeta` 성공. [매뉴얼 배포 성공](https://github.com/puruuun/FGA-Modified/actions/runs/39583033182).
- APK 및 `.apk.sha256`: `C:\Users\rl\Documents\Codex\FGA-Modified-builds\FGA-beta-1.6.0-beta.11-arm.apk`.
- SHA-256: **`54db28399d85335daaa7b8a44e2a631c5f02a80e23b8c06f08d97feef69049e0`**.
- aapt: `io.github.fate_grand_automata.beta`, **1006011 / 1.6.0-beta.11**, `FGA(beta)`, ABI **armeabi-v7a + arm64-v8a**. ZIP 무결성과 APK 내 KR 템플릿 원본 일치 확인.
- apksigner 검증 성공(v2), 인증서 SHA-256 `5acce47e71373dcfe3a6f6400ed3b3f687bd454aab91c7562c8327a3a083ac9d`.
- BlueStacks **127.0.0.1:5555 / PIXEL 2 XL**만 `install --no-streaming -r`: **Success**. 설치 후 **1.6.0-beta.11 / 1006011**, 최초 설치 시간 `2026-10-15 23:24:59` 유지.
- HA29GCWH·emulator-5554와 게임은 조작하지 않았습니다. 기기 QA는 별도 테스트 앱에서 저장 프레임만 판독했습니다. 실제 게임 이동은 이번 검증 범위 밖이며, 93020303의 안전한 실패 동작을 유지합니다. GitHub Release는 생성하지 않았습니다.
