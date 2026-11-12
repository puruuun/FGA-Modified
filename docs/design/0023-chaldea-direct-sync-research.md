# 0023 — Chaldea 계정 데이터 직접 동기화: 조사와 검증 기록

- 상태: **조사·검증 완료, 설계 미확정.** 소유자가 승인한 워크스루와 이슈는 아직 없다. 이 문서는 구현 지시가 아니라, 설계와 구현을 맡을 에이전트가 사실관계를 다시 조사하지 않도록 남기는 기록이다.
- 작성일: 2026-11-11
- 관련: 캡처 트래커(#132, `feat/capture-tracker`), 현재의 Chaldea 내보내기(`ChaldeaExport`, `ChaldeaExportStore`)
- 대상 서버: KR, 공식 JP

## 1. 목적

FGA는 캡처(스니핑)로 얻은 계정 데이터를 Chaldea가 읽는 JSON 파일로 내보낸다. 지금은 사용자가 Chaldea에서 그 파일을 직접 골라 가져와야 한다. 소유자는 **처음 한 번 설정하면 이후에는 계정 데이터가 Chaldea에 알아서 반영**되기를 원한다.

## 2. 결론

1. **Shizuku(또는 루트)만 있으면 Chaldea의 계정 데이터 파일을 FGA가 직접 고칠 수 있다.** MuMu(Android 15)와 태블릿(Android 16 실기기)에서 shell 권한으로 확인했다.
2. **Chaldea를 강제 종료한 뒤에 써야 한다.** 실행 중에 쓰면 Chaldea가 메모리 값으로 덮어쓴다.
3. 반영 시점은 실시간이 아니라 **다음에 Chaldea를 켤 때**다. 사용자가 누를 것은 없다.
4. 모든 계정 항목이 같은 파일에 있어 쓰는 통로는 같다. 항목별 차이는 **게임 데이터를 Chaldea 저장 모양으로 바꾸는 변환 작업량**뿐이다.
5. 권한이 전혀 없으면 현재의 파일 내보내기가 한계다.

## 3. 현재 구현 (`feat/capture-tracker`, beta.24 기준)

| 파일 | 역할 |
|---|---|
| `scripts/.../scripts/capture/ChaldeaExport.kt` | 로그인 응답 모양의 JSON 본문을 만든다(`response` + `cache.replaced`에 테이블 원문). |
| `app/.../capture/ChaldeaExportStore.kt` | MediaStore로 `Download/FGA/chaldea-account-<userId>-<시각>.json`에 저장하고 옛 파일을 지운다. |
| `app/.../capture/ChaldeaFiles.kt` | 파일 이름 규칙. |
| `app/.../ui/ChaldeaExportAction.kt` | 내보내기 버튼, 결과 창, Chaldea 실행 버튼(`cc.narumi.chaldea`, `cc.narumi.chaldea.fdroid`). |
| `app/.../capture/RootCaInjector.kt` | JP 루트 인증서 주입용 `su` 실행기. 루트 경로를 만들 때 재사용 후보. |

- 사용자 절차: FGA에서 내보내기 → Chaldea → 데이터 가져오기 → HTTPS 스니핑 → 파일 선택 → 가져오기 → 확인.
- FGA에는 Shizuku 연동이 없다(의존성, 매니페스트 모두 없음).

## 4. Chaldea 쪽 사실

Chaldea 소스 `chaldea-center/chaldea` 커밋 `ba81b31`(2026-11-08), 앱 버전 2.6.2에서 확인했다. Chaldea는 **AGPL-3.0**이므로 코드를 복사하지 않는다. 아래는 동작을 읽고 적은 것이다.

### 4.1 외부 입구가 없다

- `AndroidManifest.xml`에 파일 열기·공유 받기 인텐트 필터가 없다. 딥링크는 `link.chaldea.center` 앱 링크뿐이다.
- 라우터에 `/import_data` 상수는 있지만 딥링크로 가져오기 화면을 여는 처리는 없다.
- Chaldea 자체 자동 로그인은 JP/NA만 지원한다. KR에는 쓸 수 없다.
- 스니핑 캐시 파일이 4~5MB라 클립보드 가져오기(약 1MB 한도)는 쓸 수 없다.

### 4.2 저장 위치 (Android 기본 설정)

```
/sdcard/Android/data/cc.narumi.chaldea/files/
├─ user/
│  ├─ userdata.json        계정 데이터 전체(사용자 목록, 아이템, 서번트, 계획 등)
│  ├─ userdata.json.bak    같은 내용의 예비본
│  ├─ settings.json(.bak)  앱 설정
│  └─ sniff/<Chaldea 사용자 id>   마지막으로 가져온 스니핑 원문 캐시
└─ backup/ …
```

- 근거: `lib/models/paths.dart`. 사용자가 "외부 저장소(SD 카드) 사용"을 켜면 경로가 달라진다.
- 파일 권한은 `u0_aNN:ext_data_rw`, `rw-rw----`이고 폴더는 setgid다. shell 사용자는 `ext_data_rw` 그룹에 속한다.

### 4.3 스니핑 가져오기 화면 (`lib/app/modules/import_data/import_https_page.dart`)

- 파일을 고르면 내용을 읽어 `user/sniff/<사용자 id>`에 **복사**한다. 원본 파일 위치는 기억하지 않고 감시하지도 않는다.
- 화면을 열 때 이 캐시 파일이 있으면 파일 선택 없이 불러와 미리보기로 보여 준다.
- 실제 반영은 가져오기 버튼과 확인 창을 거친다. `lastImportId`(마지막으로 가져온 친구 코드)와 다르면 슬라이더 확인이 뜬다.

### 4.4 `userdata.json` 읽기·쓰기 (`lib/models/db.dart`)

- 시작할 때 한 번만 읽는다.
- 실행 중에는 메모리 값을 주기적으로, 그리고 백그라운드로 갈 때 파일에 쓴다.
- 시작 직후 본 파일과 `.bak`을 다시 저장한다. 이때 `plans` 부분을 스스로 정리해 크기가 달라진다.

## 5. 접근 방식 비교

| 방식 | 필요 권한 | 남는 수동 작업 | 판단 |
|---|---|---|---|
| 현재(파일 내보내기) | 없음 | 파일 선택 + 가져오기 + 확인 | 권한 없는 사용자의 대체 경로로 유지 |
| Chaldea에 파일 열기 인텐트 PR | 없음 | 가져오기 + 확인 | 메인테이너 수락에 달림. 병행 후보 |
| 스니핑 캐시(`user/sniff/<id>`) 덮어쓰기 | Shizuku 또는 루트 | 가져오기 화면 열기 + 가져오기 + 확인 | 변환이 필요 없어 안전하지만 탭이 남음 |
| **`userdata.json` 직접 수정** | Shizuku 또는 루트 | 없음 | 소유자가 원하는 완전 자동. 이 문서의 검증 대상 |
| 접근성 서비스로 Chaldea 화면 조작 | 없음 | 없음 | Chaldea UI가 바뀌면 깨짐. 비권장 |

## 6. 기기 검증 (2026-11-11)

adb shell은 Shizuku와 같은 shell 사용자(uid 2000)로 동작하므로, adb로 되는 것은 Shizuku로도 된다는 전제로 검증했다. 게임은 건드리지 않았다.

### 6.1 절차

1. `am force-stop cc.narumi.chaldea`
2. `userdata.json`을 PC로 꺼내 백업
3. 현재 사용자의 `items`에서 영웅의 증표(`6503`) 개수 하나만 변경
4. 수정본을 `/data/local/tmp/`에 올린 뒤 `cat 수정본 > …/user/userdata.json`으로 **기존 파일에 내용만 덮어쓰기**
5. Chaldea 실행 후 화면과 다시 저장된 파일 확인
6. 백업으로 원복(`.bak` 포함), 임시 파일 삭제

### 6.2 결과

| 확인한 것 | MuMu (Android 15, `127.0.0.1:16416`) | 태블릿 (Android 16, `HA29GCWH`) |
|---|---|---|
| shell 권한으로 읽기·쓰기 | 성공 | 성공 |
| 덮어쓴 뒤 소유자·권한 | Chaldea 그대로 | Chaldea 그대로 |
| 재시작 후 반영 | 아이템 화면에 바꾼 값 표시 | 다시 저장된 파일에서 확인 |
| `itemsLeft`(나머지) | Chaldea가 바꾼 값으로 다시 계산 | 같음 |
| `.bak`이 옛 값일 때 | 본 파일 우선, 되돌아가지 않음 | 같음 |
| 다른 아이템 | 변화 없음 | 변화 없음 |
| Chaldea 실행 중 쓰기 | 화면 미반영. 홈으로 나가자 메모리 값으로 덮어써짐 | 하지 않음 |

### 6.3 확정된 규칙

- **강제 종료 → 쓰기 순서가 필수다.**
- **새 파일로 바꿔치기하지 말고 기존 파일에 내용을 덮어쓴다.** 그래야 소유자가 Chaldea로 남는다. 이름 바꾸기 방식의 원자적 교체는 소유자가 shell로 바뀔 수 있어 별도 확인이 필요하다.
- **FGA가 바꾸는 칸 외에는 원문 그대로 둔다.** Chaldea 모델 전체를 FGA에 옮기지 말고 JSON 트리로 읽어 필요한 칸만 고친다.

## 7. 데이터 모양과 변환

`userdata.json` 최상위는 `version`(현재 4), `appVer`, `curUserKey`, `users[]` 등이다. 사용자 하나의 주요 칸은 아래와 같다(값은 예시).

| 칸 | Chaldea 저장 모양 | 키 | 게임 데이터에서 만들 때 |
|---|---|---|---|
| `items` | `"6503": 383` | 게임 아이템 id | 그대로 복사. QP·성정석·마나프리즘·레어프리즘은 `userGame`의 값을 Chaldea가 정한 아이템 id에 넣는다 |
| `itemsLeft` | `items`와 같은 모양 | 게임 아이템 id | **쓰지 않는다.** Chaldea가 시작할 때 다시 계산한다(검증됨) |
| `mysticCodes` | `"1": 10` | 마술예장 id | 그대로 복사 |
| `classBoards` | `{"unlockedSquares": […], "enhancedSquares": […]}` | 보드 id | 칸 id 목록 그대로 |
| 서번트 코인 | `items` 안에 들어감 | 코인 아이템 id | 서번트 id → 코인 아이템 id 변환표 필요 |
| `cmdCodes` | `{"status": 2, "count": 7}` | 도감 번호 | 내부 id → 도감 번호, 장수 집계 |
| `craftEssences` | `{"status": 2, "lv": 17, "limitCount": 4}` | 도감 번호 | 내부 id → 도감 번호, 여러 장을 하나로 합치는 규칙, `status` 판정 |
| `servants` | 아래 참고 | 도감 번호(2호기부터는 개체 id) | 여러 테이블 합치기와 계산 |
| `dupServantMapping` | `개체 id: 도감 번호` | 개체 id | 같은 서번트 2호기 이상일 때만 |
| `lastImportId` | 친구 코드 문자열 | — | FGA 계정과 Chaldea 사용자를 짝짓는 열쇠 |

서번트 한 기의 모양:

```json
"1": {
  "cur": {
    "favorite": true, "ascension": 4, "skills": [10, 10, 10],
    "appendSkills": [0, 10, 0, 0, 10], "costumes": {"800140": 1},
    "grail": 16, "fouHp3": 20, "fouAtk3": 20, "fouHp": 0, "fouAtk": 0,
    "fouHp5": 0, "fouAtk5": 0, "bondLimit": 16, "npLv": 4
  },
  "priority": 1, "bond": 15,
  "equipCmdCodes": [null, null, null, null, null],
  "cmdCardStrengthen": null, "grandSvt": true
}
```

Chaldea의 수동 가져오기가 서번트에 대해 하는 일(동작 요약, 재구현 시 대조용):

- 재림·스킬·보구 레벨·성배는 서번트 줄의 값을 그대로 쓴다.
- 푸는 합계 수치(`adjustHp`, `adjustAtk`)를 3성·4성·5성 칸으로 나눈다.
- 인연·인연 상한·영의는 도감(collection) 테이블에서 가져온다. 변신하는 서번트는 변신 전 영의도 합친다.
- 같은 서번트의 첫 기는 도감 번호 키에, 그 뒤는 개체 id 키에 넣고 `dupServantMapping`에 적는다.
- 그랜드 여부, 커맨드 코드 장착, 커맨드 카드 강화는 각각 다른 테이블에서 찾는다.
- 기본 옵션은 "잠금한 서번트만, 중복 제외"다. FGA가 어느 옵션을 따를지 정해야 한다.
- 기존 값을 지우지 않고 덧쓴다. 판매하거나 소모한 것의 처리 방식은 설계에서 정해야 한다.

### 권장 구현 순서

1. 아이템·재화, 마술예장, 클래스 보드(변환 없음)
2. 서번트 코인, 커맨드 코드
3. 개념예장
4. 서번트

### 변환 검증 방법

같은 스니핑 데이터를 Chaldea에서 수동으로 가져온 뒤의 `userdata.json`과 FGA가 만든 결과를 칸별로 비교한다. Chaldea가 스스로 바꾸는 `plans`, `itemsLeft`는 비교에서 뺀다.

## 8. 위험과 필요한 안전장치

| 위험 | 대비 |
|---|---|
| 파일 손상으로 사용자의 육성 계획 소실 | 쓰기 전 백업 보관, 쓴 뒤 JSON으로 다시 읽어 확인, 실패하면 백업으로 되돌림 |
| 다른 계정에 덮어씀 | `lastImportId`가 FGA 계정의 친구 코드와 같은 Chaldea 사용자에게만 쓴다. 짝이 없으면 쓰지 않고 수동 가져오기를 안내 |
| Chaldea 사용 중 강제 종료 | Chaldea가 화면에 떠 있으면 미룬다 |
| Chaldea 형식 변경 | `version` 값을 확인하고 모르는 버전이면 쓰지 않는다 |
| Shizuku 꺼짐(재부팅 후) | 쓰기를 건너뛰고 현재의 파일 내보내기로 안내 |
| 저장 위치가 SD 카드 | 기본 경로에 파일이 없으면 쓰지 않는다 |
| F-Droid 판(`cc.narumi.chaldea.fdroid`) | 패키지 이름에 따라 경로가 달라진다. 지원 범위를 정해야 함 |

## 9. 아직 확인하지 않은 것

- 실제 Shizuku 앱을 통한 실행(adb shell과 같은 권한이라 될 것으로 보지만 미확인).
- 서번트·개념예장·커맨드 코드 변환 결과의 일치 여부.
- KR 계정 데이터로 만든 값의 반영(검증 기기의 Chaldea 사용자는 JP였다).
- Android 14 이하 실기기.
- 루트 경로(`su`). shell보다 권한이 높아 될 것으로 보지만 실행해 보지 않았다.

## 10. 개발 전에 소유자가 정할 것

- 반영 범위: 아이템 계열만 먼저 낼지, 서번트까지 한 번에 낼지.
- 실행 시점: 로그인 데이터가 갱신될 때마다 자동으로 할지, 버튼으로 할지.
- 권한: Shizuku만, 루트만, 둘 다.
- Chaldea가 켜져 있을 때의 처리(미루기 또는 종료 후 쓰기).
- Chaldea에 파일 열기 인텐트 PR을 병행할지.

저장소 규칙에 따라, 이 항목들이 정해지고 워크스루가 승인된 뒤에 이슈와 에이전트 프롬프트를 만든다. 이 기능은 저장 파일과 권한을 다루므로 병합 전 검증 에이전트 검토 대상이다.

## 11. 추가 조사 (2026-11-12)

조사: 플래너(기기·데이터), Gemini 3.8 Flash high 읽기 전용 두 건(Chaldea 소스 `ba81b31`, FGA `feat/capture-tracker` `7790af0d`). 소스 줄 번호는 Chaldea 저장소 기준이다.

### 11.1 소유자 결정 (2026-11-12)
- 목표: Chaldea 스니핑 가져오기로 받을 수 있는 데이터 **전부**. 개발과 출시는 단계별(아이템 계열 → 코인·커맨드 코드 → 개념예장 → 서번트).
- 반영 시점(확정): 계정 데이터가 바뀌고 **1분** 동안 조용하면 반영한다. 스크립트 실행 중에는 반영하지 않고, 스크립트가 끝나면 반영한다. "지금 반영" 버튼을 둔다. 로그 수집이 꺼져 있으면 반영하지 않는다.
- Chaldea가 켜져 있을 때(확정):
  - Chaldea가 **완전히 꺼져 있을 때만** 쓴다.
  - 화면에 보이면(맨 위, 화면 분할, 떠 있는 창, 그 상태로 화면이 꺼진 경우 포함) 절대 닫지 않고 기다린다.
  - 뒤에 있는 상태가 **30분**을 넘으면 강제 종료하고 반영한다. 다시 화면에 나오면 시간을 처음부터 잰다. 닫기 직전에 상태를 다시 확인한다. 설정에서 "자동으로 닫지 않음"을 고를 수 있고 기본값은 30분이다.
  - 대기 중이면 FGA 화면에 "Chaldea가 켜져 있어 반영 대기 중"을 보인다. "지금 반영" 버튼은 Chaldea가 켜져 있으면 확인을 받은 뒤에만 닫는다.
  - 쓴 뒤 Chaldea가 그 사이 켜졌는지 다시 확인하고, 켜졌으면 다음에 다시 반영한다.

### 11.2 Shizuku
- 공식 문서상 `Shizuku.newProcess`는 제거 예정이고 **UserService**(별도 프로세스를 shell 또는 root 권한으로 실행)를 써야 한다. 의존성 `dev.rikka.shizuku:api`, `dev.rikka.shizuku:provider`, 매니페스트 `ShizukuProvider`, 권한 요청이 필요하다. 출처: https://github.com/RikkaApps/Shizuku-API
- 파일 읽기·수정·쓰기를 UserService 프로세스에서 하면 Chaldea 파일이 FGA 본체 메모리에 올라오지 않는다. 끝나면 서비스를 내린다.
- 기기: MuMu에 Shizuku 13.6.0 설치됨(실행 중 아님), 태블릿에는 없음. 실제 Shizuku 경유 쓰기는 아직 시험하지 않았다.

### 11.3 Chaldea 실행 상태 판별 (MuMu, shell)
| 상태 | `pidof cc.narumi.chaldea` | `dumpsys activity activities`의 `topResumedActivity` |
|---|---|---|
| 꺼짐 | 없음 | 다른 앱 |
| 화면에 있음 | 있음 | Chaldea |
| 뒤에 있음 | 있음 | 다른 앱 |

- 뒤로 보낸 직후 `userdata.json`, `.bak`, `settings.json`이 함께 다시 저장됐다.

### 11.3.1 "화면에 보임" 판별 (태블릿 Android 16, 2026-11-12, adb shell 읽기 전용)
소유자가 화면을 만들고 플래너가 `dumpsys activity activities`와 `dumpsys window windows`를 읽었다. 다섯 상황 모두 Chaldea 프로세스는 같은 pid로 살아 있었다.

| 상황 | 화면 전체 맨 위 앱(`ResumedActivity`) | Chaldea 작업 `visible=` (`mode`) | Chaldea 창 `isVisible` | 맞는 판정 |
|---|---|---|---|---|
| 1. 분할 화면, FGO 쪽 터치 | FGO | true (multi-window) | true | 보임 |
| 2. 분할 화면, Chaldea 쪽 터치 | Chaldea | true (multi-window) | true | 보임 |
| 3. 떠 있는 창, 뒤의 FGO 터치 | FGO | true (freeform) | true | 보임 |
| 4. 홈으로 보냄 | 런처 | false (fullscreen) | false | 뒤에 있음 |
| 5. Chaldea 전체 화면에서 화면 끔(잠김) | Chaldea | true (`visibleRequested=false`) | false | 보임(기다림) |

- **맨 위 앱만 보면 1·3번을 "뒤에 있음"으로 잘못 판단한다.** 창 `isVisible`만 보면 5번을 잘못 판단한다.
- **작업의 `visible=` 값은 다섯 상황 모두 맞았다.** 규칙: 다음 중 하나라도 참이면 "보임"으로 보고 닫지 않는다. Chaldea 작업 `visible=true`, Chaldea 창 `isVisible=true`, 화면 전체 맨 위 앱이 Chaldea. 셋 다 거짓일 때만 "뒤에 있음"으로 보고 30분을 잰다.
- 화면이 꺼져 있을 때(`dumpsys power`의 `mWakefulness=Asleep`)에는 위 규칙으로 "보임"이면 기다린다. 이미 뒤에 있던 Chaldea는 화면이 꺼져 있어도 시간을 계속 잰다.
- MuMu(Android 15)는 1·4번에 해당하는 상황만 확인했다(§11.3). Shizuku UserService에서도 같은 명령을 shell 권한으로 실행하므로 결과가 같을 것으로 보지만, 시험 빌드에서 다시 확인한다.

### 11.4 Chaldea 동작 (소스)
- **불러오기** (`lib/models/db.dart:174-246`): JSON이 깨졌거나 비었거나 파일이 없으면 깨진 파일을 `userdata.json.<시각>.back`으로 남기고 `.bak`을 읽는다. **올바른 JSON이지만 모양이 틀리면(예: `{}`) 예외 없이 기본 빈 사용자로 읽고, 시작 직후 저장(`db.dart:141-149`)으로 본 파일과 `.bak`을 모두 덮어쓴다.** 그래서 쓰기 전 모양 검증과 FGA 쪽 백업이 필수다.
- **저장** (`db.dart:263-272`, `98-105`, `283-324`, `lib/app/chaldea.dart:139-144`): 임시 파일 없이 본 파일과 `.bak`에 차례로 직접 쓴다. 변경 후 10초, 10초 주기 확인, 뒤로 가거나 숨겨질 때, 시작 직후에 저장한다. 계정 데이터는 이 파일에만 있다(Hive·SQLite 아님).
- **버전** (`lib/models/userdata/userdata.dart:65, 83-106`): `version` 2·3만 이전 처리가 있고, 그 밖의 값은 그대로 읽은 뒤 4로 다시 쓴다. 모르는 키는 다음 저장 때 사라진다. 맵 키는 문자열이어야 한다.
- **자동 덮어쓰기**: 시작 때 서버 복원·동기화는 없다. 24시간마다 `backup/` 폴더에 사본을 남길 뿐이다.
- **KR**: `Region.kr`을 지원한다. 가져오기는 지역으로 막지 않지만 지역 자동 추측에 KR이 없으므로, Chaldea 사용자의 지역이 KR로 설정돼 있어야 한다.
- **짝짓기**: `lastImportId` = `userGame.friendCode` 문자열(`import_https_page.dart:932`). 다르면 수동 가져오기에서 슬라이더 확인이 뜬다(`806-816`).

### 11.5 가져오기 변환 규칙 (`import_https_page.dart:799-937`, `1033-1108`)
- 옵션 기본값: 아이템·서번트·보관실·개념예장·커맨드 코드·클래스 보드 모두 포함, **잠금한 서번트만**, **중복 미허용**.
- 모든 항목이 **덧쓰기 병합**이다. 가져오기에 없는 아이템·서번트·예장은 지우지 않는다.
- `items`: `userItem.num` 그대로. QP→1, 성정석→2, 마나프리즘→3, 레어프리즘→18(`userGame`의 `qp`, `stone`, `mana`, `rarePri`). 서번트 코인은 `userSvtCoin.num`을 그 서번트의 코인 아이템 번호에 넣는다.
- `mysticCodes`: `userEquip`의 `equipId` → `lv`.
- `classBoards`: `userClassBoardSquare`의 보드별 `classBoardUnlockSquareIds` → `unlockedSquares`, `classBoardSquareIds` → `enhancedSquares`(보드 단위 교체).
- `cmdCodes`: 도감 번호 키, `userCommandCodeCollection.status`, 보유 장수 집계. 서번트별 장착(`userSvtCommandCode`)과 카드 강화(`userSvtCommandCard.commandCardParam` ÷ 20).
- `craftEssences`: 도감 번호 키, 도감 `status`, 보유 사본 중 최대 `lv`·최대 `limitCount`. 보유하지 않았지만 획득 이력이 있으면 도감 최대 기록.
- `servants`: 이탈 서번트 제외, 잠금 필터, 같은 서번트는 레벨·스킬 합·획득일·id 순으로 골라 첫 기만(중복 허용 시 2호기부터 개체 id 키 + `dupServantMapping`). 재림 `limitCount`, 스킬 `skillLv1-3`, 어펜드 5칸, 성배 `exceedCount`, 보구 `treasureDeviceLv1`, 인연·상한·영의는 도감 행, 그랜드는 `userSvtGrand`에 개체가 있는지, `favorite = true`. 푸는 `adjustHp`/`adjustAtk`에서 3성·4성·5성 칸으로 나눈다(공식은 `912-917`).
- 사용자 필드: `lastImportId`, `isGirl`(`genderType == 2`). 이름과 지역은 바꾸지 않는다.

### 11.6 FGA 쪽 사실
- 계정 상태는 다른 플레이어 표 3개만 빼고 **모든 본인 표를 원문으로** 보관하고 응답마다 갱신한다(`AccountState.kt`). 위 변환에 필요한 표는 모두 있다. 친구 코드는 `userGame.friendCode`에 있다(KR·JP).
- 반영 예약 자리: `CaptureTracker.save()`(응답 처리 후 2초 묶음 저장, AP 위젯 갱신과 같은 자리).
- 도감 번호: `GameNames`가 이미 받는 Atlas `basic_servant`, `basic_equip`(필요 시 `basic_command_code`)의 `collectionNo`를 쓴다. Chaldea 도감 번호와 같다.
- **서번트 코인 번호: Atlas `nice_item`(FGA가 이미 받음)의 `type == svtCoin` 항목에서 `value`가 서번트 id다.** JP 474개 모두 확인했고 `id = value × 100 + 1`이다. 70MB `nice_servant`는 필요 없다.
- Shizuku 연동은 없다. 루트는 `RootCaInjector`의 `su` 실행기를 참고할 수 있다.

### 11.7 쓰기 규칙 보완
- 본 파일과 `.bak`을 **둘 다** 같은 내용으로 쓴다.
- 쓰기 전에 FGA가 원본을 따로 백업하고, 쓴 뒤 다시 읽어 JSON과 모양(사용자 목록, 대상 사용자, 주요 칸)을 확인한다. 실패하면 백업으로 되돌린다.
- 기존 파일에 내용만 덮어써 소유자·권한을 유지한다. 사용자 `id`(UUID), 다른 사용자, 계획, 모르는 칸은 그대로 둔다.
- Chaldea "외부 저장소 사용" 설정이면 경로가 달라진다. 기본 경로에 파일이 없으면 쓰지 않는다.

### 11.8 Shizuku 시험 빌드 (MuMu Android 15, 2026-11-12)
- 브랜치 `spike/shizuku-userservice`, 커밋 `465ccec3`. 병합하지 않는다. debug 빌드(`io.github.fate_grand_automata.test`)에만 Shizuku 13.1.5, `ShizukuProvider`, UserService, 시험 화면을 넣었다. 구현: Gemini 3.8 Flash high(Codex 사용량 한도).
- Shizuku 13.6.0을 adb로 shell 권한으로 켰다(`libshizuku.so`). 첫 권한 요청에서는 결과가 오지 않았고, 다시 요청해 소유자가 허용한 뒤 허용됐다.
- 시험 전 `userdata.json`과 `.bak`을 PC에 백업했다(SHA-256 앞자리 `da0b6a9e`, 831,894바이트).

| 확인 | 결과 |
|---|---|
| UserService 실행 | uid 2000(shell)으로 실행됨. 연결 해제 시 `destroy()`로 프로세스가 끝남 |
| Chaldea 꺼짐 판별 | `running=false` |
| Chaldea 뒤에 있음 판별 | `running=true`, 맨 위 아님, 작업 `visible=false` (§11.3.1 규칙과 같음) |
| 파일 읽기 | 두 파일 모두 크기·해시가 PC 백업과 같음. 소유자 uid 10061(`u0_a61`), gid 1078(`ext_data_rw`), 권한 0660. `version` 4, 사용자 1명(region `jp`) |
| 바인더 전달 | `ParcelFileDescriptor` 파이프로 831,894바이트 전체, 500KB 조각 읽기도 성공 |
| 같은 내용 다시 쓰기 | Chaldea가 꺼진 상태에서 본 파일과 `.bak`을 제자리에 다시 씀. 해시·크기·소유자·그룹·권한이 그대로, 수정 시각만 바뀜 |
| 쓴 뒤 Chaldea 실행 | 정상으로 열림. 시작 직후 저장 후에도 해시가 같고 `.back` 손상 파일이 생기지 않음 |
| 강제 종료 | 확인 창을 거쳐 `am force-stop` 성공, 프로세스 없어짐 |

- 결론: Shizuku(shell)로 Chaldea 파일 읽기·제자리 쓰기·상태 판별·강제 종료가 모두 된다. 내용을 바꾼 쓰기는 이번 시험에서 하지 않았다(바뀐 값의 반영은 §6에서 adb로 확인).
- 시험 후 MuMu에는 Shizuku 서버와 시험 앱이 남아 있다. Chaldea 데이터는 시험 전과 같다.

### 11.9 남은 확인
- 태블릿(Android 16)에서 Shizuku로 같은 시험. 태블릿에는 Shizuku가 설치돼 있지 않다.
- 첫 권한 요청에서 결과가 오지 않은 원인(MuMu에서 한 번 발생). 실제 기능은 권한 결과를 다시 확인하는 흐름이 필요하다.
- UserService 프로세스가 떠 있는 동안의 메모리.
- KR 사용자에게 쓴 값의 반영.
- 단계별 변환 결과를 같은 데이터의 Chaldea 수동 가져오기 결과와 칸별 비교.
