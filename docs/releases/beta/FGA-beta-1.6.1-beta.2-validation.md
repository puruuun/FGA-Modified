# FGA-beta-1.6.1-beta.2 검증

베타 **1.6.1-beta.2 / 1006102**, 패키지 `io.github.fate_grand_automata.beta`. 정규판은 1.6.0 / 3087입니다.

- 인수한 17개 파일의 부분 변경을 검토하고 올바른 좌표·사례·프레임을 유지했습니다. 추가 KR 어펜드 캡처 32–35를 독립 실측했습니다.
- 수정: FP 소환 배너 왼쪽 화살표의 가장자리 기준, 와이드 어펜드 슬롯 높이, KR 파티·스킬·영기변환 제목 템플릿. 제목 유사도 기준은 기존 0.90 / 0.85 / 0.90을 유지하며 새 템플릿은 KR 와이드에서만 추가 검사합니다.
- 스킬 확인·결과, FP 소환 버튼·공방 메뉴·판매 확인/완료·한계 안내, 메인 레코드/인터루드 목록, 지도 퀘스트 판/지점 이름표는 기존 기준이 새 캡처에도 맞습니다. 인연 레벨업 창은 소유자 결정대로 가운데 기준을 유지합니다.
- 2560×1080 게임 영역은 `Region(20, 0, 2520, 1080)`, 스크립트 영역 3360×1440입니다. 21:9 초과 영역 계산이 맞습니다. 단위 테스트로 2560×1080·1962×827·1680×720·1920×1080을 확인했습니다.
- 전체 단위 테스트 `:app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test`: **653개 통과, 실패·오류·skip 0** (app 186, prefs 26, scripts 441, libautomata 0). 네 잠금 칸은 유지하고 2번 칸만 Lv.6→7로 강화하는 회귀 테스트 포함.
- MkDocs `build --strict` 성공.
- 캡처 15–35의 정규화 결과 21장 모두 원본 crop `(15,37,1947,865)` → 1680×720 LANCZOS 변환과 픽셀 일치. 참조 파일 누락 없음.
- 오프라인 OpenCV 스킬/어펜드 대조: 비 OCR **92개 통과**, OCR 16개는 기기 QA 대기. 이는 Android 제품 판독기를 실행한 결과가 아닙니다.
- [측정 상세](../../design/0012-widescreen-audit.md), [설계](../../design/0012-widescreen.md).

## 기기 QA 사례

이번 버전의 기기 QA 실행 결과는 **대기**입니다. 기존 beta.1 결과는 1364개 통과·기존 skip 11개이며, 이를 이번 버전의 통과 수로 보고하지 않습니다. 기존 사례는 변경·삭제하지 않고 신규 58개를 추가했습니다.

| suite | 기존 실행 대상 | 신규 | 이번 실행 대상 | 기존 skip |
|---|---:|---:|---:|---:|
| support | 189 | 2 | 191 | 3 |
| bond | 23 | 2 | 25 | 0 |
| questItems | 8 | 2 | 10 | 8 |
| noblePhantasm | 18 | 0 | 18 | 0 |
| fpSell | 762 | 18 | 780 | 0 |
| skillLevel | 89 | 19 | 108 | 0 |
| weeklyRoute | 275 | 15 | 290 | 0 |
| 합계 | 1364 | 58 | 1422 | 11 |

## 배송 확인

- 제품 커밋 `8c1900903d3d0c39229ddd5286a30085337fb87a`을 main에 직접 푸시했습니다. author·committer 모두 `puruuun <188972188+puruuun@users.noreply.github.com>`, AI 트레일러 없음.
- [Main CI 성공](https://github.com/puruuun/FGA-Modified/actions/runs/39625565989) 확인 후 같은 제품 커밋에서 `:app:assembleBeta` 성공.
- aapt: `io.github.fate_grand_automata.beta`, `1006102 / 1.6.1-beta.2`, `FGA(beta)`, ABI `arm64-v8a`·`armeabi-v7a` 확인.
- apksigner 검증 성공. 인증서 SHA-256 `5acce47e71373dcfe3a6f6400ed3b3f687bd454aab91c7562c8327a3a083ac9d` (기존 베타와 동일).
- APK ZIP 무결성 및 KR 와이드 제목 템플릿 3개의 패키지 포함·원본 일치 확인.
- APK: `C:/Users/rl/Documents/Codex/FGA-Modified-builds/FGA-beta-1.6.1-beta.2-arm.apk`와 `.apk.sha256`. 복사 후 해시 파일 재대조 성공.
- APK SHA-256: `422be4d02175ae1a745d8932f4c5aa9b12ad3cc33f10eb07db0a0ac4522e84eb`.
- `-PfgaDeviceTests=true :app:assembleDeviceTest :app:assembleDeviceTestAndroidTest` 성공. 테스트 APK 컴파일을 완료했으며 기기에서는 실행하지 않았습니다.
- APK 검증 후 `C:/platform-tools/adb.exe devices -l` 결과 **빈 목록**. 지정 기기 `127.0.0.1:5555 / PIXEL 2 XL` 미연결로 **기기 QA와 설치는 미실행·대기**입니다. 에뮬레이터 실행·조작, `adb connect`, `kill-server`, `start-server`, `disconnect`는 하지 않았습니다.
- 추가 오프라인 FP 버튼·확인창 대조 13개 통과. 카드 격자와 Android 제품 판독기 검증을 대신하지 않습니다.
- 증거 로그: `build/orchestration/0012-beta2-unit-final.log`, `0012-offline.log`, `0012-ci-success.json`, `0012-assemble.log`, `0012-aapt.txt`, `0012-apksigner.txt`, `0012-deviceqa-build.log`, `0012-adb-devices.txt`, `0012-delivery.json`.
- 최종 배송 결과는 별도 문서 커밋으로 기록합니다. APK의 제품 커밋과 Main CI는 위 SHA 기준입니다.
- 다음 단계: 소유자가 지정 BlueStacks를 실행하고 연결한 뒤, 기기 모델을 확인하고 베타 `install -r` 및 전체 저장 프레임 QA 1422개(기존 skip 11개)를 수행해야 합니다. 0실패 여부는 아직 확인하지 않았습니다.
- 게임 조작·다른 기기 설치·GitHub Release 게시·태그 생성은 하지 않습니다.
- 남은 자료: 필수 어펜드 캡처는 확보 완료. 원본 고해상도 KR 작은 점수 글자는 판독 실패 시 필요할 수 있습니다. 인연 레벨업 창은 가운데 기준 유지, 실제 게임 21:9→20:9 확인은 소유자 범위입니다.
