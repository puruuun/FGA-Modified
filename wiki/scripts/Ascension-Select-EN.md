# Re-ascension (Servant + NP Name)

Use this when you need to distinguish a Support Servant whose Noble Phantasm changes by Ascension. For example, Mélusine (`メリュジーヌ`) switches between `"今は知らず、無垢なる湖光"` (Arts) and `"誰も知らぬ、無垢なる鼓動"` (Buster) depending on Ascension. Because the NP name alone cannot distinguish her from another Servant with the same NP name, the captured image includes **both the Servant name row and the NP name row**.

FGA matches the screen image directly. It does not OCR the text, so the text language and NP level (`Lv.`) do not matter.

## Preparation: Create re-ascension images

1. Open the Support Selection screen in-game with Servant rows visible.
2. Tap the play button at the bottom-left and run **Support Image Maker**.
3. When the maker finishes, the naming dialog opens. At the bottom under **Servant + NP name (re-ascension)**, an image is listed for each row.
4. Check the box for each entry you want to save and enter a name—for example, `メリュジーヌ 今は知らず`. When saving, `JP) ` or `KR) ` is automatically prepended based on the game server (it is not added again if already present). This depends on the game server, not the app language, and is not added to Servant, CE, or Friend images.
5. Tap **Done**.

The cropped image spans from the top of the Servant name row to the bottom of the NP name row, and horizontally from the start of the name text to just before `Lv.`. Labels and card-type icons are excluded, and `Lv.N` and NP-upgrade markers are omitted so the same image matches regardless of NP level. Friend comments or extra text lines attached to the Servant name row are excluded. Images from earlier versions that included the card-type icon continue to match.

- **Re-create old images that contained only the NP name.** Because the NP name alone cannot tell Ascension 1 and Ascension 2 apart, older images should not be used for Ascension selection. While this version can still match older images, it cannot separate different Ascensions that share the same NP.
- If `Lv.` is not visible on a row, no NP image is created for that row; Servant and CE images are still created as usual.
- Saving an Ascension image again under the same name preserves existing images and adds the new image to the same entry. Giving two rows the same name groups them into one entry, and matching any image in that entry passes.

## Selection: Choose in settings

Open **Battle Configs → Support → Preferred**, and select the saved name under **Re-ascension (servant + NP name)** directly below **Preferred Servants**. Saved names appear as-is, such as `JP) メリュジーヌ 今は知らず`. The prefix is only for display and is not a server filter. Selecting multiple entries matches any support matching at least one of them. Leaving nothing selected disables this check.

## Using together with Preferred Servants

Even though the image includes the Servant's name, **it is recommended to select Preferred Servants as well**. Filtering by Preferred Servants first narrows down candidates so the re-ascension check is faster and more accurate.

- **Preferred Servants**: Mélusine (`メリュジーヌ`)
- **Re-ascension (servant + NP name)**: `JP) メリュジーヌ 今は知らず`

The check order is Servant → Re-ascension → Score → CE → Friend → NP level. If the Servant does not match, the re-ascension check is skipped.

## Limitations

- **If the Servant name, NP name, and card type are all identical, Ascensions cannot be distinguished.** In that case, combine with other filters such as score or NP level.
- Use images created from the same server you run on. Images from another server will not match.
- If the game font or display size changes after creating an image, re-create it.

## Troubleshooting

- The `FGA.SupportNp` log records `NP name best score` and `matched` for each row. If a support that should match logs `matched: false` with a score near `0.85`, re-create the image.
- If no entries appear in the naming dialog, that version of the Support Image Maker was not used. Run the maker again.

## 1.6.2 — Reset re-ascension when Preferred Servants changes

Whenever **Preferred Servants** is changed to a different set, **Re-ascension (servant + NP name)** resets to none, because NP images belong to the chosen Servant. Confirming the dialog with the same Servant(s) unchanged preserves your current re-ascension selection. The same rule applies when importing a support from Chaldea.
