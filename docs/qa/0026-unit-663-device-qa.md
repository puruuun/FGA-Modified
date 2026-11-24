# PR #663 (Rust 서포트 점수 판독 유닛) 병합 전 전체 기기 QA

- 날짜: 2026-11-24
- 기기: MuMu 15 (127.0.0.1:16416, Android 15, 1920x1080)
- 비교 대상
  - main: `76844a92` (1.8.2-beta.9, 업스트림 v3081 동기화 포함), 엔진 없음(`deviceTest`, `fga.native` 꺼짐)
  - 유닛: `6a34c1fe` (PR #663 head, main 76844a92 포함), 엔진 포함(`--native`, 모델은 고정 파일 `PP-OCRv6_rec_small.onnx`)
- 실행: `tools/device-qa/run.py`. 게임은 조작하지 않음(저장된 화면 샘플을 기기에 넣어 판독기를 실행)
- 실행 도구의 기본 묶음은 8개라서, 나머지 묶음은 `--suite`로 따로 실행함

## 결과

| 묶음 | main 76844a92 | 유닛 6a34c1fe |
|---|---|---|
| support | 231 통과, 실패 0, skip 21 | 231 통과, 실패 0, skip 21 |
| bond | 31 통과, 실패 0 | 31 통과, 실패 0 |
| questItems | 11 통과, 실패 0, skip 8 | 11 통과, 실패 0, skip 8 |
| noblePhantasm | 18 통과, 실패 0 | 18 통과, 실패 0 |
| fpSell | 803 통과, 실패 0 | 803 통과, 실패 0 |
| skillLevel | 194 통과, 실패 0 | 194 통과, 실패 0 |
| weeklyRoute | 334 통과, 실패 0 | 334 통과, 실패 0 |
| rankUp | 5 통과, 실패 0 | 5 통과, 실패 0 |
| skillLevelPanel | 282 통과, 실패 0 | 282 통과, 실패 0 |
| ceEnhance | 36 통과, 실패 0 | 36 통과, 실패 0 |
| hakunoShuffle | 3 통과, 실패 0 | 3 통과, 실패 0 |
| cardSwap | 25 통과, 실패 0 | 25 통과, 실패 0 |
| nativeProbe | (엔진 없음, 실행 안 함) | 7 통과, 실패 0 |
| ocrProbe | (엔진 없음, 실행 안 함) | 12 통과, 실패 0, skip 8 |

- 두 버전의 12개 공통 묶음은 케이스 수, 통과 수, skip 수가 모두 같음
- `ocrProbe`의 skip 8개는 제품 빌드에 넣지 않는 탐침 기능(ONNX Runtime 4개, tract 스레드 4개)이며, 사유가 요약에 항목별로 적혀 있음
- `ocrProbe`의 저장 샘플 판독(`rust-reader`, `kotlin-reader`)은 통과. 수치는 `docs/qa/0025-stage-0-baseline.md` 4.16의 MuMu 재실행과 같은 기준

## 이 QA가 함께 확인한 것 (업스트림 동기화 #652)

- main `76844a92`은 동기화의 확대 보간 변경(INTER_CUBIC)과 빌드 도구 버전 업을 포함함. 영향을 받을 수 있다고 본 판독기의 묶음(bond, noblePhantasm, weeklyRoute, support, questItems)이 모두 실패 0
- `weeklyRoute`의 배너 fixture 30개는 예전 보간으로 만든 파일이지만 334건 모두 통과

## 확인하지 않은 것

- 태블릿과 BlueStacks에서의 전체 묶음(이번에는 MuMu만)
- 실제 게임 화면에서 엔진이 든 베타의 서포트 선택(소유자 확인 항목)
- 소유자 키로 서명한 엔진 포함 베타의 `apk_check.py present`(첫 베타 빌드 때 확인)
  - 참고(2026-11-24): `present` 모드는 설계 0026 PR C에서 없어졌습니다. 지금 베타에 해당하는 검사는 `apk_check.py beta`, 정식은 `apk_check.py stable`입니다. 위 줄은 기록 당시의 표현입니다.
