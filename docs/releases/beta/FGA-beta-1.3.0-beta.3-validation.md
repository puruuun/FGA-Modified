# FGA 1.3.0-beta.3 검증 보고서

상태: 구현·전체 단위 테스트·Main CI·전체 기기 QA 통과, BlueStacks 업데이트 설치 완료.

- 설계: `docs/design/0006-ascension-np-select.md` §9, D-13~D-17.
- 베타: `1.3.0-beta.3` / `1003003`. 정규판 `1.2.1` / `3083` 유지.
- JDK: `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`.
- SDK: `ANDROID_HOME=C:\Users\rl\AppData\Local\Android\Sdk` 환경변수 사용. `local.properties` 생성 없음.

## 구현

- D-13: 이름 지정 완료 시점의 `IPreferences.gameServer`로 재림 이미지 이름에 서버 접두어 추가. JP/KR 및 이미 접두어가 있는 이름 테스트. 선호 목록은 저장된 이름 그대로 표시. 서버별 매칭 필터 없음.
- D-14: 재림 이미지를 `np/<이름>/image-N.png`에 추가. 기존 파일을 덮어쓰지 않으며, 같은 이름의 두 칸도 같은 항목에 저장. 명시적 하위 파일 이름도 새 번호로 저장. 서번트·예장·친구 저장 방식 유지.
- D-15: en/ko/ja 설정 안내는 첫 문장만 유지. `support_img_namer_np_hint` 문자열과 이름 지정 화면의 안내 뷰 삭제.
- D-16~17: 일반 아이콘 위치 실측, 글자 시작 크롭으로 전환. 가로 배율 검색 없음. 정상 크롭 하단이 검색 띠를 벗어나는 일반 행을 발견하여 검색 띠 하단에 메이커의 여백 5px(720p)을 포함하고 회귀 테스트 추가.
- 위키: 접두어·추가 저장·글자 시작 크롭 설명 반영. 레이아웃별 별도 저장 안내는 추가하지 않음.

## NORMAL_ICON_X 측정

창 제목 표시줄과 오른쪽 도구 모음을 제외한 게임 영역을 먼저 추출했다. 실제 파일 크기는 `4-grand-olga.webp`가 1971×1131, 7~13번은 1988×1140이었다.

| 원본 | 게임 영역 (x, y, 폭, 높이) | 비교 해상도 |
|---|---|---|
| 4-grand-olga.webp | (1, 49, 1920, 1080) | 1280×720 |
| 7~13.webp | (1, 49, 1937, 1089) | 1280×720 |

일반 행인 7·8·10·11번의 각 두 행, 총 8행에서 카드 아이콘 왼쪽은 모두 **x=336(720p)**이었다. 행 원점은 x=38이므로 상대 좌표는 `(336−38)×2 = 596(1440p)`. 기존 추정값 528을 **NORMAL_ICON_X=596**으로 교체했다.

근거: `docs/reference-images/Jp/np-20261018-r3/manifest.json`(원본 SHA-256, 게임 영역, 행·크롭·마스크 좌표), `layout-measurements.json`, 비교 크롭 PNG.

## D-17 선행 비교

같은 서번트·재림의 쌍: `4-grand-olga.webp`의 올가마리(Lv.5)와 `8.webp`의 올가마리(Lv.2). 크롭 변경 전에 720p 정규화·그레이스케일·`TM_CCOEFF_NORMED`로 비교했다. 글자 폭은 요소별 ROI에서 밝기 >160인 픽셀의 경계로 측정했다. 카드 폭은 외곽 프레임 기준이다.

| 요소 (720p px) | Grand | 일반 |
|---|---:|---:|
| サーヴァント 라벨 | 69 | 93 |
| 서번트 이름 글자열 | 311 | 311 |
| 宝具 라벨 | 24 | 32 |
| 카드 아이콘 | 21 | 21 |
| 보구 이름 글자열 | 220 | 221 |
| 아이콘 끝과 첫 밝은 글자 사이 빈 픽셀 | 11 | 11 |

최종 좌표 반올림을 반영한 결과(Grand 폭: 아이콘 크롭 260px, 글자 크롭 228px / 일반: 259px, 227px):

| 크롭 | Grand → 일반 | 일반 → Grand | 자기 화면 | 다른 서번트 최고값 |
|---|---:|---:|---:|---:|
| (가) 아이콘 시작 | 0.931911 | 0.931873 | 1.0 | 0.295113 |
| (나) 글자 시작 | 0.969149 | 0.969160 | 0.999999~1.0 | 0.232599 |

다른 서번트는 같은 두 화면의 솔로몬과 레이디 아발론 행이다. 이름·보구 글자의 폭 차이가 ±1px이고 (나)의 교차 유사도가 0.95 이상이므로 D-17에 따라 글자 시작 크롭을 사용한다. 이 쌍에는 **한 장으로 충분**하며 별도 저장 안내는 필요 없다. 이전 beta.1·2의 아이콘 포함 이미지는 넓은 검색 띠로 계속 찾는다.

1440p 좌표를 720p로 변환할 때 위치와 크기를 각각 반올림하여 Grand 크롭 폭이 227이 아닌 **228px**이 된다. 새 Grand 사례의 폭만 +1px로 직접 보정했으며, 글자 크기나 가로 배율은 변경하지 않았다. 최종 교차 점수도 0.95 이상이다.

## 테스트와 기기 QA

- 필수 명령: `./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test :app:compileCiKotlin` 통과.
- 단위 테스트: app 177, prefs 24, scripts 227 — 총 428개, 실패·오류·skip 0. libautomata는 테스트 소스 없음.
- 추가 단위 테스트: JP/KR/BetterFGO 접두어, 기존 접두어 보존, 다른 이미지 종류 유지, 동일 항목에 반복 추가, 명시적 하위 이름 추가, 검색 띠 하단 여백 회귀.
- 커밋 전 전체 기기 QA: `build/device-qa/20261018-200034/`.
- 설치 후 최종 전체 기기 QA: `build/device-qa/20261018-201032/`. 두 실행의 결과는 동일하다.
- 명령: `py -3 tools/device-qa/run.py --build --serial emulator-5554 --suite support,bond,questItems,noblePhantasm`. 모든 adb 명령은 지정된 SDK adb와 해당 serial을 사용한다.

| 스위트 | 통과 | 실패 | skip |
|---|---:|---:|---:|
| support | 185 | 0 | 3 |
| bond | 20 | 0 | 0 |
| questItems | 5 | 0 | 8 |
| noblePhantasm | 18 | 0 | 0 |

기존 noblePhantasm 9개와 새 9개가 모두 통과했다. 새 사례는 일반 행 8개와 올가마리 Grand 행 1개이며, 생산 코드의 Lv. 탐지·메이커 크롭 좌표도 함께 검증한다. 올가마리 텍스트 이미지는 두 레이아웃에서 서로 통과하고 다른 서번트에서는 탈락한다. 기존 beta.1 NP-only·beta.2 아이콘 포함 블록 사례도 모두 통과했다. 임계값 0.85와 모든 기대 매칭값을 유지했다.

최초 실행에서는 새 사례 3건이 실패했다(Grand 반올림 폭 1px, 일반 행 하단 여백 2건). 원인에 맞춰 크롭 라벨·마스크를 보정하고 검색 띠 하단 여백을 포함한 후 전체 스위트를 재실행했다. 실패를 skip으로 전환하거나 기대값을 바꾸지 않았다.

## 전달 및 기존 사례 보존

- 구현 커밋: `5e62059a68cf5720fa6c6e51a49cbe782c25df5e` (main, author·committer 모두 `puruuun <188972188+puruuun@users.noreply.github.com>`). AI 공동 작성자나 생성 문구 없음.
- Main CI: [run 39121692345](https://github.com/puruuun/FGA-Modified/actions/runs/39121692345) **success**. `:app:assembleBeta`는 CI 성공 확인 후 실행했다.
- APK: `C:\Users\rl\Documents\Codex\FGA-Modified-builds\FGA-beta-1.3.0-beta.3-arm.apk`.
- SHA-256: `8a2c5dfc01221b48879eaf48f7f6a3a2c94dd25f934fc76736258aae049131d4`.
- 체크섬 파일: `C:\Users\rl\Documents\Codex\FGA-Modified-builds\FGA-beta-1.3.0-beta.3-arm.apk.sha256` (APK와 같은 빌드 폴더).
- aapt: `package: name='io.github.fate_grand_automata.beta' versionCode='1003003' versionName='1.3.0-beta.3' platformBuildVersionName='17' platformBuildVersionCode='37' compileSdkVersion='37' compileSdkVersionCodename='17'`.
- ABI: `native-code: 'arm64-v8a' 'armeabi-v7a'`.
- 설치: 지정된 SDK adb로 `-s emulator-5554 install -r` 업데이트 **Success**, 제거 없음.
- 설치 후 dumpsys: `versionCode=1003003 minSdk=24 targetSdk=36; versionName=1.3.0-beta.3`.

승인 검토가 기존 사례 파일을 잘라내고 재생성하는 명령을 거부한 뒤 해당 방법은 재실행하지 않았다. 소유자가 승인한 방식으로 새 9개 사례의 `crop720`(Grand 폭 +1px)과 `masked720`만 직접 수정하고, 새 픽스처 PNG·manifest를 같은 좌표에 맞췄다. 기존 9개 사례의 본문·기대값은 수정 전후 원문 바이트가 동일하며, 커밋 전후 Git blob에서도 정확히 일치함을 검사했다. case 파일의 `git diff`를 커밋 전에 표시했다. 변경은 새 imports 9개·새 사례 9개의 추가 및 필요한 구분 쉼표뿐이다. 검토용 diff는 `build/orchestration/beta3-case-file.diff`에 남겼다.

위키에는 서버 접두어와 같은 이름에 이미지가 추가되는 동작을 반영했다. D-17 판정상 한 장으로 양쪽 레이아웃을 인식하므로 별도 저장 안내 문장은 넣지 않았다.

## 검증 범위

지정된 SDK adb와 `-s emulator-5554`만 사용했다. 읽기 전용 1920×1080 screencap 및 오프라인 판독 instrumentation으로 검증했다. 게임 입력·게임 실행/종료·FGA 스크립트 실행·태블릿 접근은 하지 않았다. 실제 게임에서 이미지 메이커와 선호 선택을 실행하는 확인은 소유자가 직접 해야 한다. NA·CN·TW 화면과 태블릿 해상도는 이번 검증 범위에 포함하지 않았다.

## GitHub 사전 출시 (2026-10-18)

- [v1.3.0-beta.3](https://github.com/puruuun/FGA-Modified/releases/tag/v1.3.0-beta.3): 태그 → `5e62059a`, pre-release, latest 아님(정규판 latest는 v1.2.1 유지).
- 첨부: `FGA-beta-1.3.0-beta.3-arm.apk`(66,065,444 바이트, SHA-256 `8a2c5dfc…49131d4`), `.sha256`.
- 소유자 요청으로 설계자(Claude)가 API로 게시했습니다.
