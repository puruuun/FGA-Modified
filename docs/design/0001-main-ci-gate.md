# 0001 main CI 게이트

- 상태: 완료 (0904bb6a, Main CI 성공: https://github.com/puruuun/FGA-Modified/actions/runs/39054318359, 약 5분)
- 선행: 없음
- 예상 규모: 워크플로 파일 1개 추가, 3개 정리, `AGENTS.md` 몇 줄 수정

## 1. 배경

이 저장소는 `AGENTS.md` 규칙에 따라 PR 없이 `main`에 바로 푸시합니다. 그런데 `main` 푸시 때 자동으로 도는 검사가 없습니다.

| 워크플로 | 현재 트리거 | 실제로 하는 일 |
|---|---|---|
| `ci.yml` | `push: master` | 원본 FGA의 Play Store 배포. 이 저장소에는 `master` 브랜치도, 배포 secret도 없어서 한 번도 실행되지 않음 |
| `beta.yml` | `push: beta/servant-portraits` + 수동 | 단위 테스트와 베타 APK 빌드. 트리거 브랜치가 더는 쓰이지 않아서 실제로는 수동 실행만 됨 |
| `stable.yml` | 수동 | 단위 테스트와 정규판 APK 빌드 |
| `pr-build.yml` | `pull_request` | PR이 없어서 실행되지 않음 |
| `close-issues.yml` | 이슈 생성 | 라벨 없는 이슈를 닫고 **원본 FGA 저장소** 이슈 페이지로 안내함 (잘못된 안내) |
| `publish-wiki.yml` | `push: master` | 실행되지 않음 |

2026-10-16~17 Actions 기록을 보면 Beta APK, Stable APK, Support device test 실행이 전부 `workflow_dispatch`입니다. 테스트를 돌리지 않고 푸시해도 알아챌 방법이 없습니다.

## 2. 목표 / 비목표

**목표**
- `main`에 코드가 푸시될 때마다 4개 모듈 단위 테스트와 R8이 포함된 정규판 빌드가 자동으로 돌게 합니다.
- 실행되지 않거나 잘못 동작하는 원본 FGA 워크플로를 정리합니다.
- 푸시 전후 확인 절차를 `AGENTS.md`에 적어 agent가 매번 지키게 합니다.

**비목표**
- 브랜치 보호 규칙이나 필수 상태 검사 설정. 직접 푸시 흐름을 막게 되므로 하지 않습니다.
- 버전 관리 변경 (0003에서 다룸).
- 기기 판독 검증의 CI 실행 (0002에서 다룸. 기기가 필요해서 CI에 넣지 않음).
- `publish-wiki.yml` 변경. GitHub 위키 게시는 외부 공개 작업이라 소유자가 따로 결정합니다.
- 원격 `beta/servant-portraits` 브랜치 삭제. 필요하면 소유자가 직접 합니다.

## 3. 결정 사항

- **D-1. 새 워크플로 `main-ci.yml`을 만듭니다.** `beta.yml`에 push 트리거를 붙이는 방식은 쓰지 않습니다. 베타 APK 업로드와 버전 값이 매 푸시마다 섞이기 때문입니다.
- **D-2. 빌드는 `:app:assembleCi`로 합니다.** 컴파일만으로는 R8 축소에서 생기는 문제를 잡지 못합니다. `ci` 빌드 타입은 저장소 안의 debug 키로 서명하므로 secret이 필요 없습니다. APK는 업로드하지 않습니다.
- **D-3. 테스트 명령은 기존 `beta.yml` / `stable.yml`과 똑같이 맞춥니다.**
  `./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test`
- **D-4. `ci.yml`과 `close-issues.yml`은 삭제합니다.** 둘 다 원본 FGA 전용이고, 이 저장소에서는 아무 일도 안 하거나 잘못된 안내를 합니다.
- **D-5. `beta.yml`에서 `push: branches: [beta/servant-portraits]` 트리거만 지웁니다.** 수동 실행은 그대로 둡니다.
- **D-6. `pr-build.yml`과 `pr-build-comment.yml`은 그대로 둡니다.** Renovate 등이 PR을 만들 때 APK와 댓글을 남겨 줍니다.

## 4. 변경 내용

### 4.1 `.github/workflows/main-ci.yml` (신규)

- 트리거
  - `push: branches: [main]`
  - `workflow_dispatch`
  - `paths-ignore`는 `pr-build.yml`의 목록을 그대로 씁니다. `docs/**`, `wiki/**`, `*.md`, `.claude/**`, `**/AGENTS.md`, `.github/ISSUE_TEMPLATE/**`, `.github/instructions/**` 등입니다. 단, `.github/workflows/**`는 무시 목록에 **넣지 않습니다.** 워크플로를 고친 커밋도 검사해야 하기 때문입니다.
- `permissions: contents: read`
- `concurrency: group: main-ci-${{ github.ref }}`, `cancel-in-progress: true`
- `env.GRADLE_OPTS`는 다른 워크플로와 같은 값을 씁니다.
- job `test-and-build` (`ubuntu-latest`, `timeout-minutes: 45`)
  1. checkout, JDK 21 (temurin), `gradle/actions/setup-gradle`. action 버전은 `beta.yml`과 **같은 SHA 고정값**을 씁니다.
  2. `Run unit tests`: D-3의 명령
  3. `Build CI APK`: `./gradlew :app:assembleCi`
  4. `if: always()`일 때 `**/build/test-results/**/*.xml`을 `main-ci-test-reports` 이름으로 업로드합니다 (`retention-days: 14`).
- `FGA_VERSION_CODE`와 `FGA_VERSION_NAME`은 설정하지 않습니다. 기본값으로 빌드해도 검사 목적에는 충분합니다.

### 4.2 정리

- 삭제: `.github/workflows/ci.yml`, `.github/workflows/close-issues.yml`
- `.github/workflows/beta.yml`: `on.push` 블록만 삭제합니다.
- `fastlane/`, `Gemfile`, `Gemfile.lock`은 이번에 건드리지 않습니다. 원본 배포 흔적이지만 삭제 범위를 넓히지 않습니다.

### 4.3 `AGENTS.md`의 Delivery 절에 추가

다음 세 가지를 영어로 짧게 추가합니다. 기존 문장은 고치지 않습니다.

1. 푸시 전에 Build & test 절의 단위 테스트 명령(D-3)을 로컬에서 실행해 통과를 확인합니다. 문서만 바꾼 커밋은 예외입니다.
2. 푸시 후 해당 커밋의 `Main CI` 실행 결과를 확인합니다. 실패하면 다른 작업보다 먼저 고칩니다.
3. `Main CI`가 실패한 커밋으로는 베타·정규판 APK를 만들지 않습니다.

Build & test 절에 D-3 명령 한 줄을 "CI와 같은 전체 테스트"로 추가합니다.

## 5. 완료 기준

- [x] `main`에 코드 변경 커밋을 푸시하면 `Main CI` 실행이 자동으로 생기고 성공합니다. 이 설계를 구현한 커밋 자체가 첫 사례가 됩니다. 워크플로 파일이 바뀌었으므로 실행돼야 합니다.
- [ ] `docs/**`만 바꾼 커밋에서는 `Main CI`가 실행되지 않습니다. 이 설계서 상태를 갱신하는 후속 커밋으로 확인합니다.
- [x] `ci.yml`과 `close-issues.yml`이 삭제되었고, `beta.yml`에는 `workflow_dispatch` 트리거만 남아 있습니다.
- [x] `AGENTS.md`에 4.3의 세 규칙이 들어갔습니다.
- [x] 실행 기록의 URL과 소요 시간을 보고합니다.

## 6. 검증 방법

- 로컬: D-3의 명령과 `./gradlew :app:assembleCi`를 실행해 통과를 확인합니다.
- 원격: 푸시 뒤 `https://api.github.com/repos/puruuun/FGA-Modified/actions/runs?branch=main&per_page=5`에서 `Main CI` 실행의 `event=push`와 `conclusion=success`를 확인합니다.
- 검증하지 않는 범위: 브랜치 보호와 PR 흐름. `pr-build.yml`은 바꾸지 않았으므로 확인하지 않습니다.

## 7. 위험과 대응

| 위험 | 대응 |
|---|---|
| `assembleCi`의 R8 단계 때문에 실행 시간이 길어짐 (10분 이상) | 처음 3회 실행 시간을 보고합니다. 20분을 넘으면 설계자에게 알리고, `assembleCi`를 빼고 `:app:compileCiKotlin`으로 바꿀지 결정받습니다. |
| 연속 푸시로 실행이 쌓임 | `cancel-in-progress`로 앞선 실행을 취소합니다. |
| 기존에 이미 깨진 테스트가 있어서 첫 실행부터 실패 | 그 테스트를 고치지 말고 실패 목록을 보고합니다. 원인 판단은 설계자가 합니다. |
