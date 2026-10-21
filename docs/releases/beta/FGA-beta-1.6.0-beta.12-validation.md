# FGA-beta-1.6.0-beta.12 검증

- 버전 **1.6.0-beta.12 / 1006012**, 설계 0009 §21 D-38·D-39만 반영했습니다.
- 판 닫기는 지도 화면을 최대 3초씩 기다리며 3회까지 탭합니다. 실패 시 제한과 별도로 진단 PNG를 저장하고, 현재 화면을 확인해 한 번 복구합니다. 루트 중단·예외·사용자 중지에도 PNG를 남깁니다. 실제 루트와 탐색 시험에 공통 적용됩니다.
- 화면 가장자리 60px 이내 지점은 먼저 안쪽으로 끕니다. 이미지 맵은 드래그 뒤 원점 변화, 기하 맵은 원점 범위로 이동 가능 여부를 판단합니다. 움직이지 않을 때 D-33을 사용하며, 판이 안 뜨면 반대 방향으로 한 번 이동해 재시도합니다.
- 93031216 이미지 맵의 원점 -177·y=19, 닫기 1~3회 성공, 3회 실패 뒤 진단·복구 성공/실패, 기하 맵 원점 범위를 회귀 테스트로 확인했습니다.
- 전체 단위 테스트 `:app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test`: **640개 통과, 실패 0** (app 184, prefs 26, scripts 430, libautomata 0).
- 한국어 매뉴얼 `mkdocs build --strict` 성공.
- 전체 기기 QA `tools/device-qa/run.py --build --serial 127.0.0.1:5555`: **1311개 통과, 실패 0, 기존 skip 11개**. 소유자 프레임 `3-q93031216-a0.png` 기반 신규 1건 포함. 해당 프레임은 지도 화면으로 인식되고 y=19에서 안쪽 41px 드래그를 선택했습니다.
- 보고서: `build/device-qa/20261021-200153/`; 단위 테스트·문서·빌드·aapt·apksigner·설치 증거: `build/orchestration/beta12-*`.

| suite | 통과 | 실패 |
|---|---:|---:|
| support | 185 | 0 |
| bond | 20 | 0 |
| questItems | 5 | 0 |
| noblePhantasm | 18 | 0 |
| fpSell | 736 | 0 |
| skillLevel | 83 | 0 |
| weeklyRoute | 264 | 0 |

- 제품 커밋 **`d36ed14a`**, author/committer 모두 `puruuun <188972188+puruuun@users.noreply.github.com>`, AI 트레일러 없음. 명시적 pathspec 스테이징 후 main 직접 푸시.
- [제품 Main CI 성공](https://github.com/puruuun/FGA-Modified/actions/runs/39589201413)을 확인한 뒤 `:app:assembleBeta` 성공.
- APK: `C:\Users\rl\Documents\Codex\FGA-Modified-builds\FGA-beta-1.6.0-beta.12-arm.apk` 및 `.apk.sha256`.
- SHA-256: **`b10469138946174168ea28311a5a96388fc76c54e8ef50f86bdf43c019ba081f`**.
- aapt: `io.github.fate_grand_automata.beta`, **1006012 / 1.6.0-beta.12**, ABI **armeabi-v7a + arm64-v8a**. ZIP 무결성 확인.
- apksigner 검증 성공. 인증서 SHA-256 `5acce47e71373dcfe3a6f6400ed3b3f687bd454aab91c7562c8327a3a083ac9d`.
- BlueStacks **127.0.0.1:5555 / PIXEL 2 XL**만 `install --no-streaming -r`: **Success**. 설치 버전 **1.6.0-beta.12 / 1006012**, 최초 설치 시간 `2026-10-15 23:24:59` 유지.


게임은 조작하지 않았습니다. 기기 QA는 별도 테스트 앱에서 저장 프레임을 판독하며, 실제 게임에서의 재시도 동작은 소유자 확인 범위입니다. HA29GCWH·emulator-5554는 조작하지 않았고 GitHub Release도 생성하지 않았습니다.
