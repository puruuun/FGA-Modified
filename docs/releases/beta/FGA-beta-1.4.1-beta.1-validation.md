# FGA 1.4.1-beta.1 검증 보고서

상태: **자동 검증·APK 확인·BlueStacks 업데이트 설치·전체 기기 QA 완료.**
버전 `1.4.1-beta.1` / `1004101`, 패키지 `io.github.fate_grand_automata.beta`.

## 수정 범위

0007 §20 D-48·D-49 구현. 제품 코드 `b73bfa8b33ca30b1866f4c3b5c81294d60669f4c`.

- D-48: 판매 뒤 배너로 돌아와 다시 소환할 때도 처음 소환과 똑같은 원본 FGA 경로를 쓴다. 10회 소환은 `first10SummonClick` → `SUMMON_CONFIRM` → `okClick`, 매일 무료 소환은 `initialSummonClick` → `initialSummonContinueClick`이다. 확인창은 처음 소환 때와 같은 창이며 원본 방식이 잘 처리함을 소유자가 확인했다.
- `confirmation` 워터마크 검사, 그 템플릿(`En/fp_resummon_confirm.png`)과 기기 QA 사례, `ResummonConfirmFailed` 사유와 문자열, `resumeSummon()`의 이미지 확인 경로를 삭제했다. 배너 복귀 때는 저장한 제목·FP 라벨·소환 버튼 표시만 확인(`verifySummon`)하고 탭은 원본 경로가 한다. 시험 모드의 두 번째 가득 참 흐름(D-36)과 D-31 배너 복귀는 그대로다.
- D-49: 서포트 이미지 검색·저장 창 상단 설명, 클래스 스코어 +16 판독의 요약 문구와 "현재 JP 서버 전용" 문구를 화면에서 지우고 쓰이지 않는 문자열을 모든 `values*`에서 제거했다. +16 판독 동작은 바꾸지 않았다.

## 테스트

```text
./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test
BUILD SUCCESSFUL
```

**507개 통과**(app 178 + prefs 24 + scripts 305), 실패·오류 0. 새 `FpResummonPathTest`가 원본 탭 순서, 일반·무료 소환 경로, 시험 모드 가득 참 흐름을 검사한다. 이전 `FpResummonConfirmTest`는 삭제했다.

## 전체 기기 QA

명령: `py -3 tools/device-qa/run.py --build --serial 127.0.0.1:5555`.
결과: `build\device-qa\20261019-164920`. 고정 화면 오프라인 판독만 수행했고 게임을 조작하지 않았다.

| suite | 통과 | 실패 | skip |
|---|---:|---:|---:|
| support | 185 | 0 | 3 |
| bond | 20 | 0 | 0 |
| questItems | 5 | 0 | 8 |
| noblePhantasm | 18 | 0 | 0 |
| fpSell | 736 | 0 | 0 |
| 합계 | **964** | **0** | **11** |

beta.11(967 통과, skip 11)에서 삭제한 재소환 워터마크 사례 3건(KR 양성·KR 배너 음성·JP 판매 확인창 음성)만 빠졌다. 나머지 기존 사례의 내용·기대값은 바꾸지 않았고 모두 통과한다. skip 11건은 beta.11과 같다.

## 커밋과 CI

- 제품 코드·버전 커밋 `b73bfa8b33ca30b1866f4c3b5c81294d60669f4c`.
- [Main CI 성공](https://github.com/puruuun/FGA-Modified/actions/runs/39217357667) (`39217357667`).

## APK·설치

- APK `C:\Users\rl\Documents\Codex\FGA-Modified-builds\FGA-beta-1.4.1-beta.1-arm.apk` 및 같은 경로의 `.sha256`. `./gradlew :app:assembleBeta`로 빌드.
- SHA-256 `2fde75f1ac7085caf462f1eb2b7caa6e41f1bb065a04b63ce37eee96ceea70c5`.
- aapt: `io.github.fate_grand_automata.beta`, `1004101`, `1.4.1-beta.1`, 앱 이름 `FGA(beta)`, ABI `arm64-v8a` + `armeabi-v7a`.
- apksigner 검증 성공. 인증서 SHA-256 `5acce47e71373dcfe3a6f6400ed3b3f687bd454aab91c7562c8327a3a083ac9d`(저장소 debug 키, 1.4.0과 같음).
- BlueStacks `127.0.0.1:5555`, 모델 `PIXEL 2 XL` 확인 후 `install -r`: **Success**.
- 설치 후 `versionName=1.4.1-beta.1`, `versionCode=1004101`. `firstInstallTime=2026-10-15 23:24:59` 보존.
- 다른 기기(`HA29GCWH`, `emulator-5554`)는 건드리지 않았고 adb 서버를 재시작하지 않았으며 게임을 조작하지 않았다.

## 소유자 확인 범위

- 소유자가 서포트 재림 구분(ascension select) 기능을 실제 게임에서 직접 써 보고 문제없음을 확인했다.
- 재소환이 첫 소환과 같은 원본 FGA 경로를 쓰며, 이 경로가 동작함을 소유자가 확인했다.
- 1.4.1의 재소환 변경 자체를 FP 자동 판매 실제 흐름에서 끝까지 돌린 확인은 이 보고서 범위 밖이다. 태그·GitHub Release는 만들지 않았다.
