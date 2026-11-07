# JP 루트 CA — PR 1 로컬 검증 (2026-11-08)

- 대상: #243 PR 1/3, 기준 브랜치 feat/capture-jp (b9ed93e9).
- JP 호스트·패키지 상수, 별도 JP CA, 루트 설치·제거, 설정 및 캡처 시작 훅을 확인했다. HOST_SERVERS·VPN 허용 앱·프록시 라우팅은 KR만 유지한다.
- Android 13 이하의 tmpfs 경로와 Android 14 이상의 APEX·zygote/zygote64·자식 네임스페이스 경로는 순수 빌더 테스트로 확인했다. 해시·경로 검증, 중복 마운트 방지, 해시 충돌 방지, 제거 후 확인을 포함한다.
- fake shell로 누락된 su·권한 거부·시간 제한·결과 파싱을 검증했다. 실제 su·root 명령·adb·기기는 사용하지 않았다.
- OpenSSL로 공개 fixture의 subject_hash_old가 bad93d86임을 계산해 고정했다. JP leaf의 SAN·체인과 KR 파일 불변을 확인하고, JP 생성 중 ContentResolver 접근도 금지했다. fixture 개인 키는 삭제했다.
- Robolectric에서 API 28 및 JP 미설치 시 숨김, 루트 없음·거부 시 꺼짐, 성공 시 설치됨·설정 저장, 끄기·재부팅 안내, 현재 CA 상태 갱신을 확인했다.
- 캡처 훅은 스위치·기능 가드, 지연된 설치와 KR 시작의 독립성, 실패 안내, 새 설치 시 실행 중 게임의 재시작 안내를 fake로 검증했다.

## 결과

- :app:compileDebugKotlin 통과.
- 전체 단위 테스트 통과: app 675, prefs 48, scripts 993, libautomata 테스트 없음. 합계 1,716건, 실패·오류·건너뜀 0건. 신규 요구사항 검증은 28건이다.
- :app:lintVitalBeta 통과. beta.versionName=1.9.0-beta.1, beta.versionCode=1009001을 로컬에만 적용했으며 version.properties는 원본 바이트로 복구했다.
- :app:recordRoborazziCi --tests '*JpRootSettingsScreenshotTest' 통과, 이미지 2건. 한국어 로그 수집 설정에서 켜진 JP 스위치와 설치됨을 육안 확인했다.
  - C:/Users/rl/Documents/Codex/FGA-Modified-builds/capture-jp-root-ca/settings-360dp.png
  - C:/Users/rl/Documents/Codex/FGA-Modified-builds/capture-jp-root-ca/settings-1280dp.png
- 실제 루트 기기의 마운트·SELinux·재부팅·게임 TLS 검증은 플래너와 소유자의 기기 QA 범위로 남긴다.

## PR #245 리뷰 수정

- 지적: JP 설정이 인증서 제목과 KR 인증서 상태 사이에 끼어 있어 두 상태가 하나로 읽혔다.
- JP 스위치·상태·재시작 안내를 KR 인증서 버튼 뒤, VPN 제목 앞으로 옮겼다. 한·일·영 설치됨·미설치·실패 상태에 JP 인증서 이름을 붙이고 문자열 들여쓰기를 맞췄다.
- 두 KR 인증서 버튼보다 JP 스위치가 아래에 있고 VPN보다 위에 있는지 실제 UI 경계로 확인하는 회귀 테스트를 추가했다.
- 전체 단위 테스트 통과: app 676, prefs 48, scripts 993, libautomata 테스트 없음. 합계 1,717건, 실패·오류·건너뜀 0건.
- 스크린샷 테스트 2건 통과. 360dp·1280dp에서 KR 상태와 JP 인증서: 설치됨이 구분되는 것을 육안 확인했다.
  - C:/Users/rl/Documents/Codex/FGA-Modified-builds/capture-jp-root-ca/settings-review-360dp.png
  - C:/Users/rl/Documents/Codex/FGA-Modified-builds/capture-jp-root-ca/settings-review-1280dp.png
- 실제 su·루트·adb·기기는 사용하지 않았다.
