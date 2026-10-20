# FGA-beta-1.6.0-beta.3 검증

- 베타 **1.6.0-beta.3 / 1006003**. 정규판 값은 **1.5.0 / 3086** 유지.
- 설계: `0009` §10 D-12~D-15, `0010` D-1·D-2.

## 변경 내용

- 전투 실행 창 왼쪽 아래 **주간 미션 클리어** 버튼으로 오른쪽 패널을 열고 접습니다. 기존 왼쪽 전투 설정·AP·아이템·제한을 사용하며 애니메이션은 없습니다.
- 패널에서 다운로드 단계·진행 막대, 이번 주 미션·수동 미션·경로·횟수 −/+·총 AP를 표시합니다. 유효한 이번 주 캐시는 자동으로 읽습니다. 계산은 UI 스레드 밖에서 합니다.
- 공용 확인은 패널이 열려 있고 양수 횟수 경로가 있으면 주간 경로, 그 외에는 일반 전투를 시작합니다. 다운로드·계산 중에는 확인을 비활성화합니다. 별도 주간 확인창과 중복 전투 설정 선택기는 삭제했습니다.
- **더 많은 옵션 → 추가기능 → 주간 퀘 추적 설정**: JP/KR별 장·프리 퀘스트 선택을 저장합니다. 장을 접고 펼칠 수 있고, 장을 껐다 켜도 개별 퀘스트 선택은 유지됩니다. 기본 모두 켬, 믹틀란311 지원 안 함, 자료가 없으면 다운로드 안내입니다.
- 목록의 아래쪽 항목은 손가락을 위로 밀어 찾습니다. 현재 위치를 먼저 확인한 뒤 아래 방향과 반대 방향을 각각 최대14회 탐색합니다. 화면이 바뀌면 탐색을 중단합니다.
- `WeeklyRoute`에 화면 판별·스크롤 방향/횟수/좌표·배너 점수·핀치 전후 배율/원점/점수·지도 맞추기·지점/판 탭 좌표·판 판정을 기록합니다. 지도 맞추기와 Free Quest/이름/AP 안전 조건은 유지했습니다.
- 인연 추적·MAX 정지·무시할 결과 칸을 편집 화면에서 전투 실행 창으로 옮겼습니다. 선택한 전투 설정의 기존 키를 그대로 쓰며 확인할 때만 인연 설정을 저장합니다. 취소는 저장하지 않습니다.
- ci 전용 아이콘17개와 배경색 override를 삭제하여 main Quick 아이콘을 상속합니다. 앱 이름은 그대로입니다.

## 초안 처리와 검증 방식

기존 초안의 인연 이동·아이콘 삭제·설정 저장 구조·다운로드 진행·목록 방향 규칙을 유지했습니다. 누락된 호출부를 먼저 연결해 컴파일했고, 오른쪽 패널·메뉴 연결·중복 창 제거는 파일별 작은 패치로 진행하면서 매 단계 `:app:compileBetaKotlin`을 통과시켰습니다. 게임은 조작하지 않았습니다.

자동 검토가 문서 빌드의 출력 경로 위험을 지적해 테스트 집계를 분리했습니다. `mkdocs.yml`의 출력이 `build/site`임을 확인한 뒤, 출력 경로를 **build/orchestration/weekly3-docsite**로 명시해 strict 빌드를 통과했습니다. planner의 `site/`, `mkdocs.yml`, `.github/workflows/pages.yml`은 변경하지 않았습니다.

## 단위 테스트·컴파일

`./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test :app:compileCiKotlin :app:compileBetaKotlin` 성공.

**585개 통과, 실패0, skip0**: app184, prefs26, scripts375, libautomata0. 기존581개에 새4개(스크롤 방향·OK 분기·서버별 후보 필터·저장 후 재생성한 설정이 솔버 최적 경로에 반영됨)를 추가했습니다.

## 전체 기기 QA

`py -3 tools/device-qa/run.py --build --serial 127.0.0.1:5555`

결과: `build\device-qa\20261020-211432`. 기존 케이스·기대값은 바꾸지 않았습니다.

| suite | 통과 | 실패 | 기존 skip |
|---|---:|---:|---:|
| support | 185 | 0 | 3 |
| bond | 20 | 0 | 0 |
| questItems | 5 | 0 | 8 |
| noblePhantasm | 18 | 0 | 0 |
| fpSell | 736 | 0 | 0 |
| skillLevel | 83 | 0 | 0 |
| weeklyRoute | 91 | 0 | 0 |
| 합계 | **1138** | **0** | **11** |

새 기기 QA 케이스는0개입니다. 저장 화면 판독 검증이며 실제 주간 이동·전투는 실행하지 않았습니다.

## 커밋·Main CI

- 제품 커밋 `e0edc5b0`. author/committer puruuun, AI 트레일러 없음.
- [Main CI 성공](https://github.com/puruuun/FGA-Modified/actions/runs/39420969704). 이 성공을 확인한 뒤 APK를 빌드했습니다.
- 태그·GitHub Release는 만들지 않았습니다.

## APK·ci 아이콘 확인

- 베타 파일: `C:\Users\rl\Documents\Codex\FGA-Modified-builds\FGA-beta-1.6.0-beta.3-arm.apk` 및 `.sha256`.
- SHA-256: **`4389565a3788526ee0059f2aacae9636a81653a6bdb33c1bd7d4b027bdd6f034`**.
- aapt: `io.github.fate_grand_automata.beta`, `1006003`, `1.6.0-beta.3`, `FGA(beta)`, armeabi-v7a + arm64-v8a.
- apksigner 검증 성공. 저장소 debug 인증서 SHA-256: `5acce47e71373dcfe3a6f6400ed3b3f687bd454aab91c7562c8327a3a083ac9d`.
- 로컬 `:app:assembleCi` 성공. aapt: `io.github.fate_grand_automata.cardcount`, `3086`, `1.5.0`, `FGA(mod)`, 두 ARM ABI.
- ci/beta aapt 아이콘 리소스를 확인했고 main Quick PNG **15/15개**의 디코딩 픽셀이 각각 APK 안에 그대로 존재함을 비교했습니다. ci 서명도 같은 인증서입니다. 정규 APK는 일반 로컬 빌드 출력에서만 검사했고 배포 폴더에 복사·설치·게시하지 않았습니다.

## 설치

- BlueStacks **127.0.0.1:5555**, 모델 **PIXEL 2 XL** 확인 후 베타만 `install --no-streaming -r` 성공.
- 설치 후 `versionName=1.6.0-beta.3`, `versionCode=1006003` 확인.
- firstInstallTime **2026-10-15 23:24:59**: 설치 전과 동일, 보존 확인.
- 다른 기기와 게임은 조작하지 않았습니다. 베타 앱도 실행하지 않았습니다.

## 소유자 확인이 남은 항목

실행 창에서 패널 열기/접기와 다운로드 진행 표시, 왼쪽 전투 설정을 고른 주간 실행, 후보 제외 설정, 실제 목록 이동 방향과 단계 로그는 소유자 실게임 확인이 필요합니다. 이번 기기 QA는 저장 화면 판독 검사이므로 해당 실제 조작의 성공을 보증하지 않습니다.
