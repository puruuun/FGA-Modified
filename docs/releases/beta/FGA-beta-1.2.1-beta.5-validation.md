# FGA(beta) 1.2.1-beta.5 검증

- 버전 커밋: `15d075a4` (`version.properties`만 변경: beta 1.2.1-beta.4 → 1.2.1-beta.5, versionCode 1002104 → 1002105). 정규판 값(1.2.1, 3083)은 그대로입니다.
- 베타 1.2.1-beta.4 이후 제품 코드 커밋: `3e9f292e`(진단 저장), `f4c2bfd5`(고급 설정 스위치), `27414b19`(+16 조건 무시, D-10). 나머지는 문서와 실기 점검 기록입니다.
- Main CI: https://github.com/puruuun/FGA-Modified/actions/runs/39104586787 (커밋 `15d075a4`, Run 7). 결과 **성공**. 웹 페이지 기준으로 확인했습니다.
- 자동 테스트(로컬, 강제 재실행): app 172, prefs 24, scripts 210, 실패·오류·건너뜀 0. `:libautomata:test`와 `:app:compileCiKotlin`도 성공했습니다.
- 패키지: `io.github.fate_grand_automata.beta`, versionName `1.2.1-beta.5`, versionCode `1002105` (`aapt dump badging`으로 확인)
- APK: `C:\Users\rl\Documents\Codex\FGA-Modified-builds\FGA-beta-1.2.1-beta.5-arm.apk`
- SHA-256: `2217c9b65701ef0505bedebc5313bc2cc2fd6d135be8a92dc84336e15989cbd2` (로컬 `./gradlew :app:assembleBeta` 산출물. CI artifact의 `.sha256` 파일과는 대조하지 않았습니다.)

## 블루스택 설치 (emulator-5554)

- 설치 전 버전: 1.2.1-beta.4 (1002104), 서명 해시 `57c37f37`
- `adb -s emulator-5554 install -r`: 첫 시도는 `Performing Streamed Install` 단계에서 4분 넘게 멈춰, 멈춘 로컬 adb 설치 프로세스만 종료했습니다(`kill-server` 없음, 기기 측 설치 기록은 없었음). 두 번째 시도는 `Success`.
- 설치 후: versionName `1.2.1-beta.5`, versionCode `1002105`, 서명 `57c37f37` 그대로, `firstInstallTime` 2026-10-15 23:24:59 보존(업데이트 설치).
- 제거한 앱은 없습니다. 게임 앱(`com.aniplex.fategrandorder`, `com.netmarble.fgok`)과 태블릿(`HA29GCWH`)은 건드리지 않았습니다.

## 기기 검사 (블루스택 emulator-5554)

실행: `py -3 tools/device-qa/run.py --build --serial emulator-5554 --suite support,bond,questItems`
결과 폴더: `build/device-qa/20261018-154406/` (`summary.md`)

| suite | 케이스 | 통과 | skip | 실패 |
|---|---|---|---|---|
| support | 188 (185 실행) | 185 | 3 | 0 |
| bond | 20 | 20 | 0 | 0 |
| questItems | 13 (5 실행) | 5 | 8 | 0 |

- 기대값(support 185 + skip 3, bond 20, questItems 5 + skip 8, 실패 0)과 일치합니다.
- skip 목록은 케이스 파일의 `skip` 표시와 같습니다. support: `Jp-np-support-np-143344-311`, `Jp-np-support-np-143344-512`, `Kr-np-support-np-134749-204`. questItems: `Jp-native-teapot-off`, `Jp-storm-off`, `Jp-storm-on`, `Jp-teapot-off`, `Jp-teapot-on`, `Kr-storm-off`, `Kr-teapot-off`, `Kr-window-teapot-off`.
- 앱 코드와 임계값은 바꾸지 않았습니다.

## 검증하지 않은 범위

- 실기 진단 저장 확인: 베타 설정에서 "서포트 판독 진단 저장"을 켜고 실제 서포트 선택 중 `diagnostics/support-score/<실행>/` 파일이 생기는지는 확인하지 않았습니다(소유자 확인 대상).
- D-10 안내: KR 서버에서 +16을 켰을 때 토스트 알림과 "현재 JP 서버 전용" 안내가 보이는지는 확인하지 않았습니다(소유자 확인 대상).
- 실제 퀘스트 실행: 이번 검증은 고정 이미지 기기 검사입니다. 실제 게임 진행은 하지 않았습니다.
- 정규판(1.2.1) 설치와 GitHub Release 게시: 하지 않았습니다.

작성자·커미터: puruuun. GitHub Release·새 PR은 만들지 않았습니다.
