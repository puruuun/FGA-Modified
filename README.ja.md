# FGA(mod)

[한국어](README.md) | [English](README.en.md) | [日本語](README.ja.md)

**KR（韓国）・JP（日本）サーバーに限定してテストしています。他のサーバーでの動作は未確認です。**

[Fate/Grand Automata（FGA）](https://github.com/Fate-Grand-Automata/FGA)に機能を追加した改変版です。公式配布版とは別に管理しています。

## ダウンロード・更新

- [最新APK](https://github.com/puruuun/FGA-Modified/releases/latest)
- 正規版 1.2.1: [変更内容（韓国語）](wiki/releases/1.2.1-KR.md)
- [Obtainiumに追加](https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fapp%2F%257B%2522id%2522%253A%2522io.github.fate_grand_automata.cardcount%2522%252C%2522url%2522%253A%2522https%253A%252F%252Fgithub.com%252Fpuruuun%252FFGA-Modified%2522%252C%2522author%2522%253A%2522puruuun%2522%252C%2522name%2522%253A%2522FGA%2528mod%2529%2522%252C%2522additionalSettings%2522%253A%257B%2522includePrereleases%2522%253Afalse%257D%257D)
- [詳細](wiki/scripts/Modified-Features-JA.md#installation)

Android 7以降のARM端末（arm64-v8a / armeabi-v7a）に対応しています。以前の改変版から上書き更新できます。

インストール元: GitHub ReleasesとObtainium以外から入手したAPKはインストールしないでください。

## 追加・変更した機能

| 機能 | 概要 |
|---|---|
| [オリジナルFGAから取り込み](wiki/scripts/Original-FGA-Import-JA.md) | スクリプト・サポート画像を選んで一括で取り込みます。 |
| [Chaldeaリンクから入力](wiki/scripts/Modified-Features-JA.md#chaldea-import) | 戦闘共有リンクをコマンドに変換して入力します。 |
| [カード枚数によるコマンド開始](wiki/scripts/Modified-Features-JA.md#card-start) | サーヴァントの通常カード枚数に応じて、2つの設定から実行する設定を選びます。 |
| [初ターンのシャッフル](wiki/scripts/Modified-Features-JA.md#first-turn-shuffle) | 開始条件を満たさない場合、シャッフル後に再判定します。 |
| [サポートのアペンドスキル](wiki/scripts/Modified-Features-JA.md#support-append) | 指定したアペンド枠がLv.10か確認します。 |
| [サポートのスコア](wiki/scripts/Modified-Features-JA.md#support-score) | クラス+62・グランド+16の条件で絞り込みます。 |
| [サポートの宝具レベル](wiki/scripts/Modified-Features-JA.md#support-np) | 指定した最低宝具レベル以上を選びます。 |
| [攻撃順の直接指定](wiki/scripts/Modified-Features-JA.md#direct-cards) | 通常カード・宝具の3枚の選択順を指定します。 |
| [ティーポット・ストームポッド](wiki/scripts/Modified-Features-JA.md#quest-items) | 有効にすると開始前に指定したON/OFFへ切り替えます。 |
| [絆ポイント集計・MAX停止](wiki/scripts/Modified-Features-JA.md#bond-tracking) | 結果欄のキャラクター1～5ごとに集計し、MAX停止を無視する対象を選びます。 |

設定方法・動作ルール・検証範囲は[詳細ガイド](wiki/scripts/Modified-Features-JA.md)をご覧ください。

## 原版・ライセンス

[原版リポジトリ](https://github.com/Fate-Grand-Automata/FGA) · [原版の利用ガイド](https://github.com/Fate-Grand-Automata/FGA/wiki) · [MITライセンス](LICENSE)

公式FGA v3059（[ベースのコミット](https://github.com/Fate-Grand-Automata/FGA/commit/e138a15273dee9cac342833ee2d8aee93d8ae700)）を基にしています。原作者の著作権表示を維持しています。
