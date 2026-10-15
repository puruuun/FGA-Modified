# 서번트 번호로 서포트 이미지 생성 — FGA(beta)

배포판: **FGA(beta) 1.0.2-beta.2**, 앱 ID `io.github.fate_grand_automata.beta`.

## 사용 방법

1. 베타 APK를 설치하고 FGA 저장 폴더를 선택합니다. 정규판과 함께 사용한다면 `FGA-beta` 같은 별도 폴더를 권장합니다.
2. **더 많은 옵션 → 추가 기능 → 서번트 번호로 서포트 이미지 생성**을 엽니다.
3. 서번트 **도감 번호**를 입력합니다. 예: `90` 네로 브라이드, `444` U-올가마리. 내부 데이터 ID를 입력하는 기능은 아닙니다.
4. **생성·저장**을 누릅니다. 해당 서번트의 모든 공개 재림·영의·지원되는 특수 초상화가 생성됩니다.
5. 결과에서 저장·실패 수를 확인합니다. **전투 설정 → 서포트 → 선호 서번트**에서 `No.444 U－オルガマリー` 같은 번호·이름 폴더를 선택하면 그 폴더의 모든 이미지를 함께 비교합니다.

이미지는 선택한 저장 폴더의 `support/servant/No.번호 서번트 이름/`에 저장됩니다. 이름은 조회한 JP 데이터의 이름이며 폴더명에 사용할 수 없는 문자는 공백으로 바꿉니다. 같은 번호를 다시 생성하면 기존 `No.444` 같은 폴더에 이름을 붙이고 모든 전투 설정의 선호 서번트 선택도 함께 갱신합니다. 폴더 안의 다른 파일은 유지하며 생성 파일만 갱신합니다. 이전 번호 폴더명을 참조하는 내보내기 설정도 이름이 붙은 폴더에서 이미지를 읽을 수 있습니다. 직접 만든 다른 이름의 파일이나 기존 내장 서번트 폴더는 삭제하지 않습니다. 실패한 항목이 있으면 다시 생성해 재시도할 수 있습니다.

## 원본 이미지와 범위

Atlas Academy의 최신 **JP 도감** 메타데이터에서 `extraAssets.faces`와 `facesChange`를 읽습니다. 이미지가 같은 항목은 중복 저장하지 않습니다. 다운로드와 저장은 앱에서 사용자가 실행할 때만 수행하며, 이미지 생성에 FGO 게임 화면이나 게임 계정 정보는 필요하지 않습니다.

게임이 사용하는 128×128 전용 초상화를 FGA의 720p 비교 크기로 변환하고 가로 띠를 저장합니다. 변환은 조사에서 확인한 `128→158`, 확대 후 `(3,47,125,44)`를 사용합니다. FGA의 기존 친구 잠금 표시 제외 처리도 그대로 적용됩니다.

이 값은 JP 실제 서포트/친구 화면에 대조한 베타 규칙입니다. 모든 서번트·기기 화면 비율·다른 서버에서의 인식을 보장하는 검증은 아닙니다. 번호가 없는 일부 특수 레코드는 도감 번호 조회 대상에서 제외됩니다. 일본판에만 출시된 서번트도 JP 번호 기준으로 조회할 수 있습니다.

## 정규판과 베타판 선택

| 항목 | 정규판 | 베타판 |
|---|---|---|
| 앱 이름 | FGA(mod) | FGA(beta) |
| 앱 ID | `io.github.fate_grand_automata.cardcount` | `io.github.fate_grand_automata.beta` |
| 배포 | 정규 GitHub Release | GitHub Pre-release |
| Obtainium | [정규판 추가](https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fapp%2F%257B%2522id%2522%253A%2522io.github.fate_grand_automata.cardcount%2522%252C%2522url%2522%253A%2522https%253A%252F%252Fgithub.com%252Fpuruuun%252FFGA-Modified%2522%252C%2522author%2522%253A%2522puruuun%2522%252C%2522name%2522%253A%2522FGA%2528mod%2529%2522%252C%2522additionalSettings%2522%253A%2522%257B%255C%2522includePrereleases%255C%2522%253Afalse%252C%255C%2522apkFilterRegEx%255C%2522%253A%255C%2522%255EFGA-mod-.%252A-arm%255C%255C%255C%255C.apk%2524%255C%2522%257D%2522%257D) | [베타판 추가](https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fapp%2F%257B%2522id%2522%253A%2522io.github.fate_grand_automata.beta%2522%252C%2522url%2522%253A%2522https%253A%252F%252Fgithub.com%252Fpuruuun%252FFGA-Modified%2522%252C%2522author%2522%253A%2522puruuun%2522%252C%2522name%2522%253A%2522FGA%2528beta%2529%2522%252C%2522additionalSettings%2522%253A%2522%257B%255C%2522includePrereleases%255C%2522%253Atrue%252C%255C%2522fallbackToOlderReleases%255C%2522%253Atrue%252C%255C%2522filterReleaseTitlesByRegEx%255C%2522%253A%255C%2522%255EFGA%255C%255C%255C%255C%2528beta%255C%255C%255C%255C%2529%255C%2522%252C%255C%2522apkFilterRegEx%255C%2522%253A%255C%2522%255EFGA-beta-.%252A-arm%255C%255C%255C%255C.apk%2524%255C%2522%257D%2522%257D) |

Obtainium의 베타 설정은 사전 출시 포함, 릴리스 제목 `^FGA\(beta\)` 및 APK 이름 `^FGA-beta-.*-arm\.apk$`로 필터링합니다. 다른 정규판이 나중에 게시돼도 베타를 찾을 수 있도록 이전 릴리스 대체 검색을 켭니다. 두 채널은 앱 ID가 달라 동시에 등록할 수 있습니다.

[정규판 설정](../../obtainium.json) · [베타판 설정](../../obtainium-beta.json) · [두 채널 가져오기 파일](../../obtainium-channels.json)

## 개발 및 검증

`./gradlew :app:assembleBeta`로 베타 APK를 만들 수 있습니다. 버전은 `FGA_VERSION_NAME`, `FGA_VERSION_CODE` 환경변수를 사용합니다. `.github/workflows/beta.yml`은 단위 테스트 후 ARM APK를 생성하며 정규 릴리스를 자동 게시하지 않습니다.

메타데이터의 번호 일치, 재림·영의·특수 변형 열거, 중복 제거, URL/파일명 검증과 정규/베타 업데이트 분리를 단위 테스트합니다. 실제 기기 검증 결과는 베타 릴리스 노트에 기록합니다.

출처: [Atlas API](https://api.atlasacademy.io/docs), [Obtainium 가져오기 형식](https://wiki.obtainium.imranr.dev/deep_links/), [초상화 조사](Support-Template-Coordinate-Study-KR.md).
