# FGA(mod)

[한국어](README.md) | [English](README.en.md) | [日本語](README.ja.md)

**KR(한국)·JP(일본) 서버에 한정해 테스트했습니다. 다른 서버의 동작은 확인하지 않았습니다.**

[Fate/Grand Automata(FGA)](https://github.com/Fate-Grand-Automata/FGA)에 기능을 추가한 수정판입니다. 원본 공식 배포판과 별도로 관리합니다.

## 다운로드·업데이트

- [최신 APK](https://github.com/puruuun/FGA-Modified/releases/latest)
- [Obtainium에 추가](https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fapp%2F%257B%2522id%2522%253A%2522io.github.fate_grand_automata.cardcount%2522%252C%2522url%2522%253A%2522https%253A%252F%252Fgithub.com%252Fpuruuun%252FFGA-Modified%2522%252C%2522author%2522%253A%2522puruuun%2522%252C%2522name%2522%253A%2522FGA%2528mod%2529%2522%252C%2522additionalSettings%2522%253A%257B%2522includePrereleases%2522%253Afalse%257D%257D)
- [상세 안내](wiki/scripts/Modified-Features-KR.md#installation)

Android 7 이상 ARM 기기(arm64-v8a / armeabi-v7a)를 지원합니다. 기존 수정판은 업데이트 설치할 수 있습니다.

## 추가·수정된 기능

| 기능 | 간단한 설명 |
|---|---|
| [카드 조건으로 커맨드 시작](wiki/scripts/Modified-Features-KR.md#card-start) | 서번트별 카드 수에 따라 두 설정 중 하나를 실행합니다. |
| [첫 턴 셔플](wiki/scripts/Modified-Features-KR.md#first-turn-shuffle) | 시작 조건이 부족하면 셔플 후 다시 검사합니다. |
| [서포트 어펜드 스킬](wiki/scripts/Modified-Features-KR.md#support-append) | 지정한 어펜드 슬롯의 10레벨 여부를 확인합니다. |
| [서포트 스코어](wiki/scripts/Modified-Features-KR.md#support-score) | 클래스 +62·그랜드 +16 조건으로 선별합니다. |
| [서포트 보구 레벨](wiki/scripts/Modified-Features-KR.md#support-np) | 설정한 최소 보구 레벨 이상을 선택합니다. |
| [공격 순서 직접 지정](wiki/scripts/Modified-Features-KR.md#direct-cards) | 일반 카드·보구 세 장의 순서를 지정합니다. |
| [티포트·스톰포드](wiki/scripts/Modified-Features-KR.md#quest-items) | 퀘스트 시작 전에 지정한 ON/OFF 상태로 맞춥니다. |
| [인연포인트·MAX 정지](wiki/scripts/Modified-Features-KR.md#bond-tracking) | 결과창 캐릭터 1~5별 획득량과 MAX 정지 대상을 설정합니다. |

설정 방법·동작 규칙·검증 범위는 [전체 상세 안내](wiki/scripts/Modified-Features-KR.md)에서 확인하세요.

## 원본 및 라이선스

[원본 저장소](https://github.com/Fate-Grand-Automata/FGA) · [원본 사용 안내](https://github.com/Fate-Grand-Automata/FGA/wiki) · [MIT 라이선스](LICENSE)

수정 기준: 공식 FGA v3059 ([기준 커밋](https://github.com/Fate-Grand-Automata/FGA/commit/e138a15273dee9cac342833ee2d8aee93d8ae700)). 원본 개발팀의 저작권 고지를 유지합니다.
