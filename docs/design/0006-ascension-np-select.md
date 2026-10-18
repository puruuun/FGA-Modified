# 0006 재림구분기능 (보구 이름으로 서포트 구분)

- 상태: 구현 대기
- 선행: 0002(기기 검사), 0003(버전 파일)
- 버전: 베타 **1.3.0-beta.1** (`1003001`). 정규판 값은 그대로 둡니다.
- 예상 규모: 이미지 종류 1개 추가, 선택 조건 1개 추가, 서포트 이미지 메이커와 이름 지정 화면 확장, 설정 UI, 기기 검사 케이스

## 1. 배경

서포트 목록의 각 행에는 서번트 이름 아래에 **보구 줄**(카드 아이콘 + 보구 이름 + `Lv.N`)이 있습니다. 일부 서번트는 재림 단계에 따라 보구 이름이나 카드 종류가 바뀝니다. 예를 들어 メリュジーヌ는 「今は知らず、無垢なる湖光」(Arts)와 「誰も知らぬ、無垢なる鼓動」(Buster)로 나뉩니다. 재림에 따라 스킬도 달라지므로, 소유자는 **특정 재림의 서포트만 고르는 기능**을 원합니다.

현재 서포트 이미지 메이커(`SupportImageMaker`)는 서번트, 예장, 친구 이름만 잘라 저장합니다. 선호 서포트 조건도 이 세 가지뿐입니다.

**레이아웃 차이.** 행이 **그랜드 서번트**이면 왼쪽에 예장 3칸 열(`概念礼装`)이 붙어서 보구 줄이 오른쪽으로 밀립니다(1920 폭 기준 보구 칸 시작이 약 580px). 일반 행은 보구 칸이 약 360px에서 시작합니다. FGA는 이미 행마다 그랜드 여부를 판별합니다(`SupportSelectionGrandChecker`, `SupportImageMaker.isGrandServant`). 보구 레벨 판독기(`SupportNpLevelReader`)는 두 레이아웃을 모두 덮는 가로 띠(`SupportNpLayout.nameRegion`) 안에서 `Lv.` 위치를 찾는 방식으로 이미 양쪽에서 동작합니다.

소유자 화면에서 확인한 점: 같은 보구(オルガマリー 「すでに過ぎし人理の終」)가 그랜드 행과 일반 행에서 **같은 글자 크기와 폭**으로 표시됩니다.

## 2. 목표 / 비목표

**목표**
- 서포트 이미지 메이커가 각 행의 **보구 이름 이미지**도 잘라 저장하고, 이름 지정 화면에서 다른 이미지와 같은 방식으로 이름을 붙여 저장할 수 있게 합니다.
- 선호 서포트 설정에 **"재림 구분 (보구 이름)"** 목록을 추가합니다. 서번트 조건을 통과한 후보 중, 선택한 보구 이미지가 그 행의 보구 줄에 있는 후보만 통과합니다.
- 그랜드 행과 일반 행, 친구 코멘트가 있는 행과 없는 행 모두에서 동작합니다.
- JP·KR 모두 동작합니다. 이미지는 사용자가 자기 서버 화면에서 만듭니다.

**비목표**
- 문자 인식(OCR). 이미지 매칭만 씁니다.
- 보구 이름이 같은 재림의 구분. 이 방법의 한계로, 설정 화면 안내에 적습니다.
- 내장 보구 이미지 제공, 원본 FGA 가져오기 대상 추가, 서번트 번호 이미지 생성기와의 연동.
- 보구 레벨 조건 변경. 기존 보구 레벨 판독은 그대로 둡니다.

## 3. 결정 사항

- **D-1. 판독은 "넓게 찾기" 방식입니다.** 보구 줄이 나올 수 있는 가로 띠 전체에서 보구 이미지를 템플릿 매칭으로 찾습니다. 레이아웃을 판별해 위치를 옮기는 방식은 쓰지 않습니다. 매칭은 띠 안에서 가장 잘 맞는 자리의 점수만 보므로, 띠 안에 예장 그림 등 다른 요소가 있어도 결과에 영향이 없습니다.
  - 띠의 세로 범위는 `SupportNpLayout.nameRegion`과 같습니다. 친구 코멘트 유무를 이미 포함하는 범위입니다.
  - 가로 범위는 `nameRegion`을 왼쪽으로 넓혀 **두 레이아웃의 카드 아이콘이 모두 들어오게** 합니다. 정확한 값은 [`docs/agents/coordinates.md`](../agents/coordinates.md)를 지켜 fixture로 정하고, 상수 이름과 근거를 `SupportNpLayout`에 주석으로 남깁니다.
- **D-2. 보구 이미지는 "카드 아이콘부터 `Lv.` 바로 앞까지"입니다.** `Lv.N`과 보구 강화 표시(▲)는 넣지 않습니다. 같은 재림인데 보구 레벨만 달라도 이미지가 달라지기 때문입니다.
  - 오른쪽 끝: 기존 `Images.SupportNpLevelPrefix` / `SupportNpLevelPrefixGold`(서버별, JP는 기본 서버 대체 포함)로 `Lv.` 위치를 찾고, 그 왼쪽 몇 px까지 자릅니다. `SupportNpLevelReader`가 템플릿을 고르는 방식을 그대로 재사용합니다.
  - 왼쪽 끝: 행이 그랜드인지(`isGrandServant`)에 따라 카드 아이콘 왼쪽 위치 상수 두 개 중 하나를 씁니다.
  - 위아래: `Lv.` 매칭 영역의 높이를 기준으로 글자가 잘리지 않을 만큼만 여백을 둡니다. 파란 보구 칸 바깥(예장 그림, 서번트 이름 줄)은 포함하지 않습니다.
  - `Lv.`를 찾지 못한 행은 보구 이미지를 저장하지 않고 넘어갑니다(서번트·예장 이미지는 지금처럼 저장).
- **D-3. 이미지 종류와 저장 위치.** `SupportImageKind.NoblePhantasm`을 추가하고, 저장 폴더는 서포트 이미지 폴더 아래 `np/`입니다. 한 항목에 여러 장을 둘 수 있게 서번트·예장과 같은 하위 폴더 규칙(`이름/파일.png`)을 지원합니다. 레이아웃별로 이미지가 다르게 보이는 경우를 대비한 것입니다.
- **D-4. 설정.** `ISupportPreferences.preferredNoblePhantasms: List<String>`, 저장 키 `support_pref_np_list`(stringSet). 비어 있으면 조건을 적용하지 않습니다(항상 통과).
- **D-5. 판정 순서.** `PreferredSupportSelection.isMatch`에서 **서번트 → 보구(재림) → 점수 → 예장 → 친구** 순서로 검사합니다. 보구 매칭은 비용이 작고, 뒤의 점수 OCR을 줄여 줍니다.
  - 선택 기록: 행마다 `ScriptLog.SupportNoblePhantasm(matched, 최고 점수)`를 남깁니다(태그 `FGA.SupportNp`, INFO). 진단과 실기 확인에 씁니다.
  - `SupportSelectionPreferredNotSet` 판정에는 보구 목록도 "설정됨"으로 포함합니다.
- **D-6. 유사도.** 상수 `NP_NAME_SIMILARITY = 0.85`에서 시작합니다. fixture 결과로 조정하되 **0.80 아래로 내리지 않습니다.** 낮춰야만 통과한다면 이미지 자르는 방식을 의심하고 보고합니다.
- **D-7. 가로 압축 대비.** 긴 보구 이름이 좁은 그랜드 행에서 가로로 눌려 표시될 가능성이 있습니다. 1차 구현은 **배율 1.0만** 씁니다. fixture에서 같은 보구가 레이아웃에 따라 맞지 않는 사례가 나오면, 그때 가로 배율 `[1.0, 0.95, 0.9, 0.85]` 검색을 추가하고 그 근거를 보고합니다. 사용자는 D-3의 여러 장 저장으로도 대응할 수 있습니다.
- **D-8. UI 문구(한국어 기준).** 기능 이름 "재림 구분 (보구 이름)". 안내: "서포트 이미지 메이커로 만든 보구 이름 이미지로 재림을 구분합니다. 재림이 달라도 보구 이름과 카드가 같으면 구분할 수 없습니다."

## 4. 변경 내용

### 4.1 scripts
- `SupportImageKind`(두 위치 모두 확인)에 `NoblePhantasm` 추가
- `locations/SupportNpLayout.kt`: D-1의 검색 띠 함수 `noblePhantasmBand(bounds)`, D-2의 자르기용 상수(그랜드/일반 왼쪽 끝, 여백)
- `supportSelection/NoblePhantasmSelection.kt`(신규, `@ScriptScope`): `check(entries: List<String>, bounds: SupportBounds): Boolean`. 비어 있으면 true. 각 항목의 이미지들을 `images.loadSupportPattern(SupportImageKind.NoblePhantasm, entry)`로 읽어, 띠 안에서 하나라도 D-6 기준 이상이면 true. 로그(D-5)를 남깁니다. 템플릿 소유권 규칙(빌려 온 템플릿은 닫지 않음)을 지킵니다.
- `PreferredSupportSelection`: D-5의 순서로 연결, `SupportSelectionPreferredNotSet` 조건 갱신
- `entrypoints/SupportImageMaker.kt`: 행마다 `np_{i}.png`를 D-2대로 저장. `getNpImgPath(dir, i)` 추가
- `prefs/ISupportPreferences.kt`, `IScriptMessages.kt`(로그 항목)

### 4.2 prefs
- `SupportPrefsCore`: `preferredNoblePhantasms = maker.stringSet("support_pref_np_list")`, `SupportPreferences`에 연결

### 4.3 app
- `StorageProvider`: `np` 폴더, `SupportImageKind.NoblePhantasm` 분기. `SupportImageExtractor`처럼 종류별 `when`이 있는 곳은 NP를 "내장 이미지 없음"으로 처리합니다(컴파일 경고·누락 방지).
- `ui/support_img_namer/SupportImageNamer.kt`와 해당 레이아웃 XML: 보구 이미지 2칸(`np_0`, `np_1`) 추가. 파일이 없으면(D-2에서 건너뛴 경우) 그 칸은 숨깁니다.
- `ui/pref_support/`: `SupportViewModel`에 NP 이미지 목록, `PreferredSupportScreen`에 D-8 섹션(예장 선호 목록과 같은 다중 선택 UI). 위치는 "서번트 선호" 바로 아래.
- 문자열: `values/localized.xml`(영어), `values-ko`, `values-ja`. [`docs/agents/translations.md`](../agents/translations.md) 준수.

### 4.4 테스트와 fixture
- 단위 테스트(`scripts/src/test`): 목록이 비면 통과, 하나라도 맞으면 통과, 모두 낮으면 탈락, 판정 순서(보구가 탈락하면 점수 판독기를 부르지 않음), `SupportSelectionPreferredNotSet` 갱신.
- 기기 검사(0002 형식, `docs/reference-images/cases/` 아래 새 suite `noblePhantasm` 또는 support suite 확장):
  1. **같은 보구가 그랜드 행과 일반 행에 모두 있는 화면**에서 한 장의 보구 이미지가 두 행 모두에 맞는지
  2. 같은 서번트의 **다른 재림 보구**(예: メリュジーヌ 두 보구)가 서로 맞지 않는지
  3. 그랜드 행(예장 그림이 띠에 걸치는 행)에서 무관한 보구 이미지가 맞지 않는지(오탐 0)
  4. 친구 코멘트 있는 행과 없는 행
  - fixture 원본은 블루스택 `exec-out screencap`(1920×1080) 전체 화면을 쓰고, **친구 이름·코멘트 영역은 `masked720`으로 가립니다**(`docs/reference-images/README.md` 규칙). 기존 `build/live-qa/jp-owner/20261018-150641/shots/`의 JP 그랜드 랜서 화면을 우선 활용하고, 일반 행·メリュジーヌ 두 재림이 필요한 화면은 **소유자에게 수집을 요청**합니다(구현 agent는 게임을 조작하지 않습니다).
  - 보구 이미지 템플릿은 fixture 화면에서 D-2 규칙으로 잘라 만들고, 자른 좌표를 케이스 파일에 남깁니다(이미지 메이커와 같은 규칙을 쓰는지 확인하는 의미).

### 4.5 버전과 문서
- `version.properties`: beta `1.3.0-beta.1` / `1003001`
- `wiki/scripts/Ascension-Select-KR.md`(신규, 한국어 사용 안내: 이미지 만들기 → 이름 붙이기 → 설정에서 선택, 한계)와 `README.md` 기능 표 한 줄
- `docs/releases/beta/FGA-beta-1.3.0-beta.1-validation.md`

## 5. 완료 기준
- [ ] 단위 테스트 전체와 Main CI 통과(새 테스트 포함)
- [ ] 기기 검사: 기존 support 185 / bond 20 / questItems 5 결과 **변화 없음**(보구 목록이 비어 있으면 동작이 같아야 함)
- [ ] 기기 검사: 4.4의 1~3번 케이스 통과(4번은 fixture가 있으면). fixture가 부족한 항목은 "소유자 수집 필요"로 보고
- [ ] 1.3.0-beta.1 APK 빌드(`aapt`로 패키지·버전 확인), `FGA-Modified-builds`에 저장, SHA-256 기록
- [ ] 블루스택 베타 앱에 **업데이트 설치**(삭제 없음), 설치 버전 확인
- [ ] 소유자 확인용 안내: 블루스택에서 서포트 화면 → 이미지 메이커 실행 → 이름 지정 화면에 보구 이미지가 나오는지 → 설정에서 선택 → 선호 실행(퀘스트 시작 전 정지)

## 6. 검증하지 않는 범위
- 실제 게임에서의 이미지 메이커 실행과 선택 동작(소유자 확인)
- 태블릿 등 고해상도 실기기에서의 매칭(소유자 확인)
- NA·CN·TW 화면

## 7. 위험과 대응
| 위험 | 대응 |
|---|---|
| 이미지 메이커가 `Lv.`를 못 찾아 보구 이미지를 만들지 못하는 행 | 그 행만 건너뛰고, 이름 지정 화면에서 칸을 숨깁니다. 로그를 남깁니다. |
| 긴 보구 이름이 레이아웃에 따라 다르게 눌려 표시 | D-7(가로 배율 검색 추가 조건)과 D-3(여러 장 저장) |
| 띠가 넓어 다른 행의 보구가 잡힘 | 띠는 해당 행 `bounds` 기준이고 세로 범위가 그 행의 보구 줄만 덮습니다. 4.4-3 오탐 검사로 확인합니다. |
| 기존 서포트 선택 동작 회귀 | 보구 목록이 비어 있으면 판정·로그 외에는 아무것도 하지 않습니다. 기존 기기 검사 결과 동일을 완료 기준에 둡니다. |
