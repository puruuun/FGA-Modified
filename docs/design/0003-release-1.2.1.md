# 0003 정규판 1.2.1과 버전 관리

- 상태: **완료** (2026-10-18). GitHub Release v1.2.1 게시(태그 145f48f5, 로컬 빌드 APK SHA-256 6f6a218b…d25419). 실제 게임 확인은 게시 후 소유자가 진행합니다. D1은 B(debug 키 유지).
- 선행: 0001 완료, 0002 완료(기기 검사 통과가 출시 조건)
- 예상 규모: 버전 파일 도입(Gradle 1곳, 워크플로 3개), 릴리스 문서 2개

## 1. 배경

- 정규판은 1.1.0(versionCode 3082, 2026-10-16)입니다. 그 뒤로 제품 코드 커밋 9개가 베타 1.2.0-beta.1 ~ 1.2.1-beta.3에만 들어가 있습니다.
  - 서포트 이미지 이름 검색과 현지화
  - 예장 이미지 검증
  - 이미지 서버 자동 전환(JP → KR → NA)
  - 재림·숨은 이름 검색
  - 캠페인 예장 번호 허용
  - 폴더 이동 확인
  - JP 인연 압축 숫자 판독
  - KR 점수 외곽선 숫자 판독
  - KR 흰색 보구 Lv 판독
- 버전은 워크플로의 `env` 값에 직접 적혀 있습니다(`beta.yml`의 `FGA_VERSION_NAME`, `FGA_VERSION_CODE`, `stable.yml`도 같음). 그래서 베타를 낼 때마다 워크플로 파일을 고칩니다(10월에만 11회).
- 검증 리포트는 저장소 밖 `C:\Users\rl\Documents\Codex\FGA-Modified-builds\*.md`에만 있습니다.
- `ci`·`beta` 빌드 타입은 **저장소에 공개된 debug 키**(`app/fgadebug.keystore`, 비밀번호가 `app/build.gradle.kts`에 적혀 있음)로 서명합니다. 정규판 1.1.0도 이 키로 배포되었습니다.

## 2. 목표 / 비목표

**목표**
- 베타 1.2.1-beta.3까지의 기능을 정규판 **1.2.1**로 출시할 준비를 끝냅니다. 실제 게시는 소유자가 합니다.
- 버전 값을 파일 하나에서 관리합니다.
- 출시 체크리스트와 검증 리포트를 저장소에 남깁니다.
- 서명 키는 지금의 debug 키를 유지합니다(D1 = B).

**비목표**
- 새 기능이나 판독 개선. 이 문서 기간 동안 제품 코드는 고정합니다.
- GitHub Release 게시와 태그 생성. `AGENTS.md`에 따라 소유자가 명시적으로 요청할 때만 합니다.
- Obtainium 설정 변경.
- 서명 키와 서명 관련 빌드 설정 변경(D1).
- 영어·일본어 릴리스 노트. 1.1.0과 같이 한국어만 씁니다.

## 3. 결정 사항

- **D-1. 버전 이름은 1.2.0이 아니라 1.2.1입니다.** 베타가 이미 `1.2.1-beta.N`까지 나갔습니다. 버전 규칙상 1.2.0은 1.2.1-beta보다 낮은 버전이라, 1.2.0으로 내면 순서가 뒤집힙니다.
- **D-2. 버전 파일은 `version.properties`(저장소 루트)입니다.**
  ```properties
  stable.versionName=1.2.1
  stable.versionCode=3083
  beta.versionName=1.2.1-beta.4
  beta.versionCode=1002104
  ```
  - 정규판 versionCode는 직전 정규판 값에 1을 더합니다(1.1.0 = 3082 → 1.2.1 = 3083).
  - 베타 versionCode는 지금 쓰는 공식을 유지합니다: `major*1000000 + minor*1000 + patch*100 + beta`. 1.2.1-beta.3 = 1002103에서 확인한 공식입니다.
  - 환경변수 `FGA_VERSION_NAME`, `FGA_VERSION_CODE`가 있으면 파일보다 우선합니다. PR 빌드가 `run_number`를 쓰기 때문에 유지합니다.
- **D-3. Gradle에서 검증합니다.** `app/build.gradle.kts`가 `version.properties`를 읽어 빌드 타입에 맞는 값을 씁니다(`beta`, `deviceTest` → beta 값 / `ci`, `release` → stable 값). 아래 경우에는 빌드를 실패시킵니다.
  - stable 이름이 `X.Y.Z` 형식이 아님
  - beta 이름이 `X.Y.Z-beta.N` 형식이 아님
  - beta 코드가 위 공식과 다름
- **D-4. 검증 리포트는 `docs/releases/<버전>-validation.md`에 둡니다.** 사용자용 릴리스 노트는 지금처럼 `wiki/releases/<버전>-KR.md`에 둡니다.
- **D-5. 출시 후보 = 1.2.1-beta.3의 제품 코드 + 0001·0002·0003의 빌드/문서 변경.** 0001과 0002는 제품 코드를 바꾸지 않습니다. 0003은 `app/build.gradle.kts`를 바꾸므로, 최종 확인은 새로 빌드한 1.2.1-beta.4로 다시 합니다.

### D1 — 정규판 서명 키: **B로 결정, 현재 debug 키 유지** (2026-10-18)

- 정규판도 지금처럼 저장소에 공개된 `app/fgadebug.keystore`로 서명합니다. 서명 설정은 바꾸지 않습니다.
- 알고 받아들인 위험: 누구나 같은 서명의 APK를 만들 수 있습니다. 사용자가 다른 곳에서 받은 변조 APK를 설치하면, Android는 이를 정상 업데이트로 받아들이고 접근성 권한도 그대로 이어집니다.
- 대응: 릴리스 노트와 README에 "GitHub Releases와 Obtainium 외의 곳에서 받은 APK는 설치하지 마세요."를 안내합니다(4.3).
- B를 고른 이유: 취미 규모의 프로젝트라 위험이 낮습니다. 반면 키를 바꾸면 기존 사용자가 재설치해야 하고, 키를 잃어버리면 업데이트를 영영 낼 수 없는 관리 부담이 생깁니다.
- 다시 검토할 시점: 사용자가 크게 늘거나 변조 APK가 실제로 발견되면 다시 결정합니다. 그때 바꾸면 기존 사용자 전원이 한 번 재설치해야 합니다.
- 참고: 원본 FGA는 공식판(`release`)만 비공개 키로 서명하고, 공개 debug 키는 앱 ID가 다른 테스트 빌드(`.debug`, `.test`)에만 씁니다.

## 4. 변경 내용

### 4.1 버전 파일 (D-2, D-3)

- `version.properties`를 새로 만듭니다(D-2의 값).
- `app/build.gradle.kts`:
  - `defaultConfig`의 `versionCode`·`versionName`을 환경변수 → `version.properties` 순서로 정합니다.
  - 빌드 타입별로 `versionNameSuffix`가 아니라 **값 자체**를 지정합니다(`buildTypes { getByName("ci") { ... } }`). 파일이 없거나 형식이 틀리면 `GradleException`을 던집니다.
  - 이 파일은 [`docs/agents/dependencies.md`](../agents/dependencies.md)의 대상입니다. 플러그인과 버전 순서 규칙을 확인하고, 의존성은 추가하지 않습니다.
- `beta.yml`, `stable.yml`: `env`의 `FGA_VERSION_NAME`, `FGA_VERSION_CODE`를 삭제합니다. APK 이름 단계는 `version.properties`에서 이름을 읽어 씁니다(`grep`/`cut` 한 줄).
- `stable.yml`의 APK 확인 단계(`aapt dump badging` 비교)는 기대값을 파일에서 읽도록 바꿉니다.
- `stable.yml`과 `beta.yml`에 `sha256sum FGA-*.apk > FGA-*.apk.sha256` 단계를 추가하고, APK와 함께 업로드합니다.

### 4.2 출시 체크리스트 `docs/releases/RELEASE-CHECKLIST.md` (신규)

소유자와 agent가 정규판을 낼 때마다 따르는 순서입니다.

1. `version.properties`에서 beta와 stable 값을 올립니다(D-2 규칙).
2. Main CI가 통과했는지 확인합니다.
3. Beta APK를 빌드하고 블루스택에 업데이트 설치합니다. 설치 성공과 SHA-256 일치를 확인합니다.
4. `python tools/device-qa/run.py --build`로 KR·JP 전체 suite가 통과하는지 확인합니다(0002).
5. 실제 게임 수동 확인(소유자). 범위는 리포트에 적습니다.
6. Stable APK를 빌드합니다(`stable.yml` 수동 실행). 확인 단계가 통과해야 합니다.
7. `docs/releases/<버전>-validation.md`와 `wiki/releases/<버전>-KR.md`를 작성하고, README의 정규판 줄을 갱신합니다.
8. **소유자가** GitHub Release를 게시합니다. 태그는 `v<버전>`, 첨부 파일은 `FGA-mod-<버전>-arm.apk`입니다. 이 이름은 앱 내 업데이트 확인(`GitHubUpdateChecker`)과 Obtainium 필터(`^FGA-mod-.*-arm\.apk$`)가 요구하는 형식이므로 바꾸지 않습니다.

### 4.3 1.2.1 문서

- `wiki/releases/1.2.1-KR.md`: `wiki/releases/1.1.0-KR.md`와 같은 구성(변경 사항 / 설치·업데이트 / 검증)으로 씁니다. 변경 사항은 1절의 9개 커밋을 사용자 관점으로 묶어 정리합니다. 판독 개선 3개는 한 항목으로 묶습니다. "설치·업데이트"에는 기존 정규판과 같은 서명이라 업데이트 설치가 된다는 점과 함께 "GitHub Releases와 Obtainium 외의 곳에서 받은 APK는 설치하지 마세요." 한 줄을 넣습니다.
- `docs/releases/1.2.1-validation.md`: 기존 베타 리포트 형식을 따릅니다. 포함 항목:
  - 커밋, CI 실행 URL, versionCode, SHA-256
  - 자동 테스트 수
  - 기기 검사 요약(0002의 `summary.md`)
  - 블루스택 설치 확인
  - 검증하지 않은 범위
- 저장소 밖의 기존 베타 리포트 4개(`FGA-Modified-builds/*.md`)를 `docs/releases/beta/`로 복사합니다. 내용은 바꾸지 않습니다.
- `README.md`·`README.en.md`·`README.ja.md`의 다운로드 절에 위와 같은 설치 출처 안내를 한 줄씩 추가합니다. 이 부분은 게시 전에 반영해도 됩니다.
- `README.md`의 정규판 줄은 **게시 직전에** 갱신합니다. 체크리스트 7단계이며, 게시 전 `main`에는 반영하지 않습니다.

## 5. 완료 기준

- [ ] `version.properties`만 고쳐서 베타 빌드 이름과 versionCode가 바뀌고, 워크플로 파일 수정이 필요 없습니다.
- [ ] D-3의 형식 오류 세 가지가 각각 빌드 실패를 냅니다. 로컬에서 확인하고 되돌립니다.
- [ ] Beta APK 1.2.1-beta.4(1002104)와 Stable APK 1.2.1(3083)이 CI에서 빌드되고 `.sha256` 파일이 함께 업로드됩니다.
- [ ] 1.2.1-beta.4를 블루스택에 업데이트 설치하고 기기 검사 전체가 통과했습니다.
- [ ] `docs/releases/RELEASE-CHECKLIST.md`, `docs/releases/1.2.1-validation.md`, `wiki/releases/1.2.1-KR.md`가 작성되었습니다.
- [ ] Stable APK 1.2.1을 기존 1.1.0 위에 업데이트 설치할 수 있습니다(같은 서명). 블루스택에서 확인합니다.
- [ ] 릴리스 노트와 README 3종에 설치 출처 안내가 들어갔습니다.
- [ ] GitHub Release는 만들지 않았습니다. 게시 준비가 끝났다고 소유자에게 보고합니다.

## 6. 검증 방법

- 로컬 `./gradlew :app:assembleBeta :app:assembleCi`를 실행하고, 각 APK의 `aapt dump badging` 첫 줄을 보고합니다.
- CI 실행 URL과 artifact 이름을 보고합니다.
- 검증하지 않는 범위: 실제 GitHub Release 게시 후 `GitHubUpdateChecker`와 Obtainium의 업데이트 감지. 게시 후 소유자가 확인합니다.

## 7. 위험과 대응

| 위험 | 대응 |
|---|---|
| versionCode를 잘못 올려서 업데이트 설치가 거부됨 | D-3 검증, 체크리스트 3단계의 블루스택 업데이트 설치 |
| 공개 키로 서명된 변조 APK 유포 | D1에서 받아들인 위험입니다. 설치 출처 안내로 대응하고, 실제 사례가 생기면 D1을 다시 결정합니다. |
