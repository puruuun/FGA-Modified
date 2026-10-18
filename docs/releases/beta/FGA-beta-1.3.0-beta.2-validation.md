# FGA 1.3.0-beta.2 검증 보고서 (재림 구분: 서번트 이름 + 보구)

- 버전: 1.3.0-beta.2 (versionCode 1003002), 패키지 `io.github.fate_grand_automata.beta`
- 설계: `docs/design/0006-ascension-np-select.md` §8 (D-9 ~ D-12)
- 커밋 (puruuun, main):
  - `4cce54b0` 보구 띠에 서번트 이름 줄 포함 (scripts 배치, 단위 테스트)
  - `48a7c90c` noblePhantasm 기기 QA를 3×3 블록 매트릭스로 확장 (QA 픽스처, 케이스, 스위트)
  - `f9f8010c` 재림 구분 설정 이름/안내 변경, 1.3.0-beta.2 (strings, 이름 지정 화면, 위키, README)
- Main CI: `f9f8010c` run #9 **성공** (4분 59초)
- 단위 테스트: `:app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test :app:compileCiKotlin` 통과. `scripts` 테스트 226개 실패 0.
- APK: `FGA-Modified-builds\FGA-beta-1.3.0-beta.2-arm.apk`, SHA-256 `f62a0f02db2bd2c7ec36ff6c306a4cdefceac73eaa65c3f63a726cee8a9b869a`
- 설치: `adb -s emulator-5554 install -r` (업데이트, 제거 없음). `dumpsys`: versionCode=1003002, versionName=1.3.0-beta.2.

## 1. 설계 변경 사항 (§8)

| 항목 | 내용 | 결과 |
|---|---|---|
| D-9 | 보구 그림을 서번트 이름 줄 + 보구 줄 두 줄 블록으로 변경. 가로는 카드 아이콘부터 `Lv.` 앞, 세로는 서번트 이름 줄 위부터 보구 줄 아래까지 | 적용 |
| D-9 | `NAME_LINE_OFFSET` 실측 | **62px (1440p)**. 아래 2절 |
| D-10 | 검색 띠를 위로 넓혀 서번트 이름 줄을 포함 (`BAND_TOP_MARGIN` 10px) | 적용. 블록 전체가 띠 안에 들어옴 |
| D-11 | 예전 보구 이름만 있는 그림(beta.1)도 계속 매칭 | 확인 (4절). 저장 폴더 `np`와 설정 키는 그대로 |
| D-11 | 이름 지정 화면에 "예전 보구 이름만 있는 그림은 다시 만드세요" 안내 | 적용 (`support_img_namer_np_hint`, ko/ja/en) |
| D-12 | 기능 이름 "재림 구분 (서번트 이름 + 보구)" (ko), 안내 문구 갱신, 서번트 선호와 함께 고르라는 권장 유지 | 적용 |

## 2. NAME_LINE_OFFSET = 62 측정 근거

서번트 이름 줄과 보구 줄 사이 거리는 같은 행 안에서 고정이다. 이 값으로 블록 상단을 `Lv.` 상단 기준으로 계산한다. 측정은 720p 화면 픽셀 기준 라벨/`Lv.` 텍스트 줄 위치로 했다.

| 화면 | 행 | 이름 줄 y | `Lv.` y | 비고 |
|---|---|---|---|---|
| now (1280x720 기준 픽스처) | row 0 | 304..317 | 335..352 | 친구 코멘트 없음 |
| now | row 1 | 504..517 | 535..552 | |
| owner-150647 | row 0, row 1 | 두 행 동일 | | 같은 이름 두 행 |
| owner-152245 (1920) | アビバ, 코멘트 없음 | 555..576 | 602..628 | 행 bounds 259, 블록이 bounds보다 약 28px 위 |
| owner-152245 (1920) | クロム, 코멘트 있음 | 876..896 | 923..949 | 행 bounds 459 |

- 코멘트 유무와 그랜드 행 모두에서 이름 줄과 보구 줄의 간격이 같다.
- 행 bounds 기준으로는 코멘트 없는 행의 블록이 약 28px 위에 있다. 블록 띠는 이 차이를 덮도록 위로 넓혔고, 여유는 약 34px이다.
- 보구 레벨이 4 미만이면 `Lv.` 글자가 흰색이다. 색과 무관하게 밝은 픽셀로 찾는다(0006 실험 결과 반영).

## 3. 3×3 매트릭스 결과 (noblePhantasm 케이스 9개, 전부 통과)

임계값 `NP_NAME_SIMILARITY` = 0.85. 각 블록 이미지는 자기 행에서만 맞아야 한다. 열은 이미지, 행은 실제 서포트 행이다.

| 케이스 | 행 | 이미지 | 자기 행 점수 | 다른 이미지 최고 점수 | 결과 |
|---|---|---|---|---|---|
| now row 0 | 2nd 재림 행 | Block Melusine Arts | **0.99999899** | Block Melusine Buster 0.70859 / Block Lancelot Arts 0.70228 | 맞음 |
| now row 1 | 3rd 행 | Block Melusine Buster | **0.99999952** | Block Melusine Arts 0.70859 | 맞음 |
| owner-150647 row 0 | 1st 행 | Block Lancelot Arts | **0.99999964** | Block Melusine Arts 0.70228 | 맞음 |
| owner-152245 アビバ (코멘트 없음) | 1st 행 | Block Lancelot Arts | **0.94245** | Block Melusine Arts 0.64315 | 맞음 (여유 작음, 아래 주의) |
| owner-152245 クロム (코멘트 있음) | 1st 행 | Block Lancelot Arts | **0.99808** | Block Melusine Arts 0.71401 | 맞음 |

- 블록 이미지는 같은 보구 이름을 가진 다른 서번트/재림 블록과 0.70 근처에서 떨어진다.
- 주의: 152245 アビバ(코멘트 없음)의 자기 점수는 0.942로 다른 케이스보다 낮다. 임계값 0.85와의 여유는 0.09다. 다른 이미지와의 차이(0.64)는 충분하다. 다음 검증에서 이 행의 블록 상단을 실측 기준으로 다시 맞출 가치가 있다.

### NP-only 이미지 (D-11, beta.1 호환)

| 케이스 | 자기 점수 | 다른 보구 최고 점수 | 결과 |
|---|---|---|---|
| now row 0, Melusine Arts | 0.99999982 (beta.1 기록) | Melusine Buster 0.58779 | 맞음 |
| now row 1, Melusine Buster | 1.0 | Melusine Arts 0.58778 | 맞음 |
| owner-150647 row 0 | 1.0 | Melusine Buster 0.58779 | 맞음 |
| owner-150647 row 1, 다른 NP | 맞지 않음 (null) | | 맞음 |

예전 NP-only 이미지는 계속 맞지만, 같은 보구를 가진 재림끼리는 구분하지 못한다. 그래서 안내에 다시 만들라고 적었다.

## 4. 기기 QA 전체 결과 (emulator-5554, 2026-10-18 18:53 실행)

| 스위트 | 결과 | 세부 |
|---|---|---|
| support | 통과 | 185개 통과, 실패 0, skip 3 (기존과 동일) |
| bond | 통과 | 20개 통과, 실패 0, skip 0 |
| questItems | 통과 | 5개 통과, 실패 0, skip 8 (범위 밖 케이스, 기존과 동일) |
| noblePhantasm | 통과 | 9개 통과, 실패 0, skip 0, OCR 호출 0 |

기존 스위트 결과(support 185/3, bond 20, questItems 5/8)는 변하지 않았다. 실행 로그는 `build/device-qa/20261018-185349/`에 있다.

## 5. 한계와 남은 일

- **일반(비그랜드) 행의 카드 아이콘 x 좌표 528은 아직 추정치다.** 측정하지 않았다. 일반 행 픽스처가 없어서 `NORMAL_ICON_X` 검증이 빠졌다. 그랜드 행 821은 실측이다.
- 코멘트 없는 그랜드 행은 아비바 한 장뿐이다. 다른 코멘트 없는 그랜드 행을 추가하면 블록 상단 여유를 더 확인할 수 있다.
- 소유자가 일반 행 화면(메리·멜뤼진 두 재림이 같은 보구 이름을 쓰는 화면)을 주면 `NORMAL_ICON_X`를 실측하고 케이스를 추가한다.
- 이 보고서의 실기 판정은 에뮬레이터 BlueStacks(`emulator-5554`)에서 한 것이다. 실제 기기 화면 해상도 차이는 확인하지 않았다.

## 6. 테스트 절차

1. `./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test :app:compileCiKotlin`
2. `./gradlew :app:assembleBeta`, aapt로 패키지/versionCode/versionName 확인
3. `adb -s emulator-5554 install -r <APK>`, `dumpsys package` 로 버전 확인
4. `py -3 tools/device-qa/run.py --serial emulator-5554 --suite support,bond,questItems,noblePhantasm`
