# FGA-beta-1.6.0-beta.5 검증

- 베타 **1.6.0-beta.5 / 1006005**. 정규판 값은 **1.5.0 / 3086** 유지.
- 설계: `0009` §12 D-18, D-19. 커밋 `401a3bb8`, Main CI 성공.

## 변경 내용

- **D-18** 지점을 탭한 뒤 Free Quest 표시가 보일 때까지(최대 약 3초) 기다리고, 0.3초 더 기다려 판이 자리 잡은 뒤 읽습니다. AP·이름이 읽히지 않거나(ApUnreadable) 이름이 어긋나면(NameMismatch) 약 2초 동안 최대 4회 다시 읽습니다. **Match 판정일 때만** 탭하며, 끝까지 Match가 아니면 이전처럼 건너뜁니다. 로그에 `attempt=` 가 추가됩니다.
- **D-19** `ターミナル`/`터미널` 뒤의 화면을 실제로 확인합니다. 장 목록이면 기존 흐름(같은 레코드면 배너 찾기, 다른 레코드면 닫기 후 메인 화면), **메인 화면이면** 장 배너를 찾지 않고 목표 장이 든 메인 레코드(JP 1/1.5/2, KR 1/1.5)를 먼저 열고 그 안에서 배너를 찾습니다. KR 2부 장은 메인 화면에서 바로 배너를 찾습니다. 단계는 터미널 → (닫기) → 레코드 열기 → 장 열기로 고정이며 각 단계의 스크롤 상한(14회)은 그대로라 무한 반복이 없습니다.

## 단위 테스트

`./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test` 성공.
`WeeklyNavigationTest`: 터미널이 장 목록으로 가는 경우(기존 테스트)와 메인 화면으로 가는 경우(같은 레코드, 다른 레코드, KR 2부) 모두 다음 장에 도착하는지 확인. 가짜 드라이버가 터미널 뒤 화면을 바꿀 수 있게 했습니다.

## 빌드

- `:app:assembleBeta` 성공, aapt: `io.github.fate_grand_automata.beta` versionCode 1006005 / 1.6.0-beta.5.
- `FGA-Modified-builds\FGA-beta-1.6.0-beta.5-arm.apk`, sha256 `f7f788e20ddcd9f70e50a681126467f9b204fac321262173fe90dab799e61273`.
- BlueStacks `127.0.0.1:5555`에 `install -r`로 설치(firstInstallTime 유지). 다른 기기는 건드리지 않았고 게임은 조작하지 않았습니다.

## 전체 기기 QA

`py -3 tools/device-qa/run.py --build --serial 127.0.0.1:5555`: support, bond, questItems, noblePhantasm, fpSell, skillLevel, weeklyRoute 모두 통과(weeklyRoute 94/94). 첫 실행은 gradle 직후 adb 데몬 재시작으로 중단되어 같은 명령을 다시 실행했습니다. 판 읽기 재시도와 화면 기반 이동은 실기기 전용 흐름이라 소유자의 실사용 확인이 필요합니다.
