# 설치 안내

## 지원 환경

- **Android 7 이상** ARM 기기(`arm64-v8a` / `armeabi-v7a`)를 지원합니다.
- **KR(한국)·JP(일본) 서버에 한정해 테스트했습니다.** 다른 서버의 동작은 확인하지 않았습니다.
- 화면을 읽고 접근성 서비스로 탭만 대신하며, 루트 권한은 필요하지 않습니다.

## 다운로드 및 업데이트

- **[최신 정규판 APK](https://github.com/puruuun/FGA-Modified/releases/latest)**: GitHub Releases에서 `FGA-mod-<버전>-arm.apk` 파일을 받아 설치합니다.
- **Obtainium**: 소스 URL `https://github.com/puruuun/FGA-Modified`를 추가하거나 [obtainium.json](../../obtainium.json)을 가져와 등록할 수 있습니다. Obtainium은 정규판만 제공합니다(`사전 릴리스 포함` 해제).
- **개발 베타 APK**: [전체 릴리스 목록](https://github.com/puruuun/FGA-Modified/releases)에서 `beta` 표시가 붙은 항목으로, 정규판(`io.github.fate_grand_automata.cardcount`)과 별도의 앱(`io.github.fate_grand_automata.beta`, 앱 이름 `FGA(beta)`)으로 설치됩니다.

!!! warning "설치 출처 주의"
    GitHub Releases와 Obtainium 외의 곳에서 받은 APK는 설치하지 마세요.

### 기존 앱과의 공존 및 업데이트

- 정규판 앱 이름은 **FGA(mod)**, 패키지 이름은 `io.github.fate_grand_automata.cardcount`입니다.
- 원본 FGA와 패키지가 달라 **같은 기기에 함께 설치**할 수 있습니다.
- 이전 FGA(mod) 정규판이 이미 설치되어 있다면 앱을 지우지 않고 그대로 **업데이트 설치**할 수 있습니다.
- 원본 FGA에서 쓰던 스크립트와 서포트 이미지를 옮기려면 [원본 FGA 데이터 가져오기](Original-FGA-Import-KR.md)를 참고하세요.

## 초기 설정 및 필수 권한

앱을 처음 실행한 뒤 정상 동작을 위해 아래 항목을 설정합니다.

1. **접근성 서비스**: 앱 메인 화면의 안내 또는 기기 설정에서 FGA(mod)의 접근성 서비스를 켭니다.
2. **다른 앱 위에 표시(오버레이 권한)**: 게임 화면 위에 시작/일시정지 컨트롤 버튼을 띄우기 위해 오버레이 권한을 허용합니다.
3. **저장소 폴더 지정**: **더 많은 옵션 → 저장소 → 폴더**에서 서포트 이미지·드롭 이미지 등을 저장할 폴더를 지정합니다.
4. **배터리 최적화 제외**: **더 많은 옵션 → 고급 → 배터리 최적화 비활성화**를 눌러 시스템 설정에서 FGA(mod)를 **최적화 안 함(제한 없음)**으로 설정합니다. 설정하지 않으면 백그라운드에서 서비스가 임의로 종료될 수 있습니다.

로그 수집을 함께 사용하려면 [로그 수집 시작 설정](Log-Collection-Setup-KR.md)을 이어서 진행하세요.

## 라이선스

앱의 **더 많은 옵션 → 고급 → 라이선스**에서 라이선스 고지를 확인할 수 있습니다. 원본 FGA와 마찬가지로 MIT 라이선스로 배포됩니다. 앱에 들어 있는 라이브러리의 고지는 같은 화면의 **오픈소스 고지**에서 볼 수 있습니다.
