# FGA-beta-1.6.1-beta.1 검증

베타 **1.6.1-beta.1 / 1006101**, 패키지 `io.github.fate_grand_automata.beta`. 정규판은 1.6.0 / 3087 그대로입니다.

- 제품 변경: 설계 0012 와이드 화면(21:9) 대응. 화면 가장자리 버튼·제목·MENU를 `WIDE_EDGE_INSET`(171)로 한 번만 정의해 `xFromLeftEdge` / `xFromRightEdge`로 쓰고, 영기변환 판매 화면, 스킬 강화 화면, 주간 미션 홈·지도·퀘스트 판, 지도 UI 금지 구역, 파티 확인 제목을 고쳤습니다. 16:9 좌표는 바뀌지 않습니다. 자세한 조사 표는 [0012-widescreen-audit.md](../../design/0012-widescreen-audit.md).
- 21:9 캡처 14장(블루스택 창 2000×880에서 게임 영역 1961×840을 잘라 1680×720으로 맞춤)을 `docs/reference-images/Jp/wide-21x9/`에 저장하고 기능별 기기 QA 사례 53개를 추가했습니다.
- 전체 단위 테스트 `:app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test`: **650개 통과, 실패·오류·skip 0** (이전 643). 모듈별 app 186, prefs 26, scripts 438(+7 `WideScreenLocationsTest`), libautomata 0.
- MkDocs `build --strict` 성공.
- 자동 검증은 저장 프레임 판독입니다. 게임은 조작하지 않았습니다. 실제 게임 수동 검증(21:9 → 20:9 체크리스트)은 소유자 범위입니다.

## 배송 검증

- 제품 커밋 `2b182cbd7022d9fa7d6ab881c8402b59db24612e`, main 직접 푸시. author/committer 모두 `puruuun <188972188+puruuun@users.noreply.github.com>`, AI 트레일러 없음.
- [Main CI 성공](https://github.com/puruuun/FGA-Modified/actions/runs/39605736558) 확인 후 `assembleBeta` 성공(같은 커밋).
- 전체 기기 QA **1364개 통과, 실패 0, 기존 skip 11개** (이전 1311개 통과, skip 11개; 와이드 사례 53개 추가). 기존 사례는 모두 그대로 통과했습니다.
- 기기 QA 보고서: `build/device-qa/20261021-220209/`.

| suite | 통과 (이전 → 이번) | 실패 | 기존 skip |
|---|---:|---:|---:|
| support | 185 → 189 | 0 | 3 |
| bond | 20 → 23 | 0 | 0 |
| questItems | 5 → 8 | 0 | 8 |
| noblePhantasm | 18 → 18 | 0 | 0 |
| fpSell | 736 → 762 | 0 | 0 |
| skillLevel | 83 → 89 | 0 | 0 |
| weeklyRoute | 264 → 275 | 0 | 0 |

| APK | SHA-256 |
|---|---|
| `FGA-beta-1.6.1-beta.1-arm.apk` | `d47b873b0276c962a25f2009c5ff7186390a37c3ea34787aa195130d51423d2c` |

- APK와 `.apk.sha256`은 `C:/Users/rl/Documents/Codex/FGA-Modified-builds/`에 저장했습니다. 해시 파일 재대조와 ZIP 무결성 검사 성공.
- aapt: 패키지 `io.github.fate_grand_automata.beta`, `1006101 / 1.6.1-beta.1`, `FGA(beta)`, ABI `arm64-v8a`, `armeabi-v7a`.
- apksigner: 검증 성공. 인증서 SHA-256 `5acce47e71373dcfe3a6f6400ed3b3f687bd454aab91c7562c8327a3a083ac9d`(기존 베타와 같은 키).
- 베타만 BlueStacks `127.0.0.1:5555 / PIXEL 2 XL`에 `install --no-streaming -r`: **Performing Push Install Success**. 설치 버전 `1.6.1-beta.1 / 1006101`, 최초 설치 시각 `2026-10-15 23:24:59` 유지.
- HA29GCWH·emulator-5554는 조작하지 않았고 adb 서버 재시작·disconnect도 하지 않았습니다. 게임·GitHub Release·태그는 조작하거나 만들지 않았습니다.

## 검증하지 않은 범위

- 21:9 캡처가 없는 화면은 현재 코드 경로 그대로입니다(스킬 확인창·결과, 어펜드 화면, FP 소환·경고·확인·완료 창, 메인 레코드·인터루드 목록, 지도의 퀘스트 판, KR 화면, 인연 레벨업 창). 소유자 캡처 요청 목록은 조사 문서 5절.
- 서포트 복합 점수(`+62/+16`) 숫자는 소유자 캡처 화질(창 2000×880을 줄인 그림)에서 템플릿 점수가 0.93–0.96이라 읽지 못하는 경우가 있어 사례에서 숫자를 요구하지 않았습니다. 표시 형식(Compound)과 나머지 판독은 통과합니다.
- 다른 비율(20:9 등)의 가장자리 여백은 21:9와 같다는 원본 FGA 가정에 따릅니다. 소유자가 20:9에서 체크리스트로 확인합니다.
