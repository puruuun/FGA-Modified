# 정규판 출시 체크리스트

정규판을 낼 때마다 이 순서를 따릅니다. 각 단계의 결과는 `docs/releases/<버전>-validation.md`에 적습니다.

버전 값은 저장소 루트의 `version.properties`에만 씁니다. 워크플로 파일에는 버전을 적지 않습니다.

1. **버전 올리기.** `version.properties`의 `beta.*`와 `stable.*`를 고칩니다.
   - 정규판 versionCode는 직전 정규판 값에 1을 더합니다.
   - 베타 versionCode는 `major*1000000 + minor*1000 + patch*100 + beta`입니다. 빌드가 이 규칙을 검사합니다.
2. **Main CI 확인.** 버전을 올린 커밋의 `Main CI` 실행이 성공했는지 확인합니다.
3. **베타 APK 설치.** `Beta APK` 워크플로를 실행하거나 `./gradlew :app:assembleBeta`로 만듭니다. 블루스택에 업데이트로 설치합니다. 설치가 되는지, APK의 SHA-256이 `.sha256` 파일과 같은지 확인합니다.
4. **기기 검사.** 블루스택에서 `py -3 tools/device-qa/run.py --build --suite support,bond,questItems`를 실행합니다. 모든 suite가 실패 없이 끝나야 합니다. skip은 `docs/reference-images/README.md`의 목록과 같아야 합니다.
5. **실제 게임 수동 확인 (소유자).** 범위와 결과를 검증 리포트에 적습니다.
6. **정규 APK 만들기.** `Stable APK` 워크플로를 실행합니다. 확인 단계(`aapt`의 패키지·버전·앱 이름·ABI, `apksigner` 서명)가 통과해야 합니다.
7. **문서 작성.** `docs/releases/<버전>-validation.md`와 `wiki/releases/<버전>-KR.md`를 작성합니다. 게시 직전에 `README.md`의 정규판 줄을 바꿉니다.
8. **GitHub Release 게시 (소유자).** 태그는 `v<버전>`, 첨부 파일은 `FGA-mod-<버전>-arm.apk`와 `FGA-mod-<버전>-arm.apk.sha256`입니다. 파일 이름 형식은 앱 내 업데이트 확인(`GitHubUpdateChecker`)과 Obtainium 필터(`^FGA-mod-.*-arm\.apk$`)가 요구하므로 바꾸지 않습니다.

## 서명

정규판도 저장소에 공개된 debug 키(`app/fgadebug.keystore`)로 서명합니다(결정 D1, `docs/design/0003-release-1.2.1.md` 참고). 서명 설정은 바꾸지 않습니다. 릴리스 노트에 설치 출처 안내를 씁니다.
