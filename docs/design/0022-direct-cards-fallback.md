# 0022 — 직접 지정 공격의 선택형 카드 부족 fallback

역할: 구현과 테스트 전담. 모델 gpt-6.1-sol, reasoning medium. 계획 담당자는 검수·병합·베타 전달을 담당한다. 한도 부족 시 상태와 재개 지점을 기록하고 멈춘다. 다른 모델에 위임하지 않는다.

## 목적과 소유자 확정 사항

`&` 직접 지정 공격은 현재 원하는 색의 일반 카드가 한 장이라도 부족하면 클릭 전 중단한다. 기본 동작은 유지하면서, 해당 직접 지정 공격별로 **카드 부족 시 우선순위로 대체**를 선택할 수 있게 한다.

1. 직접 지정한 일반 카드와 보구가 항상 우선한다.
2. 전체 세 자리에 대해 존재하는 지정 색을 **먼저 모두 확보**한다. 같은 색 후보는 기존과 같이 화면 왼쪽부터 배정한다. 동일 색이 여러 번 필요한데 부족하면 앞선 공격 자리에 먼저 배정한다.
3. 그 다음 비어 있는 일반 카드 자리만, 확보한 카드를 뺀 손패에서 기존 **카드 + 서번트 우선순위**에 따라 채운다. 현재 웨이브와 현재 선택된 1/2번째 실행 프로필을 그대로 따른다.
4. 반드시 기존 `execution.active.priority.sort(..., state.commandStage)` 또는 그 공통 구현을 재사용한다. 우선순위 알고리즘 복제/하드코딩 금지. 나중에 기존 우선순위를 개편하면 fallback도 공통 경로로 바뀌어야 한다.
5. 구체적 필수 사례: 지정 B → NP1 → A, 손패 A 한 장 + Q 네 장, 우선순위 B > A > Q일 때 A를 세 번째에 확보하고 **Q → NP1 → A**를 실행한다. 앞선 fallback이 뒤의 A를 소모해서는 안 된다.
6. fallback은 공격 순서를 재정렬하거나 NP를 추가하지 않는다. Brave-chain, rearrange, NP-spam을 직접 지정 공격에 적용하지 않는다.
7. 스턴 일반 카드는 제외한다. 정상 카드의 종류 미확정, 불완전/중복 손패, 쓸 수 있는 카드 자체 부족, 지정 NP의 전열 부재는 기존처럼 첫 클릭 전에 중단한다. NP 사용 가능 여부를 새로 판독/대체하는 작업은 아니다.
8. 모든 세 자리를 확정한 다음 클릭한다. 일부만 클릭한 다음 실패하면 안 된다.

## UI와 직렬화

- 기존 직접 지정 설정창에 체크박스 **카드 부족 시 우선순위로 대체** 추가. 새 공격/기존 strict 명령의 기본은 OFF. 직접 지정 OFF일 때 비활성/숨김 중 기존 UI에 맞는 간단한 처리.
- 해당 턴의 공격에 포함되는 값이다. 전역 prefs 스위치로 만들지 않는다. 저장/다시 읽기/편집/복사/.fga 내보내기/가져오기/secondary 프로필에서 보존한다.
- 기존 `&b4a` = strict 그대로. 새 문법 **`&?b4a`** = fallback 허용. `&` 바로 뒤의 선택적 `?` 한 글자만 추가하며 그 뒤는 기존과 같은 3글자 `baq456`이다. 대문자 색 정규화, 중복 NP 금지, 턴 마지막 명령, 일반 공격과 혼합 금지, no-op 처리 유지.
- parser/model/toCommandString/editor summary가 fallback을 유실하거나 4번째 공격으로 오인하지 않게 한다. 요약에 대체 켜짐을 간단히 표시한다. 기존 명령은 round trip 시 바뀌지 않는다.
- EN 원문 + KO 번역 제공(이번 기능에 대한 명시적 번역 예외). 관계없는 번역/자산 수정 금지.
- fallback 발생은 요청 코드와 최종 카드 순서를 테스트/로그로 확인 가능하게 하되 매 판별에 과도한 로그를 추가하지 않는다.

## 변경 후보

- scripts models `DirectCardSequence`, `AutoSkillCommand`, 필요하면 action/NPUsage 연계
- modules `Card` (공통 우선순위 주입 및 예약 후 빈 자리 배정)
- app skill_maker `DirectCardsDialog`, `SkillMakerAtk` 및 표시/저장 경로
- 관련 EN/KO strings
- 단위 테스트, `wiki/scripts/Direct-Cards-KR.md`, `wiki/scripts/Modified-Features-KR.md` 등 관련 설명 갱신
- 이 지시서를 `docs/design/0022-direct-cards-fallback.md`에 정리하고 검증 결과 `docs/qa/1.6.7-beta.1-validation.md` 작성

## 버전 / 전달

- 기존 기능 보완이므로 **1.6.7-beta.1 / 1006701**. stable 1.6.6 / 3093은 그대로.
- 최신 origin/main 기반 독립 worktree를 사용하며 다른 `feat/panel-level-reader`의 미커밋 변경을 가져오거나 수정하지 않는다.
- AGENTS.md와 automation-scripts, docs-site, translations, releases-and-versioning 가이드를 읽는다.
- 브랜치 `codex/direct-cards-fallback`. 명시적 path staging, author/committer puruuun <188972188+puruuun@users.noreply.github.com>, AI trailers 없음.
- 전체 단위 테스트 `./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test` 성공 후 push. `mkdocs build --strict`도 실행. 필요한 컴파일 포함.
- 영어 PR을 main 대상으로 작성하고 Main CI 성공을 확인한 뒤 멈춘다. PR head/URL, 변경 파일, 테스트 수, 제한점, worktree 절대경로를 보고한다. 병합/빌드/게임 조작/릴리스 게시 금지.
- 권한 필요 시 escalation 사용. API는 git credential fill의 토큰을 메모리에서만 사용하고 절대 출력하지 않는다. `gh`가 없으면 GitHub API를 사용한다. 사용자에게 서비스 메시지를 보내지 않는다.

## 필수 검증

1. 위 A1+Q4 사례와 strict 동일 손패의 클릭 0 중단.
2. 둘 이상의 빈자리, 반복 색 부족, 후순위 지정 색 예약, 동일 카드 중복 사용 없음, NP가 앞/중간/뒤인 경우.
3. 카드/서번트 우선순위·웨이브·secondary 프로필을 바꾸면 **대체된 자리만** 해당 공통 우선순위를 따름. 전부 만족하는 경우 strict와 동일 왼쪽 선택.
4. 스턴, Unknown, 부족한 정상 카드 수, 불완전/중복 손패, 빈 NP 전열: 실패 시 클릭 0.
5. Brave/rearrange/NP-spam 배제, 다음 턴에 fallback 누출 없음, strict 기존 전체 회귀 통과.
6. `&?` 정상/오류 문법, uppercase, 스킬 결합, 웨이브/턴 구분, no-op 및 혼합 금지.
7. editor 모델·복사·export/import와 두 프로필 round trip. UI 취소 시 적용 안 됨, OFF 저장 시 `?` 제거, 새 공격 OFF.

순수 카드 선택 알고리즘은 JVM 테스트가 핵심이며 새 이미지/좌표/실제 전투 실행은 필요 없다. 기기 UI나 실제 클릭을 검증하지 않았다면 명시한다.

## 구현 결정

- `DirectCardSequence`는 기존 세 글자 `code`와 기본 OFF인 `fallback`을 함께 보관한다. `parse("?b4a")`와 `toString()`은 `&?b4a`로 왕복하며 기존 strict 직렬화는 바뀌지 않는다.
- 기존 손패 검사를 먼저 수행하고, 전체 자리의 지정 색을 왼쪽부터 예약한다. 남은 빈자리만 콜백으로 전달한 공통 정렬 결과에서 채운다. 실행 경로는 `execution.active.priority.sort(remaining, state.commandStage)`를 직접 사용한다.
- 공통 우선순위 의미는 바꾸지 않는다. 잘못 저장된 중복 우선순위가 같은 카드를 반복해도 남은 실제 카드만 중복 제거하여 사용한다. 고유 후보가 부족하면 첫 클릭 전에 실패한다.
- 다이얼로그는 로컬 저장 가능 상태에서 체크를 편집하고 Save 콜백에서만 부모 문자열을 바꾼다. `?`가 포함된 문자열을 기존 편집기/프리퍼런스 경로로 전달하여 별도 전역 설정 없이 두 프로필과 복사/파일 전송에 보존한다.
- 실제 대체가 발생한 성공 공격만 non-debug INFO allowlist에 포함한 `FGA.DirectCards` 로그로 요청과 전체 결과 순서를 남긴다. 모든 세 자리와 보구 전열을 확인한 후 기록/클릭한다.
- 이번 작업의 APK 빌드·설치·실제 게임 실행은 범위 밖이며 병합 후 부모 담당자가 진행한다. stable 버전은 변경하지 않는다.

검증 기록: [1.6.7-beta.1 validation](../qa/1.6.7-beta.1-validation.md).
