# 0002 기기 판독 검증 일원화

- 상태: 구현 대기
- 선행: 0001 (Main CI가 동작해야 함)
- 예상 규모: androidTest 코드 분리·확장, 케이스 파일 추가, 호스트 실행 스크립트 1개. 단계별로 3개 커밋.

## 1. 배경

화면 판독(서포트 점수·보구 레벨·스킬, 인연 획득량, 티포트·스톰포드 상태)은 지금 세 곳에서 따로 검증됩니다. 그런데 셋 다 "실제 앱 코드가 실제 이미지를 맞게 읽는가"를 완전히 보장하지 못합니다.

| 층 | 위치 | 실제 앱 코드를 쓰나 | 한계 |
|---|---|---|---|
| JVM 단위 테스트 | `scripts/src/test/...` | 판정 로직만 | OCR·이미지 매칭을 가짜로 대체함 |
| Python 검증 | `tools/*.py` | ❌ | Kotlin 소스에서 상수를 정규식으로 긁어 오고, 판독 순서는 Python으로 다시 짬. PC용 OpenCV/Tesseract 사용. CI에서 돌지 않음 |
| 기기 검사기 | `app/src/androidTest/.../qa/SupportDeviceTestRunner.kt` | ⭕ | **서포트 화면만** 지원. 케이스 파일이 저장소 밖(`/sdcard/Download/fga-device-qa/`)에만 있음. 실행 절차도 저장소에 없음 |

기기 검사기는 제품 코드 그대로(`ImageLoader`, `DroidCvPattern`, `TesseractOcrService`, 판독기와 선택기)를 블루스택에서 고정 이미지로 실행합니다. 따라서 **판정 기준이 될 수 있는 유일한 층**입니다.

## 2. 목표 / 비목표

**목표**
- 기기 검사기를 판독 검증의 **단일 판정 기준**으로 만듭니다.
- 대상: 서포트(보구·점수·스킬·이미지 매칭), 인연, 티포트·스톰포드.
- 케이스 정의와 기대값을 저장소에 버전 관리합니다.
- 명령 하나로 빌드 → 설치 → 이미지 전송 → 실행 → 보고서 생성까지 끝나게 합니다.
- Python 검증이 확인하던 라벨을 전부 기기 케이스로 옮깁니다(동등성 확보).
- 케이스마다 OCR 호출 수와 소요 시간을 기록합니다. 0004의 측정 자료로 씁니다.

**비목표**
- GitHub Actions에서 기기 검사 실행. APK가 ARM 전용이라 x86 에뮬레이터에서 돌지 않습니다.
- 판독 임계값이나 OCR 설정 변경. 실패가 나오면 **고치지 말고 보고**합니다.
- Python 스크립트 삭제. 이번에는 역할 표시만 바꿉니다.
- 예장 이미지 생성 검증(`verify_imported_ce_images.py`) 이전. 다음 단계로 미룹니다.
- 라이브(실제 게임 화면) 모드 변경. 현재 동작을 유지합니다.

## 3. 결정 사항

- **D-1. 판정 기준.** 베타를 정규판으로 승격하려면 기기 검사가 KR·JP 케이스 모두 통과해야 합니다. Python 결과는 참고용입니다.
- **D-2. 케이스 위치.** `docs/reference-images/cases/<suite>.json`. 이미지와 라벨을 한 곳에 둡니다. 기존 이미지 파일은 **옮기지 않습니다**. Python 도구가 기존 경로를 씁니다.
- **D-3. 케이스 형식 v1.**
  ```json
  {
    "schema": 1,
    "suite": "support | bond | questItems",
    "cases": [
      {
        "name": "고유 이름",
        "server": "Kr | Jp | En | Cn | Tw",
        "file": "docs/reference-images/... (저장소 기준 상대 경로)",
        "frame": "full | crop",
        "region720": [0, 0, 0, 0],
        "masked720": [[0, 0, 0, 0]],
        "expect": {}
      }
    ]
  }
  ```
  - `full`: 16:9 전체 화면. 지금처럼 1280×720으로 줄여서 씁니다.
  - `crop`: 잘라낸 이미지. 1280×720 검은 캔버스의 `region720` 위치에 붙여서 화면 한 장을 만듭니다. 서포트 케이스는 `region720` 대신 `rowY720`을 줄 수 있습니다(D-5).
  - `masked720`: 개인정보 때문에 가린 영역. 선택 항목입니다.
  - `expect`의 형식은 suite마다 다릅니다(4.3).
- **D-4. 영역 범위 검사.** 판독기가 화면에서 읽는 모든 영역(`getPattern`, `findAll`, `exists`의 대상 Region)을 기록합니다.
  - `crop` 케이스: 읽은 영역 중 하나라도 `region720` 밖으로 나가면 케이스를 **실패**로 처리합니다("fixture does not cover region").
  - `masked720`: 읽은 영역과 겹치면 실패로 처리합니다.
  - 이유: 잘라낸 이미지 밖을 읽으면 검은 픽셀을 읽은 셈이 됩니다. 그러면 전체 화면과 다른 결과가 나와도 통과처럼 보일 수 있습니다.
- **D-5. 서포트 crop의 행 위치.** `SupportBoundsFinder`는 검은 캔버스에서 행을 찾지 못합니다. 그래서 crop 케이스는 `rowY720`으로 `SupportBounds(locations.support.defaultBounds.copy(y = rowY720 * 2))`를 직접 만듭니다. `region720`은 레이아웃 함수(`SupportScoreLayout.numberRegion` 등)로 계산합니다.
  - 계산한 영역 크기가 crop 이미지 크기와 다르면 실패로 처리합니다.
  - 기존 JP 점수 manifest의 `rowY720`이 이 정의(`bounds.region.y / 2`)와 같은 뜻인지 먼저 확인합니다. 원본 전체 화면이 있는 케이스 하나로 비교합니다. 뜻이 다르면 변환식을 문서에 적고 보고합니다.
- **D-6. 호스트 실행 스크립트는 Python 표준 라이브러리만 씁니다.** `tools/device-qa/run.py`. 여기서 Python은 adb 호출과 파일 전송만 맡고, 판독 로직은 넣지 않습니다. 사용자 PC에는 이미 Python이 있습니다.
- **D-7. 검사 APK는 제품과 분리합니다.** 기존 `deviceTest` 빌드 타입(`.qa` 접미사)을 그대로 씁니다. 사용자가 설치한 FGA(mod)·베타 앱은 건드리지 않습니다.
- **D-8. 게임 조작 금지 유지.** 고정 이미지 케이스에서는 모든 클릭을 막습니다. 라이브 모드의 스킬 표시 전환 탭 하나만 지금처럼 허용합니다.
- **D-9. 보고서 크기.** instrumentation 결과 Bundle에는 요약만 담습니다. 케이스별 통과 여부, 실제값, 시간을 넣고, 실패 케이스만 상세 오류를 넣습니다. 256KB를 넘으면 실행을 실패로 처리합니다.
- **D-10. Python 도구의 역할 표시.** `tools/README.md`를 새로 만듭니다. 각 `tools/*.py` 첫 docstring에 "Development aid. The authoritative check is `tools/device-qa/run.py`." 한 줄을 추가합니다. 로직은 바꾸지 않습니다.

## 4. 변경 내용

### 4.1 androidTest 코드 분리 (1단계)

`app/src/androidTest/java/io/github/fate_grand_automata/qa/` 아래:

- `SupportDeviceTestRunner.kt`: 클래스 이름은 유지합니다. `app/build.gradle.kts`의 `testInstrumentationRunner`가 이 이름을 참조합니다. 여기에는 instrumentation 진입, 케이스 파일 읽기, `suite`별 분기, 보고서 조립만 남깁니다.
- `FixtureApi.kt`: 지금의 `DeviceApi`를 옮기고 확장합니다.
  - `crop` 캔버스 합성(D-3)
  - 읽은 영역 기록과 범위 검사(D-4)
  - 서버 설정
  - 클릭 차단(D-8)
- `MeasuredOcr.kt`: `TesseractOcrService`를 감싸서 `detectTextLine`과 `detectText`의 호출 수와 누적 시간을 셉니다(테스트 코드). 케이스 결과에 `ocrCalls`, `ocrMs`, `elapsedMs`로 넣습니다.
- `suites/SupportSuite.kt`: 지금 `DeviceApi.verify()`의 내용을 옮깁니다. 동작은 바꾸지 않습니다.
- `suites/BondSuite.kt`, `suites/QuestItemSuite.kt`: 3단계에서 추가합니다.

### 4.2 호스트 스크립트 `tools/device-qa/run.py` (1단계)

```
py -3 tools/device-qa/run.py [--serial 127.0.0.1:5555] [--build] [--suite support,bond,questItems] [--server Kr,Jp]
```

이 PC의 실행 환경(2026-10-18 확인):
- Python 3.13은 `py -3`로 실행합니다. `python`은 Windows 스토어의 빈 실행 파일(`WindowsApps\python.exe`)이 먼저 잡혀서 실행되지 않습니다. 문서와 `tools/README.md`의 예시 명령도 `py -3 tools/device-qa/run.py …`로 적습니다.
- adb가 세 군데에 있습니다: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`, `C:\platform-tools\adb.exe`, `C:\Program Files\BlueStacks_nxt\HD-Adb.exe`. 버전이 다른 adb를 섞어 쓰면 서로 adb 서버를 종료시킵니다. 그래서 2단계의 탐색 순서대로 **하나만** 골라 끝까지 쓰고, 고른 경로와 `adb version`을 보고서에 적습니다.

1. `--build`면 `./gradlew -PfgaDeviceTests=true :app:assembleDeviceTest :app:assembleDeviceTestAndroidTest`를 실행합니다(Windows는 `gradlew.bat`).
2. adb를 찾습니다. 순서: `ANDROID_HOME/platform-tools`, `PATH`, 블루스택 기본 설치 경로의 `HD-Adb.exe`. 찾은 경로를 출력합니다.
3. 두 APK를 `install -r -t`로 설치합니다. 패키지 이름은 빌드 결과에서 확인하고 하드코딩하지 않습니다(`aapt dump badging`이나 gradle 출력 사용).
4. 선택한 suite의 케이스 파일을 읽습니다. 참조된 이미지만 `/sdcard/Download/fga-device-qa/<저장소 상대 경로>`로 `push`합니다. 경로는 기존 runner의 정규식 제한(`[A-Za-z0-9._/-]+`)을 지켜야 합니다. 어긋나는 파일명이 있으면 실행 전에 실패로 처리합니다.
5. 케이스의 `file`을 기기 경로로 바꾼 묶음 JSON을 전송합니다. 그다음 `am instrument -w -r -e cases <경로> <test 패키지>/<runner>`를 실행합니다.
6. 결과를 `build/device-qa/<YYYYMMDD-HHMMSS>/report.json`과 `summary.md`(한국어)로 저장합니다. 요약에는 suite·서버별 통과/실패 수, 실패 케이스 목록, 케이스별 OCR 호출 수·시간의 상위 10개를 넣습니다.
7. 하나라도 실패하면 종료 코드 1을 반환합니다.

### 4.3 suite별 `expect` 형식

- **support**: 기존 runner 형식을 그대로 씁니다(`view`, `rows[].y|np|class|grand|skills|matches|appendSlot2`). crop 케이스에서는 `rows`가 한 행이고 `y` 대신 `rowY720`을 씁니다. 행마다 `policy: {classOnly, grandOnly, bothScores}`를 추가로 받을 수 있습니다. 비교 대상은 `SupportScoreSelection.matches` 결과입니다.
- **bond**: `kind: normal | levelUp`.
  - normal: `columns`는 5개 원소 배열이고, 각 원소는 `[base, bonus]`, `"MAX"`, `"UNKNOWN"` 중 하나입니다. 비교 대상은 `BondReader.read()`의 `BondFrame.Normal`입니다.
  - levelUp: `level`(정수 또는 null)과 `column`(정수 또는 null)을 비교합니다.
  - 형식은 기존 `tools/bond-image-cases.json`과 맞춥니다.
- **questItems**: `QuestItemReader`가 돌려주는 상태를 그대로 비교합니다. 필드 이름은 리더의 반환 타입을 따르고, `verify_quest_item_images.py`의 라벨을 옮깁니다.

### 4.4 기존 라벨 이전 (2·3단계)

| 출처 | 대상 suite | 비고 |
|---|---|---|
| 블루스택 `/sdcard/Download/fga-device-qa/`에 있는 기존 서포트 케이스 | support | 가능하면 `adb pull`로 가져와 저장소로 옮깁니다. 없으면 이 단계는 건너뛰고 보고합니다. |
| `docs/reference-images/Jp/support-scores/manifest.json` | support (crop) | `grand16`, `compactClass62`, `ordinaryClass62` 불리언을 `policy` 기대값으로 바꿉니다. 바꾸는 규칙은 `tools/verify_support_grand_score_images.py`의 `policy`와 **똑같이** 맞춥니다. |
| `docs/reference-images/Kr/support-scores/manifest.json` | support (crop) | 위와 같습니다. |
| `docs/reference-images/Kr/support-np-20261017/manifest.json`과 `validate_support_np.py`의 추가 케이스 | support (crop) | `level`을 `np`로 옮깁니다. 이름 영역 crop이 D-4 범위 검사를 통과하는지 확인합니다. |
| `tools/bond-image-cases.json` | bond (full) | 그대로 옮깁니다. |
| `docs/reference-images/quest-items-crops.json`과 `verify_quest_item_images.py`의 라벨 | questItems (crop) | `region_720p`를 `region720`으로 옮깁니다. |

- 이전하면서 **기대값을 바꾸지 않습니다.** 기기 결과가 Python 라벨과 다르면 실패로 남기고 보고합니다.
- crop이 D-4 범위 검사에 걸리면 해당 케이스는 `"skip": "coverage"`로 표시해 실행에서 빼고 목록으로 보고합니다. 원본 전체 화면에서 다시 자르는 일은 별도 작업으로 넘깁니다.

### 4.5 문서

- `docs/reference-images/README.md` (신규): fixture 추가 규칙. 플레이어 이름과 친구 코드를 넣지 않습니다. 전체 서포트 화면은 이름 영역을 가리고 `masked720`에 기록합니다. 새 fixture는 판독 영역만 잘라서 넣습니다. 케이스 파일에 라벨을 추가하는 방법도 적습니다.
- `tools/README.md` (신규): D-10 내용, 실행 방법, 블루스택 adb 설정(ADB 활성화) 안내.
- `docs/agents/automation-scripts.md`에 한 줄 추가: 판독기를 바꾸면 `tools/device-qa/run.py` 결과를 함께 보고합니다.

## 5. 완료 기준

- [ ] 1단계: `py -3 tools/device-qa/run.py --build --suite support` 한 번으로 기존 서포트 케이스가 실행되고 `summary.md`가 생성됩니다.
- [ ] 2단계: JP·KR 점수와 KR 보구 crop 라벨이 전부 케이스로 옮겨졌습니다. 출처별 라벨 수와 케이스 수(skip 포함)를 표로 보고합니다.
- [ ] 3단계: 인연과 티포트 suite가 추가되었고, `bond-image-cases.json`과 퀘스트 아이템 라벨이 전부 옮겨졌습니다.
- [ ] 전체 실행이 KR·JP 모두 통과합니다. 실패가 있으면 임계값을 고치지 않고 실패 목록을 보고합니다(이 경우 미통과로 표시).
- [ ] 음성 검사: 로컬에서만 `SupportScoreReader.MIN_CONFIRM_CONFIDENCE`를 101로 바꿔 실행하면 점수 케이스가 실패합니다. 확인 후 되돌리고 커밋하지 않습니다.
- [ ] D-4 검사: 일부러 `region720`을 10px 줄인 케이스 하나를 로컬에서 만들면 "does not cover region"으로 실패합니다.
- [ ] 모든 케이스 결과에 `ocrCalls`, `ocrMs`, `elapsedMs`가 들어 있습니다.
- [ ] 단위 테스트와 Main CI가 통과합니다. 제품 코드(`app/src/main`, `scripts/src/main`)에는 변경이 없습니다.

## 6. 검증 방법

- 블루스택 인스턴스 하나(16:9)에서 실행합니다. 서버는 케이스마다 `server` 값으로 바뀌므로 게임 설치는 필요 없습니다.
- 보고: `summary.md` 전문, 실행 시간 합계, 실패와 skip 목록.
- 검증하지 않는 범위: 실제 게임 진행 중 판독, 16:9가 아닌 화면 비율, NA·CN·TW 서버(케이스 없음), 실기기(ARM 폰) 성능.

## 7. 위험과 대응

| 위험 | 대응 |
|---|---|
| 기존 crop이 판독기가 읽는 영역보다 작아서 대량으로 skip됨 | skip 목록을 보고합니다. 원본 전체 화면을 가린 버전으로 바꾸는 작업은 설계자가 별도로 정합니다. |
| Python 라벨과 기기 결과가 다름 | 그게 이 작업이 찾으려는 정보입니다. 고치지 말고 케이스 이름, 기대값, 실제값, OCR 원문을 보고합니다. |
| instrumentation 결과가 잘림 | D-9 요약 형식으로 줄이고, 그래도 크면 suite별로 나눠 실행합니다. |
| 블루스택 adb 포트가 인스턴스마다 다름 | `--serial` 인자로 받고, 기본값과 함께 `adb devices` 결과를 출력합니다. |
