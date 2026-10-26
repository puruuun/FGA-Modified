# 패널 레벨 판독기 — Phase 1 측정

최신 슬롯별 샘플과 프레임 제외 측정은 하단 **1.6.8-beta.4 — 슬롯별 자체 샘플 (#31)** 및 [beta.4 검증 기록](1.6.8-beta.4-validation.md)을 참고하세요. beta.3 채널 비교와 아래 Phase 1 수치는 당시 판독기의 기록입니다.

Phase 2 강화 흐름 연결과 1.6.8-beta.1 검증 결과는 [Phase 2 검증 기록](1.6.8-beta.1-validation.md)을 참고하세요. 아래의 Phase 1 판독 기준·자료·한계는 그대로 유지합니다.

기준 main `139b2123`, 브랜치 `feat/panel-level-reader`, [PR #18](https://github.com/puruuun/FGA-Modified/pull/18). **강화 흐름에 연결하지 않았으며 version.properties도 변경하지 않았습니다.** AutoSkillLevel/SkillLevelFlow/SkillLevelDigits의 동작은 그대로입니다.

## 측정 정정 및 입력

이전 가로 압축 실험은 wide 1680×720을 1280×720으로 눌러 글자 폭까지 바꾸었으므로 실제 16:9/16:10 성능을 검증하지 못합니다. 해당 450개 합성 케이스, QA 좌표 어댑터, acceptance 수치와 개별 결과를 삭제했습니다. 아래 표에는 **네이티브 캡처만** 포함합니다. 실제 16:10 캡처는 없어 **미검증**입니다.

- 총 **62장 / 282슬롯**: 기존 wide 57장 / 261슬롯 + 새 16:9 5장 / 21슬롯입니다. 원본 levels 라벨은 바꾸지 않았고 눈으로 확인한 라벨 모순도 없습니다. 초기 56장 이후 추가된 JP 스킬 Lv.5도 유지했습니다.
- wide 2560×1080은 중앙 게임 영역 2520×1080(x=20)을 잘라 1680×720으로, 실제 16:9 1920×1080은 가로·세로 동일 배율로 1280×720으로 저장했습니다. 글자 비율을 바꾸지 않습니다. [source manifest](../reference-images/panel-0021-sources.json)에 원본·레이아웃·selectedSlot을 기록했습니다.
- 새 프레임: JP 스킬 7/6/5, JP 실제 어펜드 6/7/8/4/5(모두 강화 가능), JP 어펜드 10/10/잠금/10/10, KR 스킬 8/6/4, KR 실제 어펜드 잠금/잠금/잠금/9/잠금입니다.
- 새 16:9는 **검증 전용**입니다. KR/JP 각 40개 셀의 기존 템플릿 뱅크(132×210), 점수 0.70·마진 0.10 기준을 바꾸거나 새 입력으로 재학습하지 않았습니다. 준비 도구는 동결된 PNG와 바이트가 달라지면 중단합니다. [템플릿 출처 80개](../reference-images/panel-0021-template-sources.json)를 유지했습니다.

## 제품 좌표와 16:9 수정

좌표는 SkillLevelLocations.panelLevel에 있고 xFromCenter/기존 slotX를 사용합니다. 교환 화면과 실제 강화 화면의 아이콘 위치는 같아 교환 화면 전용 오프셋은 **0**입니다. 별도 화면 분기를 넣지 않았습니다.

- 스킬: 1440p 기준 아이콘 중심 x+130, y=584-slotRise, 290×68. 720p 크롭 y는 wide 270, 비와이드 292이며 변경하지 않았습니다.
- 어펜드: 1440p 기준 아이콘 중심 x−10, 66×42. wide y=546(720p **273**)은 유지했습니다. **비와이드 y만 590→588(720p 295→294), 1px 위로 수정**했습니다. KR·JP의 새 실제 16:9 숫자 윤곽이 기존 wide 셀과 세로로 1px 어긋나는 것을 확인했습니다. 아이콘 행의 wide 상승은 22px이나 숫자 행의 대응은 21px입니다. x, 폭, 높이, 잠금 영역은 그대로입니다.
- 수정 전 실제 기기에서 열린 16:9 어펜드 10개 모두 기권했습니다. 수정 후 JP 7개를 읽고 JP 2개·KR 1개는 여전히 보수적으로 기권합니다. 후보는 모두 기대 레벨이나 score<0.70이며, 임계값 완화·슬롯별 미세 이동으로 통과시키지 않았습니다. 잔여 기권은 모두 4번 아이콘이며 wide에서도 이 슬롯에 기권이 집중됩니다. 배경과 서브픽셀 정렬의 영향을 분리하지 못했으므로 원인을 배경 하나로 단정하지 않습니다.
- 기존 **wide 261개**의 출력·잠금·후보·상태·원인·score·margin은 이전 최종 기기 보고서 `build/device-qa/20261025-011230/report-skillLevelPanel.json`와 모두 동일합니다(점수/마진 차이 <1e−12).

### 수정 전후 — 실제 16:9 제품 경로

| 16:9 어펜드 | 기대 | 수정 전 출력 | 전 score | 수정 후 출력 | 후 score | 후 margin |
|---|---|---|---|---|---|---|
| `Jp-58-Append-1` | 6 | null | 0.5953 | 6 | 0.8856 | 0.2530 |
| `Jp-58-Append-2` | 7 | null | 0.4505 | 7 | 0.7555 | 0.2253 |
| `Jp-58-Append-3` | 8 | null | 0.5512 | 8 | 0.8791 | 0.2107 |
| `Jp-58-Append-4` | 4 | null | 0.5313 | null | 0.5841 | 0.2141 |
| `Jp-58-Append-5` | 5 | null | 0.4774 | 5 | 0.8425 | 0.1734 |
| `Jp-59-Append-1` | 10 | null | 0.6499 | 10 | 0.8943 | 0.4213 |
| `Jp-59-Append-2` | 10 | null | 0.5794 | 10 | 0.8404 | 0.4103 |
| `Jp-59-Append-4` | 10 | null | 0.5088 | null | 0.6791 | 0.2291 |
| `Jp-59-Append-5` | 10 | null | 0.5875 | 10 | 0.8597 | 0.3777 |
| `Kr-61-Append-4` | 9 | null | 0.3583 | null | 0.6529 | 0.1337 |

## 판독과 안전한 기권

스킬은 `Lv. N/10` 전체 행을 실제 Tesseract eng로 읽습니다. scale 2/3 × 이진화 0.6/원본 grayscale, padding 8의 네 프로필이며 whitelist는 사용하지 않습니다. Lv. 접두사(선택), 1–10, `/10`의 전체 문법을 검사합니다. 신뢰도 80 이상 판독이 최소 3개이고 모두 같은 유효 값일 때만 반환합니다. 신뢰도 0을 합의만으로 승격하지 않습니다.

어펜드는 기존 SkillLevelScreens.slotLocked/slotLock(0.85)을 먼저 확인합니다. 잠금은 level=null, locked=true이며 숫자를 읽지 않습니다. 열린 슬롯은 33×21 숫자 열의 Laplacian 윤곽 특징과 동결된 셀의 NCC를 계산합니다. 최고 점수 ≥0.70, 서로 다른 레벨의 1·2위 차이 ≥0.10일 때만 반환합니다. 2번 슬롯은 템플릿 출처에서 제외했고 대상과 같은 출처 슬롯의 셀도 항상 제외합니다. 누락 에셋·저대비·낮은 점수/마진·OCR 충돌은 증거와 함께 null을 반환합니다. 클릭이나 강화 호출은 없습니다.

## 실제 기기 acceptance

BlueStacks **127.0.0.1:5555**, 실제 ImageLoader/OpenCV/Tesseract 및 제품 좌표를 사용했습니다. 호스트 분류기 수치를 acceptance로 쓰지 않았습니다. strict QA는 오독·잠금 오인식·스킬 기권을 실패로 처리합니다. **282건 통과, 실패 0, skip 0**입니다. 아래 정답/기권/오독은 열린 슬롯만 세며 잠금 정답은 별도 열입니다.

| 레이아웃 | 모드 | 서버 | 정답 | 기권 | 오독 | 잠금 정답 |
|---|---|---|---|---|---|---|
| native-16:9 | Skill | Kr | 3 | 0 | 0 | 0 |
| native-16:9 | Skill | Jp | 3 | 0 | 0 | 0 |
| native-16:9 | Append | Kr | 0 | 1 | 0 | 4 |
| native-16:9 | Append | Jp | 7 | 2 | 0 | 1 |
| native-wide | Skill | Kr | 15 | 0 | 0 | 0 |
| native-wide | Skill | Jp | 21 | 0 | 0 | 0 |
| native-wide | Append | Kr | 58 | 12 | 0 | 60 |
| native-wide | Append | Jp | 75 | 7 | 0 | 13 |

스킬은 두 레이아웃·양 서버 모두 100%입니다. wide 어펜드는 KR 58/70(82.9%), JP 75/82(91.5%); 실제 16:9는 KR 0/1, JP 7/9입니다. 16:9 KR 열린 표본은 선택된 4번 Lv.9 하나뿐이므로 서버 전체 읽기율로 일반화하지 않습니다. 잠금은 총 78/78 정확합니다.

### 어펜드 슬롯별

| 레이아웃 | 서버 | 슬롯 | 정답 / 열린 수 | 기권 | 오독 |
|---|---|---|---|---|---|
| native-16:9 | Kr | 4 | 0/1 | 1 | 0 |
| native-16:9 | Jp | 1 | 2/2 | 0 | 0 |
| native-16:9 | Jp | 2 | 2/2 | 0 | 0 |
| native-16:9 | Jp | 3 | 1/1 | 0 | 0 |
| native-16:9 | Jp | 4 | 0/2 | 2 | 0 |
| native-16:9 | Jp | 5 | 2/2 | 0 | 0 |
| native-wide | Kr | 1 | 14/14 | 0 | 0 |
| native-wide | Kr | 2 | 14/14 | 0 | 0 |
| native-wide | Kr | 3 | 14/14 | 0 | 0 |
| native-wide | Kr | 4 | 2/14 | 12 | 0 |
| native-wide | Kr | 5 | 14/14 | 0 | 0 |
| native-wide | Jp | 1 | 16/16 | 0 | 0 |
| native-wide | Jp | 2 | 18/18 | 0 | 0 |
| native-wide | Jp | 3 | 16/16 | 0 | 0 |
| native-wide | Jp | 4 | 9/16 | 7 | 0 |
| native-wide | Jp | 5 | 16/16 | 0 | 0 |

2번 슬롯은 템플릿 출처에 포함하지 않았으며 wide KR 14/14·JP 18/18, 실제 16:9 JP 2/2를 읽었습니다. 실제 16:9 KR 2번은 잠금만 있어 열린 레벨 읽기는 미검증입니다.

### score / margin 분포

열린 슬롯의 정답·기권을 모두 포함하고 잠금은 제외합니다. 스킬 score는 유효 후보 그룹의 최소 OCR 신뢰도/100, margin은 차순위 후보 그룹과의 차이(차순위가 없으면 0 기준)입니다. 어펜드는 NCC와 서로 다른 레벨 간 차이입니다. 서로 다른 지표를 확률로 해석하지 않습니다. 모든 슬롯의 개별 점수·마진·원문·좌표·선택 여부는 [per-case JSON](panel-level-reader-phase1-cases.json)에 있습니다.

| 레이아웃 | 모드 | 서버 | 지표 | min | p05 | median | p95 | max |
|---|---|---|---|---|---|---|---|---|
| native-16:9 | Skill | Kr | score | 0.9500 | 0.9500 | 0.9500 | 0.9500 | 0.9500 |
| native-16:9 | Skill | Kr | margin | 0.9500 | 0.9500 | 0.9500 | 0.9500 | 0.9500 |
| native-16:9 | Skill | Jp | score | 0.8900 | 0.8950 | 0.9400 | 0.9400 | 0.9400 |
| native-16:9 | Skill | Jp | margin | 0.8900 | 0.8950 | 0.9400 | 0.9400 | 0.9400 |
| native-16:9 | Append | Kr | score | 0.6529 | 0.6529 | 0.6529 | 0.6529 | 0.6529 |
| native-16:9 | Append | Kr | margin | 0.1337 | 0.1337 | 0.1337 | 0.1337 | 0.1337 |
| native-16:9 | Append | Jp | score | 0.5841 | 0.6221 | 0.8425 | 0.8908 | 0.8943 |
| native-16:9 | Append | Jp | margin | 0.1734 | 0.1883 | 0.2291 | 0.4169 | 0.4213 |
| native-wide | Skill | Kr | score | 0.9400 | 0.9400 | 0.9500 | 0.9500 | 0.9500 |
| native-wide | Skill | Kr | margin | 0.9400 | 0.9400 | 0.9500 | 0.9500 | 0.9500 |
| native-wide | Skill | Jp | score | 0.8900 | 0.9200 | 0.9400 | 0.9500 | 0.9500 |
| native-wide | Skill | Jp | margin | 0.8900 | 0.9200 | 0.9400 | 0.9500 | 0.9500 |
| native-wide | Append | Kr | score | 0.6301 | 0.6352 | 0.8434 | 0.9181 | 0.9208 |
| native-wide | Append | Kr | margin | 0.0393 | 0.1306 | 0.2412 | 0.5288 | 0.5361 |
| native-wide | Append | Jp | score | 0.6352 | 0.6849 | 0.8783 | 0.9369 | 0.9369 |
| native-wide | Append | Jp | margin | 0.0937 | 0.1607 | 0.2594 | 0.4353 | 0.4353 |

### 최저 마진 어펜드 사례

| 케이스 | 기대 | 출력 | 후보 | score | margin |
|---|---|---|---|---|---|
| `Kr-42-Append-4` | 8 | null | 8 | 0.6925 | 0.0393 |
| `Kr-26-Append-4` | 3 | null | 3 | 0.7314 | 0.0702 |
| `Jp-09-Append-4` | 3 | null | 3 | 0.7147 | 0.0937 |
| `Kr-32-Append-4` | 5 | 5 | 5 | 0.7348 | 0.1167 |
| `Kr-47-Append-4` | 9 | null | 9 | 0.6981 | 0.1171 |
| `Jp-10-Append-4` | 8 | 8 | 8 | 0.7028 | 0.1281 |
| `Kr-61-Append-4` | 9 | null | 9 | 0.6529 | 0.1337 |
| `Kr-35-Append-4` | 7 | null | 7 | 0.6965 | 0.1470 |
| `Jp-16-Append-4` | 9 | 9 | 9 | 0.7014 | 0.1473 |
| `Kr-25-Append-3` | 3 | 3 | 3 | 0.8629 | 0.1491 |

### 2번 슬롯 최저 마진 사례

| 케이스 | 기대 | 출력 | 후보 | score | margin |
|---|---|---|---|---|---|
| `Jp-11-Append-2` | 5 | 5 | 5 | 0.8045 | 0.1604 |
| `Jp-24-Append-2` | 5 | 5 | 5 | 0.8227 | 0.1656 |
| `Kr-55-Append-2` | 6 | 6 | 6 | 0.7530 | 0.1700 |
| `Kr-34-Append-2` | 6 | 6 | 6 | 0.7533 | 0.1708 |
| `Kr-40-Append-2` | 8 | 8 | 8 | 0.7681 | 0.1711 |
| `Jp-07-Append-2` | 3 | 3 | 3 | 0.8022 | 0.1733 |
| `Kr-30-Append-2` | 5 | 5 | 5 | 0.7415 | 0.1825 |
| `Kr-29-Append-2` | 3 | 3 | 3 | 0.7495 | 0.1909 |
| `Jp-00-Append-2` | 2 | 2 | 2 | 0.8027 | 0.2055 |
| `Kr-25-Append-2` | 2 | 2 | 2 | 0.7541 | 0.2237 |

## 선택 테두리

wide 스킬 18–23·50–54·56의 선택 슬롯 12개와 실제 어펜드 24(2번)·55(1번), 새 16:9 프레임 5개의 selectedSlot을 별도로 집계했습니다. 기존 wide 선택은 [선택 위치 감사 기록](../reference-images/panel-0021-selection-audit.json), 새 16:9 선택은 제공된 labels.json을 따릅니다.

| 레이아웃 | 모드 | 서버 | 정답 | 기권 | 오독 | 잠금 정답 |
|---|---|---|---|---|---|---|
| native-16:9 | Skill | Kr | 1 | 0 | 0 | 0 |
| native-16:9 | Skill | Jp | 1 | 0 | 0 | 0 |
| native-16:9 | Append | Kr | 0 | 1 | 0 | 0 |
| native-16:9 | Append | Jp | 1 | 1 | 0 | 0 |
| native-wide | Skill | Kr | 5 | 0 | 0 | 0 |
| native-wide | Skill | Jp | 7 | 0 | 0 | 0 |
| native-wide | Append | Kr | 1 | 0 | 0 | 0 |
| native-wide | Append | Jp | 1 | 0 | 0 | 0 |

| 레이아웃 | 선택 케이스 | 기대 | 출력 | score | margin |
|---|---|---|---|---|---|
| native-wide | `Jp-18-Skill-3` | 3 | 3 | 0.9400 | 0.9400 |
| native-wide | `Jp-19-Skill-2` | 4 | 4 | 0.9400 | 0.9400 |
| native-wide | `Jp-20-Skill-1` | 7 | 7 | 0.9200 | 0.9200 |
| native-wide | `Jp-21-Skill-1` | 9 | 9 | 0.9200 | 0.9200 |
| native-wide | `Jp-22-Skill-1` | 10 | 10 | 0.9500 | 0.9500 |
| native-wide | `Jp-23-Skill-1` | 9 | 9 | 0.9200 | 0.9200 |
| native-wide | `Jp-24-Append-2` | 5 | 5 | 0.8227 | 0.1656 |
| native-wide | `Kr-50-Skill-3` | 2 | 2 | 0.9500 | 0.9500 |
| native-wide | `Kr-51-Skill-1` | 5 | 5 | 0.9400 | 0.9400 |
| native-wide | `Kr-52-Skill-1` | 10 | 10 | 0.9500 | 0.9500 |
| native-wide | `Kr-53-Skill-1` | 8 | 8 | 0.9500 | 0.9500 |
| native-wide | `Kr-54-Skill-1` | 9 | 9 | 0.9400 | 0.9400 |
| native-wide | `Kr-55-Append-1` | 5 | 5 | 0.9149 | 0.1640 |
| native-wide | `Jp-56-Skill-1` | 5 | 5 | 0.9400 | 0.9400 |
| native-16:9 | `Jp-57-Skill-1` | 7 | 7 | 0.9400 | 0.9400 |
| native-16:9 | `Jp-58-Append-1` | 6 | 6 | 0.8856 | 0.2530 |
| native-16:9 | `Jp-59-Append-4` | 10 | null | 0.6791 | 0.2291 |
| native-16:9 | `Kr-60-Skill-1` | 8 | 8 | 0.9500 | 0.9500 |
| native-16:9 | `Kr-61-Append-4` | 9 | null | 0.6529 | 0.1337 |

선택 스킬 **14/14**와 선택 wide 어펜드 **2/2**는 모두 정답입니다. 선택된 실제 16:9 어펜드는 JP Lv.6 정답, JP Lv.10·KR Lv.9 기권이며 오독은 없습니다.

### 어펜드 크롭의 테두리 유입과 판독 영향

실제 제품 QA가 보고한 region720으로 컬러 참조 이미지를 잘랐습니다. 청색 픽셀 조건은 B−R>45, G−R>40, B>145, G>140이며 거리 계산은 아이콘 바깥 링(중심 ±68×66 중 안쪽 제외)에 한정해 파란 아이콘 아트와 구분했습니다. 아래 거리는 크롭의 마지막 포함 픽셀부터 링까지의 Chebyshev 거리입니다. **wide JP 24의 2번 슬롯은 테두리의 청색 번짐이 크롭 마지막 3행(y=291–293)의 68픽셀에 들어옵니다.** 나머지 네 선택 어펜드 크롭은 청색 픽셀이 0이고 테두리까지 2px 떨어져 있습니다. 이 색상 조건은 관찰용 감사이며 제품 판독 조건이 아닙니다.

| 선택 어펜드 | 크롭 안 청색 픽셀 | 해당 크롭 행(0부터) | 외곽 테두리 거리(px) | score | margin |
|---|---|---|---|---|---|
| `Jp-24-Append-2` | 68 | 18,19,20 | 0 | 0.8227 | 0.1656 |
| `Kr-55-Append-1` | 0 | 없음 | 2 | 0.9149 | 0.1640 |
| `Jp-58-Append-1` | 0 | 없음 | 2 | 0.8856 | 0.2530 |
| `Jp-59-Append-4` | 0 | 없음 | 2 | 0.6791 | 0.2291 |
| `Kr-61-Append-4` | 0 | 없음 | 2 | 0.6529 | 0.1337 |

wide에는 같은 서버·슬롯·레벨의 비선택 대조가 있어 실제 기기 점수도 직접 비교했습니다.

| 선택 / 비선택 대조 | 크롭 최대 명도차 | score 차이 | margin 차이 |
|---|---|---|---|
| `Jp-24-Append-2` / `Jp-11-Append-2` | 71 | +0.018210 | +0.005198 |
| `Kr-55-Append-1` / `Kr-34-Append-1` | 79 | +0.017092 | -0.000021 |

번짐이 들어온 JP 2번도 비선택 대조보다 score +0.018210, margin +0.005198이며 읽기 결과가 유지됐습니다. KR 선택 1번은 score +0.017092, margin −0.000021로 판정 기준에서 충분히 떨어져 있습니다. 따라서 제공된 wide 선택 표본에서 **점수 저하나 판독 실패를 유발하는 테두리 영향은 관찰하지 못했습니다.** 선택/비선택 캡처에는 강화 가능 오버레이 등 다른 차이도 있어 이 차이를 테두리만의 인과 효과라고 단정하지 않습니다. 크롭을 바꾸지 않았으며 기존 wide 전 케이스 결과가 동일합니다.

16:9는 같은 레벨·슬롯의 비선택 대조 캡처가 없어 테두리의 score 영향에 대한 A/B 단정은 하지 않습니다. 세 선택 크롭 모두 테두리 유입이 없으며, 기권한 두 선택 슬롯은 기존에도 어려웠던 4번 아이콘입니다. 테두리를 없앤 캡처 없이 잔여 차이의 원인을 테두리로 돌리지 않습니다.

## 전체 검증과 재현

전체 단위 테스트는 다음 명령으로 통과했습니다.

```text
gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test
```

| 모듈 | 테스트 | 실패 |
|---|---|---|
| app | 198 | 0 |
| prefs | 26 | 0 |
| scripts | 482 | 0 |
| libautomata | 0 | 0 |

전체 기기 QA는 수정 좌표를 포함한 QA 전용 APK로 실행했습니다. ANDROID_HOME을 설정하고 실행 중인 서버와 같은 ADB 바이너리, BlueStacks 127.0.0.1:5555만 사용했습니다. 최초 전체 실행은 support 통과 후 ADB 연결 중단으로 끝났으며 최종 통계에 합산하지 않았습니다. 서버 명령 없이 현재 실행 중인 서버 바이너리를 확인하고 전체를 재실행했습니다. 게임 조작은 하지 않았습니다.

| suite | 통과 | 실패 | 기존 명시 skip |
|---|---|---|---|
| support | 203 | 0 | 21 |
| bond | 26 | 0 | 0 |
| questItems | 10 | 0 | 8 |
| noblePhantasm | 18 | 0 | 0 |
| fpSell | 826 | 0 | 0 |
| skillLevel | 131 | 0 | 0 |
| weeklyRoute | 320 | 0 | 0 |
| rankUp | 5 | 0 | 0 |
| skillLevelPanel | 282 | 0 | 0 |

총 **1821 통과 / 0 실패 / 29 기존 명시 skip**입니다. skip은 기존 support/questItems의 원본 범위 불일치 사례이며 새 panel 282개에는 skip이 없습니다. 최종 보고 디렉터리: `build/device-qa/20261025-164041`. PR Main CI 결과는 [PR checks](https://github.com/puruuun/FGA-Modified/pull/18/checks)에서 확인합니다.

```text
python tools/device-qa/prepare_panel_levels.py
set ANDROID_HOME=<Android SDK>
gradlew -PfgaDeviceTests=true :app:assembleDeviceTest :app:assembleDeviceTestAndroidTest
python tools/device-qa/run.py --serial 127.0.0.1:5555 --suite support,bond,questItems,noblePhantasm,fpSell,skillLevel,weeklyRoute,rankUp,skillLevelPanel
python tools/device-qa/report_panel_levels.py --report build/device-qa/<run>/report-skillLevelPanel.json
```

재측정 보고서 생성에는 비교용 이전 최종 wide 보고서와 수정 전 16:9 보고서도 필요합니다. 경로는 `--baseline`, `--before`로 지정할 수 있습니다. 원본 captures는 입력 디렉터리에 두고, 저장소에는 720p 참조만 보관합니다.

## Phase 2 권고

현재 기권 기준을 유지하며 패널·다이얼로그의 값과 score/margin/reason을 함께 기록하는 제한된 결합부터 권합니다. 두 확정값이 다르면 즉시 중단하고 잠금은 해방하지 않아야 합니다. 실제 16:9는 이번에 검증했지만 열린 KR 표본과 4번 아이콘 배경, 실제 16:10 캡처를 보강해야 합니다. 특히 현재 16:9 선택 4번은 기권하므로 패널 단독 진행 범위를 넓히기에는 이릅니다. 템플릿은 wide 자료에서 뽑았으므로 동일 슬롯 출처를 제외했어도 완전한 독립 holdout은 아닙니다. 이번 변경에는 흐름 연결·버전 변경이 없습니다.


## 1.6.8-beta.3 — 어펜드 whiteness 채널 (#24)

기준 `d0eefcb4`의 기존 판독기와 변경 판독기를 BlueStacks **127.0.0.1:5555**에서 각각 실행했습니다. 기존 **62장/282슬롯 전체**(KR·JP, native-wide·native-16:9, 선택 테두리 포함)를 실제 ImageLoader/OpenCV 및 제품 좌표로 읽었습니다. 아래 정답/기권/오독은 열린 어펜드만 세며, 잠금 78/78과 스킬 42/42는 전후 모두 정확합니다. [개별 전후 결과](append-panel-whiteness-cases.json)에 기대값·선택 여부·좌표·후보·점수·마진·이유를 보관합니다.

### 채널 선택과 기권 기준

- **blue + luminance 보조 후보**를 선택했습니다. blue와 `min(R,G,B)`를 같은 80개 출처 셀과 모든 열린 어펜드에서 비교했습니다. 숫자 창과 luminance 보조 후보를 동일하게 적용한 기기 비교에서도 둘 다 159/3/0이며 전체 282건이 통과했습니다. 경계 사례 `Jp-59-Append-4`의 score는 blue 0.7065, min 0.7050입니다. 이 사례의 여유와 단순한 채널 추출을 근거로 blue를 선택했습니다. min 비교 보고서는 `build/device-qa/20261026-151742/report-skillLevelPanel.json`입니다. 단순 전체 크롭 채널 교체는 2번 기권을 늘려 채택하지 않았습니다.
- 제품 크롭 **33×21 및 좌표는 그대로**입니다. 왼쪽 14px의 노란 `Lv.` 접두사·아이콘 부분을 특징 계산에서 제외하고 오른쪽 **19×21** 숫자를 사용합니다. 모든 슬롯·서버·채널·샘플에 같은 창과 Laplacian/NCC를 적용합니다.
- 샘플 PNG는 **132×210 RGB**로 재생성했습니다. [기존 80개 출처](../reference-images/panel-0021-template-sources.json)와 원본 프레임은 그대로이며, 샘플과 실제 크롭을 **같은 RGB→blue/luminance 코드**로 변환합니다. 기존 단일 숫자 에셋은 확인창용이므로 변경하지 않았습니다. 2번은 여전히 샘플 출처에서 제외하고 대상과 같은 슬롯의 샘플도 제외합니다. 16:9 프레임은 재학습에 사용하지 않았습니다.
- **score ≥0.70 및 margin ≥0.10 유지**. 두 채널의 최상위 후보가 같고 적어도 하나가 두 기준을 모두 통과해야 합니다. 기권한 채널도 다른 후보를 제시하면 최종 기권합니다. 높은 점수가 낮은 마진을 보충하지 않습니다.

### 슬롯별 전후 — 기기 제품 경로

| 슬롯 | 이전 정답/기권/오독 | 이후 정답/기권/오독 |
|---|---|---|
| 1 | 32/0/0 | 32/0/0 |
| 2 | 34/0/0 | 34/0/0 |
| 3 | 31/0/0 | 31/0/0 |
| 4 | 11/22/0 | 30/3/0 |
| 5 | 32/0/0 | 32/0/0 |

| 레이아웃 | 서버 | 슬롯 | 이전 정답/기권/오독 | 이후 정답/기권/오독 |
|---|---|---|---|---|
| native-16:9 | Jp | 1 | 2/0/0 | 2/0/0 |
| native-16:9 | Jp | 2 | 2/0/0 | 2/0/0 |
| native-16:9 | Jp | 3 | 1/0/0 | 1/0/0 |
| native-16:9 | Jp | 4 | 0/2/0 | 1/1/0 |
| native-16:9 | Jp | 5 | 2/0/0 | 2/0/0 |
| native-16:9 | Kr | 4 | 0/1/0 | 0/1/0 |
| native-wide | Jp | 1 | 16/0/0 | 16/0/0 |
| native-wide | Jp | 2 | 18/0/0 | 18/0/0 |
| native-wide | Jp | 3 | 16/0/0 | 16/0/0 |
| native-wide | Jp | 4 | 9/7/0 | 15/1/0 |
| native-wide | Jp | 5 | 16/0/0 | 16/0/0 |
| native-wide | Kr | 1 | 14/0/0 | 14/0/0 |
| native-wide | Kr | 2 | 14/0/0 | 14/0/0 |
| native-wide | Kr | 3 | 14/0/0 | 14/0/0 |
| native-wide | Kr | 4 | 2/12/0 | 14/0/0 |
| native-wide | Kr | 5 | 14/0/0 | 14/0/0 |

| 선택된 어펜드 | 이전 정답/기권/오독 | 이후 정답/기권/오독 |
|---|---|---|
| native-16:9 Jp | 1/1/0 | 2/0/0 |
| native-16:9 Kr | 0/1/0 | 0/1/0 |
| native-wide Jp | 1/0/0 | 1/0/0 |
| native-wide Kr | 1/0/0 | 1/0/0 |


2번은 이미 전부 정답이므로 정답 수는 34/34로 유지됩니다. **최저 score 0.7352→0.7877, 중앙값 0.8036→0.8579, 최저 margin 0.1604→0.2384**로 판정 여유가 개선됐습니다. KR wide 최저 score도 0.7352→0.7877입니다. 열린 KR 16:9 2번 프레임은 아직 없습니다.

4번은 **11/33→30/33 정답**, 기권 **22→3**, 오독 **0 유지**입니다. 잔여 기권은 `Jp-03-Append-4`(Lv.1), `Jp-58-Append-4`(Lv.4), `Kr-61-Append-4`(Lv.9)입니다. KR native-16:9 Lv.9는 여전히 기권하므로 모든 4번을 읽는다고 일반화하지 않습니다. 선택된 어펜드는 3/5→4/5 정답, 스킬 선택 14/14는 그대로입니다.

### 라벨된 참고 이미지 — fixture 제외

소유자 프레임 `build/orchestration/inputs/0025-append-slot4/kr-append-check-16x9-2560x1440-dimmed.png`: **KR, 2560×1440 native-16:9, 어펜드 4번 실제 Lv.10, 기존 판독 기권(score 약 0.60)**. FGA 종료 다이얼로그가 게임 화면을 어둡게 한 이미지입니다. 유효한 fixture나 템플릿 출처가 아니며 위 측정·학습에서 제외했습니다. 원본을 커밋하지 않았습니다.

### 재현

기존 판독기: `build/device-qa/20261026-145730/report-skillLevelPanel.json`. 변경 판독기: `build/device-qa/20261026-150832/report-skillLevelPanel.json`. 각각 282 통과 / 0 실패 / 0 skip입니다.

```text
python tools/device-qa/prepare_panel_levels.py
python tools/device-qa/run.py --build --serial 127.0.0.1:5555 --suite skillLevelPanel
python tools/device-qa/report_append_whiteness.py --before build/device-qa/20261026-145730/report-skillLevelPanel.json --after build/device-qa/20261026-150832/report-skillLevelPanel.json
```

준비 도구는 기본적으로 동결된 RGB 뱅크의 일치를 검사합니다. 기존 출처 셀을 의도적으로 재생성할 때만 `--write-templates`를 사용합니다. 전체 검증과 소유자 확인 항목은 [beta.3 검증 기록](1.6.8-beta.3-validation.md)에 있습니다.

## 1.6.8-beta.4 — 슬롯별 자체 샘플 (#31)

기준 `80a2896b`(beta.3), 브랜치 `feat/append-own-slot-samples`. Beta만 **1.6.8-beta.4 / 1006804**, Stable은 **1.6.7 / 3094**입니다. [개별 기기·호스트 결과](append-panel-own-slot-cases.json), [출처·셀 번호·원본 픽셀 해시](../reference-images/panel-0021-own-template-sources.json), [짧은 검증 기록](1.6.8-beta.4-validation.md)을 함께 보세요.

### 판정과 샘플

- KR **70개**, JP **82개**: 기존 0021의 열린 wide 교환·실제 강화 화면을 모두 사용했습니다. 서버×슬롯×레벨 100개 셀에 출처가 있으며 누락 셀은 없습니다. 같은 레벨도 서로 다른 프레임을 모두 보존합니다. JP `24-append-real` 2번 Lv.5의 창 안 선택 테두리와 KR `55-append-real` 1번 Lv.5도 별도 샘플입니다. native 16:9 다섯 프레임은 전부 검증 전용입니다.
- 33×21 크롭의 오른쪽 **19×21**, blue·luminance, Laplacian/NCC, **score ≥ 0.70·margin ≥ 0.10**은 beta.3 그대로입니다. 각 은행 안에서 두 채널의 최상위 후보가 같아야 하며 적어도 한 채널이 두 기준을 모두 넘어야 합니다.
- **자체 슬롯 은행이 주 판독**입니다. 동결된 기존 교차 슬롯 은행도 확정한 값이 다르면 기권합니다. 교차 은행이 기권하면 자체 판독의 통과값을 사용합니다. 기준 미달의 최상위 후보를 확정값처럼 취급하지 않습니다. **자체 은행이 기권하면 beta.3 교차 은행의 전체 판정 결과(확정 또는 기권)를 그대로 사용**하며 reason에 `cross-slot-fallback-`만 붙입니다. 교차 은행 내부의 두 채널 합의·점수·마진 기준도 그대로입니다.

### 슬롯 3 확인

**슬롯 3도 자체 은행을 사용합니다.** 서로 다른 서번트의 JP `00-append-exchange`, `14-append-exchange`, `16-append-exchange`에서 검 아이콘 영역 **(963,180,130,80)**은 RGB 픽셀이 완전히 같습니다(MAD **0.0**). 각각 슬롯 3 레벨은 10·8·9입니다. KR `25-append-exchange`·`55-append-real`, JP native 16:9 `58-append-real`도 같은 검·화살표와 숫자 배경입니다. JP/KR 사이 미세 렌더링 차이는 서버별 은행으로 분리했습니다. 검토 이미지: 로컬 `build/slot3-audit.png`·`build/own-icons.png`. 0021 밖의 클래스까지 동일하다는 추가 주장은 하지 않습니다.

`강화 가능 / 強化可能`은 숫자 창 위쪽에 있으므로 기권 원인으로 돌리지 않았습니다. 좌표 도구로 `Region(366,546,66,42).xFromCenter()`가 JP wide 슬롯 3의 **(1023,273,33,21)**에 놓이는 것을 확인했습니다. 좌표와 크롭은 변경하지 않았습니다.

### 측정 방법과 해석

기기는 BlueStacks **127.0.0.1:5555**, 실제 `skillLevelPanel` suite의 ImageLoader/OpenCV/제품 좌표를 사용합니다. 기기에서는 제품 은행의 출처를 프레임마다 제거할 수 없으므로 **제품 은행 결과**로 표시합니다. 학습 원본과 같은 화면을 읽은 wide 결과가 포함되며 독립 검증으로 해석하면 안 됩니다.

호스트는 `:scripts:panelAppendEvaluation`에서 **동일한 제품 Kotlin matcher**를 직접 호출합니다. 대상 프레임 전체를 디코딩한 RGB SHA-256이 같은 모든 참조 프레임을 찾고, 그 프레임에서 잘라낸 **모든 슬롯·레벨 샘플을 자체 은행과 교차 은행 양쪽에서 제외**합니다. PNG 인코딩만 다른 동일 픽셀 프레임도 같은 그룹입니다. 제외 셀 수와 프레임 그룹을 JSON으로 남기며, native 16:9가 은행의 출처에 없는지도 검사합니다. beta.3도 같은 방식으로 평가합니다.

호스트의 제외 없는 결과는 두 버전 모두 기기 결과의 level·candidate·status가 같고 score·margin 차이가 **1e-12 미만**이어야 보고서를 생성합니다. **오독 0**도 필수입니다. 적중률 분모는 잠금과 일반 스킬을 제외한 열린 어펜드 **162개**입니다.

**제품 은행과 독립 평가를 구분해야 합니다.** 자체 은행이 기권하면 beta.3 판정으로 보완하여 호스트 프레임 제외는 **157/5/0 (96.91%)**입니다. beta.3의 **159/3/0 (98.15%)**보다 정답이 2개 적습니다. native 16:9의 기존 기권 2개를 읽지만, wide에서 자체·교차 은행이 서로 다른 값을 확정한 4개를 기권하기 때문입니다. 나머지 1개는 교차 은행도 점수가 부족합니다. 새로운 wide 원본 없이 전체 일반화 성능 개선을 주장하지 않습니다. 기준은 완화하지 않았습니다.

### 재현

```text
python tools/device-qa/prepare_panel_levels.py
gradlew :scripts:panelAppendEvaluation
set ANDROID_HOME=C:\Users\rl\AppData\Local\Android\Sdk
python tools/device-qa/run.py --build --serial 127.0.0.1:5555 --suite support,bond,questItems,noblePhantasm,fpSell,skillLevel,weeklyRoute,rankUp,skillLevelPanel
python tools/device-qa/report_append_own_samples.py --before build/device-qa/20261026-155510/report-skillLevelPanel.json --after build/device-qa/20261026-160002/report-skillLevelPanel.json --output build/beta4-tables.md
```

beta.3 기기 보고서: `build/device-qa/20261026-155510/`. beta.4 전체 기기 보고서: `build/device-qa/20261026-160002/`. 호스트: `build/panel-append-evaluation.json`. 게임 조작·다른 기기·ADB 서버 명령은 사용하지 않았습니다.

### device-product-bank

정답/기권/오독. 열린 슬롯이 없는 셀은 0/0/0입니다.

| 레이아웃 | 서버 | 슬롯 | beta.3 | beta.4 |
|---|---|---|---|---|
| native-wide | Kr | 1 | 14/0/0 | 14/0/0 |
| native-wide | Kr | 2 | 14/0/0 | 14/0/0 |
| native-wide | Kr | 3 | 14/0/0 | 14/0/0 |
| native-wide | Kr | 4 | 14/0/0 | 14/0/0 |
| native-wide | Kr | 5 | 14/0/0 | 14/0/0 |
| native-wide | Jp | 1 | 16/0/0 | 16/0/0 |
| native-wide | Jp | 2 | 18/0/0 | 18/0/0 |
| native-wide | Jp | 3 | 16/0/0 | 16/0/0 |
| native-wide | Jp | 4 | 15/1/0 | 16/0/0 |
| native-wide | Jp | 5 | 16/0/0 | 16/0/0 |
| native-16:9 | Kr | 1 | 0/0/0 | 0/0/0 |
| native-16:9 | Kr | 2 | 0/0/0 | 0/0/0 |
| native-16:9 | Kr | 3 | 0/0/0 | 0/0/0 |
| native-16:9 | Kr | 4 | 0/1/0 | 1/0/0 |
| native-16:9 | Kr | 5 | 0/0/0 | 0/0/0 |
| native-16:9 | Jp | 1 | 2/0/0 | 2/0/0 |
| native-16:9 | Jp | 2 | 2/0/0 | 2/0/0 |
| native-16:9 | Jp | 3 | 1/0/0 | 1/0/0 |
| native-16:9 | Jp | 4 | 1/1/0 | 2/0/0 |
| native-16:9 | Jp | 5 | 2/0/0 | 2/0/0 |

- beta.3: **159/3/0**, 적중률 **159/162 = 98.15%**.
- beta.4: **162/0/0**, 적중률 **162/162 = 100.00%**.

### host-leave-one-frame-out

정답/기권/오독. 열린 슬롯이 없는 셀은 0/0/0입니다.

| 레이아웃 | 서버 | 슬롯 | beta.3 | beta.4 |
|---|---|---|---|---|
| native-wide | Kr | 1 | 14/0/0 | 14/0/0 |
| native-wide | Kr | 2 | 14/0/0 | 13/1/0 |
| native-wide | Kr | 3 | 14/0/0 | 14/0/0 |
| native-wide | Kr | 4 | 14/0/0 | 13/1/0 |
| native-wide | Kr | 5 | 14/0/0 | 14/0/0 |
| native-wide | Jp | 1 | 16/0/0 | 16/0/0 |
| native-wide | Jp | 2 | 18/0/0 | 18/0/0 |
| native-wide | Jp | 3 | 16/0/0 | 16/0/0 |
| native-wide | Jp | 4 | 15/1/0 | 13/3/0 |
| native-wide | Jp | 5 | 16/0/0 | 16/0/0 |
| native-16:9 | Kr | 1 | 0/0/0 | 0/0/0 |
| native-16:9 | Kr | 2 | 0/0/0 | 0/0/0 |
| native-16:9 | Kr | 3 | 0/0/0 | 0/0/0 |
| native-16:9 | Kr | 4 | 0/1/0 | 1/0/0 |
| native-16:9 | Kr | 5 | 0/0/0 | 0/0/0 |
| native-16:9 | Jp | 1 | 2/0/0 | 2/0/0 |
| native-16:9 | Jp | 2 | 2/0/0 | 2/0/0 |
| native-16:9 | Jp | 3 | 1/0/0 | 1/0/0 |
| native-16:9 | Jp | 4 | 1/1/0 | 2/0/0 |
| native-16:9 | Jp | 5 | 2/0/0 | 2/0/0 |

- beta.3: **159/3/0**, 적중률 **159/162 = 98.15%**.
- beta.4: **157/5/0**, 적중률 **157/162 = 96.91%**.

### 샘플 수 (레벨 1→10 순)

| 서버 | 슬롯 | 레벨별 원본 샘플 수 |
|---|---|---|
| Kr | 1 | 1/1/1/1/2/1/1/1/1/4 |
| Kr | 2 | 1/1/1/1/1/2/1/1/1/4 |
| Kr | 3 | 1/1/1/1/1/1/1/1/1/5 |
| Kr | 4 | 1/1/1/1/1/1/1/1/1/5 |
| Kr | 5 | 1/1/1/2/1/1/1/1/1/4 |
| Jp | 1 | 1/1/1/2/1/1/1/1/1/6 |
| Jp | 2 | 1/1/1/2/2/1/1/1/2/6 |
| Jp | 3 | 1/1/1/2/1/1/1/1/1/6 |
| Jp | 4 | 1/1/1/2/1/1/1/1/1/6 |
| Jp | 5 | 1/1/1/2/1/1/1/1/1/6 |

호스트 잔여 기권 5건입니다. 모두 프레임 제외 후 해당 슬롯·레벨의 자체 샘플이 없습니다. fallback 확정 75건·fallback 기권 1건, 은행 간 충돌 기권 4건입니다.

| 케이스 | 기대 | 이유 | score | margin |
|---|---|---|---|---|
| Jp-03-Append-4 | 1 | cross-slot-fallback-weak-outline-or-margin | 0.6957 | 0.3652 |
| Jp-12-Append-4 | 6 | own-cross-slot-conflict | 0.7272 | 0.1085 |
| Jp-16-Append-4 | 9 | own-cross-slot-conflict | 0.7457 | 0.1049 |
| Kr-31-Append-4 | 6 | own-cross-slot-conflict | 0.7393 | 0.1033 |
| Kr-40-Append-2 | 8 | own-cross-slot-conflict | 0.7331 | 0.1143 |

동일 픽셀 중복 프레임 그룹은 이번 0021 참조 집합에서 0개였습니다. 위 기기 표는 fallback 수정 전 제품 은행 결과이며, 수정 후 전체 기기 QA 재실행은 지정 BlueStacks 실행을 기다리고 있습니다.
