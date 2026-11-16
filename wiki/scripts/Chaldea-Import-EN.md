# Chaldea Command Import

On the battle config edit screen, to the right of **Command**, tap **Import from link** (link icon) immediately to the left of the text-command editor (terminal icon).

1. Copy a Chaldea share link for a battle that has recorded skill and attack actions.
2. Paste the full URL into the **Chaldea share URL** box in the dialog.
3. To import without enemy-targeting instructions, check **Ignore enemy targeting information** right below the URL field (unchecked by default).
4. Review the warning that the existing command will be replaced, and tap **Convert and apply**.
5. Once conversion and FGA syntax validation succeed, the command is applied immediately.

This works independently for both Setting 1 and Setting 2 commands and replaces only the command you tapped. With **Also import support settings** left off (default), no other settings are changed. Invalid URLs, failed conversions, or canceling before confirming leave both existing commands and support settings untouched.

## Also Importing Support Settings

1. Check **Also import support settings** in the link import dialog and tap **Convert and apply**.
2. In the preview, inspect the friend Servant and equipped CE's collection number and name, whether a saved folder, built-in image, or download will be used, and which settings will change.
3. Tap **Confirm and import** to download any needed images and apply both the command and support settings. Tapping **Cancel** before confirming changes nothing and saves no portrait images. While applying, wait until it finishes.

Support settings are imported only when there is **exactly one `friend`** among the 6 party slots; if there are none or more than one, a notice is shown and only the command is imported. Settings 1 and 2 share the same support configuration within a battle config.

Selection Mode becomes **Preferred**, and Preferred Servants and Preferred CEs are each replaced with that single entry (if no CE is equipped, Preferred CEs is cleared). CE max limit break, NP level check and minimum level, Max Skills (skills 1–3), Append Skills (check and 5 slots), and Support Class are imported. Skills require level 10 only for skills that are 10; Append check turns on if any Append is level 10. All other settings—Grand Servant, scores, Friends Only, Max-ascended, etc.—stay unchanged. Unsupported classes keep the existing Support Class.

Images are searched by collection number in the order **saved folder → built-in mapping table → Atlas image download**. JP collection numbers take precedence, with region-exclusive numbers distinguished as `KR.No.` / `NA.No.`. Built-in folders and filenames are never modified. Fetching catalog metadata may require internet access before confirming, whereas image downloading and saving happen only after confirmation.

If catalog metadata cannot be loaded, support import is skipped and only the command is imported. If CE metadata or a specific image download fails, the existing selection for that category is kept and a notice is shown. You can check your connection and retry, or prepare images via **More options → Additional features → Search and save support images**. You should still verify in game whether a friend matching those conditions is available and test support selection once.

### Built-in Servant and Craft Essence Mapping Tables

Entries verified against Atlas NA, KR, and JP data are linked here; remaining entries stay unlinked and are downloaded when needed. Currently 24 Servants are linked (0 unlinked) and 23 Craft Essences are linked (0 unlinked). Servant folders without internal IDs, such as `asc1.png`, are linked via a verified explicit mapping table.

Built-in U-Olga Marie images include the 4 regular ascension portraits and the Costume portrait (`f_40001300.png`).

### Servants

<!-- builtin-servant-table:start -->
| Folder | JP No. | Atlas name |
|---|---|---|
| Arcueid | 351 | アーキタイプ：アース |
| Artoria (Caster) | 284 | アルトリア・キャスター |
| BB Dubai | 421 | ＢＢドバイ |
| Chloe (Avenger) | 388 | クロエ・フォン・アインツベルン |
| Ciel | 418 | 謎の代行者C.I.E.L |
| Douman | 297 | 蘆屋道満 |
| Flora | 464 | フローラ |
| Jalter | 106 | ジャンヌ・ダルク〔オルタ〕 |
| Kazuradrop | 426 | カズラドロップ |
| Koyanskaya | 314 | 光のコヤンスカヤ |
| Koyanskaya of the Darkness (Foreigner) | 334 | 闇のコヤンスカヤ |
| Lady Avalon | 353 | レディ・アヴァロン |
| Mash | 1 | マシュ・キリエライト |
| Merlin | 150 | マーリン |
| Nero (Bride) | 90 | ネロ・クラウディウス〔ブライド〕 |
| Oberon | 316 | オベロン |
| Omii-san | 463 | 蛇女房 |
| Sima Yi (Reines) | 241 | 司馬懿〔ライネス〕 |
| Skadi | 215 | スカサハ＝スカディ |
| Skadi (Ruler) | 357 | スカサハ＝スカディ |
| Tamamo | 62 | 玉藻の前 |
| Tiamat Summer | 450 | ラーヴァ／ティアマト |
| U-Olga Marie | 444 | Ｕ－オルガマリー |
| Waver | 37 | 諸葛孔明〔エルメロイⅡ世〕 |
<!-- builtin-servant-table:end -->

### Craft Essences

<!-- builtin-ce-table:start -->
| Folder (file) | collection No | Atlas name |
|---|---|---|
| Bella Lisa.png | 988 | Bella Lisa |
| Beyond the Night Without the Snow Flower.png | 1410 | 눈꽃 없는 밤의 너머로 |
| Black Grail.png | 48 | The Black Grail |
| Blessed Bride.png | 2233 | 幸せな花嫁へ |
| Chaldea Dinnertime.png | 1121 | Chaldea Dinnertime |
| Chaldea Lunchtime.png | 330 | Chaldea Lunchtime |
| Chaldea Morning.png | 2020 | Chaldea Morning |
| Chaldea Teatime.png | 910 | Chaldea Teatime |
| Detective Fou.png | 1080 | 명탐정 폼즈 |
| Foreign God.png | 2437 | 異星の神 |
| From NFF with Love.png | 1949 | From NFF with Love |
| Great Library of Memories.png | 2583 | 記憶の大図書館 |
| Gun Dealer.png | 2701 | ガン・ディーラー |
| Kaleidoscope.png | 34 | Kaleidoscope |
| Marshal of the Sorcerors.png | 1458 | 마도원수 |
| Mona Lisa.png | 80 | Mona Lisa |
| Needle of Sincerity.png | 2209 | 지성의 한 땀 |
| Report Check.png | 2052 | 리포트 체크 |
| Rising Against the Azure Sky.png | 1605 | 창공에 서다 |
| Secret Mission.png | 2142 | 시크릿 미션 |
| Sword Saints of Wandering.png | 2607 | 流転の剣聖 |
| The Chaldean.png | 1797 | 칼데아의 남자 |
| Wings of Manuscript.png | 2124 | 수고의 날개 |
<!-- builtin-ce-table:end -->

Enabling **Ignore enemy targeting information** omits all enemy-selection commands (`t1`, `t2`, `t3`, special enemy slots, etc.). Skills that target a single enemy still retain their skill letters; ally target indices, skill choices, Order Change, NPs, and attack ordering are preserved. This applies only to the imported link and does not tap to change enemy targets in game.

If an import fails due to special-layout enemy target indices, check the link or enable this option and try again. This option does not ignore broken links or errors in other required action data. The previously reported link for Quest `94146807` converts to `defab1cgix31hgj54` when checked.

## Conversion Method

The link's data is decoded inside the app and converted into FGA command syntax following the base rules of [fgo-converter](https://github.com/Unic31/fgo-converter) for action order, skill targets, Noble Phantasms, Order Change, plugsuit/swaps, etc. You do not need to open any external converter website or copy/paste manually, and the pasted URL itself is never opened over the network.

To resolve target types, NP types, and swap effects, FGA queries JP skill, NP, and Mystic Code metadata from [Atlas Academy](https://api.atlasacademy.io/docs). First-time lookups require an internet connection; retrieved metadata is cached in the app and refreshed when stale, allowing cached data to be reused if a later lookup fails offline. Just like the original converter, JP metadata is used regardless of FGA's server setting.

## Splitting Waves and Turns

Chaldea links do not record which attacks trigger a wave change—each attack is simply recorded as one turn. Importing a same-wave follow-up turn as a wave boundary (`,#,`) would cause FGA to stall waiting for a wave that never arrives.

- FGA looks up the quest's wave count on Atlas Academy using the quest ID and phase in the link and pre-assigns boundaries when unambiguous: 1-wave quests apply every boundary as next turn in the same wave (`,`), and quests whose attack count is ≤ the wave count apply every boundary as wave change (`,#,`) directly without showing the turn list.
- For multi-wave quests where the attack count exceeds the wave count, or if quest metadata could not be loaded or reports 0 waves, FGA defaults all boundaries to wave changes and shows a notice asking you to verify them manually.
- The boundary list appears only when FGA cannot determine boundaries automatically. Pick **Wave** or **Turn** for each `"After attack N"` row before applying (skipped when there is only 1 attack).
- In multi-wave quests with more attacks than waves, double-check which attacks actually advance the wave.

## Supported Scope

- Uses Chaldea share links whose `data=` payload contains battle actions. Formation-only links with no recorded actions cannot be converted.
- Turn/wave separators follow the original converter's default `,#,` output; if a single wave spans multiple turns, adjust separators in the boundary list or the standard Skill Maker afterward.
- Normal face cards convert to the number of cards before NP, matching the original converter; this does not reproduce card types, critical hits, or simulator damage rolls.
- Choice skills support 2-choice and 3-choice prompts supported by FGA. Other choice types or unrecognized actions fail with an error while keeping the existing command.
- Limitations of the original converter—such as Guts, special stage gimmicks, and certain death/swap calculations—apply here too. A matching conversion does not guarantee every team clears reliably in live gameplay.

## NP Level and Ascension Conditions

When the friend's NP level is 1, **Check support NP level** is turned off while preserving your saved minimum level; for Lv.2–5, it turns on with that level as the minimum. If the imported Servant differs from the previously selected Preferred Servant, **Re-ascension (servant + NP name)** resets to none; if it is the same Servant, the existing setting is kept.
