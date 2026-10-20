# FGA-beta-1.6.0-beta.4 검증

- 베타 **1.6.0-beta.4 / 1006004**. 정규판 값은 **1.5.0 / 3086** 유지.
- 설계: `0009` §11 D-16, D-17. 커밋 `6a947b1b`, Main CI 성공.

## 변경 내용

- **D-16** 마지막 전투 후 남는 "연속해서 같은 퀘스트에 출격하시겠습니까?" 대화상자를 전용 신호(기존 `Images.Repeat` 템플릿, JP/KR 자산 사용)로 판별하고, **왼쪽 닫기(閉じる/닫기) 버튼을 좌표로만** 누릅니다. 오른쪽 연속출격은 누르지 않습니다. 닫기 위치는 owner 프레임 32(1920x1080)의 (665,875)를 1440p 스크립트 좌표 `Location(-394, 1166).xFromCenter()`로 변환했습니다. 횟수 2 이상은 기존 FGA 반복·실행 제한을 그대로 쓰고 계획한 횟수 뒤에만 닫으며, 이후 퀘스트 패널로 지도 복귀를 확인합니다. 이전의 인터루드 종료 화면 영역·`Images.Close` 판별은 제거했습니다.
- **D-17** 최소 줌에서도 지도가 화면보다 약간 크므로, 아이콘의 안전 여백 전체가 화면 안(가장자리 20px@720p 안쪽)에 있어야 탭 가능으로 봅니다. 화면 밖·가장자리·UI에 가려진 지점은 탭 전에 스크롤 후 재맞추기 경로를 거칩니다. 비유한 좌표는 탭하지 않습니다.

## 단위 테스트

`./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test` 성공 (실패 0).
새 `WeeklyMapEdgeTest` 4개: 16:9, 18:9, 4:3 각각 화면 밖·가장자리 지점이 스크롤과 재맞추기를 거친 뒤에만 탭되는지, 그리고 부분 잘림·비유한 좌표 거부.

## 빌드

- `:app:assembleBeta` 성공, aapt: `io.github.fate_grand_automata.beta` versionCode 1006004 / 1.6.0-beta.4.
- `FGA-Modified-builds\FGA-beta-1.6.0-beta.4-arm.apk`, sha256 `811a47c504a3f437a354a7cec0704196b2e2c00170569a35fc547d1615bb9b2f`.
- BlueStacks `127.0.0.1:5555`에 `install -r`로 설치(firstInstallTime 유지). 다른 기기는 건드리지 않았습니다. 게임은 조작하지 않았습니다.

## 전체 기기 QA

`py -3 tools/device-qa/run.py --build --serial 127.0.0.1:5555`: support, bond, questItems, noblePhantasm, fpSell, skillLevel, weeklyRoute 모두 통과.
weeklyRoute는 94개 케이스 중 94개 통과(실패0, skip0). 새 케이스: `JP-32-repeat-dialog-left-exit`(대화상자 판별=true, 닫기 위치 443,583@720p), 비대화상자 대조 `JP-11-map-not-repeat-dialog`, `KR-30-quest-panel-not-repeat-dialog`(판별=false).
