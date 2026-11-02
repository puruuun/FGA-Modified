# 정규판 출시 체크리스트

정규판을 낼 때마다 이 순서를 따릅니다. 각 단계의 결과는 `docs/releases/<버전>-validation.md`에 적습니다.

버전 값은 저장소 루트의 `version.properties`에만 씁니다. 워크플로 파일에는 버전을 적지 않습니다.

1. **유의적 버전(SemVer) 결정 및 베타 우선 검증.** 상세 규칙은 [`docs/agents/releases-and-versioning.md`](../agents/releases-and-versioning.md)를 따릅니다.
   - 기존 기능 개선·버그 수정·번역/에셋 정리·업스트림 동기화는 **패치 버전(`X.Y.(Z+1)`)**, 새로운 기능 추가는 **마이너 버전(`X.(Y+1).0`)**으로 올립니다.
   - 기능·코드 변경 단계에서는 먼저 `beta.versionName`(`X.Y.Z-beta.N`)과 `beta.versionCode`(`major*1000000 + minor*1000 + patch*100 + beta`)만 올리고 `./gradlew :app:assembleBeta`로 베타 APK를 만들어 블루스택·태블릿에서 검증합니다.
   - 베타 검증이 끝나고 정규판 출시가 결정되면 `stable.versionName`(`X.Y.Z`)과 `stable.versionCode`(직전 정규판 값 + 1)를 올립니다.
2. **Main CI 확인.** 버전을 올린 커밋의 `Main CI` 실행이 성공했는지 확인합니다.
3. **베타 APK 설치.** 이 PC에서 `./gradlew :app:assembleBeta`로 만듭니다(오너 키로 서명). 블루스택에 업데이트로 설치합니다(`install --no-streaming -r`). `.sha256` 파일은 만들지 않습니다.
4. **기기 검사.** 정규판 전에는 블루스택에서 11개 suite 전체(`support,bond,questItems,noblePhantasm,fpSell,skillLevel,weeklyRoute,rankUp,skillLevelPanel,ceEnhance,hakunoShuffle`)를 `py -3 tools/device-qa/run.py --build --serial 127.0.0.1:5555 --suite …`로 실행합니다. 모든 suite가 실패 없이 끝나야 합니다. skip은 `docs/reference-images/README.md`의 목록과 같아야 합니다.
5. **실제 게임 수동 확인 (소유자).** 범위와 결과를 검증 리포트에 적습니다.
6. **정규 APK 만들기.** 릴리스 PR을 병합한 `main`에서 `./gradlew :app:assembleCi`로 만듭니다. `aapt2`로 패키지·버전·앱 이름·ABI를, `apksigner`로 서명(v3 오너 키, v2 `fgadebug`, lineage 인증서 2개)을, `zipalign -c -P 16 4`를 확인합니다. 블루스택의 이전 정규판 위에 업데이트로 설치되는지 확인합니다.
7. **문서 작성.** `docs/releases/<버전>-validation.md`와 `wiki/releases/<버전>-KR.md`를 작성합니다. 게시 직전에 `README.md`(한국어)와 `README.en.md`(영어)를 이번 정규판에 맞춥니다. README는 베타 동안 바꾸지 않고 이 단계에서만 바꿉니다.
   - 두 파일의 **추가·수정된 기능 표**를 같은 순서로 고칩니다. 근거는 릴리스 노트와 `git diff v<직전 정규판>..HEAD -- wiki/scripts`입니다.
   - 한국어 정규판 줄과 영문 릴리스 노트 링크를 바꿉니다.
8. **GitHub Release 게시.** 정규판(프리릴리스가 아닌) Release를 게시하면 설명서 사이트가 그 태그 기준으로 자동 갱신됩니다(`Publish manual site` 실행 확인). 태그는 `v<버전>`, 첨부 파일은 `FGA-mod-<버전>-arm.apk` 하나입니다(GitHub가 파일마다 SHA-256을 보여 줍니다). 파일 이름 형식은 앱 내 업데이트 확인(`GitHubUpdateChecker`)과 Obtainium 필터(`^FGA-mod-.*-arm\.apk$`)가 요구하므로 바꾸지 않습니다.

## 서명

정규판과 베타는 이 PC에서 오너 개인 키로 서명합니다(APK 서명 v3 키 교체, 1.7.1부터, #128). 키는 저장소 밖에 있고 워크플로는 키를 쓰지 않습니다. 자세한 내용은 [`docs/agents/releases-and-versioning.md`](../agents/releases-and-versioning.md)의 "Release signing"을 보세요.
