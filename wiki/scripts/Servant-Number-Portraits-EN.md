# Search and Save Support Images

Search for Servants and Craft Essences by collection number or name and save their support recognition images named in the language configured for FGA.

## How to use

1. Open **More options → Additional features → Search and save support images**.
2. Choose **Servant** or **Craft Essence**, enter a **Collection number or name** (official name or partial match), and tap **Search**—for example, Servant `90`, `Nero` (`네로`), `スカサハ`; CE `34`, `Kaleido` (`컬라이더`), `カレイド`.
3. Check the number, name, class, rarity, and portrait, then select the entry to save. A single search result is selected automatically; when there are multiple results, FGA does not auto-select the first one.
4. Tap **Generate and save**. Search and dedicated portrait lookup run in **JP → KR → NA** order, stopping at the first server with results. There is no manual server picker, and CN/TW are not used for portrait lookup.
5. Under **Battle Configs → Support**, choose that folder in **Preferred Servants** or **Preferred CEs**.

Images are saved under your chosen FGA folder at `support/servant/No.<number> <name>/` or `support/ce/No.<number> <name>/`. You do not need to run Support Image Maker or touch the game. An internet connection is required.

## Downloading images from the Preferred Servants / Preferred CEs picker

You can open the same search-and-save dialog directly from **Download images** at the bottom left of the **Preferred Servants** or **Preferred CEs** picker under **Battle Configs → Support**. From the Servant picker, **Servant** is preselected; from the CE picker, **Craft Essence** is preselected. This button is not present on the NP or Friend name pickers.

Saving one or more images reloads the list and immediately checks the saved folder while keeping your previously checked entries in the open picker. If you switched types inside the search dialog before saving, only entries matching the current picker's type are checked. You must tap **OK** to apply the selection to your config; tapping **Cancel** discards selection changes for this dialog, though the downloaded images stay in the folder. Simply closing the search dialog, failing to save, or having no storage folder set leaves selections unchanged.

If a numbered folder already exists, opening from the picker saves images into that folder while keeping its name and existing preferences. The standalone **More options → Additional features** entry remains available as before.

## Chip layout and deleting images

The **Preferred Servants, Preferred CEs, NP level and friend name** pickers lay their choices out as chips. Servant and CE chips show a face and the name (a grey placeholder when there is no image), and a selected chip is filled. Tapping a chip toggles it; a long name may take a whole line.

In the **Preferred Servants** and **Preferred CEs** pickers a **long press** on a chip deletes images from the support folder.

1. A long press on a chip starts delete mode with that chip marked. The title row shows **N selected**, a close button at the start and a **Delete** button at the end; the Download images, Cancel and OK buttons are hidden.
2. In delete mode a tap marks or unmarks a chip with a red border and a delete mark. Marks are separate from the preference: tapping never changes it. Unmarking the last chip leaves delete mode.
3. Back or the close button leaves delete mode and deletes nothing.
4. **Delete** asks for confirmation. On **Delete**, a servant's whole folder or a CE's image file is removed from `support` in the FGA folder, and the deleted entries leave the list and this config's saved selection. An entry that could not be deleted stays, and an error is shown.

**Built-in images** cannot be deleted: they are written back when the app is updated or when support images are extracted again from More options, so a long press on one only shows a message. Other battle configs that still list a deleted entry keep it; clear it there if needed. The NP level and friend name pickers have no delete.

## Naming language and search

Saved names use the **App Language selected in FGA**. When set to follow the system default, Android's effective locale is used. Korean uses KR data, Japanese uses JP, English uses NA, and Simplified/Traditional Chinese use CN/TW; if an entry has no localized name or uses an unsupported language, it falls back to the JP Japanese name.

For example, in Korean mode `No.90` is saved as `No.90 네로 클라우디우스(브라이드)`, while `No.444` (not yet in KR data) is saved as `No.444 Ｕ－オルガマリー`. Whether a translation exists depends on Atlas data at the time of lookup.

Collection numbers **prioritize the JP version**. If there is no search result in JP, lookup falls back to KR and then NA. When the same internal ID exists in JP, the JP collection number is kept; region-exclusive entries use that region's number. Cross-region name mapping matches by internal ID rather than collection number, so names from regions with different numbering will not be attached wrongly. Search matches official names and partial names, and you can search using Japanese names as well. Whitespace, common punctuation, English case, and full-width/half-width differences are normalized. Korean names are never guessed automatically from community nicknames or machine translation.

**Korean name search for KR-unreleased Servants.** Servants released on JP but not yet on KR have no official Korean name. You can still search for them using the built-in Korean name dictionary (based on Namuwiki, including Ascension-specific names)—for example, `히폴` → Hippolyta (`히폴리테`), `카구야` → `No.448`. Pre-release names in the dictionary (e.g., `큐티 코마` → Dynamite Rikyu / `다이너마이트 리큐`) are used only until the Servant is added to the KR collection; once an official KR name exists, those entries are ignored and only the official name is searched. A separate list of nicknames (`김충선` → Saika Magoichi / `사이카 마고이치`, `버미육` → Matsunaga Hisahide / `마츠나가 히사히데`) keeps working after KR release. Displayed names, collection numbers, and saved folder names still follow the official Japanese names. For Servants not yet in the dictionary, search by Japanese name or collection number. The dictionary currently covers `No.438–487` and continues to expand in later versions. It is not used for number search or Craft Essence search.

**Official names that change across Ascensions, Costumes, or True Name reveals search as the same Servant.** For example, both `Fairy Knight Lancelot` (`요정기사 랜슬롯`) and `Mélusine` (`멜뤼진`) find `No.312`, and searching `Mélusine` (`멜뤼진`) lists other classes sharing that name as separate candidates. Folders use the canonical collection name rather than your search query, so changing the search term never creates duplicate folders.

For name search, FGA collects base names, Ascension/Costume Servant name overrides, and True Name reveal names from Atlas public game data (`mstSvt`, `mstSvtLimitAdd`, `mstSvtChange`) and links them to internal IDs. NP names, skill names, and descriptions are excluded from search. Your first Servant name search downloads an extra name list, which follows the cache refresh rule below. Number search and CE search do not perform this extra download.

If re-generating changes a Servant's name, the existing numbered folder is updated while keeping preferred selections across all battle configs. Region-exclusive entries missing from JP are saved under `KR.No.<number> <name>` or `NA.No.<number> <name>` so they do not mix with JP images of the same number or existing selections. Other built-in images and user-created files are never deleted. Older configs referencing previous names can still locate the current folder by number.

## Images and verification scope

For Servants, FGA collects Ascensions, Costumes, and supported special variants from `extraAssets.faces` and `facesChange`. When a JP-only image list exists, that list is used. Only when the entry is absent or the list is empty does it check KR and then NA, never mixing images across servers. It applies the standard conversion: `128×128` dedicated portrait → `158×158` → `(3,47,125,44)`.

For Craft Essences, FGA uses the **`150×68` dedicated strip image in `extraAssets.equipFace`** rather than full card illustrations, scaling it to the comparison size used for both normal and Grand supports. Regardless of display-name language, dedicated images are looked up in **JP → KR → NA** order. If dedicated assets are missing or have a different format, it never crops full illustrations arbitrarily.

False CE positives against live game displays on supported servers must be verified separately using screen samples. Recognition is not guaranteed on unobserved devices or aspect ratios. Individual failures are reported in the Saved/Failed counts and can be retried by tapping **Generate again**.

The KR/JP Servant and CE lists and extra name list used for search are cached in the app and refreshed in sync with Atlas data versions (checked at most once per hour), so newly added Servants and CEs become searchable promptly. When the version cannot be read, or for other regions such as NA, the existing 24-hour cache rule applies, while the Korean dictionary for KR-unreleased Servants uses the fixed list bundled in the app. When a valid cache exists, it is reused for searches during temporary network failures. Image generation and downloads only run when triggered by the user.

## Auto-selecting Support Class from Preferred Servants

When **Preferred Servants** in a battle config is set to **exactly one Servant**, that Servant's class tab is selected automatically as the Support Class (for both built-in images and `No.<number>` folders). Shielder, Ruler, Avenger, Moon Cancer, Alter Ego, Foreigner, Pretender, and Beast classes map to the **Extra** tab. It does not change when 0 or 2+ Servants are selected, for custom user folders, when the class is unknown, or when only CEs change; any class you pick manually stays until you change the Servant selection again.
