# 0004 OCR 판독기 구조 정리와 성능 측정

- 상태: 보류 (2026-10-18). 점수 판독 방식이 [0005](0005-support-score-accuracy.md) 결과에 따라 바뀔 수 있어 B단계는 0005 뒤에 합니다. A단계 측정은 0005의 진단 기록(`elapsedMs`)으로 일부 대체합니다.
- 선행: 0002 완료. A단계(측정)는 0002의 기기 검사기만 있으면 바로 시작할 수 있습니다.
- 예상 규모: A 측정(제품 코드 변경 없음) → B 구조 정리(동작 동일) → C 안내 문구 → D 최적화(조건부). 단계마다 1커밋.

## 1. 배경

mod에서 추가한 OCR 판독기 3개가 같은 패턴을 각자 따로 구현하고 있습니다. 패턴은 "전처리 설정 목록으로 여러 번 읽기 → 신뢰도와 일치 수로 합의 → 실패하면 다음 설정 묶음을 **앞선 결과에 더해** 다시 합의"입니다.

| 판독기 | 단계 구성 | 서버 분기 |
|---|---|---|
| [`BondReader`](../../scripts/src/main/java/io/github/fate_grand_automata/scripts/modules/BondReader.kt) 획득량 | 1차 → 가로 확대 → 압축 (누적) | `:106` KR이면 `GAIN_PROFILES`(6개), 그 외 서버는 전부 `JP_GAIN_PROFILES`(12개) |
| `BondReader` 레벨 | 1차 → 가로 확대 | 없음 |
| [`SupportScoreReader`](../../scripts/src/main/java/io/github/fate_grand_automata/scripts/supportSelection/SupportScoreReader.kt) | 1차 4개 → (KR만) 어두운 임계값 2개 → 템플릿 보조 | `:118` KR만 추가 단계 |
| [`SupportNpLevelReader`](../../scripts/src/main/java/io/github/fate_grand_automata/scripts/supportSelection/SupportNpLevelReader.kt) | 빠른 1회 → 다중 배율 접두어 탐색 + 4개 | `:73` JP는 En 템플릿도 사용, `:86` KR만 낮은 유사도 |

문제는 세 가지입니다.

1. 서버별 차이가 `if (gameServer is Kr)`로 흩어져 있어서, 서버마다 무엇이 다른지 한눈에 보이지 않습니다. NA·CN·TW는 따로 정하지 않았는데도 JP 설정을 쓰고 있습니다.
2. 비용을 모릅니다. JP 인연 결과 화면은 칸당 1차 OCR만 12회라서, 5칸이면 최소 60회입니다. 재시도까지 가면 칸당 최대 18회가 늘어납니다. 실제 시간은 측정한 적이 없습니다.
3. 새 보정을 넣을 때마다 상수와 분기가 하나씩 늘어납니다(1.1.0 이후 커밋 12개 중 5개가 "Recover …").

## 2. 목표 / 비목표

**목표**
- (A) 판독기별 OCR 호출 수와 시간을 블루스택과 **실제 ARM 폰**에서 측정합니다. 설정별로 결과에 얼마나 기여하는지도 집계합니다.
- (B) 단계적 합의 로직을 공통 부품 하나로 모으고, 서버별 설정을 표 하나로 모읍니다. **판독 결과는 한 글자도 바뀌면 안 됩니다.**
- (C) KR·JP 외 서버에서 판독 필터를 켤 때 "검증되지 않음"이 보이게 합니다.
- (D) A의 측정값이 예산을 넘을 때만 설정 수를 줄입니다.

**비목표**
- OCR 모델 교체나 재학습(`eng.traineddata` 유지).
- 판독 임계값과 합의 규칙 변경(D 제외).
- 템플릿 이미지 위치 변경(`En` 폴더에 있는 JP 기반 템플릿 포함). 이번에는 표에 "출처"를 기록하는 것까지만 합니다.
- 원본 FGA 판독 코드(`detectText`, 선물함 OCR 등) 변경.

## 3. 결정 사항

- **D-1. A단계는 제품 코드를 바꾸지 않습니다.** 0002의 `MeasuredOcr`를 확장해서 측정합니다. 실제 폰은 USB 디버깅으로 연결해 `run.py --serial <폰>`으로 같은 케이스를 돌립니다. 기기 검사기는 고정 이미지만 쓰므로 폰에 게임이 없어도 됩니다.
- **D-2. 설정 기여도 집계.** `run.py --trace-ocr`일 때 runner가 OCR 호출마다 `(판독기, 단계, 설정 index, 신뢰도 ≥ 기준 여부, 파싱 결과가 최종 결과와 같은지)`를 모아서 설정별 횟수만 보고합니다. 원문 텍스트는 실패 케이스에서만 넣습니다(0002 D-9의 크기 제한 준수).
- **D-3. 예산(D단계 진입 조건).** 실제 폰 기준 중앙값으로 판단합니다.
  - 인연 결과 화면 1장 판독: **2.0초** 이하
  - 서포트 후보 1개의 점수 + 보구 판독: **0.5초** 이하
  - 둘 다 만족하면 D단계는 하지 않고 측정 결과만 기록합니다.
- **D-4. 공통 부품 `StagedOcr`** (`scripts/.../scripts/ocr/StagedOcr.kt`, 순수 JVM):
  ```kotlin
  /** Reads stage by stage; each stage's readings are added to all earlier ones before deciding. */
  fun <T> readStaged(
      stages: List<List<TextLineOcrOptions>>,
      read: (TextLineOcrOptions) -> OcrText,
      decide: (List<OcrText>) -> T?
  ): Pair<T?, List<OcrText>>
  ```
  - 단계를 앞에서부터 실행하고, 지금까지의 **모든** 결과로 `decide`를 부릅니다. null이 아니면 멈춥니다.
  - 마지막 단계까지 null이면 null과 전체 결과를 돌려줍니다. `SupportScoreReader`는 이 전체 결과로 템플릿 보조와 `/` 판단을 하기 때문입니다.
  - 합의 규칙(`decide`)은 판독기마다 다르므로 각 판독기에 그대로 둡니다. 공통 헬퍼로 `consensus(readings, minConfidence, minAgreement, parse)` 하나만 둡니다. 지금 `BondReader.consensus`와 같은 의미입니다.
- **D-5. 서버별 설정 표 `OcrProfiles`** (`scripts/.../scripts/ocr/OcrProfiles.kt`):
  - 판독기마다 `fun bondGain(server: GameServer): List<List<TextLineOcrOptions>>` 같은 함수를 둡니다. 서버별로 다른 유사도 값(`KR_RECOVERY_PREFIX_SIMILARITY`)과 템플릿 서버 목록(NP의 JP + 기본)도 이 표로 옮깁니다.
  - `when (server)`에 `Kr`, `Jp`를 명시하고, `else` 분기에는 `// Unverified servers reuse the JP profile.` 주석을 달아 JP 값을 씁니다. 지금 동작과 같습니다.
  - 표 위 KDoc에 서버별 템플릿 출처를 적습니다(예: "`En/support_grand_16_*` are JP crops").
- **D-6. 동작이 같은지 이중으로 확인합니다.**
  1. 단위 테스트 `OcrProfilesSnapshotTest`: 리팩터링 **전**의 상수와 분기를 테스트 코드에 그대로 복사해 두고, 모든 `GameServer`에 대해 표가 돌려주는 단계 목록과 **순서까지 같은지** 확인합니다.
  2. 0002 전체 suite를 리팩터링 전과 후에 돌려서 `actual` 값이 케이스마다 같은지 비교합니다. `run.py --compare <이전 report.json>` 옵션을 추가합니다.
- **D-7. 깨지는 Python 도구 처리.** `tools/*.py` 일부는 Kotlin 소스의 상수 선언을 정규식으로 읽습니다(예: `verify_bond_images.py`의 `GAIN_PROFILES = listOf(`). B단계로 깨지는 스크립트는 고치지 말고 **삭제**합니다. 단, 0002의 동등성 표에서 그 스크립트의 라벨이 전부 기기 케이스로 옮겨진 경우에만 삭제합니다. 남은 라벨이 있으면 삭제하지 말고 보고합니다.
- **D-8. C단계 안내는 고정 문구입니다.** [`PreferredSupportScreen.kt`](../../app/src/main/java/io/github/fate_grand_automata/ui/pref_support/PreferredSupportScreen.kt)의 보구 레벨, +62, 그랜드 +16 스위치 묶음 아래에 "KR·JP 서버에서만 검증된 기능입니다." 한 줄을 표시합니다. 서버를 판별하는 로직은 넣지 않습니다. 서버 설정이 자동 감지일 수 있어서 UI 단계에서는 서버를 확정할 수 없기 때문입니다. 기능을 막지도 않습니다.

## 4. 변경 내용

### A단계 — 측정 (제품 코드 변경 없음)

- `app/src/androidTest/.../qa/MeasuredOcr.kt`: 판독기 태그별 집계를 추가합니다. 태그는 호출 스택이 아니라 `Pattern.tag`(`"bond_text"`, `"support_class_grand_scores"`, `"support_np_level"`)로 구분합니다. 판독기들이 이미 `getPattern(tag)`로 태그를 붙입니다.
- `tools/device-qa/run.py`에 `--trace-ocr`을 추가하고, `summary.md`에 "OCR 비용" 절을 넣습니다.
  - 판독기·서버별 호출 수와 시간: 중앙값, p90, 최대
  - 설정별 기여 횟수(D-2)
- 결과를 `docs/design/0004-measurements.md`에 기록합니다. 블루스택 1회, 실제 폰 1회(기종·Android 버전 명시).
- D-3 예산과 비교한 결론을 한 줄로 적습니다.

### B단계 — 구조 정리 (동작 동일)

- 신규: `scripts/.../scripts/ocr/StagedOcr.kt`, `scripts/.../scripts/ocr/OcrProfiles.kt`
- 수정: `BondReader`, `SupportScoreReader`, `SupportNpLevelReader`
  - 각 파일의 프로필 상수와 `prefs.gameServer` 분기를 `OcrProfiles` 호출로 바꿉니다.
  - 단계 반복 코드는 `readStaged`로 바꿉니다.
  - 파싱(`parse`, `parseGain`)과 합의 규칙(`confirmed`, `recoveryLevel`)은 **그대로** 둡니다. 다른 파일과 테스트가 참조하므로 `companion object`의 공개 함수 시그니처를 유지합니다.
  - `SupportScoreReader`의 템플릿 보조 단계와 `legacyClass62` 경로는 `readStaged` 밖에 둡니다.
- 신규 테스트: `scripts/src/test/.../OcrProfilesSnapshotTest.kt`(D-6-1), `StagedOcrTest.kt`(단계 누적, 조기 종료, 전체 결과 반환).
- 기존 테스트(`BondTrackingTest`, `SupportScoreReaderTest`, `SupportNpLevelReaderTest`)는 **수정하지 않고** 통과해야 합니다.
- D-7에 따라 Python 도구를 정리하고 `tools/README.md`를 갱신합니다.

### C단계 — 안내 문구

- `app/src/main/res/values/localized.xml`에 영어 문구를 추가합니다. `values-ko`, `values-ja`에도 추가합니다. 번역 파일 규칙은 [`docs/agents/translations.md`](../agents/translations.md)를 따릅니다.
- `PreferredSupportScreen.kt`에 D-8의 한 줄을 추가합니다.

### D단계 — 최적화 (A에서 예산을 넘은 판독기만)

- 기여 횟수가 **0**인 설정만 제거 후보로 삼습니다. 모든 케이스에서 그 설정이 최종 결과와 같은 값을 신뢰도 기준 이상으로 낸 적이 없다는 뜻입니다.
- 후보를 지운 뒤 0002 전체 suite에서 `actual`이 바뀐 케이스가 **하나도 없어야** 합니다.
- 제거 목록, 근거 수치, 전후 시간을 설계자에게 보고하고 승인받은 뒤 커밋합니다. 제거는 판독 동작을 바꾸는 일이므로 승인 없이 커밋하지 않습니다.

## 5. 완료 기준

- [ ] A: `0004-measurements.md`에 블루스택과 실제 폰의 판독기별 비용, 설정별 기여도, 예산 판정이 기록되었습니다.
- [ ] B: `OcrProfilesSnapshotTest`가 모든 `GameServer` 값에 대해 통과합니다.
- [ ] B: 기존 판독기 테스트는 수정 없이 통과하고, Main CI가 통과합니다.
- [ ] B: `run.py --compare` 결과 리팩터링 전후 `actual` 차이가 0건입니다.
- [ ] B: 판독기 3개 파일에 `prefs.gameServer is GameServer.` 분기가 남아 있지 않습니다(`grep`으로 확인).
- [ ] C: 한국어·일본어·영어 UI에서 문구가 보이는 화면 캡처 3장을 보고합니다.
- [ ] D(진행한 경우): 제거 설정, 근거, 전후 시간, 차이 0건을 보고하고 승인을 받았습니다.

## 6. 검증 방법

- 단위 테스트와 Main CI, 0002 기기 검사(블루스택), A단계에서는 실제 폰 기기 검사.
- 검증하지 않는 범위: 실제 게임 진행 중 체감 속도, NA·CN·TW 판독 정확도(케이스 없음, C단계 문구로 안내만 함).

## 7. 위험과 대응

| 위험 | 대응 |
|---|---|
| 리팩터링 중 단계 순서나 누적 규칙이 미묘하게 바뀜 (예: 재시도에서 1차 결과를 빠뜨림) | D-6의 두 가지 확인. 특히 `SupportScoreReader`는 1차가 성공해도 일부 경우 1차 결과만 반환하는 분기가 있으므로 그대로 옮깁니다. |
| 실제 폰 연결이 어려움 | 소유자에게 기종과 연결 가능 여부를 묻습니다. 불가능하면 블루스택 결과만으로 A를 마치고, 예산 판정은 "보류"로 적습니다. |
| 기여도 0인 설정이 fixture에 없는 화면에서만 필요함 | 그래서 D는 승인을 받은 뒤에만 커밋합니다. 제거한 설정은 `0004-measurements.md`에 남겨서 다시 넣기 쉽게 합니다. |
