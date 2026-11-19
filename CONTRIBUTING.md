# 기여 안내 · コントリビューションガイド · Contributing

[한국어](#한국어) · [日本語](#日本語) · [English](#english)

## 한국어

FGA(mod)에 관심을 가져 주셔서 고맙습니다. 제보, 제안, 질문, 코드 기여를 모두 받습니다.

### 제보와 질문

- **버그 제보와 기능 제안:** [이슈](https://github.com/puruuun/FGA-Modified/issues/new/choose)에 남겨 주세요. 양식의 칸은 모두 선택 사항입니다. 아는 만큼만 적어 주시면 되고, 더 필요한 내용은 답글로 여쭙겠습니다.
- **사용법 질문:** [Discussions](https://github.com/puruuun/FGA-Modified/discussions)에 남겨 주세요. 기능별 사용 방법은 [설명서](https://puruuun.github.io/FGA-Modified/)에 있습니다.
- **언어:** 한국어, 일본어, 영어 중 편한 언어로 적어 주세요.
- **계정 정보:** 이 저장소는 공개되어 있습니다. 스크린샷이나 로그에 프렌드 코드, 닉네임 같은 계정 정보가 있으면 가린 뒤 올려 주세요.
- **보안 문제:** 공개 이슈에 올리지 말고 [SECURITY.md](SECURITY.md)의 방법으로 알려 주세요.

### 지원 범위

이 앱은 **한국 서버와 일본 공식 서버**를 대상으로 개발하고 시험합니다. 다른 서버(북미, 중국, 대만)의 코드는 원본 FGA에서 온 그대로 두지만, 그 서버 전용 기능이나 수정은 반영하기 어렵습니다. 원본 FGA의 문제는 [원본 저장소](https://github.com/Fate-Grand-Automata/FGA)에 제보해 주세요.

### 코드 기여(PR)

- 버그 수정, 번역 수정, 설명서 수정은 바로 PR을 보내 주셔도 됩니다.
- 새 기능이나 자동화 동작을 바꾸는 변경은 먼저 이슈로 이야기해 주세요. 방향이 맞지 않아 작업이 헛되는 일을 줄일 수 있습니다.
- PR은 `main` 브랜치를 대상으로 열어 주세요.
- 보내기 전에 단위 테스트를 돌려 주세요. JDK 21이 필요합니다.

  ```bash
  ./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test
  ```

- 계정 정보가 담긴 캡처, 5MB가 넘는 파일, 빌드 결과물은 넣지 말아 주세요. CI가 거부합니다.
- 처음 기여하시는 분의 PR은 관리자가 내용을 확인한 뒤에 CI가 실행됩니다.
- 검토가 끝나면 관리자가 병합합니다. 작성자 이름은 커밋에 그대로 남습니다. 기여한 코드는 이 저장소와 같은 [MIT 라이선스](LICENSE)로 배포됩니다.
- `AGENTS.md`와 `docs/agents/`는 관리자가 쓰는 AI 에이전트용 작업 규칙입니다. 외부 기여자가 따라야 하는 문서는 아니지만, 코드 구조와 좌표 규칙을 이해하는 데 도움이 됩니다.

## 日本語

FGA(mod) に関心を持っていただきありがとうございます。不具合の報告、提案、質問、コードの貢献をすべて受け付けています。

### 報告と質問

- **バグ報告と機能の提案:** [Issue](https://github.com/puruuun/FGA-Modified/issues/new/choose) に書いてください。フォームの項目はすべて任意です。わかる範囲で書いていただければ、足りない情報はコメントでお聞きします。
- **使い方の質問:** [Discussions](https://github.com/puruuun/FGA-Modified/discussions) に書いてください。機能ごとの使い方は[マニュアル](https://puruuun.github.io/FGA-Modified/)にあります。
- **言語:** 日本語、韓国語、英語のうち書きやすい言語でどうぞ。
- **アカウント情報:** このリポジトリは公開されています。スクリーンショットやログにフレンドコードやマスター名などが含まれる場合は、隠してから投稿してください。
- **セキュリティの問題:** 公開 Issue には書かず、[SECURITY.md](SECURITY.md) の方法で知らせてください。

### 対応範囲

このアプリは**日本の公式サーバーと韓国サーバー**を対象に開発・テストしています。他のサーバー（北米、中国、台湾）のコードは本家 FGA のまま残していますが、そのサーバー専用の機能や修正は対応が難しいです。本家 FGA の問題は[本家リポジトリ](https://github.com/Fate-Grand-Automata/FGA)に報告してください。

### コードの貢献（PR）

- バグ修正、翻訳の修正、マニュアルの修正は、そのまま PR を送っていただいてかまいません。
- 新機能や自動化の動作を変える変更は、先に Issue で相談してください。
- PR は `main` ブランチ宛てに開いてください。
- 送る前にユニットテストを実行してください。JDK 21 が必要です。

  ```bash
  ./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test
  ```

- アカウント情報が写ったキャプチャ、5MB を超えるファイル、ビルド成果物は入れないでください。CI が拒否します。
- 初めて貢献される方の PR は、管理者が内容を確認した後に CI が実行されます。
- レビューが終わると管理者がマージします。作者名はコミットに残ります。貢献されたコードはこのリポジトリと同じ [MIT ライセンス](LICENSE)で配布されます。
- `AGENTS.md` と `docs/agents/` は、管理者が使う AI エージェント向けの作業ルールです。外部の貢献者が従う必要はありませんが、コードの構成や座標のルールを理解する助けになります。

## English

Thank you for your interest in FGA(mod). Bug reports, ideas, questions and code are all welcome.

### Reports and questions

- **Bug reports and feature requests:** open an [issue](https://github.com/puruuun/FGA-Modified/issues/new/choose). Every field of the forms is optional. Write what you know; the maintainer will ask in a comment for what is missing.
- **How-to questions:** ask in [Discussions](https://github.com/puruuun/FGA-Modified/discussions). The [manual](https://puruuun.github.io/FGA-Modified/) explains each feature (in Korean, with English and Japanese pages for some features).
- **Language:** Korean, Japanese or English, whichever is easiest for you.
- **Account data:** this repository is public. If a screenshot or a log shows a friend code, a player name or other account data, hide it before posting.
- **Security problems:** do not post them in a public issue; see [SECURITY.md](SECURITY.md).

### Scope

The app is developed and tested for the **KR server and the official JP server**. Code for other servers (NA, CN, TW) is kept as it comes from upstream FGA, but features or fixes that only apply to those servers are unlikely to be accepted. Problems of upstream FGA belong in the [upstream repository](https://github.com/Fate-Grand-Automata/FGA).

### Pull requests

- Bug fixes, translation fixes and manual fixes can be sent directly.
- For a new feature or a change to how the automation behaves, open an issue first, so the direction is agreed before you spend time on it.
- Open the PR against the `main` branch.
- Run the unit tests before you send it. JDK 21 is required.

  ```bash
  ./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test
  ```

- Do not add captures with account data, files over 5 MB or build output. CI refuses them.
- CI runs on a first-time contributor's PR after the maintainer has looked at it.
- After review the maintainer merges the PR. Your name stays on the commits. Contributed code is distributed under the same [MIT licence](LICENSE) as this repository.
- `AGENTS.md` and `docs/agents/` are working rules for the maintainer's AI agents. Outside contributors do not have to follow them, but they help to understand the code layout and the coordinate rules.
