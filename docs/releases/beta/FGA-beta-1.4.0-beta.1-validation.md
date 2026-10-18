# FGA 1.4.0-beta.1 검증 보고서

상태: FP 자동 판매 구현, 전체 단위 테스트, 전체 기기 QA 통과. 아래 "남은 일"은 커밋·푸시·CI·APK 설치 뒤에 기록합니다.

- 설계: `docs/design/0007-fp-gacha-auto-sell.md`, §1~§7 및 §8(D-10), §9(D-1 변경, D-11~D-17) 전부 반영.
- 베타: `1.4.0-beta.1` / `1004001`. 정규판 `1.2.1` / `3083` 유지.
- §9는 원래 `1.4.0-beta.2`로 계획했지만, beta.1이 빌드되지 않았으므로 **모두 beta.1에 포함**했습니다.
- JDK: `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`. `ANDROID_HOME`은 환경변수로만 지정했습니다.

## 구현 요약

- D-1 최종 기본값: 1★·2★ 서번트 켜짐, 3★·4★ 서번트 꺼짐, 4★·5★ 경험치 카드 꺼짐. 자동 판매 꺼짐, NEW!만 켜짐, 시험 모드 꺼짐.
- D-2, D-12: 자물쇠, `잠금 중`/`ロック中`, `選択不可`, `グランドサーヴァント`, `PARTY`, `Servant`·`Exp. UP`가 아닌 라벨은 시험 모드에서도 선택하지 않습니다(`FpSellPolicy.select`, `FpSellReader.readCard`).
- D-10: 시험 모드는 잠금 템플릿이 없거나 판별이 불확실한 칸을 선택하고 "잠금 판별 안 됨 N칸"으로 따로 셉니다. 확실히 잠긴 칸은 선택하지 않습니다.
- D-11: 가득 참 경고는 첫 줄 띠 전체에서 서버별 서번트 단어(KR `서번트칸`, JP `サーヴァント枠`)를 찾습니다. 없으면 `InventoryFullNotServant`로 멈춥니다.
- D-13: 실제 판매 허용. 아래 기기 QA에서 잠금·선택 불가·PARTY 칸이 한 칸도 후보가 되지 않았으므로 `FpSellPolicy.REAL_SELL_VALIDATED = true`로 열었습니다. 실제 판매는 잠금 템플릿이 있고 KR 또는 JP일 때만 열리며, 시험 모드를 켜면 항상 선택만 합니다.
- D-14: 칸 오른쪽 위 배지(COST/Lv.)는 판별에 쓰지 않습니다.
- D-15: 점 줄은 행 우선 순번으로 계산합니다. 활성 점은 활성 템플릿 최고 점수가 2위보다 0.05 이상 높을 때만 인정합니다. 점 순번이 없거나 불안정하면 배너 제목 이미지 비교만으로 찾고, 최대 60번 넘긴 뒤 `BannerNotFound`로 멈춥니다. JP 점 템플릿은 아직 없어 JP는 제목 이미지 경로로 복귀합니다.
- D-16: JP 버튼 문구 템플릿(`決定`, `くべる`, `閉じる`, `サーヴァント` 탭, `MENU`, `召喚`, `ダ・ヴィンチ工房`)을 `assets/Jp`에 추가했습니다. KR은 기존 템플릿을 그대로 씁니다.
- D-17: 아이콘 크기 전환은 최대 2번까지만 누릅니다(5→6→7). 7열 판별은 라벨 또는 NEW! 태그가 같은 행에 6칸 이상 있을 때 참입니다.
- 판매 화면이 개념예장 탭으로 열리면 서번트 탭을 먼저 누릅니다.

## 템플릿과 자료

- 잘라 낸 방식: `tools/device-qa/cut_fp_templates.py`. 소유자 캡처(1988×1140)를 게임 영역 1280×720으로 정규화한 참조(`docs/reference-images/{Kr,Jp}/fp-sell/`)에서 720p 좌표 그대로 자릅니다. 기존 KR 템플릿은 `tools/device-qa/extract_fp_templates.py`로 만들었습니다.
- 공용(`assets/En`): 자물쇠(`fp_sell_lock.png`), 카드 그래픽(라벨·별·NEW!·선택 테두리·점 등). JP 카드 라벨 중 `Exp. UP`만 JP 전용(`assets/Jp/fp_sell_exp.png`)입니다.
- JP 전용(`assets/Jp`): 제목, 서번트 탭, 닫기, 売却, 決定, くべる, 閉じる(완료), 공방, MENU, 召喚, 서번트 단어, 잠금·선택 불가·그랜드 서번트·PARTY 글자.
- 소유자 자료: KR 15~28, 38(잠금 화면, 새 자료), JP 29~43(`docs/reference-images/Jp/fp-sell/`).
- 아직 받지 못한 자료(§9 "더 받을 것"): KR 개념예장칸 가득 참 화면, JP 칸을 선택한 상태 화면, JP 점 줄이 두 줄인 화면의 점 템플릿.

## 단위 테스트

`./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test :app:compileCiKotlin` 통과.

- `FpAutoSellTest` 12개: 칸 정책 표(잠금·제외·NEW·종류·dry run 조합 전체), 비활성 종류 제외, 실제 판매 게이트, dry run 강제, 배너 점 순번 차이·행 우선 번호, 7열 토글 횟수, 서번트 경고 판정, 가득 참 반복·비서번트 정지, 자동 판매 끔(기존 동작), dry run 확인 버튼 미호출.

## 기기 QA (emulator-5554, BlueStacks)

`py -3 tools/device-qa/run.py --build --serial emulator-5554` 전체 실행 결과:

| 스위트 | 결과 | 비고 |
|---|---|---|
| support | 185 통과, skip 3 | 기존 값과 동일 |
| bond | 20 통과 | 기존 값과 동일 |
| questItems | 5 통과, skip 8 | 기존 값과 동일 |
| noblePhantasm | 18 통과 | 기존 값과 동일 |
| fpSell | 142 통과, 실패 0 | 신규. 기존 케이스는 수정하지 않았고, 잠금 값만 실제 화면 판독으로 갱신 |

fpSell 구성: 레이아웃(KR 18·19·20·27·28, JP 29·37, KR 38, JP 42·43 개념예장 탭), 서번트 경고(KR 17, JP 40 참, JP 39 거짓), 점 줄(KR 15·25·26), 칸 판독(KR 18·20·27·28, JP 29 비잠금 칸 보호, 잠금 칸 KR 38 20칸, JP 37 잠금·그랜드·선택 불가·PARTY 칸). 모든 잠금 칸에서 `candidateReal`, `candidateDryAll`이 false입니다.

한계: 레이아웃 판별은 오버레이로 라벨이 가려진 JP 37에서 NEW! 태그로 보완했습니다. 기기 QA는 저장 화면 판독이며, 게임을 조작하는 실전 QA는 하지 않았습니다.
