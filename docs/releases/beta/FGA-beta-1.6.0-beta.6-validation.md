# FGA-beta-1.6.0-beta.6 검증

- 베타 **1.6.0-beta.6 / 1006006**, 정규판 **1.5.0 / 3086** 유지.
- 설계 0009 §13 D-20·D-21·D-22 수정·D-23. 마지막 소유자 결정대로 **앵커 판독을 먼저** 합니다.

## 변경

- Free Quest 표시를 넓은 띠에서 찾고 AP·이름·탭 위치를 그 표시에서 계산합니다. 기존 고정 슬롯 판독 API는 기존 QA 호환용으로 남기고, 주간 실행과 전투 후 복귀 판별은 앵커를 사용합니다.
- **앵커 판독 → 실패할 때만 목록 초기화 1회 → 재판독 → 여전히 실패하면 판 닫기·지점 중앙 이동·지도 재맞추기 후 재시도 1회**. 첫 판독이 Match면 드래그하지 않습니다. 매 단계 실제 위치를 새로 찾으며 **Match가 아니면 퀘스트를 누르지 않습니다**.
- 초기화는 이름/진행 영역에서 위→아래로 720p 기준100px 움직이는 관성 없는 scroll입니다. 보상·정보 버튼을 피합니다. 잘린 표시의 아래 절반은 복구 드래그 위치를 잡는 데만 쓰며 퀘스트 탭을 허용하지 않습니다. 안전한 표시를 찾지 못하면 임의 드래그를 하지 않습니다.
- 중앙 복구도 기존 최대4회 스크롤·재맞추기 상한과 안전 영역 검사를 지킵니다. 지도 경계 때문에 중앙에 도달하지 못하면 건너뜁니다.
- 장 배너 탭 후 / 터미널 탭 후 화면 판별 전 대기를 각각 **2초 → 3초**로 변경했습니다. 다른 전투 대기는 유지합니다.
- 주간 패널이 열려 있으면 제한(반복·재료·CE) UI만 숨기고 닫으면 복원합니다. AP·아이템·인연과 저장 값은 유지합니다.
- BuildConfig의 beta에서만 non-Match 프레임 PNG를 FGA 저장소 `diagnostics/weeklyRoute/<실행번호>/`에 저장하고 WeeklyRoute 로그에 경로를 남깁니다. 실행당 **최대8회**(저장 실패도 상한에 포함). 정규판·디버그에서는 저장하지 않습니다. 저장 실패는 실행을 중단하지 않습니다.

## 화면과 앵커

33·34의 BlueStacks 창 2000×1147에서 게임 영역 `(0,48)–(1950,1144)`만 잘라 기존 규칙과 같이1280×720으로 정규화했습니다. 기존 `En/free_quest_label.png`를 재사용하며 새 템플릿은 없습니다.

| 화면 | Free Quest 좌상단 (720p) | AP | 이름 | 판정 |
|---|---|---:|---|---|
| 33 | 652,155 | 5 | 骸彷徨う教会 | Match |
| 34 | 652,143 | 5 | 骸彷徨う教会 | Match |

앵커 점수는 오프라인 OpenCV에서33=0.9965,34=0.9862입니다. 앵커에서 AP 오프셋은 `(167,50)`, 이름은 `(126,-19)`, 탭은 `(228,37)`(720p). 두 화면의12px 세로 이동에 맞춰 모두 이동합니다.

## 단위 테스트·컴파일

`./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test :app:compileCiKotlin :app:compileBetaKotlin` 성공.

**599개 통과, 실패0**(app184, prefs26, scripts389, libautomata0). 새7개는 앵커 오프셋/이동/경계, 드래그 경로, 첫 Match에서 드래그 없음, 실패 뒤 초기화 순서, 마지막 실패 종료, 안전 경로 없음, 3초 대기·중앙 이동을 검사합니다. 파일별 작은 편집 후 베타 컴파일을 확인했습니다.

## 전체 기기 QA

`py -3 tools/device-qa/run.py --build --serial 127.0.0.1:5555` 성공. 결과 `build\device-qa\20261021-022048`.

| suite | 통과 | 실패 | 기존 skip |
|---|---:|---:|---:|
| support | 185 | 0 | 3 |
| bond | 20 | 0 | 0 |
| questItems | 5 | 0 | 8 |
| noblePhantasm | 18 | 0 | 0 |
| fpSell | 736 | 0 | 0 |
| skillLevel | 83 | 0 | 0 |
| weeklyRoute | 101 | 0 | 0 |
| 합계 | **1148** | **0** | **11** |

기존 **1141개 통과**, 새 **7개 통과**. 기존94개 주간 케이스와 기대값은 변경하지 않았습니다. 아래는 새 앵커 검사이며 기존01·10·30과33·34 모두 생산 앵커 판독기를 통과합니다. 미션 이름과 잘못된 이름은 거부합니다.

| 케이스 | 읽은 AP | 읽은 이름 | Match |
|---|---:|---|---|
| JP-01-anchored | 5 | 骸彷徨う教会 | True |
| JP-10-anchored-free | 21 | 赦免の城 | True |
| KR-30-anchored | 19 | 피가끓어오르는광연 | True |
| JP-33-xe-anchored | 5 | 骸彷徨う教会 | True |
| JP-34-xe-shifted-anchored | 5 | 骸彷徨う教会 | True |
| JP-10-anchored-reject-mission | 21 | 赦免の城 | False |
| JP-33-anchored-reject-name | 5 | 骸彷徨う教会 | False |

## 커밋·CI

- 제품 커밋 `5be8ee44`, author/committer puruuun, AI 트레일러 없음.
- [Main CI 성공](https://github.com/puruuun/FGA-Modified/actions/runs/39465326710). 성공 후 베타 APK를 빌드했습니다.
- 태그·GitHub Release 없음.

## APK·설치

- `C:\Users\rl\Documents\Codex\FGA-Modified-builds\FGA-beta-1.6.0-beta.6-arm.apk` + `.sha256`
- SHA-256 **`d6892016dc59a1ee112b32e5ea3c1cccf19bdcf2115b0d0699272faadb2759cb`**
- aapt: `io.github.fate_grand_automata.beta`, `1006006`, `1.6.0-beta.6`, `FGA(beta)`, armeabi-v7a + arm64-v8a.
- apksigner 성공, 기존 저장소 debug 인증서 SHA-256 `5acce47e71373dcfe3a6f6400ed3b3f687bd454aab91c7562c8327a3a083ac9d`.
- BlueStacks **127.0.0.1:5555 / PIXEL 2 XL**에 베타만 `install --no-streaming -r` 성공. 설치 후1006006/1.6.0-beta.6 확인.
- 최초 설치 시간 **2026-10-15 23:24:59** 보존. 다른 기기와 게임은 조작하지 않았고 베타 앱도 실행하지 않았습니다.

## 확인 범위

소유자가 beta.5에서 X-C와 터미널→세프템 경로 성공을 확인했습니다. 이번 앵커 판독은 저장 화면으로 검증했습니다. 실제 목록 드래그, 실패 후 중앙 이동, 진단 PNG 저장과 런처 제한 숨김의 실사용은 소유자 확인이 남아 있습니다. 와이드·다른 비율의 실제 퀘스트 판은 추가 자료가 필요합니다.
