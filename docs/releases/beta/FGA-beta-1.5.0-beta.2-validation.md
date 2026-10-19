# FGA 1.5.0-beta.2 검증 보고서

상태: **자동 검증·APK 확인·BlueStacks 업데이트 설치 완료. 실제 게임 강화 미실행.**
베타 `1.5.0-beta.2` / `1005002`, 패키지 `io.github.fate_grand_automata.beta`. 정규판 **1.4.1 / 3085 유지**.

## 범위와 변경

0008 §9~§11 기반의 beta.1 위에 소유자가 제공한 확인창 10장(25~34)을 추가했다. 각 화면은 제목 표시줄·사이드바를 제외한 **(0,48)~(1950,1145)** 게임 영역을 잘라 Lanczos **1280×720**으로 정규화했다. 기존 01~24와 같은 처리다. 재현 도구 `normalize_skill_level.py`는 확장된 `번호-서버-레벨` 파일명도 처리한다.

- QA에 대화창 판별 10건과 current/next 판독 10건을 추가했다. 기존 63건의 내용·기대값은 수정하지 않았다.
- 기존 숫자 OCR 그대로 검사했을 때 **80/83 통과**, `KR-29`·`JP-31` current 9와 `JP-32` next 9를 읽지 못했다. 두 자리 next 10은 기존 OCR로 정상 판독했다.
- 기본 OCR 프로필 20개는 유지했다. 확신 있는 **어떤 비어 있지 않은 판독도 없을 때만** 숫자 9 템플릿 보완을 사용한다. 템플릿 점수 **≥0.96**, 0~8 경쟁 숫자보다 **≥0.10** 높아야 9로 인정한다. 필요 템플릿이 하나라도 없으면 보완하지 않는다. 기존의 모순·범위 밖·단 하나의 확신 판독을 템플릿으로 덮어쓰지 않는다.
- current와 next를 **모두** 읽고 `next == current + 1`일 때만 강화 결정을 허용하도록 강화했다. 이전의 next 미독 허용은 제거했다. 현재 0·10·미독·모순·목표 이상·진전 없음이면 결정하지 않으며 잠긴 칸의 해방 보호는 그대로다.
- 공유 Tesseract 구현·일반 OCR API는 최종 변경하지 않았다. 단일 문자 OCR 실험은 효과가 없어 제거했다.

## 템플릿

추가 공통 En 자산 **12개**: `skill_level_digit_0.png`~`skill_level_digit_9.png`, `skill_level_digit_9_kr.png`, `skill_level_digit_9_next.png`.
0~8은 9의 경쟁 후보이며 이 보완 경로가 반환하는 숫자는 9뿐이다. 9는 JP 흰색·KR 흰색·JP 노란색 세 가지를 비교한다. 소유자 정규화 화면 JP04·18·25·31·32, KR16·26·27·28·29에서 직접 잘랐고 다른 포크 자료는 쓰지 않았다. 위치·크기는 `tools/device-qa/cut_skill_level_templates.py`에 있다.

기존 21개와 신규 12개, **33개 레시피의 RGB 픽셀 재현 일치**를 확인했다. 추출 스킬의 영역·녹색 템플릿 검증 이미지를 확인했다. 모든 14장 current/next 28영역을 오프라인 비교했을 때 새 9 보완 기준은 실패했던 세 영역만 추가 허용했다. 해당 9의 최고 점수는 모두 1.000, 모든 숫자의 전체 NCC를 비교한 최소 점수 차이는 0.248이었다. 기존 OCR이 이미 읽은 나머지 숫자는 그대로 유지된다.

## 최종 기기 current / next 결과와 시간

최종 전체 QA 결과에서 추출했다. **14/14 일치**, 확인창 판별도 신규 10/10 통과.
`OCR ms`는 기존 QA 방식대로 **실제 판독 + 진단용 raw 재판독** 총 80회 OCR 누적 시간이다. 게임에서 한 번 읽는 지연과 같지 않다. `전체 ms`에는 화면 처리·템플릿 비교·raw 진단 비용도 포함한다.

| 프레임 | current | next | OCR ms | 전체 ms | 방식 |
|---|---:|---:|---:|---:|---|
| JP-04 | 4 | 5 | 560 | 613 | 기본 OCR |
| KR-16 | 3 | 4 | 366 | 416 | 기본 OCR |
| KR-17 | 5 | 6 | 383 | 433 | 기본 OCR |
| JP-18 | 1 | 2 | 278 | 322 | 기본 OCR |
| JP-25 | 5 | 6 | 349 | 398 | 기본 OCR |
| KR-26 | 6 | 7 | 406 | 452 | 기본 OCR |
| KR-27 | 8 | 9 | 490 | 536 | 기본 OCR |
| KR-28 | 7 | 8 | 335 | 379 | 기본 OCR |
| KR-29 | 9 | 10 | 455 | 505 | 9 템플릿 보완 |
| KR-30 | 5 | 6 | 359 | 404 | 기본 OCR |
| JP-31 | 9 | 10 | 456 | 506 | 9 템플릿 보완 |
| JP-32 | 8 | 9 | 461 | 513 | 9 템플릿 보완 |
| JP-33 | 7 | 8 | 335 | 381 | 기본 OCR |
| JP-34 | 6 | 7 | 405 | 455 | 기본 OCR |

## 단위 테스트와 문서

JDK 21, ANDROID_HOME 환경변수만 사용.

```text
./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test :app:compileCiKotlin
BUILD SUCCESSFUL
```

**533개 통과**(app 180 + prefs 24 + scripts 329), 실패·오류·skip 0. beta.1의 528개 대비 5개 추가:

- 9→10 / 목표 10: 정확히 한 번 결정, 10 도달.
- 9→10 / 목표 9: 결정 0회, 취소 1회.
- current 9 / next 미독: 결정 0회, 취소.
- 확신 있는 무효·모순·잘못된 형식의 OCR은 템플릿으로 덮어쓰지 않음.
- 9 템플릿의 최소 점수·경쟁 후보 차이·유효 점수 조건.

기존 목표 초과 금지 100조합, 0·잠김·미독·진전 없음 보호도 통과했다. libautomata 태스크 성공(테스트 소스 없음).
`mkdocs build --strict` 통과. 사용자 위키의 검증 범위와 미독 next 취소 안내를 갱신했다.

## 전체 기기 QA

명령 `py -3 tools/device-qa/run.py --build --serial 127.0.0.1:5555`.
결과 `build\device-qa\20261019-223625`. 고정 화면 오프라인 검사이며 실제 게임을 조작하지 않았다.

| suite | 통과 | 실패 | 기존 skip |
|---|---:|---:|---:|
| support | 185 | 0 | 3 |
| bond | 20 | 0 | 0 |
| questItems | 5 | 0 | 8 |
| noblePhantasm | 18 | 0 | 0 |
| fpSell | 736 | 0 | 0 |
| skillLevel | **83** | **0** | 0 |
| 합계 | **1047** | **0** | **11** |

기존 1027건 모두 통과 + 신규 20건 통과. 기존 사례와 기대값은 변경하지 않았다. 처음 ADB 연결이 끊겨 검사가 중단되어, 소유자가 허용한 `adb -s 127.0.0.1:5555 connect 127.0.0.1:5555` 후 모델을 확인하고 재실행했다. 명시적인 kill-server/start-server/disconnect 명령은 쓰지 않았다.

## 커밋과 CI

- 제품·버전 `3552cb72ce76bbd89a392f2feb80efc14664bc01`.
- [Main CI 성공](https://github.com/puruuun/FGA-Modified/actions/runs/39261520136). 최종 검증 문서 전용 후속 커밋은 Main CI paths-ignore 대상이다.
- 태그·GitHub Release는 만들지 않았다.

## APK와 설치

- APK `C:\Users\rl\Documents\Codex\FGA-Modified-builds\FGA-beta-1.5.0-beta.2-arm.apk`, 같은 경로 `.apk.sha256`.
- SHA-256 **`2a9b8caa6015d1212704b7395dcb4cb85b27aae22fcd9f743328f92fd8eb0bb5`**. 원본·보관 APK·체크섬 파일 일치 확인.
- aapt: `io.github.fate_grand_automata.beta`, `1005002`, `1.5.0-beta.2`, `FGA(beta)`, `arm64-v8a` + `armeabi-v7a`.
- apksigner 검증 성공, 저장소 공개 debug 키 인증서 SHA-256 `5acce47e71373dcfe3a6f6400ed3b3f687bd454aab91c7562c8327a3a083ac9d`와 일치. APK 안의 신규 숫자 자산 12개 확인.
- BlueStacks **127.0.0.1:5555 / PIXEL 2 XL**에 베타만 `install --no-streaming -r`: **Success**.
- 설치 후 `versionName=1.5.0-beta.2`, `versionCode=1005002 minSdk=24 targetSdk=36`.
- 최초 설치 시간 **2026-10-15 23:24:59** 보존. 지정 외 기기·게임 조작·태그·Release 생성 없음.

## 남은 범위

- 이번에 제공된 확인창에서 current는 1·3·4·5·6·7·8·9, next는 2·4·5·6·7·8·9·10을 확인했다. **current 2 / next 3 확인창은 아직 없다.**
- 실제 게임 강화·연출 스킵·와이드 화면 좌표는 이번에도 검증하지 않았다. 해당 beta.1 한계와 KR 최대 버튼·해방 확인창 실제 제목 자료 부족은 남는다.
- 다른 글꼴·게임 UI 변화에서 템플릿 기준에 못 미치거나 OCR이 모호하면 결정을 누르지 않고 취소한다.
