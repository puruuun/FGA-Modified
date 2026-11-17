# FGA(mod)

[한국어](README.md) | [English](README.en.md) | [日本語](README.ja.md)

**KR（韓国）・JP（日本）サーバーに限定してテストしています。他のサーバーでの動作は確認していません。**

[Fate/Grand Automata(FGA)](https://github.com/Fate-Grand-Automata/FGA)に機能を追加した修正版です。公式配布版とは別に管理しています。

**📖 [ユーザーマニュアルサイト（韓国語）](https://puruuun.github.io/FGA-Modified/)** — 機能ごとの使い方をまとめています。

## ダウンロード・アップデート

**正式版 1.7.5** — 概念礼装の自動強化で、フィルターに★4・★5や強化済み礼装が含まれる場合に表示される確認ダイアログを自動で進めます。[変更内容（韓国語）](wiki/releases/1.7.5-KR.md)

| 配布版 | APK | Obtainium |
|---|---|---|
| **正式版 FGA(mod)** | [最新の正式版](https://github.com/puruuun/FGA-Modified/releases/latest) | [正式版を追加](https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fapp%2F%257B%2522id%2522%253A%2522io.github.fate_grand_automata.cardcount%2522%252C%2522url%2522%253A%2522https%253A%252F%252Fgithub.com%252Fpuruuun%252FFGA-Modified%2522%252C%2522author%2522%253A%2522puruuun%2522%252C%2522name%2522%253A%2522FGA%2528mod%2529%2522%252C%2522additionalSettings%2522%253A%2522%257B%255C%2522includePrereleases%255C%2522%253Afalse%252C%255C%2522apkFilterRegEx%255C%2522%253A%255C%2522%255EFGA-mod-.%252A-arm%255C%255C%255C%255C.apk%2524%255C%2522%257D%2522%257D) |

Obtainiumでは正式版のみ提供しています。[正式版設定ファイル](obtainium.json)をインポートして登録できます。開発ベータAPKはPCに保存するか、BlueStacksに直接インストールします。

入手元に関する注意: GitHub ReleasesおよびObtainium以外から入手したAPKはインストールしないでください。

[インストール案内（韓国語）](wiki/scripts/Modified-Features-KR.md#installation) · [サーヴァント図鑑番号での画像作成案内（韓国語）](wiki/scripts/Servant-Number-Portraits-KR.md)

Android 7以降のARM端末（arm64-v8a / armeabi-v7a）に対応しています。既存の修正版からは上書きアップデートできます。

## 追加・修正された機能

| 機能 | 説明 |
|---|---|
| [フレンドポイント召喚・自動売却](wiki/scripts/FP-Auto-Sell-KR.md) | サーヴァント枠がいっぱいになるとロックされていないカードを売却し、設定したバナー位置に戻って召喚を続けます。 |
| [スキル・アペンドスキル自動強化](wiki/scripts/Auto-Skill-Level-KR.md) | 強化パネルと確認ダイアログのレベルを照合しながら、各枠で設定した目標レベルまで強化します。 |
| [ウィークリーミッション自動周回](wiki/scripts/Weekly-Mission-Route-KR.md) | 選択したミッションの最小APルートを計算し、宝物庫・初級を含めて全ルートを周回します。 |
| [概念礼装の自動強化](wiki/scripts/CE-Auto-Enhance-KR.md) | おすすめ選択フィルターで自動セットを有効にし、強化を繰り返します。 |
| [ハクノシャッフル](wiki/scripts/Hakuno-Shuffle-KR.md) | NPを消費するスキル1を、カード条件を満たすまで繰り返します。 |
| [カードの優先順位の拡張](wiki/scripts/Card-Priority-KR.md) | 最大10項目、ウェーブ・ターン単位の指定、サーヴァント方式に対応し、項目ごとに方式とサーヴァントの優先順位を設定します。 |
| [バトル設定フォルダー](wiki/scripts/Battle-Config-Folders-KR.md) | バトル設定をフォルダーにまとめて、一覧やランチャーで開閉できます。 |
| [ターン制限撤退](wiki/scripts/Turn-Limit-Retreat-KR.md) | 全滅するかNターン目に到達すると撤退してやり直し、設定した連続撤退回数に達すると停止します。 |
| [敵配置ごとのターゲット指定](wiki/scripts/Enemy-Layout-Targets-KR.md) | 6体配置・レイド配置の敵を指定します。 |
| [ゲームエリア](wiki/scripts/Game-Area-Preview-KR.md) | 分割画面のゲームエリアをプレビューしながら調整し、スクリーンショットから自動調整します。 |
| [追加機能の微調整](wiki/scripts/Added-Features-Fine-Tune-KR.md) | 修正版機能の待機時間と認識の類似度を機能ごとに調整します。 |
| [クエスト後のフレンド申請](wiki/scripts/Modified-Features-KR.md#auto-friend-request) | フレンド以外のサポートを使用した後、フレンド申請を送ります。 |
| [グランドサポート礼装](wiki/scripts/Support-Grand-CE-KR.md) | グランドサポートの通常枠・報酬枠の概念礼装と絆礼装の効果を確認します。 |
| [サーヴァント図鑑番号での画像作成](wiki/scripts/Servant-Number-Portraits-KR.md) | 図鑑番号からサポート画像を作成して保存します。 |
| [オリジナルFGAのデータを取り込む](wiki/scripts/Original-FGA-Import-KR.md) | オリジナルFGAのスクリプトとサポート画像を取り込みます。 |
| [Chaldeaリンクからコマンド入力](wiki/scripts/Chaldea-Import-KR.md) | 共有リンクの戦闘行動をコマンドに変換し、クエストのウェーブ数に応じてウェーブとターンを分割します。 |
| [カード条件でコマンド開始](wiki/scripts/Card-Count-Start-KR.md) | カード色別の条件行（AND/OR）を満たしたときのみコマンドを開始します。 |
| [1ターン目シャッフル](wiki/scripts/Modified-Features-KR.md#first-turn-shuffle) | 開始条件を満たしていない場合、シャッフルしてから再判定します。 |
| [サポートのアペンドスキル](wiki/scripts/Support-Append-Skills-KR.md) | アペンドスキルがLv.10のサポートを選択します。 |
| [サポートのスコア判定](wiki/scripts/Support-Score-62-KR.md) | クラス +62・グランド +16のサポートを選択します。 |
| [サポートの宝具レベル](wiki/scripts/Support-NP-Level-KR.md) | 設定した最小宝具レベル以上のサポートを選択します。 |
| [再臨の区別](wiki/scripts/Ascension-Select-KR.md) | 再臨段階が異なるサーヴァントを「サーヴァント名＋宝具」画像で区別して選択します。 |
| [攻撃順の直接指定](wiki/scripts/Direct-Cards-KR.md) | そのターンのカードと宝具の攻撃順を直接指定します。 |
| [ティーポット・ストームポッド](wiki/scripts/Quest-Items-KR.md) | クエスト開始前にON/OFFを設定します。 |
| [絆ポイント集計・MAX停止](wiki/scripts/Bond-Tracking-KR.md) | 獲得した絆ポイントを合算し、絆MAXになると停止します。 |
| [戦闘記録ログ](wiki/scripts/Battle-Log-KR.md) | 完了した戦闘の結果を最新200件まで保存し、メイン画面 → ログから確認できます。 |

詳細なガイドは韓国語で記載されています。設定方法や動作は[使用説明書（韓国語）](wiki/scripts/Modified-Features-KR.md)をご確認ください。

## 支援

FGA(mod)が役に立ちましたら、[Ko-fi](https://ko-fi.com/puruuun)からご支援いただけますと幸いです。

<a href="https://ko-fi.com/puruuun"><img height="36" src="https://storage.ko-fi.com/cdn/kofi2.png?v=6" alt="Buy Me a Coffee at ko-fi.com"></a>

## オリジナルおよびライセンス

[オリジナルリポジトリ](https://github.com/Fate-Grand-Automata/FGA) · [オリジナル版ガイド](https://github.com/Fate-Grand-Automata/FGA/wiki) · [MITライセンス](LICENSE)

ベースバージョン: 公式 FGA v3059 ([基準コミット](https://github.com/Fate-Grand-Automata/FGA/commit/e138a15273dee9cac342833ee2d8aee93d8ae700))。オリジナル開発チームの著作権表示を保持しています。追加・修正部分の著作権は puruuun に帰属し、同じ MIT ライセンスで配布されます。

参照: 「Chaldeaリンクからコマンド入力」の変換ルールは [Unic31/fgo-converter](https://github.com/Unic31/fgo-converter)（コミット `1c95510`）の変換ロジックを参考に Kotlin へ移植しています。
