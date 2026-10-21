# FGA-beta-1.6.1-beta.3 검증

버전 **1.6.1-beta.3 / 1006103**, 패키지 `io.github.fate_grand_automata.beta`.

## 원인과 수정

- 소유자 로그의 01:46:15, 01:50:54 실행에서 `stop step=bannerReturn reason=workshop`을 확인했습니다. 매각은 완료됐지만 공방 인식이 실패해 닫기를 한 번 더 누르는 기존 재시도 경로로 들어갔습니다.
- 프레임 36의 원본은 창 테두리 없는 2560×1080입니다. 게임 영역 `(20,0,2520,1080)`만 잘라 1680×720으로 저장했습니다. 21:9 스크립트 영역은 3360×1440이며 `shop`의 검색 영역은 720p에서 `(1230,0,355,80)`입니다. 기존 KR 제목 템플릿의 최적 위치는 이 영역 안입니다. 영역 누락이나 JP/KR 글자 크기 차이가 원인이 아닙니다.
- 기존 KR 템플릿(243×45)은 제목 뒤의 배경·그림자까지 포함합니다. 정규화 프레임의 유사도는 **0.8666**으로 기준 **0.90** 미만입니다. 전체 화면 INTER_AREA 축소 후 게임 영역을 자르는 경로에서도 **0.8589**입니다.
- 기존 KR 16:9 원본 `fp-sell/23.png`에서 제목의 **빈** 글자 내부 `(1010,14,43,40)`을 다시 잘랐습니다. 프레임 36에서 **0.9543**, INTER_AREA 경로에서 **0.9398**, 기존 KR 16:9에서 **1.0000**입니다. 영역·인식 기준·JP 템플릿·복귀 순서는 유지합니다.
- JP 16:9 공방 **1.0000**, JP 2560 공방 **0.9455**. 21:9와 2560×1080은 같은 3360×1440 게임 영역/오른쪽 기준 좌표를 사용합니다. KR의 실제 다른 21:9 캡처는 없으므로 해당 해상도의 실기 성공으로 보고하지 않습니다.
- 재생성: `python tools/device-qa/prepare_kr_workshop.py build/orchestration/inputs/0012-wide/36-2560x1080-now-kr.png` (Pillow, OpenCV 필요). 정규화본과 런타임 축소 경로본 모두 720p 무손실 WebP로 보관합니다.

## KR 복귀 경로 확인 범위

| 단계 | 프레임 36 결과 | KR 와이드 양성 검증 |
|---|---|---|
| 공방 제목 | 수정 후 0.9543 / INTER_AREA 0.9398 | 확인 |
| MENU | 0.9781 / INTER_AREA 0.9917 | 확인 |
| 소환 메뉴 아이콘 | 메뉴가 닫혀 있어 미검출이 정상 | 미확인: 열린 메뉴 캡처 없음 |
| 소환 화면의 왼쪽 화살표·FP 라벨·소환 버튼 | 공방에서 미검출이 정상 | 미확인: KR 와이드 소환 화면 없음 |
| 저장 배너 제목 + FP 라벨 | 공방에서 불일치가 정상 | 미확인: KR 와이드 배너 캡처 없음 |
| 소지 한계 문구·매각 버튼 | 공방에서 미검출이 정상 | 미확인: KR 와이드 한계창 없음 |

미확인 단계는 기존 KR 16:9 및 JP 와이드 사례를 계속 검증합니다. 게임을 조작해 캡처를 만들지는 않습니다. 공방 프레임의 음성 검증은 위 단계의 KR 와이드 양성 검증을 대신하지 않습니다.

검색 영역도 다시 확인했습니다(720p, 21:9 게임 영역). 소환 메뉴는 중앙 기준 `(710,590,170,90)`에 KR 59×29 템플릿이 들어갑니다. 화살표는 왼쪽 안전 영역 기준 `(86,300,75,125)`에 공통 16×40 템플릿이 들어갑니다. 소환 버튼은 중앙 기준 `(965,470,250,90)` 및 무료 소환 `(730,470,220,90)`, 저장 제목은 `(515,300,650,130)`입니다. 한계 문구 `(380,145,920,45)`에는 KR 118×34 템플릿, 매각 버튼 `(415,440,290,80)`에는 KR 56×31 템플릿이 들어갑니다. 이 크기·기준점 점검은 화면 안의 실제 위치를 확인한 양성 검증과 구분합니다.

## 검증 및 배송

- 공방 도착을 인식하면 닫기는 한 번만 누르고 MENU로 진행하는 회귀 단위 테스트 추가.
- 기존 사례를 유지하고 QA 12개 추가: 프레임 36의 양성/음성 8개, KR 기존 16:9 공방/MENU 2개, INTER_AREA 공방/MENU 2개.
- 전체 단위 테스트 `:app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test`: **654개 통과**, 실패·오류·skip 0 (app 186, prefs 26, scripts 442).
- `mkdocs build --strict` 성공.
- 최종 전체 기기 QA `build/device-qa/20261022-020620/`: **1434개 통과, 실패 0**, 기존 skip 11개. support 191, bond 25, questItems 10, noblePhantasm 18, fpSell 792, skillLevel 108, weeklyRoute 290.
- 최종 QA APK에 최종 43×40 KR 템플릿이 원본과 바이트 단위로 동일하게 포함된 것을 확인했습니다. QA APK SHA-256: `47ef05341e444ac541c14cc0fb5454ca3b4e9787d2c7664c5efba90a5535808c`.
- 제품 수정 `c92ef6dabf4e89c3e95080336eb2b28588ca9fcc`, 재생성 레시피 정합성 `acf1ae44ce480864ff6fd340ec97165936a208dc`를 main에 직접 푸시했습니다. 두 커밋의 author·committer는 `puruuun <188972188+puruuun@users.noreply.github.com>`, AI 트레일러 없음. 첫 CI는 후속 커밋으로 대체 취소됐으며, [최종 제품 커밋의 Main CI 성공](https://github.com/puruuun/FGA-Modified/actions/runs/39639969162)을 확인했습니다.
- CI 성공 확인 후 같은 제품 커밋에서 `:app:assembleBeta` 성공. aapt: `io.github.fate_grand_automata.beta`, `1.6.1-beta.3 / 1006103`, `FGA(beta)`, ABI `armeabi-v7a`·`arm64-v8a` 확인.
- apksigner 검증 성공. 인증서 SHA-256 `5acce47e71373dcfe3a6f6400ed3b3f687bd454aab91c7562c8327a3a083ac9d` (기존 베타와 동일). APK ZIP 무결성 및 수정 KR 템플릿의 패키징 원본 일치 확인.
- APK: `C:/Users/rl/Documents/Codex/FGA-Modified-builds/FGA-beta-1.6.1-beta.3-arm.apk`, 체크섬: `.apk.sha256`. 복사 후 체크섬 파일과 APK를 다시 대조했습니다.
- APK SHA-256: `c5f1f0491c7349fbff0ddb7016507a9149ff1aee2b1f88a6f4003023a9b93638`.
- 설치: BlueStacks `127.0.0.1:5555` / `PIXEL 2 XL`만 사용, `install -r` **Success**. dumpsys로 `1.6.1-beta.3 / 1006103` 확인. 최초 설치 시각 유지.
- 모든 기기 QA는 저장 프레임 7개 suite입니다. 게임 입력·진행, 다른 기기 설치, adb kill-server/start-server/disconnect, GitHub Release 게시를 하지 않았습니다. 실제 KR 와이드 복귀의 소유자 QA는 위 미확인 캡처 범위와 구분합니다.
- 증거: `build/orchestration/beta3/`의 `unit-final.log`, `unit-delivery.log`, `scores.json`, `deviceqa-final.log`, `ci-success.json`, `assemble.log`, `aapt.txt`, `apksigner.txt`, `delivery.json`, `install.json`, `package-before.txt`, `package-after.txt`.
