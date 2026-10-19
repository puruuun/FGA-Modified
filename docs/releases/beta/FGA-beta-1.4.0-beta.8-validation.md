# FGA 1.4.0-beta.8 검증 보고서

0007 §16 D-37·D-38 구현, 전체 단위 테스트·오프라인 기기 QA·Main CI 통과. APK 검증 및 BlueStacks 업데이트 설치 완료.
버전 `1.4.0-beta.8` / `1004008`, 패키지 `io.github.fate_grand_automata.beta`. 안정판 `1.2.1` / `3083` 유지.

## 원인과 변경

beta.7 QA-JP는 407칸 일치, 잠금 보호, 결정→왼쪽 취소→판매 없음→닫기→공방까지 통과했다. 공방 제목이 먼저 보이는 동안 MENU를 한 번만 찾고 실패해 종료했다. 이번 구현에서는 템플릿 기준 0.9를 낮추지 않고 화면 전환 뒤 버튼을 새 프레임에서 최대 2초 기다린다. 캡처·매칭 시간도 시간 예산에 포함한다.

- 공방 MENU, 소환 메뉴 아이콘, 왼쪽 화살표, FP 재소환과 판매·결정·취소·변환·닫기에 공통 단일 탭 대기를 적용했다. 판매 확인창·완료 화면의 기존 20초 경로도 2초로 맞췄다. 자동 판매가 꺼진 기존 소환 흐름은 유지했다.
- MENU 뒤 소환 메뉴 아이콘, 소환 메뉴 뒤 소환 화면의 왼쪽 화살표를 최대 3초 기다린다. 신호가 없으면 해당 메뉴 버튼만 다시 찾아 한 번 재탭한다. 두 번째 시도 후에도 신호가 없으면 정지한다. 재시도 함수는 MENU/소환 메뉴 이외 이미지에는 진입할 수 없다.
- FP 소환·결정·취소·변환·닫기에는 재탭을 적용하지 않는다. FP 재소환 뒤에는 소환 반복 상태를 고정해 FP 표시가 남아 있어도 초기 소환 동작으로 재진입하지 않는다. 시험 모드 기본 켬과 변환 미탭, 모든 잠금·제외·선택 규칙은 유지했다.
- 정지를 공방 도착 `WorkshopNotReached`, MENU `WorkshopMenuFailed`, 소환 `SummonMenuFailed`, 제목 탐색 `BannerTitleNotFound`로 구분했다. 각 en/ko 문자열과 `stop step=bannerReturn reason=workshop/menu/summon/title` 로그를 추가했다. 기존 `BannerNotFound`와 그 문자열을 참조 확인 후 교체했고, 오래된 일본어 문자열은 제거해 영어로 폴백한다. 과거 설계·출시 보고서는 수정하지 않았다.
- `final-summary`는 매각 함수 내부에서 출력하지 않고 스크립트 종료 처리에서만 한 번 출력한다. 중복 호출에도 한 번만 출력하며, 수동 중단과 여러 판매 회차에서도 중간 요약이 생기지 않는지 검사했다.

## QA 증거와 템플릿

`build/orchestration/runs/0007-jp-beta7-qa-20261019/video-frames/`의 원본은 1920×1080 게임 화면이다. 창 테두리 없는 전체 게임 영역을 Lanczos 1280×720으로 정규화했다. FGA 종료창은 중앙에 있으며 검사하는 우측 하단 MENU 영역을 가리지 않는다. 새 템플릿·좌표·임계값은 추가하거나 변경하지 않았다.

| 프레임 | 기존 MENU 템플릿 점수 (호스트 NCC) | 기기 QA 기대·결과 |
|---|---:|---|
| flow-detail-22 | 0.671439 | 미검출, 통과 |
| flow-detail-23 | 0.977003 | 검출, 통과 |
| flow-detail-25 | 0.951475 | 검출, 통과 |
| flow-detail-30 | 0.972794 | 검출, 통과 |

저장된 25 프레임에서는 이미 MENU를 검출할 수 있다. 이 프레임이 실패한 단발 탐색의 정확한 순간이라고 단정하지 않았다. 더 이른 22와 이후 23/25/30을 함께 검사해 이른 페이드에 탭하지 않고 완성된 프레임을 기다리는 근거를 남겼다. 신규 4개는 production `FpSellButtons.visible`과 동일 Region/이미지를 사용한다.

## 로컬 검사

JDK `21.0.12.101`, SDK는 ANDROID_HOME 환경변수만 사용했다.

```text
./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test :app:compileCiKotlin
BUILD SUCCESSFUL
```

app 177 + prefs 24 + scripts 285 = **486개 통과**, 실패·오류·skip 0. libautomata는 테스트 소스 없음. beta.7보다 15개 증가했다.
2초 마지막 폴링 성공·템플릿 누락 무탭, 캡처 지연 포함, 3초 뒤 메뉴 1회 재시도·다음 화면 재검증·총 2탭 상한, 재탭 전 템플릿 재확인, FP/결정/취소/변환 재시도 경로 금지, 실제 판매 흐름의 전환 실패 시 단일 탭, 재소환 후 초기 FP 재진입 방지, 4단계 오류·로그, 종료 요약 한 번을 검증했다. 시험 모드 변환 탭 0회 테스트도 그대로 통과했다.

## 전체 기기 QA

커밋한 최종 코드로 `py -3 tools/device-qa/run.py --build --serial 127.0.0.1:5555` 실행. 결과 `build\device-qa\20261019-134914`.
구현 중 첫 전체 실행 `build/device-qa/20261019-134330`도 모두 통과했고, 마지막 상태 보호·시간 예산 보완 후 위 최종 소스로 전 제품군을 다시 빌드·실행했다.

| 제품군 | 통과 | 기존 skip |
|---|---:|---:|
| support | 185 | 3 |
| bond | 20 | 0 |
| questItems | 5 | 8 |
| noblePhantasm | 18 | 0 |
| fpSell | 735 (기존 731 + 신규 4) | 0 |
| 전체 | **963** | **11** |

실패 0. 기존 fpSell 사례 731개의 객체와 기대값은 beta.7 HEAD와 동일함을 비교했다. 기존 959개 통과 결과와 skip 수가 유지됐고 신규 4개가 추가됐다. 게임 화면을 조작하는 실시간 QA는 하지 않았으며, 별도 테스트 앱에서 저장 이미지만 판독했다.

## 전달

- 코드 커밋 `2aad8d6909fde0232c96369c9c5a7a665fa09af5`: author/committer puruuun, AI trailer 없이 main 푸시.
- [Main CI #18](https://github.com/puruuun/FGA-Modified/actions/runs/39201316948): `success`. 코드 커밋 성공 확인 후 `:app:assembleBeta` 실행.
- aapt: `io.github.fate_grand_automata.beta`, `1004008`, `1.4.0-beta.8` 일치. ARM64 + ARMv7, x86 없음.
- APK: `C:\Users\rl\Documents\Codex\FGA-Modified-builds\FGA-beta-1.4.0-beta.8-arm.apk`
- SHA-256: `9c8c4fa28ac44bebbf98b8818e517fde2c238f46ef9b8f7c5f38611d2cc1d4d7`. 같은 폴더에 `.apk.sha256` 저장.
- 지정 SDK adb의 모든 호출에 `-s 127.0.0.1:5555`. 모델 **PIXEL 2 XL** 확인 후 `install --no-streaming -r` 성공.
- 설치 버전 `1.4.0-beta.8` / `1004008 minSdk=24 targetSdk=36`.
- firstInstallTime `2026-10-15 23:24:59`는 설치 전후 동일. lastUpdateTime `2026-10-19 13:56:47`.

게임 입력·게임 앱 시작/강제 종료·FGA 스크립트 실행·다른 기기·adb 서버 제어는 하지 않았다. 소유자는 시험 모드를 켠 채 공방 MENU→소환→원래 FP 배너→재소환→두 번째 가득 참 종료를 실기 확인해야 한다. beta.7 QA에서 미도달한 이 경로와 FP 전후 불변은 이번 오프라인 검증만으로 실기 통과라고 하지 않는다.
