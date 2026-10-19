# FGA 1.4.0-beta.10 검증 보고서

0007 §18 D-40~D-43 구현. 전체 단위 테스트·이미지 기기 QA·Main CI 성공 후 APK 검증과 BlueStacks 업데이트 설치를 완료했다.
버전 `1.4.0-beta.10` / `1004010`, 패키지 `io.github.fate_grand_automata.beta`. 안정판 `1.2.1` / `3083` 유지.

## 원인 및 변경

소유자 beta.9 로그(KR)는 목록 끝 감지→決定→취소→閉じる→공방→MENU까지 통과한 뒤, 소환 화면이 긴 검은 로딩 뒤에 나타나 3초 확인에 실패해 `bannerReturn reason=summon`으로 정지했다. 같은 로그의 `tap-rejected` 16건은 대부분 `lock=Locked` 칸이었고, 스와이프 거리로 계산한 칸 식별이 실제 스크롤량과 달라 다른 칸을 같은 칸으로 착각한 것이다.

| 항목 | 변경 |
|---|---|
| D-40 소환 로딩 | 소환 아이콘을 한 번 누른 뒤 **고정 5초** 대기, 이어서 최대 **15초**(100ms × 150) 동안 소환 화면(왼쪽 화살표) 확인. 이 구간에서 재탭 없음. 15초 안에 없으면 `summon` 사유로 정지. |
| D-41 경고문 | 굵은 자동 판매 경고를 `bodySmall`→`bodyLarge`, 색 `colorScheme.error`(빨강). "예: ★1~★3…" 보조 문장과 영어·한국어 문자열 `fp_sell_filter_hint`, 위키의 같은 문장 삭제. |
| D-42 FP 소환 대기 | 처음 10회/100회·매일 무료·계속 소환 확인 **0.3초**, 연출 **3초**, 반복 **0.5초**. 자동 판매 ON/OFF 구분을 없애고 상수 하나로 통일(`LEGACY_*` 제거). 판매·배너 복귀 구간의 늘린 대기는 유지. |
| D-43 거부 판정 | `tap-rejected`는 행이 움직이지 않았고(±12px) 직전 쪽에서 누른 칸과 같은 화면 위치일 때만 추정. 스크롤을 건너는 구간에서는 추정하지 않고 선택 여부만 본다(선택 안 됨이면 일반 칸으로 다시 판정). 잠금·제외 칸은 어떤 경우에도 거부 대상이 아니다. 선택 수 = 누른 수 − 확정 거부 수(종료 메시지에 표시). |

## 로컬 테스트

JDK 21, ANDROID_HOME 환경변수만 사용했다.

```text
./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test
BUILD SUCCESSFUL
```

app 177 + prefs 24 + scripts 298 = **499개 통과**, 실패·오류·skip 0. beta.9(494개)보다 5개 증가했다.

- 신규: 스크롤 불일치에서 거부 없음+이후 미선택 일반 칸 재탭, 정지 끝에서 거부된 칸 재탭 없음, 잠금·제외 칸 거부 없음(progress 3개). 소환 로딩 5초 고정 대기·15초 확인·재탭 없음, 15초 뒤 `summon` 정지(banner 2개).
- 수정: 소환 진입점 대기 순서를 자동 판매 ON/OFF 모두 `[0.3, 0.5, 0.3, 3.0]`으로 갱신.

## 전체 이미지 기기 QA

`py -3 tools/device-qa/run.py --build --serial 127.0.0.1:5555`, 결과 `build\device-qa\20261019-150610`. 전 제품군 PASS, beta.9와 동일.

| 제품군 | 통과 | 기존 skip |
|---|---:|---:|
| support | 185 | 3 |
| bond | 20 | 0 |
| questItems | 5 | 8 |
| noblePhantasm | 18 | 0 |
| fpSell | 736 | 0 |
| 전체 | **964** | **11** |

실패 0. 별도 테스트 앱에서 저장 이미지만 판독했으며 게임 입력이나 FGA 스크립트 실행을 하지 않았다.

## 전달

- 코드 커밋 `d7071d6894ec1828c75aed321afae09c3d347c26`: author/committer puruuun, AI trailer 없이 main 푸시.
- [Main CI](https://github.com/puruuun/FGA-Modified/actions/runs/39207333196): `success`. 성공 확인 뒤 `:app:assembleBeta` 실행.
- aapt 패키지/버전 `io.github.fate_grand_automata.beta` / `1004010` / `1.4.0-beta.10` 일치. ARM64 + ARMv7, x86 없음.
- APK: `C:\Users\rl\Documents\Codex\FGA-Modified-builds\FGA-beta-1.4.0-beta.10-arm.apk`
- SHA-256: `741e5351cba194aa00b21d7be3d9d281d9d452c52c02f1f2902d18ea1b43cea9`. 같은 폴더에 `.apk.sha256` 저장.
- 지정 SDK adb에 항상 `-s 127.0.0.1:5555`. **PIXEL 2 XL** 확인 후 `install --no-streaming -r` 성공.
- 설치 버전 `1.4.0-beta.10` / `1004010 minSdk=24 targetSdk=36`. firstInstallTime `2026-10-15 23:24:59` 보존. lastUpdateTime `2026-10-19 15:05:02`.

다른 기기·게임 앱 시작/강제 종료·adb 서버 제어·실게임 조작은 하지 않았다. 소유자는 시험 모드에서 소환 아이콘 뒤 긴 로딩을 넘어 원래 배너 복귀→재소환→두 번째 가득 참 종료까지 확인해야 한다.
