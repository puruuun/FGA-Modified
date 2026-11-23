# 0024. Chaldea 내보내기 규격(`FateTopLogin` vs `UserData`)과 `fgo-converter` 체크리스트 연동 조사

작성일: 2026-11-14
상태: **조사·원인 분석 완료** (다른 에이전트 및 후속 설계 참고용; 승인된 구현 스펙 아님)
관련 문서: [`0023-chaldea-direct-sync-research.md`](0023-chaldea-direct-sync-research.md)

> **2026-11-19 갱신 (저장소 등록 시점)**
> - 아래 본문은 2026-11-14 조사 당시 기준이다. 그 뒤 `feat/capture-tracker`는 `main`에 합쳐져 삭제되었고, 여기서 다루는 구현은 모두 `main`에 있다(정규 1.8.1).
> - 파일 내보내기 형식이 바뀌었다(#494, 1.8.1): `ChaldeaExport.kt`는 로그인 응답 원문 대신 최소 응답 `[{"nid":"login","resCode":"00"}]`을 쓰고, `cache.replaced`에는 Chaldea가 읽는 테이블(`CHALDEA_TABLES`, 45개)만 넣는다. 5절 1항의 형식 설명은 이 점에서 낡았다. 두 규격이 서로 다르다는 결론과 6절의 선택지는 그대로 유효하다.
> - 5절 3항의 안내 문구 불일치는 아직 고치지 않았다. Chaldea `intl_ko.arb`(2026-11-19 확인)의 이름은 `불러오기`(`import_data`) → `이외의 불러오는 방법`(`import_userdata_more`) → `Https 스나이핑`(`https_sniff`) → `소스 파일 가져오기`(`import_source_file`) → `파일에서`(`import_from_file`)이고, FGA의 `capture_chaldea_guide`와 `wiki/scripts/Gains-Tracker-KR.md`는 여전히 `가져오기 → HTTPS 스니핑 → 파일에서`라고 안내한다.
> - 앱과 설명서에는 내보낸 파일을 `백업 불러오기`에 넣지 말라는 경고가 없다(3.2절 1항의 동작). 문구 수정과 경고 추가는 오너가 보류했다(2026-11-19).

> **브랜치·출처 구분**
> - 본 문서 작성 시점 기준 `main`(`1.7.5`)에는 `0023-chaldea-direct-sync-research.md`만 존재하며, FGA 측 Chaldea 내보내기(`ChaldeaExport.kt`)와 직접 동기화(`ChaldeaPatch.kt`·`ChaldeaUserdataEditor.kt`) 구현은 `origin/feat/capture-tracker`(`1.8.0-beta.39`)에 위치한다.
> - 외부 저장소 확인 대상: `chaldea-center/chaldea`(`main`, **AGPL-3.0**), `Unic31/fgo-converter`(`main`). AGPL-3.0 라이선스 준수를 위해 본 문서는 관찰된 공개 규격·스키마·동작만 정리하며 Chaldea 소스코드는 복제하지 않는다.

---

## 1. 핵심 요약

1. **Chaldea JSON 입력 규격은 목적별로 완전히 분리된 2가지다.**
   - **경로 A (`이외의 불러오는 방법 → Https 스나이핑`)**: FGO `/login/top` 원문 구조(`FateTopLogin`: `response` + `cache`)를 받아 현재 활성 계정(`db.curUser`)에 **부분 병합(merge)** 한다.
   - **경로 B (`Chaldea 백업 → 백업 불러오기`)**: Chaldea 자체 저장 파일 구조(`UserData` v4: `userdata.json`)를 받아 앱 전체 상태(`db.userData`)를 **통째로 교체(overwrite)** 한다.
2. **`Https 스나이핑` 규격과 `userdata.json`은 서로 한쪽만 가진 데이터가 있는 비대칭 구조다.**
   - `FateTopLogin`에는 `userPresentBox`, `userGacha`, `userShop`, `userQuest`, `userDeck`, 개별 서번트 인스턴스 등 `userdata.json`에 저장되지 않는 원본 FGO 데이터가 들어 있다.
   - 반대로 `userdata.json`에는 육성 플랜(`plans`), 성정석 플랜(`saintQuartzPlan`), 전투 시뮬레이터(`battleSim`), 다중 계정 목록(`users[]`), 계정 식별자(`id`, `lastImportId`) 등 스니핑 JSON에 없는 Chaldea 사용자 데이터가 들어 있다.
3. **FGA 파일 내보내기를 `백업 불러오기`용 `UserData` JSON으로 대체하는 것은 부적절하며 위험하다.**
   - 현재 FGA의 `FateTopLogin` JSON을 `백업 불러오기`에 넣으면, 오류 안내 없이 `db.userData`가 빈 `"Gudako"`(`region = Region.jp`) 기본 데이터로 덮어써지고 `"불러오기를 성공했습니다"`가 표시된다(`importUserData`의 `users == null` 미검사 + `$checkedCreate`의 `null` fallback).
   - 설령 FGA가 `UserData` JSON을 생성하더라도 일반 파일 내보내기(Scoped Storage)에서는 Chaldea의 기존 `userdata.json`을 먼저 읽을 수 없으므로 기존 플랜·다른 계정·`id`/`lastImportId` 매칭이 유실되거나 교체된다. 그래서 `userdata.json` 갱신은 `0023` 및 `feat/capture-tracker`처럼 Shizuku/루트 기반 read-modify-write 경로(`ChaldeaUserdataEditor`)가 필요하다.
4. **`Unic31/fgo-converter` 체크리스트가 FGA의 스니핑 내보내기 JSON을 거부하는 이유는 기대 규격이 다르기 때문이다.**
   - 체크리스트는 Chaldea의 **백업/내보내기 파일(`UserData`, 즉 `chaldea-userdata-*.json`)** 만 파싱한다(`data.users[data.curUserKey ?? 0].servants`를 도감 번호 `collectionNo` 키로 읽고 `cur.favorite` 필터 적용). FGA의 스니핑 내보내기(`FateTopLogin`)에는 `users` 배열이 없어 `'칼데아 앱의 사용자 데이터 JSON 파일이 아닙니다.'` 경고가 발생한다.

---

## 2. Chaldea의 두 가지 JSON 규격과 앱 내 경로

### 2.1 Chaldea 한국어 UI 경로 (`lib/l10n/intl_ko.arb`)

| 기능 | 실제 한국어 UI 메뉴 경로 | 근거 파일 |
|---|---|---|
| Https 스나이핑(파일 가져오기) | `홈 → 불러오기 → Https 스나이핑 → 소스 파일 가져오기 → 파일에서` (또는 `클립보드에서`) | `gallery_item.dart:341-348`, `home_import_page.dart`, `import_https_page.dart`, `intl_ko.arb` |
| 사용자 데이터 진입(대체 경로) | `설정 → 사용자 데이터` (`UserDataPage`) | `settings_page.dart:100-106`, `user_data_page.dart` |
| 백업 불러오기 / 다른 이름으로 저장 | `UserDataPage` 내 `백업 불러오기` / `다른 이름으로 저장` (`chaldea-userdata-*.json`) | `user_data_page.dart:160-175`, `intl_ko.arb` |
| 사용자 데이터 페이지에서 스나이핑 이동 | `설정 → 사용자 데이터 → 이외의 불러오는 방법 → Https 스나이핑` | `user_data_page.dart`, `home_import_page.dart`, `intl_ko.arb` |

기타 참고:
- `home_import_page.dart`(`ImportPageHome`)에는 `Chaldea 백업`(`UserDataPage`), `Https 스나이핑`(`ImportHttpPage`), `계정 파일`(`AutoLoginPage`), `CSV 템플릿`(`ImportCSVPage`), 비활성화된 스크린샷 항목(`enabled: false`)이 있다.
- `AutoLoginPage`(`autologin_page.dart:68-83`)는 `Region.jp`, `Region.na` 로그인 파일 전용이며 `Region.jp` 선택 시 `"JP is not supported! DO NOT USE!"`를 표시하므로 FGA 파일 내보내기 대상이 아니다.
- `ImportCSVPage`(`import_csv.dart`)는 서번트 상태/코인/플랜 CSV 전용이다.
- `android/app/src/main/AndroidManifest.xml`에는 파일 열기용 `VIEW` / `SEND` 인텐트 필터가 없고 `link.chaldea.center` App Link만 선언되어 있어, FGA에서 OS "공유/열기" 인텐트로 Chaldea JSON 가져오기 화면을 직접 여는 경로는 없다.

### 2.2 규격 비교: `FateTopLogin` vs `UserData`

| 구분 | 경로 A: `Https 스나이핑` (`FateTopLogin`) | 경로 B: `백업 불러오기` (`UserData`) |
|---|---|---|
| 모델 / 버전 | `FateTopLogin` (`lib/models/gamedata/mst_data.dart`) | `UserData`, `modelVersion = 4` (`lib/models/userdata/userdata.dart`) |
| 최상위 JSON 키 | `response` (리스트), `cache` (`replaced` 등 맵), `sign` | `version`, `appVer`, `curUserKey`, `users`, `itemAbundantValue`, `svtAscensionIcon`, `preferAprilFoolIcon`, `customSvtIcon` |
| 허용 인코딩 (`fromBytes`) | UTF-8 JSON, Base64(`ey...`), gzip(`0x1f`, `0x8b`), raw deflate | UTF-8 JSON (`jsonDecode(utf8.decode(bytes))`) |
| 파싱 검증 | `response` 안에 `nid == 'login'` 및 `resCode == '00' \|\| resCode == '0'` 필수 (`import_https_page.dart:1002-1012`) | `importUserData()`(`user_data_page.dart:160-175`)는 `users == null` 사전 검증이 없음 (반면 `_BackupHistoryPage` 395행에는 `jsonData['users'] == null` 검사 존재) |
| 반영 방식 | 현재 활성 유저 `db.curUser`에 항목별 **병합(merge)** (`didImportData`, `import_https_page.dart:799-937`) | 앱 전체 `db.userData`를 **통째로 교체(overwrite)** 후 저장 |
| 다중 계정 보호 | `user.lastImportId != null && user.lastImportId != userGame.friendCode`이면 확인 슬라이더(`ConfirmSliderDialog`) 표시 후 덮어쓰기 방지 | 다중 계정 구분 없음 (`users[]` 전체 교체) |
| 서버 판별 | `guessRegion`: `globalUser`→`na`, `rkchannel=='1000'`→`tw`, `friendCode.length>=12`→`cn`, 그 외 `jp` (KR 추론 분기 없음) | 각 `User.region` 값 그대로 사용 |

---

## 3. 데이터 비대칭과 `백업 불러오기` 사용 시 문제 원인

### 3.1 어느 쪽에 더 많은 데이터가 있는가

어느 한쪽의 상위 호환이 아니라 **목적이 다른 비대칭 구조**다.

- **`FateTopLogin`(스나이핑 JSON) 쪽에만 존재하는 데이터**:
  - `ImportHttpPage`(`import_https_page.dart:700-784`)의 통계·리포트·보유 상세(`buildStatDialog`, `FgoAnnualReportPage`, `UserDeckListPage`)는 `userdata.json`에 저장되지 않는 원본 FGO 테이블을 직접 읽는다.
  - 예: `userPresentBox`(선물함), `userGacha` + `userShop`(가챠·상점 이력), `userQuest`(프리퀘 주회·관위/클래스 스코어 미션 통계), `userDeck`(편성), `userSvtCollection` + `userSvt` / `userSvtStorage`(개별 서번트·인연 상세).
  - 참고: `import_https_page.dart:80`의 `tmpPath`는 `joinPaths(db.paths.userDir, 'sniff', db.curUser.id)`에 스니핑 원본을 유지한다.
- **`UserData`(`userdata.json`) 쪽에만 존재하는 데이터**:
  - `userdata.g.dart:128-155` 기준 `User` 객체에는 `id`(UUID), `name`, `region`, `dupServantMapping`, `plans`(최대 `kSvtPlanMaxNum = 5`: `title`, `servants`, `limitEvents`, `mainStories`, `tickets`, `classBoards`, `recipes`), `sameEventPlan`, `curSvtPlanNo`, `itemsLeft`, `eventItemCalc`, `summons`, `myRoomMusic`, `freeLPParams`, `luckyBagSvtScores`, `saintQuartzPlan`, `battleSim`, `formationBondOption`, `lastImportId` 등이 포함된다.
  - 최상위 `UserData`에도 `curUserKey`, `users[]`, UI 표시 설정 등이 들어 있다.

### 3.2 `백업 불러오기`에 스니핑 JSON을 넣거나 파일 내보내기로 대체할 수 없는 이유

1. **스니핑 JSON을 `백업 불러오기`에 넣으면 무음 초기화 발생**:
   - `UserDataPage.importUserData()`(`user_data_page.dart:160-175`)는 `jsonData['users'] == null` 여부를 확인하지 않고 `UserData.fromJson(...)`을 호출한다.
   - `userdata.dart` / `userdata.g.dart` / `shared.dart:9-25`의 `$checkedCreate` 조합에서 알 수 없는 키(`response`, `cache`)만 있는 JSON은 예외 대신 필드 `null`로 처리되고, `UserData` 생성자(`userdata.dart:67`)가 빈 `users`를 `<User>[User()]`(기본 `"Gudako"`, `Region.jp`)로 채운다.
   - 직전에 `db.backupUserdata()`가 돌지만, 사용자 화면에는 `"불러오기를 성공했습니다"`가 뜨면서 현재 데이터가 빈 `"Gudako"` 계정 1개로 교체된다.
2. **계정 연동 데이터(`id`, `lastImportId`, 다중 계정, 플랜) 문제**:
   - 일반 Android 파일 내보내기 흐름에서는 FGA가 Chaldea 앱 내부의 기존 `userdata.json`을 먼저 읽을 수 없다(Scoped Storage 격리).
   - 기존 `userdata.json` 없이 FGA가 `UserData` JSON을 새로 만들어 `백업 불러오기`로 넣게 하면:
     - 사용자가 만들어 둔 다중 계정(`users[]`), 육성 플랜(`plans`), 성정석 플랜(`saintQuartzPlan`), 전투 시뮬레이터(`battleSim`) 등이 전부 사라진다.
     - Chaldea 내부 `id`(스니핑 임시 폴더·계획 식별과 연결)나 `lastImportId`(`friendCode` 기반 타 계정 오병합 방지 키)도 기존 상태와 맞출 수 없다.
   - 따라서 파일 내보내기 경로에서는 `Https 스나이핑`(`FateTopLogin`)을 쓰는 것이 구조적으로 맞고, `userdata.json` 자체를 손대려면 `0023` 및 `feat/capture-tracker`처럼 Shizuku/루트로 기존 `userdata.json`을 읽어 필요한 필드만 병합하는 방식이어야 한다.

---

## 4. `Unic31/fgo-converter` 체크리스트 연동 실패 원인

대상 사이트: `https://unic31.github.io/fgo-converter/checklist` (저장소: `Unic31/fgo-converter`, `main` 브랜치)

### 4.1 체크리스트가 기대하는 JSON 규격

`src/routes/checklist/+page.svelte`(278–331행)의 `parseChaldeaUserdata(data)` 및 `importJSON` 동작:

- **기대 대상**: Chaldea 앱의 사용자 데이터 백업 JSON (`UserData`, 예: Chaldea에서 `설정 → 사용자 데이터 → 다른 이름으로 저장`으로 내보낸 `chaldea-userdata-*.json`).
  - `src/lib/i18n.js:38`: `checkListGuide.step2 = '2. 칼데아앱 사용자 데이터(JSON)에서 서번트 가져오기'`
- **참조 경로**: `data?.users?.[data.curUserKey ?? 0]` 아래의 `servants` 맵
  - `user?.servants`가 없으면 `null` 반환 → `importJSON`이 `alert(t.importInvalid)` 실행 (`'칼데아 앱의 사용자 데이터 JSON 파일이 아닙니다.'`, `src/lib/i18n.js:75`).
- **엔트리 키 및 필터/필드 규칙**:
  - `user.servants`의 키를 `Number(key)`로 변환하고, `svtClassMap`(`src/lib/svtList.js`, 서번트 **도감 번호 `collectionNo`** 기준)에 포함된 ID만 대상으로 삼는다.
  - `svt?.cur?.favorite`가 truthy(잠금/보유 표시)인 항목만 가져온다.
  - 읽어들이는 필드:
    - `cur.npLv` → `npLv` (최소 `1`)
    - `cur.grail` → `grail` (`0` 초과 정수일 때만 반영)
    - `svt.grandSvt` → `isGrand` (truthy일 때 `true`)
    - `svt.bond` → `bond` (`0..16` 정수로 클램프, `0` 초과일 때 반영)

### 4.2 FGA 스니핑 내보내기(`ChaldeaExport`)와 불일치하는 지점

| 비교 항목 | FGA 스니핑 내보내기 (`ChaldeaExport.kt`) | `fgo-converter` 체크리스트 기대치 (`UserData`) |
|---|---|---|
| 최상위 루트 | `{"response": ..., "cache": {"replaced": {...}}}` | `{"curUserKey": 0, "users": [{"servants": {...}}]}` |
| 서번트 위치 | `cache.replaced.userSvt` / `userSvtStorage` / `userSvtCollection` / `userSvtGrand` | `users[curUserKey].servants` (단일 병합 맵) |
| 서번트 식별 키 | 게임 내부 `svtId` 기반 원시 테이블 | 서번트 도감 번호 `collectionNo` |
| 보유·보구·성배·그랜드·인연 표현 | FGO 원시 테이블(`userSvt`, `userSvtStorage`, `userSvtCollection`, `userSvtGrand`)에 분산 | `servants[collectionNo]` 아래 `cur.favorite`, `cur.npLv`, `cur.grail`, `svt.grandSvt`, `svt.bond`로 병합됨 |

결론: FGA의 스니핑 내보내기는 **Chaldea `Https 스나이핑` 입력 규격(`FateTopLogin`)** 이고, `fgo-converter` 체크리스트는 **Chaldea 앱이 내보낸 `UserData` 백업 규격(`userdata.json`)** 을 요구하므로, 스니핑 내보내기 JSON을 체크리스트에 직접 올리면 `user?.servants` 부재로 즉시 거부된다.

---

## 5. `FGA-Modified` (`origin/feat/capture-tracker`) 현재 구현 상태 메모

조사 시점 기준 `origin/feat/capture-tracker`(`1.8.0-beta.39`)에 구현된 관련 구성 요소:

1. **스니핑 규격 파일 내보내기**:
   - `scripts/src/main/java/io/github/fate_grand_automata/scripts/capture/AccountState.kt`: `OTHER_PLAYER_TABLES = setOf("otherUserGame", "userFollower", "userRecommendFollower")`만 제외하고 본인 계정 테이블 원문을 `rawTables`, `rawLoginResponse`, `serverTime`에 보관.
   - `scripts/src/main/java/io/github/fate_grand_automata/scripts/capture/ChaldeaExport.kt`: `{"response":<rawLoginResponse>,"cache":{"updated":{},"deleted":{},"replaced":{<rawTables>},"serverTime":<serverTime|null>}}` 포맷으로 직렬화.
   - `app/src/main/java/io/github/fate_grand_automata/capture/ChaldeaExportStore.kt`: `chaldea-account-<userId>-<yyyyMMdd-HHmm>.json`으로 저장(계정당 최신 1개 유지).
2. **Shizuku/루트 직접 `userdata.json` 동기화**:
   - `scripts/src/main/java/io/github/fate_grand_automata/scripts/capture/chaldea/ChaldeaPatch.kt`, `ChaldeaUserdataEditor.kt`, `ChaldeaSyncManager`: 기존 `userdata.json`을 읽어 `lastImportId`(`friendCode`)로 대상 유저를 찾고, `AccountState` + Atlas `collectionNo` 매핑을 사용해 `items`/코인/개념예장/커맨드 코드/마술예장/클래스 스코어/서번트/영의/`isGirl`만 in-place 병합(`plans`, 다른 `users[]`, `itemsLeft`, 미지 키 보존).
   - 즉, FGA는 이미 `AccountState` + Atlas `collectionNo` 매핑으로 `UserData`의 `servants` 엔트리를 계산하는 변환 로직을 `ChaldeaPatch.kt` 쪽에 보유하고 있다.
3. **UI 안내 문구 미세 불일치 참고**:
   - `app/src/main/res/values-ko/localized.xml` 1012행 `capture_chaldea_guide` 문구: `"%1$s에 저장했습니다. Chaldea → 가져오기 → HTTPS 스니핑 → 파일에서 → %2$s 파일을 선택하세요."`
   - 실제 Chaldea 한국어 번역(`intl_ko.arb`) 기준 정확한 명칭은 `불러오기 → Https 스나이핑 → 소스 파일 가져오기 → 파일에서`다.

---

## 6. 후속 에이전트를 위한 대응 선택지와 주의사항

### 선택지 비교 (`fgo-converter` 체크리스트 호환 관점)

| 방안 | 구현 위치 | 동작 방식 | 장점 | 단점 · 주의사항 |
|---|---|---|---|---|
| **A. 현재 우회 절차 안내** | 문서/가이드만 | FGA → Chaldea 동기화(스니핑 또는 Shizuku/루트 직접 동기화) → Chaldea `설정 → 사용자 데이터 → 다른 이름으로 저장`(`chaldea-userdata-*.json`) → `fgo-converter` 업로드 | 코드 변경 없음; 기존 Chaldea 데이터와 100% 일치 | 사용자가 Chaldea 앱에서 한 번 더 내보내기 해야 함 |
| **B. FGA에서 체크리스트용 경량 `UserData` JSON 별도 내보내기** | `FGA-Modified` | `AccountState` + Atlas `collectionNo` 매핑(이미 `ChaldeaPatch`에 존재하는 계산 활용)으로 `{"curUserKey":0,"users":[{"servants":{...}}]}` 최소 스키마 JSON 생성 | 일반 Android 환경(무루트/비Shizuku)에서도 FGA만으로 `fgo-converter` 체크리스트 바로 업로드 가능 | **치명적 위험**: 사용자가 이 파일을 Chaldea `백업 불러오기`에 넣으면 기존 `userdata.json`(플랜·다른 계정)이 전부 덮어써짐. 메뉴명·파일명(예: `checklist-only` 등)과 안내 문구로 **"Chaldea 백업 복원용 아님"** 을 강하게 분리해야 함 |
| **C. `fgo-converter` 측에서 `FateTopLogin` 지원 추가** | `Unic31/fgo-converter` | `parseChaldeaUserdata` 실패 시 `response`+`cache.replaced` 구조 감지 후 정적 `svtId → collectionNo` 테이블로 서번트 파싱 | FGA는 단일 스니핑 내보내기만 유지하면 됨 | 외부 저장소 PR/반영 필요; Chaldea의 잠금/중복 합산/보관실 규칙을 `fgo-converter`가 동일하게 구현해야 함 |

### 설계 시 반드시 지킬 주의사항

1. **기존 `ChaldeaExport`(`FateTopLogin`)를 `UserData` 포맷으로 대체하지 말 것**: Chaldea `Https 스나이핑`과 `백업 불러오기`는 호환되지 않으며, `백업 불러오기`로 전환하면 기존 플랜·다중 계정 유실 위험이 생긴다.
2. **체크리스트 전용 내보내기를 도입할 경우 스니핑 내보내기·Chaldea 백업 파일과 혼동 금지**: 파일명·버튼 위치·안내 다이얼로그에서 `fgo-converter 체크리스트 전용`임을 명확히 구분하고, Chaldea `백업 불러오기`에 넣지 않도록 경고해야 한다.
3. **대상 서버 범위(`AGENTS.md`)**: `FGA-Modified` 신규 기능·테스트·가이드는 **KR 및 공식 JP(`GameServer.Jp.Original`)** 기준으로만 설계·검증한다.
