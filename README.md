# FGA(mod)

[한국어](README.md) | [English](README.en.md) | [日本語](README.ja.md)

**KR(한국)·JP(일본) 서버에 한정해 테스트했습니다. 다른 서버의 동작은 확인하지 않았습니다.**

[Fate/Grand Automata(FGA)](https://github.com/Fate-Grand-Automata/FGA)에 기능을 추가한 수정판입니다. 원본 공식 배포판과 별도로 관리합니다.

## 다운로드·업데이트

**정규판 1.2.1** — 서포트 검색·이미지 서버 자동 선택, 판독 개선, 서포트 판독 진단 저장(기본 꺼짐), +16 조건 JP 전용을 포함합니다. [변경 사항](wiki/releases/1.2.1-KR.md).

| 배포판 | APK | Obtainium |
|---|---|---|
| **정규판 FGA(mod)** | [최신 정규판](https://github.com/puruuun/FGA-Modified/releases/latest) | [정규판 추가](https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fapp%2F%257B%2522id%2522%253A%2522io.github.fate_grand_automata.cardcount%2522%252C%2522url%2522%253A%2522https%253A%252F%252Fgithub.com%252Fpuruuun%252FFGA-Modified%2522%252C%2522author%2522%253A%2522puruuun%2522%252C%2522name%2522%253A%2522FGA%2528mod%2529%2522%252C%2522additionalSettings%2522%253A%2522%257B%255C%2522includePrereleases%255C%2522%253Afalse%252C%255C%2522apkFilterRegEx%255C%2522%253A%255C%2522%255EFGA-mod-.%252A-arm%255C%255C%255C%255C.apk%2524%255C%2522%257D%2522%257D) |

Obtainium은 정규판만 제공합니다. [정규판 설정 파일](obtainium.json)을 가져와 등록할 수 있습니다. 개발 베타 APK는 컴퓨터에 저장하거나 블루스택에 직접 설치합니다.

설치 출처: GitHub Releases와 Obtainium 외의 곳에서 받은 APK는 설치하지 마세요.

[기존 기능 상세 안내](wiki/scripts/Modified-Features-KR.md#installation) · [서번트 번호 이미지 생성 안내](wiki/scripts/Servant-Number-Portraits-KR.md)

Android 7 이상 ARM 기기(arm64-v8a / armeabi-v7a)를 지원합니다. 기존 수정판은 업데이트 설치할 수 있습니다.

## 추가·수정된 기능

| 기능 | 간단한 설명 |
|---|---|
| [FP 소환 자동 판매](wiki/scripts/FP-Auto-Sell-KR.md) | 서번트칸이 가득 차면 판매 화면에 보이는 잠금 아닌 카드를 선택·판매하고 FP 배너로 복귀합니다. 시작 전 게임 필터 확인 필수. 기본 꺼짐, 시험 모드 기본 켜짐. |
| [서번트 번호 이미지 생성](wiki/scripts/Servant-Number-Portraits-KR.md) | 도감 번호로 재림·영의 서포트 이미지를 생성하고 번호·이름 폴더에 저장합니다. |
| [원본 FGA 데이터 가져오기](wiki/scripts/Original-FGA-Import-KR.md) | 스크립트·서포트 이미지를 골라 일괄로 가져옵니다. |
| [Chaldea 링크로 커맨드 입력](wiki/scripts/Modified-Features-KR.md#chaldea-import) | 공유 링크의 전투 행동을 커맨드로 변환해 입력합니다. |
| [카드 조건으로 커맨드 시작](wiki/scripts/Modified-Features-KR.md#card-start) | 서번트별 카드 수에 따라 두 설정 중 하나를 실행합니다. |
| [첫 턴 셔플](wiki/scripts/Modified-Features-KR.md#first-turn-shuffle) | 시작 조건이 부족하면 셔플 후 다시 검사합니다. |
| [서포트 어펜드 스킬](wiki/scripts/Modified-Features-KR.md#support-append) | 지정한 어펜드 슬롯의 10레벨 여부를 확인합니다. |
| [서포트 스코어](wiki/scripts/Modified-Features-KR.md#support-score) | 클래스 +62·그랜드 +16 조건으로 선별합니다. |
| [서포트 보구 레벨](wiki/scripts/Modified-Features-KR.md#support-np) | 설정한 최소 보구 레벨 이상을 선택합니다. |
| [재림 구분 (서번트 이름 + 보구)](wiki/scripts/Ascension-Select-KR.md) | 서포트 이미지 메이커로 만든 서번트 이름 + 보구 그림으로 재림이 다른 서번트를 구분해 고릅니다. |
| [공격 순서 직접 지정](wiki/scripts/Modified-Features-KR.md#direct-cards) | 일반 카드·보구 세 장의 순서를 지정합니다. |
| [티포트·스톰포드](wiki/scripts/Modified-Features-KR.md#quest-items) | 사용 설정을 켜면 퀘스트 시작 전 ON/OFF를 맞춥니다. |
| [인연포인트·MAX 정지](wiki/scripts/Modified-Features-KR.md#bond-tracking) | 결과창 캐릭터 1~5별 획득량과 MAX 정지 예외를 설정합니다. |

설정 방법·동작 규칙·검증 범위는 [전체 상세 안내](wiki/scripts/Modified-Features-KR.md)에서 확인하세요.

## 원본 및 라이선스

[원본 저장소](https://github.com/Fate-Grand-Automata/FGA) · [원본 사용 안내](https://github.com/Fate-Grand-Automata/FGA/wiki) · [MIT 라이선스](LICENSE)

수정 기준: 공식 FGA v3059 ([기준 커밋](https://github.com/Fate-Grand-Automata/FGA/commit/e138a15273dee9cac342833ee2d8aee93d8ae700)). 원본 개발팀의 저작권 고지를 유지합니다.
