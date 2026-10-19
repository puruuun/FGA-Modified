# FGA 1.4.2-beta.1 검증 보고서

상태: **자동 검증·APK 확인·BlueStacks 업데이트 설치·전체 기기 QA 완료.**
버전 `1.4.2-beta.1` / `1004201`, 패키지 `io.github.fate_grand_automata.beta`. 정규 버전은 1.4.1 / 3085 그대로다.

## 수정 범위

제품 코드 `5efdc723e42207b694036c16f20f9561576d3acf`.

- 저장소 루트 `LICENSE`(Jerry Ho, puruuun 두 저작권 표기가 있는 MIT 라이선스)를 빌드 때 APK 자산에 복사한다. `app/build.gradle.kts`의 `copyLicenseAsset` 작업이 모든 변형의 생성 자산 폴더에 넣으므로 손으로 복사한 사본이 없고 항상 루트 파일과 같다.
- 더 많은 옵션 → 고급 맨 아래에 **라이선스**(영어 License) 항목을 추가했다. 누르면 "FGA(mod)는 Fate/Grand Automata를 기반으로 하며 MIT 라이선스로 배포됩니다." 머리글과 번들된 LICENSE 전문을 스크롤 화면(`LicenseScreen`)으로 보여 준다.
- 문자열 `p_license`, `license_header`를 `values/localized.xml`(영어)과 `values-ko`(한국어)에 추가했다.
- 한국어 설명서 `wiki/scripts/Modified-Features-KR.md`에 라이선스를 볼 수 있는 위치를 적었다.

## 테스트

```text
./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test
BUILD SUCCESSFUL
```

**509개 통과**(app 180 + prefs 24 + scripts 305), 실패·오류 0. 새 `LicenseAssetTest`가 번들 자산이 생성되는지, 두 저작권 줄이 모두 있는지, 루트 `LICENSE`와 내용이 같은지 검사한다. `:app:compileDebugKotlin`도 성공했다.

## 전체 기기 QA

명령: `py -3 tools/device-qa/run.py --build --serial 127.0.0.1:5555`.
결과: `build\device-qa\20261019-174338`. 고정 화면 오프라인 판독만 수행했고 게임을 조작하지 않았다.

| suite | 통과 | 실패 | skip |
|---|---:|---:|---:|
| support | 185 | 0 | 3 |
| bond | 20 | 0 | 0 |
| questItems | 5 | 0 | 8 |
| noblePhantasm | 18 | 0 | 0 |
| fpSell | 736 | 0 | 0 |
| 합계 | **964** | **0** | **11** |

1.4.1-beta.1과 같은 수치이며 이번 변경은 자동화 로직을 건드리지 않았다.

## 커밋과 CI

- 제품 코드·버전 커밋 `5efdc723e42207b694036c16f20f9561576d3acf`.
- [Main CI 성공](https://github.com/puruuun/FGA-Modified/actions/runs/39224034895) (`39224034895`).

## APK·설치

- APK `C:\Users\rl\Documents\Codex\FGA-Modified-builds\FGA-beta-1.4.2-beta.1-arm.apk` 및 같은 경로의 `.sha256`. `./gradlew :app:assembleBeta`로 빌드.
- SHA-256 `20484f0d7378566a09360b116d160b08c74f6b8ca7283c83e90c3ddd622a8efb`.
- aapt: `io.github.fate_grand_automata.beta`, `1004201`, `1.4.2-beta.1`, 앱 이름 `FGA(beta)`, ABI `arm64-v8a` + `armeabi-v7a`.
- APK 안 `assets/LICENSE`를 꺼내 확인: `Copyright (c) 2017 Jerry Ho 何建霖`, `Copyright (c) 2026 puruuun` 두 줄이 있고 루트 `LICENSE`와 바이트 단위로 같다.
- BlueStacks `127.0.0.1:5555`, 모델 `PIXEL 2 XL` 확인 후 `install -r`: **Success**.
- 설치 후 `versionName=1.4.2-beta.1`, `versionCode=1004201`. `firstInstallTime=2026-10-15 23:24:59` 보존.
- 다른 기기(`HA29GCWH`)는 건드리지 않았고 adb 서버를 재시작하지 않았으며 게임을 조작하지 않았다. 라이선스 화면을 기기에서 직접 열어 본 확인은 하지 않았다(번들 자산과 컴파일만 확인).
- 태그·GitHub Release는 만들지 않았다.
