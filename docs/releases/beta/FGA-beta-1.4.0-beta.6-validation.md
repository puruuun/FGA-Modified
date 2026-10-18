# FGA 1.4.0-beta.6 검증 보고서

상태: 전체 단위 테스트·기기 QA·Main CI 성공. APK 검증 및 BlueStacks 업데이트 설치 완료.

버전 `1.4.0-beta.6` / `1004006`. 안정판 `1.2.1` / `3083` 유지. 0007 §14 D-29~D-32를 우선 적용했고 §13은 D-26의 격자 원칙만 구현했다.

## 동작 변경

- 종류·별 수·NEW!·프레임 등급 판독을 없앴다. 실제 카드 존재, 잠금/금지 표시 없음, D-19 미선택(false)인 칸만 선택한다. 선택 여부 null은 누르지 않는다. 자물쇠·잠금 문구·선택 불가·Grand·PARTY는 시험 모드에서도 선택하지 않는다.
- 실제 판매의 `realSellAllowed`, 불명확 잠금 거부, 템플릿/검증 서버가 없을 때 시험 모드 강제, D-10 시험 모드 Unknown 예외, 시험 모드 기본 켬, 999칸 제한, 0개 판매 후 재가득 참 정지를 유지했다.
- 누른 뒤 확인 대기·재탭은 없다. 겹친 화면의 이미 선택된 칸은 청록색으로 건너뛴다. D-23 겹침 스크롤과 관성 억제를 유지했다.
- 카드 테두리의 주기/여백으로 한 화면의 행 기준점을 한 번 잡고 고정 열 간격으로 전 칸을 만든다. 종류 라벨과 무관하게 카드의 양쪽 연속 테두리로 존재를 판정한다. 7열 검증도 글자를 쓰지 않는다. 평가 행이 없으면 한 번 스크롤·재기준 설정 후에만 사유별로 정지한다. 화면 좌표·행 간격은 `FpSellLocations`가 제공한다.
- 제목 이미지만 기억한다. MENU→소환 후 제자리 비교, 이후 왼쪽 최대 10회와 각 이동 후 비교를 한다. 제목과 FP 버튼이 모두 맞아야 복귀하고 아니면 `BannerNotFound`로 정지한다.
- 설정은 자동 판매와 시험 모드만 남겼다. 자동 판매 스위치 바로 아래 별도 en/ko 경고를 `FontWeight.Bold`로 표시하고 위키 맨 위에도 같은 경고를 굵게 썼다. 합계는 선택 수와 잠금 판별 불가 수다. 단계별 정지에도 두 수와 시험 모드 상태를 보존한다.
- `FpSell` 태그의 칸 로그는 page, r/c, present, lock, excluded, selected, decision만 남긴다. 쪽·스크롤·정지 줄도 유지한다.

## 판독 보완과 정답 확인

QA-JP 프레임은 1920×1080 게임 원본을 Lanczos로 1280×720으로 정규화했다. 27장 중 판매 화면 12장을 `docs/reference-images/Jp/fp-sell/qa-beta5/`에 보관했다. 기존 KR/JP와 46~48도 재사용한다. 수동 라벨 근거는 `docs/reference-images/Jp/fp-sell/beta6-annotations.json`이다.

QA-JP 02:35:12 프레임의 파란 디버그 표시는 기존 두 프레임 띠를 동시에 가린다. 실제 왼쪽 프레임만 읽는 독립된 중간 띠를 더해 이미 선택된 c2를 false로 읽지 않도록 했다. 종류/카드 그림 판독은 추가하지 않았다. c3의 잠금 문구는 기존 템플릿 0.788점으로 임계값 0.8 아래였다. `extract-template-asset` 스킬의 검색 Region 검증/추출 절차로 잠금 문구 변형을 추가했다. 임계값은 0.8 그대로다. 새 라벨의 JP37 둘째 완전 행 c1은 화면상 잠금 없는 포우이므로 최초 신규 라벨 오류를 정정했다. 기존 안전 사례 기대값을 완화하지 않았다.

종료 대화상자 프레임은 `(366,270,548,180)` 마스크를 사용해 읽는 영역이 가림 영역에 닿으면 실패시킨다. 가려진 칸은 추정하지 않는다. 빈 슬롯 원본이 없어서 46 화면의 실제 행간 배경으로 만든 **합성 빈 슬롯 4개**를 명시적으로 추가했다. 실제 게임 조작을 통한 빈 슬롯 검증은 아니다.

## 출시 기준

완전히 보이는 신규 격자 칸 **423개**를 수동 라벨링했다. 아래 수는 스크린샷별 칸 사례이며 스크롤로 재등장하는 같은 카드를 포함한다.

| 기준 | 완전 노출 신규 칸 평가 | 전체 cell 회귀 포함 |
|---|---:|---:|
| 후보 누락 | **0 / 200** | **0 / 333** |
| 잠금·제외·기선택 오선택 | **0 / 223** | **0 / 281** |
| 빈 슬롯을 카드로 오인 | **0 / 4 (합성)** | 동일 |

cell 사례 합계 618개. 별도 프레임 선택 상태 52개, 7열/탭 10개, 가득 참 경고 3개, 전체 행/아래 7열 2개, 실제 배너 제목/FP 버튼 11개를 더해 fpSell 696개다. 새 칸 사례는 production `readPage`의 행·열 포함과 실제 계산된 탭 Region의 `readCard`를 함께 검사한다. 마스크 화면은 가려지지 않은 전체 칸 또는 기존 노출 선택 띠만 검사한다.

실제 배너 검사는 KR15·25·26·27, JP34~36·41을 사용한다. KR27은 설계 표의 배너 표기와 달리 실제로는 판매 화면이며 소환 불가를 확인한다. KR25와 JP34의 성정석 화면도 제목이 자기 자신과 같더라도 FP 버튼이 없으면 거부한다. KR26↔15 같은 제목과 다른 제목의 거부도 검사한다. 점 관련 검사는 없다.

## 로컬 검사

JDK `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`. SDK는 `ANDROID_HOME` 환경변수로만 설정했다.

```text
./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test :app:compileCiKotlin
BUILD SUCCESSFUL
```

app 177 + prefs 24 + scripts 252 = **453개**, 실패·오류·skip 0. libautomata는 테스트 소스 없음. D-29 전체 조합, 시험 모드 강제, 잠금 불명확 집계, 겹침 재탭 없음, 누른 뒤 확인 없음, 행 없음 재시도, 단계별 정지, 청록의 경계 null, 행 위상/빈 슬롯/연속 테두리, 정확한 스크롤, 배너 제자리/왼쪽 10회/열 번째 성공/FP 버튼 없음/제목 해제를 검사한다.

## 기기 QA

```text
py -3 tools/device-qa/run.py --build --serial 127.0.0.1:5555
```

최종 커밋 전체 실행 `build\device-qa\20261019-042644/summary.md`: **924/924 통과**, 실패 0, 기존 skip 11.

| suite | beta.5 | beta.6 | 실패 | skip |
|---|---:|---:|---:|---:|
| support | 185 | 185 | 0 | 3 |
| bond | 20 | 20 | 0 | 0 |
| questItems | 5 | 5 | 0 | 8 |
| noblePhantasm | 18 | 18 | 0 | 0 |
| fpSell | 262 | 696 | 0 | 0 |

fpSell 기존 262개 중 점 3개와 NEW! 단독 음성 배너 1개를 폐기하고 **258개를 새 규칙으로 재라벨**, 신규 **438개**를 추가했다(완전 노출 칸 423 + 합성 빈 슬롯 4 + 제목/FP 버튼 11). 선택 상태·잠금·제외·레이아웃·경고의 기존 안전 기대값을 유지했다. 다른 네 suite의 사례 파일·기대값은 수정하지 않았다.

최종 커밋의 첫 전체 QA(`20261019-041805`)는 support·bond 통과 후 ADB 읽기 명령의 서버 응답 오류로 중단됐다. 서버 제어 명령 없이 기존 SDK adb 리스너의 프로세스 경로와 모델 조회 성공을 확인한 뒤 전체를 다시 실행했다. 위의 최종 실행 `20261019-042644`는 다섯 suite 모두 통과했다.

## 삭제 / 유지

삭제 전에 기존 사용처를 검색하고 참조·레시피를 정리한 후 각 자산 파일명과 Images 심벌을 다시 검색해 남은 사용처가 없는 것을 확인했다. 도구 로그는 `build/orchestration/beta6-cleanup-before.json`, `beta6-symbol-cleanup-audit.json`, `beta6-deleted-assets.json`에 있다.

- 삭제: `fpSellServant1~4`, `fpSellExp4/5`, `fpSellNewOnly`의 prefs/UI/en·ko·ja 문자열. 남은 저장값은 읽지 않는다.
- 삭제: `Category/Kind/Tier`, 종류·별·NEW! 및 등급 점수/슬롯/재판독, 라벨/NEW 기반 행·7열 판독, 종류별 합계/판정 로그.
- 삭제: `awaitSelected`, `SELECTION_POLLS/POLL`, `SelectionNotConfirmed`와 대응 문자열/매핑. 화면·격자 분실은 `SelectionScreenLost`와 새 전용 메시지로 구분한다.
- 삭제: `Dots`, `steps`, `rowMajor`, `readDots`, 점 기억/판독, `dots`와 오른쪽 화살표 좌표, 관련 테스트/사례/추출 레시피.
- 삭제: 아래 **44개 자산**, 해당 Images 항목 **43개**, 추출 레시피와 이전 manifest 항목.
- 유지: 자물쇠·잠금 문구·선택 불가·Grand·PARTY 템플릿과 D-19 프레임 색은 안전 제외/기선택 판독에 필요하다.
- 유지: `fp_sell_word_servant`, 탭·제목·버튼·메뉴·판매 완료 자산은 진입 경고 및 화면/판매 흐름 검증용이며 칸 종류 판독에 쓰지 않는다.
- 유지: D-23 정확한 스크롤 primitive와 겹침 테스트, 기존 원본 reference 이미지, 과거 설계/릴리스 기록은 동작 유지와 추적을 위해 남겼다. 두 추출 도구에는 남은 화면 흐름/안전 자산 레시피만 있다.

삭제 자산:

```text
app\src\main\assets\En\fp_sell_dot_off.png
app\src\main\assets\En\fp_sell_dot_off_a.png
app\src\main\assets\En\fp_sell_dot_off_b.png
app\src\main\assets\En\fp_sell_dot_off_c.png
app\src\main\assets\En\fp_sell_dot_off_d.png
app\src\main\assets\En\fp_sell_dot_off_light.png
app\src\main\assets\En\fp_sell_dot_on.png
app\src\main\assets\En\fp_sell_exp.png
app\src\main\assets\En\fp_sell_exp_dark.png
app\src\main\assets\En\fp_sell_new_bright.png
app\src\main\assets\En\fp_sell_new_dark.png
app\src\main\assets\En\fp_sell_new_orange.png
app\src\main\assets\En\fp_sell_new_orange_right.png
app\src\main\assets\En\fp_sell_new_selected.png
app\src\main\assets\En\fp_sell_new_selected_right.png
app\src\main\assets\En\fp_sell_rarity1.png
app\src\main\assets\En\fp_sell_rarity2.png
app\src\main\assets\En\fp_sell_rarity2_r2c3.png
app\src\main\assets\En\fp_sell_rarity2_r2c6.png
app\src\main\assets\En\fp_sell_rarity3.png
app\src\main\assets\En\fp_sell_rarity3_r0c5.png
app\src\main\assets\En\fp_sell_rarity3_r0c6.png
app\src\main\assets\En\fp_sell_rarity3_r1c0.png
app\src\main\assets\En\fp_sell_rarity3_r1c1.png
app\src\main\assets\En\fp_sell_rarity3_r1c2.png
app\src\main\assets\En\fp_sell_rarity3_r1c3.png
app\src\main\assets\En\fp_sell_rarity3_r1c4.png
app\src\main\assets\En\fp_sell_rarity3_r1c5.png
app\src\main\assets\En\fp_sell_rarity3_r1c6.png
app\src\main\assets\En\fp_sell_rarity4.png
app\src\main\assets\En\fp_sell_rarity4_r0c1.png
app\src\main\assets\En\fp_sell_rarity_exp4.png
app\src\main\assets\En\fp_sell_rarity_exp4_dark.png
app\src\main\assets\En\fp_sell_rarity_exp5.png
app\src\main\assets\En\fp_sell_rarity_exp5_dark.png
app\src\main\assets\En\fp_sell_rarity_star.png
app\src\main\assets\En\fp_sell_selected.png
app\src\main\assets\En\fp_sell_selected_lower.png
app\src\main\assets\En\fp_sell_servant.png
app\src\main\assets\En\fp_sell_servant_selected.png
app\src\main\assets\En\fp_sell_servant_selected_bronze.png
app\src\main\assets\En\fp_sell_star.png
app\src\main\assets\En\fp_sell_star_exp.png
app\src\main\assets\Jp\fp_sell_exp.png
```

## 배포

- 코드 `6cc445e9a1754ddf23bad02d721f00868d2dda33`: puruuun 작성자·커미터, main 푸시, AI trailer 없음.
- [Main CI #16](https://github.com/puruuun/FGA-Modified/actions/runs/39158057087): **success**. 성공과 출시 기준 통과를 확인한 뒤 beta APK를 빌드했다.
- APK: `C:\Users\rl\Documents\Codex\FGA-Modified-builds\FGA-beta-1.4.0-beta.6-arm.apk`. 같은 위치의 `.sha256` 파일 포함.
- SHA-256: `2637b099d010fa728ae7a4e407533094e5eef444c27200705f9847b21f95a7b1`.
- aapt: `io.github.fate_grand_automata.beta` / `1004006` / `1.4.0-beta.6`, `native-code: 'arm64-v8a' 'armeabi-v7a'`.
- SDK adb `-s 127.0.0.1:5555 install --no-streaming -r`: **Success**. 모델 `PIXEL 2 XL` 확인.
- 설치 전 `1.4.0-beta.5`, 설치 후 `1.4.0-beta.6` / `1004006 minSdk=24 targetSdk=36`.
- firstInstallTime `2026-10-15 23:24:59` **유지**. lastUpdateTime `2026-10-19 04:31:57`.
- GitHub Release 게시 없음. 검증 보고서 추가 커밋은 문서 전용이므로 Main CI paths-ignore 대상이다.

## 소유자 확인

[위키](../../../wiki/scripts/FP-Auto-Sell-KR.md)의 굵은 필터 경고를 확인한다. 게임 판매 필터를 먼저 맞추고 **자동 판매 + 시험 모드 켬**으로 FP 소환을 시작한다. 판매 화면에서 선택 대상과 합계, 잠금/금지 칸 보호를 확인한다.

게임 입력·게임 시작/종료·FGA 스크립트 실행·실전 QA는 수행하지 않았다. 기기 QA는 저장된 화면만 읽었으며 실제 스크롤/판매 흐름은 소유자가 시험 모드에서 확인해야 한다. HA29GCWH 및 ADB 서버 제어 명령은 사용하지 않았다.
