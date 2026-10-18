# FGA 1.4.0-beta.2 검증 보고서

상태: 기본값 변경만 적용. 전체 단위 테스트와 fpSell 기기 QA 확인.

- 베타: `1.4.0-beta.2` / `1004002`. 정규판 `1.2.1` / `3083` 유지.
- 변경 이유: 실제 판매의 확인·변환·닫기 흐름은 아직 게임에서 실행되지 않았습니다. 시험 모드가 기본으로 꺼져 있으면 자동 판매를 켜는 순간 바로 판매되므로, 기본값을 바꿉니다.

## 변경

- `FpSellPolicy.DEFAULT_DRY_RUN = true`. `PrefsCore.fpSellDryRun`의 기본값이 이 상수를 따릅니다.
- 실제 판매는 시험 모드를 소유자가 명시적으로 끌 때만 실행됩니다. 자동 판매가 꺼져 있거나 시험 모드가 켜져 있으면 선택까지만 합니다.
- 단위 테스트 `dry run is on by default and real sale needs it turned off explicitly`: 기본값이 true이고, 시험 모드가 꺼졌을 때만 실제 판매가 허용되는지 확인합니다. `PrefsCore`는 Android Context가 필요해 JVM 테스트에서 직접 만들 수 없으므로, 기본값은 상수로 검증합니다.
- 위키 `wiki/scripts/FP-Auto-Sell-KR.md`: 시험 모드 기본 켜짐과 끄면 실제 판매한다는 안내를 추가했습니다.

## 검증

- `./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test :app:compileCiKotlin` 통과.
- `py -3 tools/device-qa/run.py --serial emulator-5554 --suite fpSell`: 142 케이스 판독 결과를 확인했습니다. 판독 로직은 바뀌지 않았습니다.

## 배포 상태

- 커밋: `2b47b5c` (`main`에 푸시). Main CI #12 성공.
- APK: `C:\Users\rl\Documents\Codex\FGA-Modified-builds\FGA-beta-1.4.0-beta.2-arm.apk`, aapt: package `io.github.fate_grand_automata.beta`, versionCode 1004002, versionName 1.4.0-beta.2.
- SHA-256: `fa1ca6eca43256fb0965883ed21b2ddabff98987b44f74402383d4d95934be9c`
- 설치: **보류**. `adb devices`에 `emulator-5554`가 나타나지 않아 `adb install -r`이 실패했습니다. ADB 서버 재시작이나 재연결은 하지 않았습니다. BlueStacks를 다시 연결한 뒤 설치하고 versionCode 1004002를 확인해야 합니다.
- fpSell 기기 QA 재실행도 같은 이유로 아직 하지 않았습니다.
