# 0012 와이드 화면 좌표 전수 조사 (D-1) 와 수정 결과 (D-2)

- 대상 버전: 1.6.1-beta.1 / 1006101
- 기준 캡처: 소유자 21:9 캡처 01–14 (`build/orchestration/inputs/0012-wide/`, 블루스택 창 2000×880).
  - 게임 영역은 창 왼쪽 위 (0, 38)부터 폭 1961, 높이 840(위 38px 제목줄, 오른쪽 38px 도구줄 제외)입니다. 이 영역을 잘라 1680×720(21:9)으로 맞춘 프레임을 `docs/reference-images/Jp/wide-21x9/01–14.webp`(무손실)에 저장했습니다. 종횡비는 바꾸지 않았습니다.
  - 스크립트 영역은 높이 1440 기준 3360×1440입니다. 아래 "와이드 값"은 모두 이 영역의 1440 좌표이고, 실측은 720 프레임 픽셀입니다.
- 비교 기준은 같은 화면의 16:9(1280×720) 참조 캡처(`docs/reference-images/Jp/...`)입니다. 같은 그림(버튼 글자, 제목, 라벨)을 두 프레임에서 템플릿 매칭해 이동량을 쟀습니다.

## 1. 측정한 와이드 이동량 (16:9 → 21:9, 720 픽셀)

| 붙은 곳 | 이동량 | 1440 좌표 | 이 화면에서 확인 | 코드 상수 |
|---|---:|---:|---|---|
| 왼쪽 가장자리 | 오른쪽으로 +85 | +171 | お知らせ/ターミナル/閉じる/戻る 버튼(01–05, 12, 14), 서번트 탭, 서번트 재선택 버튼, 지도 초상화·상태창 | `WIDE_EDGE_INSET = 171` (`xFromLeftEdge`) |
| 오른쪽 가장자리 | 왼쪽으로 −85 (가운데 기준으로는 +315) | −171 | 제목 배너, MENU, 이벤트 보상, 퀘스트 판과 스크롤바, 영기변환 제목·決定 | `WIDE_EDGE_INSET` (`xFromRightEdge`) |
| 오른쪽 아래 실행 버튼 | 왼쪽으로 −58 (가운데 기준 +342), 위로 −30 | −115 / −60 | 스킬 강화 `強化する` 버튼. 원본 `Locations.enhancementClick`의 와이드 값(−281 → −396, 1343 → 1284)과 같습니다 | `WIDE_ACTION_INSET = 115` |
| 가운데 내용 | +200 | 가운데 기준 그대로 | 인연 결과, 연속 출격 창, 아이템 자동 사용 창, 스킬 화면 서번트 카드와 패널(세로로는 아래 참고) | `xFromCenter` |
| 서포트 목록 | +220 | 원본 `supportOffset` | 서포트 행(05, 09–11) | 원본 그대로 |
| 영기변환 카드 격자 | +205 (가운데 기준보다 5 더 오른쪽) | +10 | 왼쪽 버튼 열과 스크롤바 사이 가운데에 놓입니다 | `FpSellLocations.gridShift` |

- 왼쪽·오른쪽 가장자리 여백은 같은 값(85px)이고, 원본 FGA 가정대로 화면 비율과 무관하다고 봅니다(소유자 결정). 20:9 등 다른 비율의 실측은 소유자 QA 체크리스트에서 확인합니다.
- 세로 이동(21:9에서만 달라짐, 스킬 강화 화면 12): 안내 띠 −14, 보유 스킬 패널·슬롯 아이콘 −22, 아이템 줄 −25, 하단 QP·도움말·버튼 −30 (720 픽셀). 퀘스트 아이템 메인 버튼(06)은 −36(원본 `QuestItemLocations.mainControl`의 와이드 값 −242와 같음). 영기변환 왼쪽 버튼 열은 간격이 좁아져 마지막 버튼이 25px 위입니다(14).
- 영기변환 카드 행 위치는 16:9와 같습니다(세로 이동 없음, 첫 행 카드 윗변 약 184). 행 위상은 기존처럼 화면에서 찾습니다.

## 2. 기능별 결과

범례: **수정** = 이번에 고침, **원래 정상** = 21:9 캡처에서 확인해 변경 없음, **캡처 필요** = 21:9 캡처가 없어 현재 코드 경로를 그대로 두고 소유자 캡처를 요청.

| 기능 | 결과 |
|---|---|
| 서포트 선택 읽기(행 찾기, 보구 레벨, 점수, 스킬/어펜드 표시, 표시 전환 버튼) | 원래 정상 (05, 09, 10, 11) |
| 인연 추적 | 원래 정상 (08) |
| 티포트·스톰포드 | 파티 확인 제목 **수정**, 나머지 원래 정상 (06, 07, 13) |
| FP 자동 판매 판매 화면 | **수정** (14) |
| FP 자동 판매 소환·복귀·확인창 | 캡처 필요 |
| 스킬·어펜드 자동 강화 | 스킬 화면 **수정** (12), 확인창·어펜드 화면 캡처 필요 |
| 주간 미션 루트 | 상단 왼쪽 버튼·판 열·지도 금지 구역 **수정**, 판 읽기 원래 정상(앵커), 퀘스트 판 열린 지도 캡처 필요 |

## 3. 좌표별 표

앵커: **왼**(왼쪽 가장자리), **중**(가운데), **오**(오른쪽 가장자리), **찾**(화면에서 찾은 그림 기준), **행**(찾은 서포트 행 기준).
와이드 값 열의 "−"는 와이드 전용 값이 없다는 뜻이고, 결과 열은 21:9 프레임에서 확인한 것입니다.

### 3.1 `SupportScreenLocations` (원본 + 포크 추가분)

원본 FGA가 이미 와이드를 처리합니다(`headerOffset` 171 → `edgeInset`으로 같은 값 재사용, `supportOffset`, 스크롤바).

| 좌표 | 앵커 | 와이드 값 | 21:9 결과 |
|---|---|---|---|
| `skillDisplayLabelsRegion` (포크) | 행 목록 | `supportOffset` | 정상: 05/10 Normal, 09/11 Append 판독 |
| `skillDisplaySwitchClick` (포크) | 왼 + 헤더 | +171 | 정상: 버튼 중심 (933, 130) 안 |
| `confirmSetupButtonRegion` → 행 찾기 | 행 | `supportOffset` | 정상: 행 3·2·2·2개 모두 찾음 |
| `listRegion`, `defaultBounds` 등 나머지 | 행 | `supportOffset` | 정상 |

### 3.2 `Support*Layout` (`SupportNpLayout`, `SupportScoreLayout`, `SupportSkillLayout`)

모두 찾은 행의 왼쪽 위(`SupportBounds`)에서의 상대 좌표입니다. 앵커: **행**. 와이드 값: 없음(필요 없음).

| 읽기 | 21:9 결과 |
|---|---|
| 보구 레벨(NP) | 정상: 05 Lv.2/1, 09 Lv.5/4, 10 Lv.3/5, 11 Lv.5/5 |
| 스킬 최대 표시(일반 3칸, 어펜드 5칸) | 정상: 05, 10 일반 [T,T,T], 09/11 어펜드 [T,T,F,T,T] 등 |
| 일반 점수 `+57`, `+62` | 정상 |
| 복합 점수 `+62/+16`, `+57/+16` | 표시 형식은 Compound로 판독. 작은 숫자 템플릿(0.94)이 소유자 캡처(창 2000×880을 1680×720으로 다시 줄임)에서 0.93–0.96이라 숫자는 판독하지 못하는 경우가 있습니다. 위치 문제가 아니라 캡처 화질 문제이므로 사례에서는 숫자를 요구하지 않습니다. 실제 기기 화면(원본 해상도)은 소유자 체크리스트에서 확인합니다. |

### 3.3 `BondLocations`

| 좌표 | 앵커 | 와이드 값 | 21:9 결과 |
|---|---|---|---|
| `resultTitle`, `popupTitle`, `popupArrow` | 중 | − | 정상: 08에서 5열 `[4748,5259] … [4748,3644]` 판독 |
| `gain`, `gainText`, `levelUpMarker` (인덱스별) | 중 | − | 정상 |
| `reachedLevel` | 찾(화살표) | − | 레벨업 창 캡처 없음. 찾은 그림 기준이라 영향 없음 |

### 3.4 `QuestItemLocations`

| 좌표 | 앵커 | 와이드 값 | 21:9 결과 |
|---|---|---|---|
| `mainControl` | 오+아래 | 있음(−1330, −242, 와이드 전용) | 정상: 06에서 티포트 ON/OFF 버튼 판독 |
| `partyHeading` | 오 | `xFromRightEdge`(수정 전 가장자리 여백 없음) | **수정**: 제목이 영역 밖 14px에 걸려 06이 Unknown이었음 |
| `dialogHeading`, `stormPodControl`, `teapotControl`, `dialogClose` | 중 | − | 정상: 13에서 Dialog와 두 버튼 판독 |

### 3.5 `QuestPanelLocations`와 `QuestPanelAnchor`

| 좌표 | 앵커 | 와이드 값 | 21:9 결과 |
|---|---|---|---|
| `freeSearchBand` | 찾(화면 전체) | − | 정상(폭만 화면에 맞춤) |
| `panel()` → `freeLabel`, `apDigits`, `name`, `tap` | 오 | 수정 전 −190(미측정), **수정 후 −171**(`xFromRightEdge`) | **수정**: 04 퀘스트 판이 16:9보다 85px 안쪽, 크기와 안쪽 배치 동일. 겹쳐 그려 `apDigits`가 `AP 40` 숫자에, `name`이 퀘스트 이름 띠에 맞음을 확인 |
| `QuestPanelAnchor`(`ap`, `name`, `tap`, `resetLane`, `resetStart/End`) | 찾(Free Quest 표시) | − | 판 안쪽 배치가 같으므로 영향 없음. 단 Free Quest 판 캡처가 없어 직접 판독은 **캡처 필요** |
| `enlargedSpotName` | 중 | − | 지도에서 판을 연 21:9 캡처 없음: **캡처 필요** |

### 3.6 `SkillLevelLocations`

| 좌표 | 앵커 | 와이드 값 | 21:9 결과 |
|---|---|---|---|
| `title` | 오 | `xFromRightEdge` | **수정**: 중심 기준이라 제목이 영역 밖이었음. 스킬 제목 판독됨(12) |
| `reselect` | 왼 | `xFromLeftEdge` | **수정**: 중심 기준이라 버튼 오른쪽 절반만 덮었음. 재선택 판독됨(12) |
| `bandWords`, `bandColor` | 중 | 세로 −28(띠 14px 위) | **수정**: 띠 판독 Enhance(12) |
| `buttonLabel` | 오 | 안쪽 −115, 위 −60 | **수정**: 원본 `enhancementClick`과 같은 이동 |
| `slotClick`, `slotLock` (스킬) | 중 | 세로 −44(22px 위) | **수정**: 슬롯 3칸 위치 실측. 자물쇠 없음 판독(12) |
| `slotClick`, `slotLock` (어펜드) | 중 | 없음 | **캡처 필요**: 어펜드 화면 |
| `dialogWatermark`, `dialogTitle`, `currentLevel`, `nextLevel`, `dialogCancel`, `dialogConfirm` | 중 | − | 가운데 정렬 창(13의 아이템 창 `confirmation` 문구는 16:9와 같은 높이±1px). 스킬·어펜드 확인창 캡처는 없음: **캡처 필요** |
| `skipClick`, `result` | 중 | − | 결과 화면 캡처 없음: **캡처 필요** (`skipClick`은 아무 동작 없는 제목 영역) |
| `Locations.enhancementClick`(원본) | 오 | 원본 와이드 값 | 정상: 버튼 중심 (1481, 645) |

### 3.7 `FpSellLocations`

| 좌표 | 앵커 | 와이드 값 | 21:9 결과 |
|---|---|---|---|
| `screenClose`, `close` | 왼 | `xFromLeftEdge` | **수정**: 중심 기준이라 버튼 밖(영역 200–390, 버튼 97–258). 판독 확인(14) |
| `servantTab`, `servantTabClick` | 왼 | `xFromLeftEdge` | **수정** |
| `sizeSwitch` | 왼 | 안쪽 +171, 세로 1268 | **수정**: 왼쪽 열 마지막 버튼 (122, 634) 안 |
| `heading`, `shop` | 오 | `xFromRightEdge` | **수정**: 제목 (1354, 7) 판독(14) |
| `confirm` | 오 | `xFromRightEdge` | **수정**: 決定 (1439, 633) 영역 안 |
| `menu` | 오 | `xFromRightEdge` | **수정**: MENU (1456, 636) 판독(01) |
| `grid`, `columnX`, `card`, `presence`, `lock`, `temporary`, `frameStrip` | 중 + 찾(행 위상) | 가로 +10 | **수정**: 7열 모두 위치(283.5, 417, 550, …)에 맞음. 3행 21칸 모두 존재·잠금 없음·선택 안 됨 판독(14) |
| `swipeStart`, `swipeEnd` | 중 | − | 격자 안(840)에 있으므로 정상 |
| `fullDialogLine`, `fullSell`, `convertHeading`, `cancel`, `convert`, `done`, `doneClose` | 중 | − | 경고·확인·완료 창 캡처 없음: **캡처 필요** |
| `summonButton`, `dailySummonButton`, `summonMenu`, `bannerTitle`, `leftArrow`, `leftArrowRegion` | 중 | − | 소환 화면·공방 캡처 없음: **캡처 필요** |
| `FPLocations`(원본, 배너 복귀가 사용하는 `summonCheck`, `initialSummonCheck`) | 중 | − | 소환 화면 캡처 없음: **캡처 필요** |

### 3.8 `WeeklyRouteLocations`

| 좌표 | 앵커 | 와이드 값 | 21:9 결과 |
|---|---|---|---|
| `topLeftButton` | 왼 | `xFromLeftEdge` | **수정**: 중심이 16:9 위치라 버튼(105–253)을 못 덮어 01–03의 화면 판별이 Unknown이었음 → Home / Map / Map |
| `repeatDialogSignal`, `repeatDialogClose` | 중 | − | 정상: 07에서 `Repeat` 판독, 닫기 (643, 583) |
| `listArea`, `listScrollStart/End` | 오 | − | 정상: 홈 배너(965–1525) 열 안. 장 목록(메인 레코드·메인 인터루드) 캡처는 없음: **캡처 필요** |
| `panelColumn` | 오 | 수정 전 −1520(−190 가정), 수정 후 `xFromRightEdge`(−171) | **수정**: 04 판 위치에 맞춤 |
| `pinchBounds` | 중(화면 폭 기준) | − | 정상: 지도 가운데 영역 |

### 3.8.1 지도 화면 UI 금지 구역 (`MapView.covered`, 720 픽셀 인라인 상수)

| 구역 | 앵커 | 와이드 값 | 21:9 결과 |
|---|---|---|---|
| 터미널/닫기 버튼, 초상화, 레벨·AP, 선물 상자와 미션 노트 | 왼 | 구역에 +85 | **수정**: 02, 03에서 터미널 (105–253), 초상화 (85–300), 선물 상자 (475–660) 모두 구역 안 |
| 장 제목, MENU, 퀘스트 목록 스크롤바 | 오 | 구역에 +85 | **수정**: MENU (1405–1600), 지도 버튼 (1345) 구역 안 |
| `dragPath` 손가락 경로 | 왼·오 | 양쪽 +85 | **수정**: 버튼에 닿지 않는 가운데만 사용 |

지도 구역은 화면에 고정된 UI라 "찾은 그림" 앵커가 없습니다. 모두 테두리 여백 상수(`WIDE_EDGE_INSET`)로 처리했고 `MapView`의 720 픽셀 단위는 `edgeInset / 2`로 바꿔 씁니다.
지도 위치 맞추기(`MapLocalizer`)는 화면 전체에서 특징점을 찾는 방식이라 폭과 무관합니다(03에서 JP-100 맞춤 수락).

### 3.9 모듈 안 직접 좌표

`WeeklyRouteDriver`, `QuestPanelReader`, `WeeklyRouteScreens`, `WeeklyRepeatDialog`, `SkillLevelScreens`, `FpSellReader`, `FpBannerReturn`, `FpAutoSell`, `ServantTracker`, `QuestItemReader`, `BondReader`, `SkillLevelDigits`, `SkillLevelDevice`를 조사했습니다.

- 화면에 고정된 직접 좌표 상수는 **더 없습니다**. 클릭·읽기는 모두 `locations/`의 값 또는 화면에서 찾은 위치(`MapView.toScript(...)`는 찾은 지도 점의 720 → 1440 변환)입니다. 옮길 직접 좌표가 없었으므로 이동 작업은 하지 않았습니다.
- `MapView.covered`의 720 픽셀 상수만 화면 고정 UI 좌표이고 `weekly/MapView.kt`(순수 기하 객체)에 있습니다. 와이드 보정을 이 객체의 `inset()`에 모았습니다.
- `QuestPanelReader`의 `Region(0, half, …)`, `ServantSelection`의 잠금 자르기 등은 이미지 안의 자르기 좌표이고 화면 좌표가 아닙니다.
- `ServantTracker`, `CEDropsTracker`, `AutoCEBomb` 등 원본 FGA 코드는 이번 범위가 아닙니다.

## 4. 위 표의 근거 (측정 방법)

- 같은 템플릿(`app/src/main/assets/Jp/...`의 서포트·FP·스킬·주간 그림 63개)을 16:9 참조 캡처 251장과 21:9 프레임 14장에서 매칭해 위치를 비교했습니다. 예: `fp_sell_screen_close` 16:9 (64, 22) → 21:9 (149, 21), `weekly_terminal` (20, 24) → (105, 23), `fp_sell_heading` (1040, 8) → (1354, 7), `skill_level_reselect` (194, 14) → (279, 13), `fp_sell_menu` (1142, 636) → (1456, 636), `repeat` (763, 561) → (964, 561).
- 그림이 없는 요소(영기변환 격자, 스킬 슬롯 아이콘, 퀘스트 판 안쪽)는 같은 화면의 고정 UI 글자를 템플릿으로 잘라 매칭했습니다.
- 겹쳐 그리기: 수정 뒤 값을 21:9 프레임 위에 그려 영역이 해당 UI를 덮는지 확인했습니다.

## 5. 소유자 21:9 캡처가 더 필요한 화면

현재 코드 경로를 그대로 두었고, 추측한 좌표는 넣지 않았습니다.

1. 스킬 강화 **확인창**(강화 확인, 해금 확인)과 **결과 화면** (`SkillLevelLocations` 확인창 좌표, `skipClick`, `result`)
2. **어펜드 스킬 강화 화면**(5칸 슬롯, 해금 띠, 확인창)
3. FP 소환 화면(`FPLocations`, 배너 왼쪽 화살표, 소환 버튼, 일일 소환 버튼)과 공방(소환 메뉴 아이콘, 영기변환 제목 `Shop`)
4. 영기변환 **서번트 한계 경고 창**, 판매 확인(`選択した…`)·완료 창
5. 홈에서 **메인 레코드**·**메인 인터루드** 장 목록(배너 열, 스크롤 시작·끝)
6. **지도에서 퀘스트 판이 열린** 화면(Free Quest 표시, 판 안쪽, 확대된 지점 이름 띠)
7. 기능별 **KR 화면**(KR 서버 21:9): 서포트, 파티 확인, 스킬 강화, 영기변환
8. 인연 **레벨업 창**(인연 결과 화면 다음에 나오는 창)

## 6. 검증 요약

- 단위 테스트 `WideScreenLocationsTest`: 16:9 값은 수정 전 중심 기준 좌표와 같고(변경 없음), 21:9 값은 위 실측과 같습니다. `MapView` 금지 구역의 16:9·21:9 값도 확인합니다.
- 기기 QA: 21:9 프레임 14장에서 기능별 사례를 추가했습니다(서포트 4, 인연 3, 퀘스트 아이템 3, FP 판매 26, 스킬 강화 6, 주간 루트 12). 기존 사례는 모두 그대로 통과합니다.
