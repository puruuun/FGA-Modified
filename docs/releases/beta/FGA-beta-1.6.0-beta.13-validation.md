# FGA-beta-1.6.0-beta.13 검증

베타 **1.6.0-beta.13 / 1006013**, 패키지 `io.github.fate_grand_automata.beta`.

- 제품 변경: D-1 티포트·스톰포드 제어 기본 켜짐(저장 값 유지), D-2 전역 인연 스위치 기본 켜짐, D-3 꺼진 기능의 실행 창 블록 숨김, D-4 서포트 진단을 추가기능으로 이동, D-5 정규판 탐색 UI 숨김·주간 진단 PNG 베타 전용.
- 전체 단위 테스트 `:app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test`: **643개 통과, 실패·오류·skip 0**. 모듈별 app 186, prefs 26, scripts 431, libautomata 0.
- MkDocs `build --strict` 성공.
- APK는 같은 제품 커밋에서 만듭니다. 한 커밋 조건을 유지하기 위해 푸시 후 확정되는 CI·기기 QA·SHA-256·설치 결과는 이 문서의 로컬 최종본과 최종 인계 보고에 기록합니다.
- 자동 검증은 저장 프레임 판독입니다. 게임은 조작하지 않으며 실제 게임 수동 검증과 태블릿 설치·게시·태그는 소유자 범위입니다.
- 정규 APK는 설치하지 않습니다. 베타만 `127.0.0.1:5555`에 업데이트 설치합니다. HA29GCWH·emulator-5554는 조작하지 않습니다.
