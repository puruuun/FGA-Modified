# 0013 소유자 창 캡처 점수 판독 감사

- 상태: **점수 기준 측정 완료(70행) + 어펜드 측정 완료(20장 40행 추가)**. 아래 "어펜드 감사" 이후 절이 추가분입니다.
- 1.6.2-beta.1 제품 리더 `aefa6bcb`; 라벨·정규화·QA 준비 커밋 `1c157ba2`.
- 35장, 완전히 보이는 **70행**: JP 40행·KR 30행, 일반 50행·복합 20행. 제품 리더·임계값·버전 변경 없음.
- **(1차 70행) 성공 43/70(61.4%), unknown 27/70, 오독 0/70.** 모든 확정 숫자와 확정 표시 형식을 정답과 비교했습니다.
- **(추가 40행, 어펜드 표본) 어펜드 슬롯 판독 170/170 정답·오독 0.** 그러나 **점수 판독에서 오독 1건**이 나왔습니다(199.webp 행 2, Grand 16을 1로 읽음). 아래 "오독" 절 참조. 110행 합산: 성공 65, unknown 44, 오독 1.

## 측정 환경과 방법

- 기기: **127.0.0.1:5555**, `getprop ro.product.model` 결과 **PIXEL 2 XL**. SDK adb 1.0.41만 사용했습니다.
- 파일 기반 instrumentation만 실행했습니다. 게임 입력, live 모드, 화면 전환, 다른 기기 조작은 없습니다.
- 실행: `tools/device-qa/run.py --serial 127.0.0.1:5555 --suite support --cases build/score-audit-baseline.json`.
- 원시 결과(로컬): `build/device-qa/20261022-145147/report-support.json`. 아래에 70행의 점수 원시 반환값을 모두 보존했습니다.
- 최초 측정용 임시 케이스에서는 숫자 기대값을 제거해 실패 assert가 결과를 버리지 않도록 했습니다. 70행 모두 Normal 화면·행 탐지를 통과하고 제품 `SupportScoreReader.read(bounds,true,true)`를 실행했습니다. 이 실행의 PASS는 정답률 100%라는 뜻이 아닙니다.
- 입력 프레임·행 좌표·라벨은 기존 자료를 유지했습니다. 화면 크기는 기기 현재 해상도 대신 각 파일의 종횡비로 계산됩니다.

| 측정 APK | SHA-256 |
|---|---|
| app-deviceTest.apk | `511504de47b78ece44d8e3ac87fe266cb876a1a39add4a561c3a91f5223d8e0b` |
| app-deviceTest-androidTest.apk | `9f984c4a67052ef46b786db10a022f4121264a01dcb20f909064661d6f11d78a` |

## 정규화와 라벨

[labels.json](../reference-images/support-score-audit/labels.json)에 각 원본의 크기·게임 크롭 사각형·정규화 크기와 행별 서버·행 번호·720p Y·화면 종류·점수 표시·두 숫자를 기록했습니다. `rowIndex`는 완전 노출 행 사이의 1 기반 번호이고, `screenRowIndex`는 잘린 행까지 포함한 화면상의 번호입니다.

좌표는 `[left, top, right, bottom)`입니다. 창 상단 바와 오른쪽 툴바를 제거했고, 1832×1164 입력은 위아래 장식 레터박스도 제거했습니다. 색상 게임 프레임을 OpenCV `INTER_AREA`로 높이 720, 너비 `floor(cropWidth × 720 / cropHeight)`로 정규화했습니다. QA는 이 PNG를 흑백으로 읽습니다. 확대·선명화·합성 숫자는 사용하지 않았습니다. 창에서 이미 리스케일된 이미지이므로 실제 MediaProjection 캡처의 정확도로 일반화할 수 없습니다.

| 원본 크기 | 게임 크롭 | 정규화 크기 | 720/크롭 높이 | 캡처 / 행 |
|---|---|---|---:|---:|
| 2000×923 | [1, 39, 1960, 922] | 1597×720 | 0.815402 | 9 / 18 |
| 2000×946 | [1, 40, 1959, 944] | 1559×720 | 0.796460 | 14 / 28 |
| 1832×1164 | [1, 106, 1782, 1105] | 1283×720 | 0.720721 | 5 / 10 |
| 2000×1147 | [1, 49, 1951, 1145] | 1281×720 | 0.656934 | 7 / 14 |

## 분류 기준

성공: class·grand·display가 라벨과 전부 일치. 일반 점수의 grand=null은 정상입니다. unknown: 필요한 숫자가 null이거나 표시가 Unknown인 행(일부만 읽힌 행 포함). 오독: null/Unknown이 아닌 반환값이 라벨과 다른 행. 이 표본에는 오독과 unknown이 겹치는 행이 없습니다. 비율의 분모는 분류별 전체 라벨 행 수이며, skip 행도 분모에서 빼지 않습니다.

## 서버·표시별 결과

| 서버 | 화면 | 표시 | 행 | 성공 | 성공률 | unknown | 오독 |
|---|---|---|---:|---:|---:|---:|---:|
| Jp | skill | Ordinary | 20 | 11 | 55.0% | 9 | 0 |
| Jp | skill | Compound | 20 | 3 | 15.0% | 17 | 0 |
| Jp | skill | 전체 | 40 | 14 | 35.0% | 26 | 0 |
| Kr | skill | Ordinary | 30 | 29 | 96.7% | 1 | 0 |
| Kr | skill | 전체 | 30 | 29 | 96.7% | 1 | 0 |

| 서버 | 표시 | 원본 크기 | 행 | 성공 | 성공률 | unknown | 오독 |
|---|---|---|---:|---:|---:|---:|---:|
| Jp | Compound | 1832×1164 | 1 | 0 | 0.0% | 1 | 0 |
| Jp | Compound | 2000×923 | 5 | 2 | 40.0% | 3 | 0 |
| Jp | Compound | 2000×946 | 9 | 0 | 0.0% | 9 | 0 |
| Jp | Compound | 2000×1147 | 5 | 1 | 20.0% | 4 | 0 |
| Jp | Ordinary | 1832×1164 | 3 | 3 | 100.0% | 0 | 0 |
| Jp | Ordinary | 2000×923 | 5 | 3 | 60.0% | 2 | 0 |
| Jp | Ordinary | 2000×946 | 9 | 4 | 44.4% | 5 | 0 |
| Jp | Ordinary | 2000×1147 | 3 | 1 | 33.3% | 2 | 0 |
| Kr | Ordinary | 1832×1164 | 6 | 6 | 100.0% | 0 | 0 |
| Kr | Ordinary | 2000×923 | 8 | 8 | 100.0% | 0 | 0 |
| Kr | Ordinary | 2000×946 | 10 | 9 | 90.0% | 1 | 0 |
| Kr | Ordinary | 2000×1147 | 6 | 6 | 100.0% | 0 | 0 |

| 원본 크기 전체 | 행 | 성공 | 성공률 | unknown | 오독 |
|---|---:|---:|---:|---:|---:|
| 1832×1164 | 10 | 9 | 90.0% | 1 | 0 |
| 2000×923 | 18 | 13 | 72.2% | 5 | 0 |
| 2000×946 | 28 | 13 | 46.4% | 15 | 0 |
| 2000×1147 | 14 | 8 | 57.1% | 6 | 0 |

## 필드별 결과

| 분류 | 필드 | 표시된 필드 수 | 성공 | unknown | 오독 |
|---|---|---:|---:|---:|---:|
| Jp Ordinary | class | 20 | 11 | 9 | 0 |
| Jp Ordinary | display | 20 | 11 | 9 | 0 |
| Jp Compound | class | 20 | 3 | 17 | 0 |
| Jp Compound | grand | 20 | 6 | 14 | 0 |
| Jp Compound | display | 20 | 19 | 1 | 0 |
| Kr Ordinary | class | 30 | 29 | 1 | 0 |
| Kr Ordinary | display | 30 | 29 | 1 | 0 |

## 오독 전체 목록 (1차 70행)

**0건.** 70행의 class·grand·display에서 확정된 값이 정답과 다른 경우는 없었습니다. 따라서 오독 크롭은 없습니다. 이는 이 70행 표본에 한정된 관측이며, 모든 실전 화면에서 오독이 없다는 보장은 아닙니다.

## unknown 전체 목록과 크롭

27행 전부 아래에 나열했습니다. `null`은 숫자 미확정이며 `Unknown`은 표시 형식 미확정입니다. 크롭은 정규화 프레임에서 제품이 읽은 점수 ROI 60×30이고, 주변 크롭은 80×50입니다. 이미지를 확대하거나 숫자를 보정하지 않았습니다.

| file | row | 서버 | 정답 | 실제 class / grand / display | 미판독 필드 | 크롭 |
|---|---:|---|---|---|---|---|
| 163.webp | 1 | Jp | Compound +62/+16 | null / 16 / Compound | class | [ROI](0013-score-audit-crops/owner-163-row-1.png) · [주변](0013-score-audit-crops/owner-163-row-1-wide.png) |
| 164.webp | 2 | Jp | Compound +24/+16 | null / 16 / Compound | class | [ROI](0013-score-audit-crops/owner-164-row-2.png) · [주변](0013-score-audit-crops/owner-164-row-2-wide.png) |
| 165.webp | 1 | Jp | Compound +54/+16 | null / 16 / Compound | class | [ROI](0013-score-audit-crops/owner-165-row-1.png) · [주변](0013-score-audit-crops/owner-165-row-1-wide.png) |
| 166.webp | 1 | Jp | Ordinary +57 | null / null / Unknown | class, display | [ROI](0013-score-audit-crops/owner-166-row-1.png) · [주변](0013-score-audit-crops/owner-166-row-1-wide.png) |
| 170.webp | 2 | Kr | Ordinary +1 | null / null / Unknown | class, display | [ROI](0013-score-audit-crops/owner-170-row-2.png) · [주변](0013-score-audit-crops/owner-170-row-2-wide.png) |
| 171.webp | 1 | Jp | Compound +9/+16 | null / null / Compound | class, grand | [ROI](0013-score-audit-crops/owner-171-row-1.png) · [주변](0013-score-audit-crops/owner-171-row-1-wide.png) |
| 171.webp | 2 | Jp | Compound +28/+16 | null / null / Compound | class, grand | [ROI](0013-score-audit-crops/owner-171-row-2.png) · [주변](0013-score-audit-crops/owner-171-row-2-wide.png) |
| 172.webp | 1 | Jp | Compound +23/+16 | null / null / Compound | class, grand | [ROI](0013-score-audit-crops/owner-172-row-1.png) · [주변](0013-score-audit-crops/owner-172-row-1-wide.png) |
| 172.webp | 2 | Jp | Compound +62/+16 | null / null / Compound | class, grand | [ROI](0013-score-audit-crops/owner-172-row-2.png) · [주변](0013-score-audit-crops/owner-172-row-2-wide.png) |
| 173.webp | 1 | Jp | Compound +54/+16 | null / null / Compound | class, grand | [ROI](0013-score-audit-crops/owner-173-row-1.png) · [주변](0013-score-audit-crops/owner-173-row-1-wide.png) |
| 176.webp | 1 | Jp | Ordinary +58 | null / null / Unknown | class, display | [ROI](0013-score-audit-crops/owner-176-row-1.png) · [주변](0013-score-audit-crops/owner-176-row-1-wide.png) |
| 177.webp | 1 | Jp | Ordinary +23 | null / null / Unknown | class, display | [ROI](0013-score-audit-crops/owner-177-row-1.png) · [주변](0013-score-audit-crops/owner-177-row-1-wide.png) |
| 177.webp | 2 | Jp | Ordinary +62 | null / null / Unknown | class, display | [ROI](0013-score-audit-crops/owner-177-row-2.png) · [주변](0013-score-audit-crops/owner-177-row-2-wide.png) |
| 178.webp | 1 | Jp | Ordinary +0 | null / null / Unknown | class, display | [ROI](0013-score-audit-crops/owner-178-row-1.png) · [주변](0013-score-audit-crops/owner-178-row-1-wide.png) |
| 179.webp | 2 | Jp | Ordinary +0 | null / null / Unknown | class, display | [ROI](0013-score-audit-crops/owner-179-row-2.png) · [주변](0013-score-audit-crops/owner-179-row-2-wide.png) |
| 180.webp | 1 | Jp | Compound +0/+16 | null / null / Compound | class, grand | [ROI](0013-score-audit-crops/owner-180-row-1.png) · [주변](0013-score-audit-crops/owner-180-row-1-wide.png) |
| 180.webp | 2 | Jp | Compound +24/+16 | null / null / Compound | class, grand | [ROI](0013-score-audit-crops/owner-180-row-2.png) · [주변](0013-score-audit-crops/owner-180-row-2-wide.png) |
| 181.webp | 1 | Jp | Compound +5/+16 | null / null / Compound | class, grand | [ROI](0013-score-audit-crops/owner-181-row-1.png) · [주변](0013-score-audit-crops/owner-181-row-1-wide.png) |
| 181.webp | 2 | Jp | Compound +54/+16 | null / null / Unknown | class, grand, display | [ROI](0013-score-audit-crops/owner-181-row-2.png) · [주변](0013-score-audit-crops/owner-181-row-2-wide.png) |
| 182.webp | 1 | Jp | Compound +54/+16 | null / null / Compound | class, grand | [ROI](0013-score-audit-crops/owner-182-row-1.png) · [주변](0013-score-audit-crops/owner-182-row-1-wide.png) |
| 187.webp | 1 | Jp | Compound +62/+16 | null / null / Compound | class, grand | [ROI](0013-score-audit-crops/owner-187-row-1.png) · [주변](0013-score-audit-crops/owner-187-row-1-wide.png) |
| 187.webp | 2 | Jp | Compound +1/+16 | null / null / Compound | class, grand | [ROI](0013-score-audit-crops/owner-187-row-2.png) · [주변](0013-score-audit-crops/owner-187-row-2-wide.png) |
| 188.webp | 2 | Jp | Compound +24/+16 | null / null / Compound | class, grand | [ROI](0013-score-audit-crops/owner-188-row-2.png) · [주변](0013-score-audit-crops/owner-188-row-2-wide.png) |
| 189.webp | 1 | Jp | Compound +54/+16 | null / null / Compound | class, grand | [ROI](0013-score-audit-crops/owner-189-row-1.png) · [주변](0013-score-audit-crops/owner-189-row-1-wide.png) |
| 189.webp | 2 | Jp | Ordinary +62 | null / null / Unknown | class, display | [ROI](0013-score-audit-crops/owner-189-row-2.png) · [주변](0013-score-audit-crops/owner-189-row-2-wide.png) |
| 190.webp | 1 | Jp | Ordinary +50 | null / null / Unknown | class, display | [ROI](0013-score-audit-crops/owner-190-row-1.png) · [주변](0013-score-audit-crops/owner-190-row-1-wide.png) |
| 194.webp | 2 | Jp | Ordinary +5 | null / null / Unknown | class, display | [ROI](0013-score-audit-crops/owner-194-row-2.png) · [주변](0013-score-audit-crops/owner-194-row-2-wide.png) |

## 주요 실패 패턴

- **JP 복합 17/20행 미완전 판독**: 3행은 Grand 16만 맞게 읽고 Class를 놓쳤으며, 14행은 두 숫자를 모두 놓쳤습니다. 14행 중 13행은 Compound 형식만 식별했고, 1행은 형식도 Unknown이었습니다.
- **일반 점수 10/50행 미판독**: JP 9행·KR 1행이며 모두 class=null, display=Unknown입니다. JP는 +57·+58·+23·+62·+0·+50·+5 등 여러 값에서 나타났습니다. KR은 `170.webp / 2 / +1`만 놓쳤습니다.
- **같은 점수도 창 크기·캡처에 따라 결과가 다릅니다.** +54/+16은 2000×923에서 Grand만 읽혔지만 2000×946와 1832×1164에서는 둘 다 미확정이었습니다. +0/+16은 2000×923와 2000×1147에서 성공한 캡처가 있으나 2000×946에서는 두 숫자를 놓쳤습니다.
- 따라서 크기별 차이는 관측되지만 리스케일링이 유일 원인이라고 확정할 수는 없습니다. 중복 서포트, 글꼴·배경·원본 창 리샘플링이 섞인 표본이고 OCR 신뢰도·템플릿 유사도는 이번 하네스의 반환 자료에 없습니다. 제품 원인이나 임계값 변경은 이번 측정 범위에 포함하지 않았습니다.

## 무작위 라벨 10개 원본 재검수

70행에서 `random.Random(202610220013).sample(rows,10)`으로 중복 없이 추출했습니다. 정규화 PNG 대신 원본 WEBP의 점수 부분을 직접 확인했습니다. 10개 모두 기존 라벨과 일치하며 수정은 없습니다. row는 완전 노출 행 사이의 1 기반 `rowIndex`입니다.

| file | row | label | what you see |
|---|---:|---|---|
| 181.webp | 2 | Compound +54/+16 | 흰색 +54, 슬래시, 노란색 +16 |
| 167.webp | 2 | Ordinary +60 | 흰색 +60 한 값 |
| 180.webp | 2 | Compound +24/+16 | 흰색 +24, 슬래시, 노란색 +16 |
| 188.webp | 1 | Compound +0/+16 | 흰색 +0, 슬래시, 노란색 +16 |
| 173.webp | 2 | Ordinary +62 | 노란색 +62 한 값 |
| 186.webp | 2 | Ordinary +0 | 흰색 +0 한 값 |
| 196.webp | 2 | Ordinary +29 | 흰색 +29 한 값 |
| 175.webp | 1 | Ordinary +0 | 흰색 +0 한 값 |
| 196.webp | 1 | Ordinary +42 | 흰색 +42 한 값 |
| 182.webp | 1 | Compound +54/+16 | 흰색 +54, 슬래시, 노란색 +16 |

## 70행 점수 원시 결과

| file | row | 서버 | 정답 | 실제 class / grand / display | 판정 |
|---|---:|---|---|---|---|
| 162.webp | 1 | Kr | Ordinary +0 | 0 / null / Ordinary | 성공 |
| 162.webp | 2 | Kr | Ordinary +18 | 18 / null / Ordinary | 성공 |
| 163.webp | 1 | Jp | Compound +62/+16 | null / 16 / Compound | unknown |
| 163.webp | 2 | Jp | Compound +1/+16 | 1 / 16 / Compound | 성공 |
| 164.webp | 1 | Jp | Compound +0/+16 | 0 / 16 / Compound | 성공 |
| 164.webp | 2 | Jp | Compound +24/+16 | null / 16 / Compound | unknown |
| 165.webp | 1 | Jp | Compound +54/+16 | null / 16 / Compound | unknown |
| 165.webp | 2 | Jp | Ordinary +62 | 62 / null / Ordinary | 성공 |
| 166.webp | 1 | Jp | Ordinary +57 | null / null / Unknown | unknown |
| 166.webp | 2 | Jp | Ordinary +62 | 62 / null / Ordinary | 성공 |
| 167.webp | 1 | Kr | Ordinary +0 | 0 / null / Ordinary | 성공 |
| 167.webp | 2 | Kr | Ordinary +60 | 60 / null / Ordinary | 성공 |
| 168.webp | 1 | Kr | Ordinary +60 | 60 / null / Ordinary | 성공 |
| 168.webp | 2 | Kr | Ordinary +0 | 0 / null / Ordinary | 성공 |
| 169.webp | 1 | Kr | Ordinary +0 | 0 / null / Ordinary | 성공 |
| 169.webp | 2 | Kr | Ordinary +42 | 42 / null / Ordinary | 성공 |
| 170.webp | 1 | Kr | Ordinary +18 | 18 / null / Ordinary | 성공 |
| 170.webp | 2 | Kr | Ordinary +1 | null / null / Unknown | unknown |
| 171.webp | 1 | Jp | Compound +9/+16 | null / null / Compound | unknown |
| 171.webp | 2 | Jp | Compound +28/+16 | null / null / Compound | unknown |
| 172.webp | 1 | Jp | Compound +23/+16 | null / null / Compound | unknown |
| 172.webp | 2 | Jp | Compound +62/+16 | null / null / Compound | unknown |
| 173.webp | 1 | Jp | Compound +54/+16 | null / null / Compound | unknown |
| 173.webp | 2 | Jp | Ordinary +62 | 62 / null / Ordinary | 성공 |
| 174.webp | 1 | Kr | Ordinary +62 | 62 / null / Ordinary | 성공 |
| 174.webp | 2 | Kr | Ordinary +22 | 22 / null / Ordinary | 성공 |
| 175.webp | 1 | Kr | Ordinary +0 | 0 / null / Ordinary | 성공 |
| 175.webp | 2 | Kr | Ordinary +62 | 62 / null / Ordinary | 성공 |
| 176.webp | 1 | Jp | Ordinary +58 | null / null / Unknown | unknown |
| 176.webp | 2 | Jp | Ordinary +62 | 62 / null / Ordinary | 성공 |
| 177.webp | 1 | Jp | Ordinary +23 | null / null / Unknown | unknown |
| 177.webp | 2 | Jp | Ordinary +62 | null / null / Unknown | unknown |
| 178.webp | 1 | Jp | Ordinary +0 | null / null / Unknown | unknown |
| 178.webp | 2 | Jp | Ordinary +62 | 62 / null / Ordinary | 성공 |
| 179.webp | 1 | Jp | Ordinary +28 | 28 / null / Ordinary | 성공 |
| 179.webp | 2 | Jp | Ordinary +0 | null / null / Unknown | unknown |
| 180.webp | 1 | Jp | Compound +0/+16 | null / null / Compound | unknown |
| 180.webp | 2 | Jp | Compound +24/+16 | null / null / Compound | unknown |
| 181.webp | 1 | Jp | Compound +5/+16 | null / null / Compound | unknown |
| 181.webp | 2 | Jp | Compound +54/+16 | null / null / Unknown | unknown |
| 182.webp | 1 | Jp | Compound +54/+16 | null / null / Compound | unknown |
| 182.webp | 2 | Jp | Ordinary +62 | 62 / null / Ordinary | 성공 |
| 183.webp | 1 | Jp | Ordinary +50 | 50 / null / Ordinary | 성공 |
| 183.webp | 2 | Jp | Ordinary +62 | 62 / null / Ordinary | 성공 |
| 184.webp | 1 | Kr | Ordinary +45 | 45 / null / Ordinary | 성공 |
| 184.webp | 2 | Kr | Ordinary +0 | 0 / null / Ordinary | 성공 |
| 185.webp | 1 | Kr | Ordinary +0 | 0 / null / Ordinary | 성공 |
| 185.webp | 2 | Kr | Ordinary +58 | 58 / null / Ordinary | 성공 |
| 186.webp | 1 | Kr | Ordinary +0 | 0 / null / Ordinary | 성공 |
| 186.webp | 2 | Kr | Ordinary +0 | 0 / null / Ordinary | 성공 |
| 187.webp | 1 | Jp | Compound +62/+16 | null / null / Compound | unknown |
| 187.webp | 2 | Jp | Compound +1/+16 | null / null / Compound | unknown |
| 188.webp | 1 | Jp | Compound +0/+16 | 0 / 16 / Compound | 성공 |
| 188.webp | 2 | Jp | Compound +24/+16 | null / null / Compound | unknown |
| 189.webp | 1 | Jp | Compound +54/+16 | null / null / Compound | unknown |
| 189.webp | 2 | Jp | Ordinary +62 | null / null / Unknown | unknown |
| 190.webp | 1 | Jp | Ordinary +50 | null / null / Unknown | unknown |
| 190.webp | 2 | Jp | Ordinary +62 | 62 / null / Ordinary | 성공 |
| 191.webp | 1 | Kr | Ordinary +45 | 45 / null / Ordinary | 성공 |
| 191.webp | 2 | Kr | Ordinary +0 | 0 / null / Ordinary | 성공 |
| 192.webp | 1 | Kr | Ordinary +58 | 58 / null / Ordinary | 성공 |
| 192.webp | 2 | Kr | Ordinary +58 | 58 / null / Ordinary | 성공 |
| 193.webp | 1 | Kr | Ordinary +0 | 0 / null / Ordinary | 성공 |
| 193.webp | 2 | Kr | Ordinary +3 | 3 / null / Ordinary | 성공 |
| 194.webp | 1 | Jp | Ordinary +62 | 62 / null / Ordinary | 성공 |
| 194.webp | 2 | Jp | Ordinary +5 | null / null / Unknown | unknown |
| 195.webp | 1 | Kr | Ordinary +0 | 0 / null / Ordinary | 성공 |
| 195.webp | 2 | Kr | Ordinary +0 | 0 / null / Ordinary | 성공 |
| 196.webp | 1 | Kr | Ordinary +42 | 42 / null / Ordinary | 성공 |
| 196.webp | 2 | Kr | Ordinary +29 | 29 / null / Ordinary | 성공 |

## 어펜드 감사 (20장 40행 추가)

입력: `build/orchestration/inputs/0013-score/owner-append/197..216.webp` 20장(JP 11장·KR 9장), 완전히 보이는 **40행**. 같은 정규화(크롭 표의 2000×1147, 1832×1164)를 적용해 `docs/reference-images/support-score-audit/197..216.png`로 저장했고, 라벨은 `labels.json`에 70행 뒤에 이어 붙였습니다(기존 70행 무변경, `appendSlots`/`skillLevels` 필드 추가).

**입력 해석 주의:** 20장 중 **209·210·211.webp(KR 1장·JP 2장, 1832×1164, 6행)는 어펜드 화면이 아니라** 서번트 고유 스킬 3칸(`고유 스킬`/`固有スキル`, 전부 Lv.10) 화면입니다. 이 6행은 `view=skill`로 라벨하고 점수 측정에만 포함했습니다. 어펜드 슬롯 통계는 나머지 **34행(JP 18·KR 16, 모두 2000×1147)**입니다. 슬롯 라벨은 레벨 숫자 또는 `locked`(숫자 없는 어두운 아이콘)입니다. 실제 값은 2·9·4처럼 10이 아닌 레벨 5칸(201 행 1의 2, 214 행 1의 9/4/9/4)과 잠금 102칸을 포함합니다.

측정은 동일 방법(`--suite support --cases`, 파일 기반 instrumentation, 게임 입력 없음, 제품 리더·임계값 무변경, 같은 APK SHA-256)입니다. 원시 결과(로컬): `build/device-qa/20261022-150035/report-support.json`. 모든 프레임은 view 판독이 기대(어펜드 34행=Append, 고유 스킬 6행=Normal)와 일치했고 행 탐지도 통과했습니다.

### 어펜드 슬롯별 정확도 (제품 `SupportSkillLevelReader.read(bounds, append=true)`, "레벨==10" 기준)

| 슬롯 | 정답 10 | 정답 locked | 정답 10 아닌 해금(2·9·4) | 정확 | 정확도 | 10 오판(오독) | 10 놓침 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | 5 | 27 | 2 | 34/34 | 100% | 0 | 0 |
| 2 | 24 | 9 | 1 | 34/34 | 100% | 0 | 0 |
| 3 | 8 | 26 | 0 | 34/34 | 100% | 0 | 0 |
| 4 | 9 | 24 | 1 | 34/34 | 100% | 0 | 0 |
| 5 | 17 | 16 | 1 | 34/34 | 100% | 0 | 0 |
| **전체** | 63 | 102 | 5 | **170/170** | **100%** | **0** | **0** |

행 전체(5칸 모두 일치) 34/34. 서버별 JP 18행·KR 16행 모두 100%, 크기는 전부 2000×1147(1종)이므로 크기별 비교는 불가합니다. 10이 아닌 해금 레벨(2·9·4)은 5칸으로 표본이 작지만 전부 false로 읽혔습니다.

### 조건 통과/불통과 정확도 (제품 `SupportSkillLevelReader.matches(required, detected)`와 같은 규칙: 필요 칸이 모두 true)

`detected`는 제품 리더의 슬롯별 반환값, 정답은 라벨(레벨==10). 34행 기준.

| 조건 | 정답 통과 행 | 맞은 판정 | 정확도 | 통과해야 하는데 실패(미통과) | **실패해야 하는데 통과(오독)** |
|---|---:|---:|---:|---:|---:|
| 10/10/x/x/x | 5 | 34/34 | 100% | 0 | **0** |
| x/10/x/x/x | 24 | 34/34 | 100% | 0 | **0** |
| 모두 10 (10/10/10/10/10) | 5 | 34/34 | 100% | 0 | **0** |
| x/x/x/x/10 | 17 | 34/34 | 100% | 0 | **0** |
| x/10/x/x/10 | 15 | 34/34 | 100% | 0 | **0** |
| 10/x/x/x/x | 5 | 34/34 | 100% | 0 | **0** |
| x/10/10/x/x | 8 | 34/34 | 100% | 0 | **0** |
| x/10/x/10/x | 9 | 34/34 | 100% | 0 | **0** |

`10/10/x/x/x` 통과 정답 5행은 전부 5칸 모두 10인 행(197 행 2, 198 행 1, 205 행 1, 206 행 2, 208 행 1)이며, 1번 칸이 locked인 행이나 2칸만 10인 행에서 잘못 통과한 경우는 없었습니다.

## 오독 (이번 추가분 포함, 가장 중요)

### 어펜드 슬롯·조건 오독: **0건**
10이 아닌 슬롯(locked 포함)을 10으로 읽은 경우 0, 실패해야 할 조건이 통과한 경우 0. (표본: 어펜드 34행, 슬롯 170칸. 이 표본에 한정된 관측이며 모든 화면의 보장이 아닙니다.)

### 점수 판독 오독: **1건 (1차 70행에서는 0건이었음)**
| file | row | 서버 | 화면 | 정답 | 제품 반환 (class / grand / display) | 오독 필드 | 크롭 |
|---|---:|---|---|---|---|---|---|
| 199.webp | 2 | Jp | append | Compound +62/+16 | 62 / **1** / Compound | grand (정답 16) | [ROI](0013-score-audit-crops/owner-199-row-2.png) · [주변](0013-score-audit-crops/owner-199-row-2-wide.png) |

원본 WEBP를 직접 확인한 결과 점수는 흰색 +62, 슬래시, 노란색 +16이 분명하므로 라벨 오류가 아닙니다. 리더가 Grand `+16`을 `1`로 확정 반환했습니다(이 값은 `SupportScoreSelection`에서 grand 조건을 잘못 통과/실패시킬 수 있음). 점수 오독 후보 원인은 이번 측정에서 규명하지 않았고 리더·임계값은 변경하지 않았습니다. 크롭은 정규화 프레임에서 점수 ROI와 같은 크기(60×30)로 근사 위치를 잘라낸 것으로, 제품이 실제 사용한 ROI 픽셀과 정확히 일치하지 않을 수 있습니다.

## 어펜드 행의 점수 판독 결과 (신규 40행)

분류 기준은 위와 같습니다(성공/unknown/오독).

신규 40행, 서버·화면·표시별:

| 서버 | 화면 | 표시 | 행 | 성공 | 성공률 | unknown | 오독 |
|---|---|---|---:|---:|---:|---:|---:|
| Jp | append | Compound | 13 | 2 | 15.4% | 10 | 1 |
| Jp | append | Ordinary | 5 | 2 | 40.0% | 3 | 0 |
| Jp | skill | Compound | 4 | 0 | 0.0% | 4 | 0 |
| Kr | append | Ordinary | 16 | 16 | 100.0% | 0 | 0 |
| Kr | skill | Ordinary | 2 | 2 | 100.0% | 0 | 0 |

신규 40행, 원본 크기별:

| 원본 크기 | 행 | 성공 | 성공률 | unknown | 오독 |
|---|---:|---:|---:|---:|---:|
| 1832×1164 | 6 | 2 | 33.3% | 4 | 0 |
| 2000×1147 | 34 | 20 | 58.8% | 13 | 1 |

### 합산(110행) 서버·화면·표시별

| 서버 | 화면 | 표시 | 행 | 성공 | 성공률 | unknown | 오독 |
|---|---|---|---:|---:|---:|---:|---:|
| Jp | append | Compound | 13 | 2 | 15.4% | 10 | 1 |
| Jp | append | Ordinary | 5 | 2 | 40.0% | 3 | 0 |
| Jp | skill | Compound | 24 | 3 | 12.5% | 21 | 0 |
| Jp | skill | Ordinary | 20 | 11 | 55.0% | 9 | 0 |
| Kr | append | Ordinary | 16 | 16 | 100.0% | 0 | 0 |
| Kr | skill | Ordinary | 32 | 31 | 96.9% | 1 | 0 |

### 합산 서버·표시별

| 서버 | 표시 | 행 | 성공 | 성공률 | unknown | 오독 |
|---|---|---:|---:|---:|---:|---:|
| Jp | Compound | 37 | 5 | 13.5% | 31 | 1 |
| Jp | Ordinary | 25 | 13 | 52.0% | 12 | 0 |
| Kr | Ordinary | 48 | 47 | 97.9% | 1 | 0 |

### 합산 원본 크기별 / 서버·표시·크기별

| 원본 크기 | 행 | 성공 | 성공률 | unknown | 오독 |
|---|---:|---:|---:|---:|---:|
| 1832×1164 | 16 | 11 | 68.8% | 5 | 0 |
| 2000×1147 | 48 | 28 | 58.3% | 19 | 1 |
| 2000×923 | 18 | 13 | 72.2% | 5 | 0 |
| 2000×946 | 28 | 13 | 46.4% | 15 | 0 |

| 서버 | 표시 | 크기 | 행 | 성공 | 성공률 | unknown | 오독 |
|---|---|---|---:|---:|---:|---:|---:|
| Jp | Compound | 1832×1164 | 5 | 0 | 0.0% | 5 | 0 |
| Jp | Compound | 2000×1147 | 18 | 3 | 16.7% | 14 | 1 |
| Jp | Compound | 2000×923 | 5 | 2 | 40.0% | 3 | 0 |
| Jp | Compound | 2000×946 | 9 | 0 | 0.0% | 9 | 0 |
| Jp | Ordinary | 1832×1164 | 3 | 3 | 100.0% | 0 | 0 |
| Jp | Ordinary | 2000×1147 | 8 | 3 | 37.5% | 5 | 0 |
| Jp | Ordinary | 2000×923 | 5 | 3 | 60.0% | 2 | 0 |
| Jp | Ordinary | 2000×946 | 9 | 4 | 44.4% | 5 | 0 |
| Kr | Ordinary | 1832×1164 | 8 | 8 | 100.0% | 0 | 0 |
| Kr | Ordinary | 2000×1147 | 22 | 22 | 100.0% | 0 | 0 |
| Kr | Ordinary | 2000×923 | 8 | 8 | 100.0% | 0 | 0 |
| Kr | Ordinary | 2000×946 | 10 | 9 | 90.0% | 1 | 0 |

합산 110행: 성공 65(59.1%), unknown 44, 오독 1. JP 복합은 신규 17행(어펜드 13·고유 스킬 4) 중 성공 2·unknown 14·오독 1로 1차와 같은 약점이 재현됐고, KR 일반은 신규 18행 전부 성공(합산 48행 중 47행)입니다. 어펜드 화면 자체가 점수 판독을 방해하는 뚜렷한 증거는 없습니다(어펜드 KR 16/16, JP 일반 2/5·복합 2/13).

### 신규 행 중 성공이 아닌 전체 목록 (18행: unknown 17·오독 1)

| file | row | 서버 | 화면 | 정답 | 실제 class / grand / display | 판정 | 미판독 필드 | 크롭 |
|---|---:|---|---|---|---|---|---|---|
| 197.webp | 1 | Jp | append | Compound +62/+16 | null / null / Compound | unknown | class, grand | [ROI](0013-score-audit-crops/owner-197-row-1.png) · [주변](0013-score-audit-crops/owner-197-row-1-wide.png) |
| 197.webp | 2 | Jp | append | Compound +62/+16 | null / null / Compound | unknown | class, grand | [ROI](0013-score-audit-crops/owner-197-row-2.png) · [주변](0013-score-audit-crops/owner-197-row-2-wide.png) |
| 198.webp | 1 | Jp | append | Compound +51/+16 | null / null / Compound | unknown | class, grand | [ROI](0013-score-audit-crops/owner-198-row-1.png) · [주변](0013-score-audit-crops/owner-198-row-1-wide.png) |
| 198.webp | 2 | Jp | append | Compound +62/+16 | null / null / Compound | unknown | class, grand | [ROI](0013-score-audit-crops/owner-198-row-2.png) · [주변](0013-score-audit-crops/owner-198-row-2-wide.png) |
| 199.webp | 1 | Jp | append | Compound +57/+16 | null / null / Compound | unknown | class, grand | [ROI](0013-score-audit-crops/owner-199-row-1.png) · [주변](0013-score-audit-crops/owner-199-row-1-wide.png) |
| 199.webp | 2 | Jp | append | Compound +62/+16 | 62 / 1 / Compound | 오독 | (오독:grand) | [ROI](0013-score-audit-crops/owner-199-row-2.png) · [주변](0013-score-audit-crops/owner-199-row-2-wide.png) |
| 200.webp | 2 | Jp | append | Compound +24/+16 | null / null / Compound | unknown | class, grand | [ROI](0013-score-audit-crops/owner-200-row-2.png) · [주변](0013-score-audit-crops/owner-200-row-2-wide.png) |
| 201.webp | 1 | Jp | append | Compound +54/+16 | null / null / Compound | unknown | class, grand | [ROI](0013-score-audit-crops/owner-201-row-1.png) · [주변](0013-score-audit-crops/owner-201-row-1-wide.png) |
| 201.webp | 2 | Jp | append | Ordinary +62 | null / null / Unknown | unknown | class, display | [ROI](0013-score-audit-crops/owner-201-row-2.png) · [주변](0013-score-audit-crops/owner-201-row-2-wide.png) |
| 206.webp | 1 | Jp | append | Compound +62/+16 | null / null / Compound | unknown | class, grand | [ROI](0013-score-audit-crops/owner-206-row-1.png) · [주변](0013-score-audit-crops/owner-206-row-1-wide.png) |
| 210.webp | 1 | Jp | skill | Compound +62/+16 | null / null / Compound | unknown | class, grand | [ROI](0013-score-audit-crops/owner-210-row-1.png) · [주변](0013-score-audit-crops/owner-210-row-1-wide.png) |
| 210.webp | 2 | Jp | skill | Compound +1/+16 | null / null / Compound | unknown | class, grand | [ROI](0013-score-audit-crops/owner-210-row-2.png) · [주변](0013-score-audit-crops/owner-210-row-2-wide.png) |
| 211.webp | 1 | Jp | skill | Compound +0/+16 | null / null / Compound | unknown | class, grand | [ROI](0013-score-audit-crops/owner-211-row-1.png) · [주변](0013-score-audit-crops/owner-211-row-1-wide.png) |
| 211.webp | 2 | Jp | skill | Compound +24/+16 | null / null / Compound | unknown | class, grand | [ROI](0013-score-audit-crops/owner-211-row-2.png) · [주변](0013-score-audit-crops/owner-211-row-2-wide.png) |
| 212.webp | 1 | Jp | append | Compound +24/+16 | null / null / Compound | unknown | class, grand | [ROI](0013-score-audit-crops/owner-212-row-1.png) · [주변](0013-score-audit-crops/owner-212-row-1-wide.png) |
| 212.webp | 2 | Jp | append | Compound +62/+16 | null / null / Compound | unknown | class, grand | [ROI](0013-score-audit-crops/owner-212-row-2.png) · [주변](0013-score-audit-crops/owner-212-row-2-wide.png) |
| 214.webp | 1 | Jp | append | Ordinary +62 | null / null / Unknown | unknown | class, display | [ROI](0013-score-audit-crops/owner-214-row-1.png) · [주변](0013-score-audit-crops/owner-214-row-1-wide.png) |
| 214.webp | 2 | Jp | append | Ordinary +0 | null / null / Unknown | unknown | class, display | [ROI](0013-score-audit-crops/owner-214-row-2.png) · [주변](0013-score-audit-crops/owner-214-row-2-wide.png) |

## 신규 40행 점수 원시 결과

| file | row | 서버 | 화면 | 정답 | 실제 class / grand / display | 판정 |
|---|---:|---|---|---|---|---|
| 197.webp | 1 | Jp | append | Compound +62/+16 | null / null / Compound | unknown |
| 197.webp | 2 | Jp | append | Compound +62/+16 | null / null / Compound | unknown |
| 198.webp | 1 | Jp | append | Compound +51/+16 | null / null / Compound | unknown |
| 198.webp | 2 | Jp | append | Compound +62/+16 | null / null / Compound | unknown |
| 199.webp | 1 | Jp | append | Compound +57/+16 | null / null / Compound | unknown |
| 199.webp | 2 | Jp | append | Compound +62/+16 | 62 / 1 / Compound | 오독 |
| 200.webp | 1 | Jp | append | Compound +0/+16 | 0 / 16 / Compound | 성공 |
| 200.webp | 2 | Jp | append | Compound +24/+16 | null / null / Compound | unknown |
| 201.webp | 1 | Jp | append | Compound +54/+16 | null / null / Compound | unknown |
| 201.webp | 2 | Jp | append | Ordinary +62 | null / null / Unknown | unknown |
| 202.webp | 1 | Kr | append | Ordinary +62 | 62 / null / Ordinary | 성공 |
| 202.webp | 2 | Kr | append | Ordinary +45 | 45 / null / Ordinary | 성공 |
| 203.webp | 1 | Kr | append | Ordinary +0 | 0 / null / Ordinary | 성공 |
| 203.webp | 2 | Kr | append | Ordinary +0 | 0 / null / Ordinary | 성공 |
| 204.webp | 1 | Kr | append | Ordinary +0 | 0 / null / Ordinary | 성공 |
| 204.webp | 2 | Kr | append | Ordinary +60 | 60 / null / Ordinary | 성공 |
| 205.webp | 1 | Kr | append | Ordinary +15 | 15 / null / Ordinary | 성공 |
| 205.webp | 2 | Kr | append | Ordinary +0 | 0 / null / Ordinary | 성공 |
| 206.webp | 1 | Jp | append | Compound +62/+16 | null / null / Compound | unknown |
| 206.webp | 2 | Jp | append | Compound +1/+16 | 1 / 16 / Compound | 성공 |
| 207.webp | 1 | Kr | append | Ordinary +0 | 0 / null / Ordinary | 성공 |
| 207.webp | 2 | Kr | append | Ordinary +62 | 62 / null / Ordinary | 성공 |
| 208.webp | 1 | Kr | append | Ordinary +15 | 15 / null / Ordinary | 성공 |
| 208.webp | 2 | Kr | append | Ordinary +0 | 0 / null / Ordinary | 성공 |
| 209.webp | 1 | Kr | skill | Ordinary +21 | 21 / null / Ordinary | 성공 |
| 209.webp | 2 | Kr | skill | Ordinary +21 | 21 / null / Ordinary | 성공 |
| 210.webp | 1 | Jp | skill | Compound +62/+16 | null / null / Compound | unknown |
| 210.webp | 2 | Jp | skill | Compound +1/+16 | null / null / Compound | unknown |
| 211.webp | 1 | Jp | skill | Compound +0/+16 | null / null / Compound | unknown |
| 211.webp | 2 | Jp | skill | Compound +24/+16 | null / null / Compound | unknown |
| 212.webp | 1 | Jp | append | Compound +24/+16 | null / null / Compound | unknown |
| 212.webp | 2 | Jp | append | Compound +62/+16 | null / null / Compound | unknown |
| 213.webp | 1 | Jp | append | Ordinary +62 | 62 / null / Ordinary | 성공 |
| 213.webp | 2 | Jp | append | Ordinary +62 | 62 / null / Ordinary | 성공 |
| 214.webp | 1 | Jp | append | Ordinary +62 | null / null / Unknown | unknown |
| 214.webp | 2 | Jp | append | Ordinary +0 | null / null / Unknown | unknown |
| 215.webp | 1 | Kr | append | Ordinary +0 | 0 / null / Ordinary | 성공 |
| 215.webp | 2 | Kr | append | Ordinary +0 | 0 / null / Ordinary | 성공 |
| 216.webp | 1 | Kr | append | Ordinary +45 | 45 / null / Ordinary | 성공 |
| 216.webp | 2 | Kr | append | Ordinary +0 | 0 / null / Ordinary | 성공 |

## 무작위 라벨 10개 원본 재검수 (신규 40행)

`random.Random(202610220014).sample(rows,10)`로 신규 40행에서 중복 없이 추출해 정규화 PNG가 아닌 원본 WEBP의 슬롯·점수 부분을 직접 확인했습니다. **10개 모두 라벨과 일치, 수정 없음**입니다.

| file | row | 슬롯(또는 고유 스킬) 라벨 | 점수 라벨 |
|---|---:|---|---|
| 213.webp | 1 | locked/10/locked/locked/10 | Ordinary +62 |
| 215.webp | 2 | locked/10/locked/locked/locked | Ordinary +0 |
| 216.webp | 1 | locked/locked/locked/locked/10 | Ordinary +45 |
| 212.webp | 2 | locked/10/10/locked/locked | Compound +62/+16 |
| 198.webp | 2 | locked/10/locked/10/10 | Compound +62/+16 |
| 208.webp | 2 | 전부 locked | Ordinary +0 |
| 214.webp | 1 | 9/4/locked/9/4 | Ordinary +62 |
| 197.webp | 1 | locked/10/10/10/10 | Compound +62/+16 |
| 202.webp | 2 | locked ×4, 5번 10 | Ordinary +45 |
| 211.webp | 2 | 10/10/10 (고유 스킬 화면) | Compound +24/+16 |

추가로 오독이 나온 199.webp 행 2도 원본에서 +62/+16과 슬롯 locked/10/10/10/10을 확인했습니다.

## 회귀 QA와 검증

[support-score-audit.json](../reference-images/cases/support-score-audit.json)에 정답 class·grand·display·Normal 화면 기대값을 유지했습니다. **43행 활성화, 27행 skip**이며, 각 skip에 정답·실제 반환값·미판독 필드를 기록했습니다. skip은 오독이나 unknown을 성공으로 바꾸지 않으며, 위 기준 측정 통계에는 70행이 모두 포함됩니다.
- 정답 기대값을 복구한 QA 재실행(기획자, `build/device-qa/20261022-145736/`): **43개 통과, 실패 0, skip 27**. 기존 support suite 194개도 그대로 통과(`20261022-145601`).
- 전체 단위 테스트 `:app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test`: **통과**.
- Codex가 측정 직후 사용 한도로 멈춰, 기획자가 QA 재실행·커밋을 마무리했습니다.
- 어펜드 추가분(20장 40행): 같은 `support-score-audit.json`에 행마다 2케이스를 추가했습니다(`owner-N-row-K-skills`: view·슬롯 10 여부, `owner-N-row-K`: 점수). 슬롯 케이스 40개와 점수 성공 22개는 활성, **점수 unknown 17·오독 1은 skip(사유와 실제 반환값 기록)**입니다. 이 파일 전체 실행: **105개 통과, 실패 0, skip 45**(`build/device-qa/20261022-150428`). 기존 support suite 194개도 그대로 통과(`20261022-150515`). skip은 오독·unknown을 성공으로 바꾸지 않으며 위 통계에는 모두 포함됩니다.
