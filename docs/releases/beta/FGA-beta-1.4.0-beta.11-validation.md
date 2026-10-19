# FGA 1.4.0-beta.11 검증 보고서

상태: **자동 검증·APK 확인·BlueStacks 업데이트 설치 완료. 실제 게임 확인은 미실행.**
버전 `1.4.0-beta.11` / `1004011`, 패키지 `io.github.fate_grand_automata.beta`.

## 수정 범위

0007 §19 D-44·D-45(소유자 정정)·D-47 구현. D-46은 철회되어 구현·문자열·상태 플래그가 남아 있지 않다.

- KR 52번 확인창 아래 `confirmation` 워터마크를 720p 정규화 프레임에서 잘라 공통 En 템플릿으로 추가했다. 제목 표시줄이 없는 1920×1080 원본을 전체 게임 영역으로 보고 1280×720으로 정규화했다. 자르기 `(540,600,208,12)`, 중앙 기준 검색 영역 1440p `(-320,1170,640,100)`.
- 실제 판매 뒤 재소환은 새 프레임에서 가득 참 경고를 우선 확인한다. 워터마크가 확인될 때만 원래 `fp.okClick`을 한 번 누른다. 약 2초 찾기 내에 둘 다 없으면 `ResummonConfirmFailed`의 영어·한국어 안내와 선택 수로 정지한다. 베타 시험 모드의 두 번째 가득 참 대기 흐름은 유지한다.
- D-45: 2초는 판매 버튼을 **누른 뒤** 추가된다. 변환 뒤 기존 1초에 0.3초를 추가한다. 두 값은 `FpSellTiming`에 있다. 칸 선택·스크롤·원래 FP 소환 루프의 속도는 바꾸지 않았다.
- 다시 가득 차면 기존 흐름대로 다시 판매하고 선택 수 0이면 `InventoryFullNothingToSell`로 정지한다. `InventoryFullAfterSale`는 없다.
- D-47: 베타 시험 모드 스위치·기본 ON 유지. 앱 BuildConfig의 ci/release만 stable 정책으로 주입한다. 정규판은 시험 스위치·시험 설명을 숨기고 저장값을 읽지 않으며 `dryRun=false`이다. 실제 판매 게이트에 막히면 판매 버튼을 누르기 전에 정지하고 시험 모드로 대체하지 않는다. 빨간 큰 굵은 필터 경고는 두 빌드 모두 표시한다.

## 테스트

JDK 21 및 ANDROID_HOME 환경변수만 사용.

```text
./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test :app:compileCiKotlin
BUILD SUCCESSFUL
```

**507개 통과**(app 178 + prefs 24 + scripts 305), 실패·오류 0. 종전 499개 대비 8개 증가.
새 검사는 워터마크 없을 때 OK 금지, 확인 시 정확히 한 번, 가득 참 우선 및 반복 판매, 판매/변환 뒤 대기 순서, stable 차단 시 무제스처, beta/stable 옵션 해석과 앱 빌드 정책 주입을 포함한다. 기존 시험 모드에서 변환을 누르지 않는 테스트와 선택 타이밍 테스트도 통과했다.

`mkdocs build --strict` 통과. 기존 영어 문서의 INFO 수준 앵커 안내는 그대로이며 오류는 없다.

## 전체 기기 QA

명령: `py -3 tools/device-qa/run.py --build --serial 127.0.0.1:5555`.
결과: `build\device-qa\20261019-155328`. 고정 화면 오프라인 판독만 수행했고 게임을 조작하지 않았다.

| suite | 통과 | 기존 skip |
|---|---:|---:|
| support | 185 | 3 |
| bond | 20 | 0 |
| questItems | 5 | 8 |
| noblePhantasm | 18 | 0 |
| fpSell | 739 | 0 |
| 합계 | **967** | **11** |

기존 964건 모두 유지·통과, 새 3건 통과, 실패 0. fpSell 기존 736건의 내용·기대값을 수정하지 않았다.
추가: KR52 FP 사용 확인 워터마크 양성, KR 배너 음성, JP 판매 최종 확인창 음성.
오프라인 OpenCV 점수는 각각 **0.999999 / 0.127344 / 0.316958**, 판정 기준 0.9.
JP 입력 자료 전체를 검토했으나 **JP FP 사용 확인창 프레임은 없어서 JP 실제 양성 사례는 추가하지 못했다.** JP 판매 확인창은 해당 양성 검증을 대신하지 않는다.

## 커밋과 CI

- 제품 코드 `226f4ae8f51bb189835aa9dea45eaae84b182cf1`.
- [Main CI 성공](https://github.com/puruuun/FGA-Modified/actions/runs/39212426060) (`39212426060`).

## APK·설치

- APK `C:\Users\rl\Documents\Codex\FGA-Modified-builds\FGA-beta-1.4.0-beta.11-arm.apk` 및 같은 경로의 `.sha256`.
- SHA-256 `07e1378057d9ea09fb9ab0bfddbb789bc13dd988104e8fed96cb9f9a07102b99`.
- aapt: `io.github.fate_grand_automata.beta`, `1004011`, `1.4.0-beta.11`, ARM `arm64-v8a` + `armeabi-v7a`.
- apksigner 검증 성공. 저장소 공개 debug 키 인증서 SHA-256 `5acce47e71373dcfe3a6f6400ed3b3f687bd454aab91c7562c8327a3a083ac9d`와 일치.
- BlueStacks `127.0.0.1:5555`, 모델 `PIXEL 2 XL` 확인 후 `install --no-streaming -r`: **Success**.
- 설치 후 `versionName=1.4.0-beta.11`, `versionCode=1004011 minSdk=24 targetSdk=36`.
- `firstInstallTime=2026-10-15 23:24:59` 보존. 다른 기기·서버 제어·게임 조작 없음.

## 소유자 확인 범위

소유자 제공 결과: beta.10 JP·KR 시험 모드 전체 흐름 확인, KR 첫 실판매 92칸과 배너 복귀 확인. beta.11의 새 확인창 처리는 **실제 게임 미검증**이다. 게임 확인은 소유자가 별도로 진행한다. 태그·GitHub Release는 만들지 않았다.
