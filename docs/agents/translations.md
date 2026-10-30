# Localization

`app/src/main/res/values/localized.xml` holds the English source of every translatable
string — add and edit them there, in the source tree. `values/strings.xml` is only for
`translatable="false"` values (app name, server names, links, language labels).

The fork does not use POEditor, upstream's crowd-translation service (owner, 2026-10-30). The
translated `values-*/localized.xml` files are edited in the repo like any other file. The
`MissingTranslation` lint errors for languages the fork does not maintain are expected.

## Who owns which translation

- **Korean (`values-ko`) and Japanese (`values-ja`)** belong to the owner. The owner reviews
  them and has them fixed directly in the repo; a fix needs no other approval.
- **Every other language** (Chinese, Vietnamese, …) follows upstream: take upstream's files as
  they are and do not edit them in the fork.

## Translations in an upstream sync

See [`upstream-sync.md`](upstream-sync.md) for the merge itself. For strings:

1. **Upstream changed a translation** the fork never touched: take upstream's text.
2. **Both sides changed the same string** (a conflict, or a Korean/Japanese text the fork had
   already rewritten): do not pick one yourself. List each string with the fork text and the
   upstream text and let the owner choose.
3. **Upstream added a new English string**: the owner decides its Korean and Japanese text.
   Propose a draft for each, mark it as a draft, and use the owner's wording once they give it.
4. **Other languages**: take upstream's text, conflicts included.

## Default support images carry translated names

`app/src/main/assets/Support/servant/<Name>/` and `app/src/main/assets/Support/ce/<Name>.png`
ship the default servants and craft essences. Those English names *are* the identity of a
support entry — they are the keys written to preferences and the names matched against the
extracted image files — so never translate them in place. The UI shows a translated label
instead, resolved through `SupportNameResources`
(`app/src/main/java/io/github/fate_grand_automata/util/SupportNameResources.kt`), which maps
each servant folder name and each CE file name (minus the extension) to a string resource.

Adding, renaming or removing anything under `assets/Support` therefore takes three edits:

1. the asset — a folder under `servant/`, or a `.png` under `ce/`;
2. a `servant_name_*` / `ce_name_*` entry in `values/localized.xml`;
3. the matching line in `servantNameResIds` or `ceNameResIds`.

A missing entry fails silently: the lookup falls back to the raw English name, which is also
how user-added custom supports are meant to display. A rename that skips step 3 breaks the
mapping the same quiet way, so the existing translation just stops being used. A new name
needs its Korean and Japanese strings in the same change; other languages fall back to English
until upstream adds them.

### Looking up official names

Take translated servant and CE names from the game data on [Atlas Academy](https://atlasacademy.io)
rather than translating them by hand. The language folders map to servers: `values-ja` → JP,
`values-b+zh+CN` → CN, `values-b+zh+TW` → TW, `values-ko` → KR.

- Search by English name: `https://api.atlasacademy.io/basic/NA/servant/search?name=<name>`.
  For an entry that isn't on NA yet, or to look up many at once, download
  `https://api.atlasacademy.io/export/<region>/basic_servant.json` (CEs: `basic_equip.json`).
  Find the ID by its JP or NA name, then read the other regions' names under that same ID.
  Keep only `type` `normal`/`heroine` (servants) or `servantEquip` (CEs).
- One JP name can belong to several servants (Jalter Avenger and Berserker, Skadi Caster and
  Ruler). Choose by class to match the asset.
- If the ID is missing from a region's export, the servant or CE isn't released there yet.
  Leave the string out; the app then falls back to English.
- Short names a region uses in battle are in the `nice` endpoint
  (`/nice/<region>/servant/<id>`) under `ascensionAdd.overWriteServantBattleName`.

Servant labels stay short, at most 15 characters, like the English ones:

- Use the first name alone when no other servant shares it (マシュ, not マシュ・キリエライト).
- Add the class in parentheses when the asset is one of several versions of a servant:
  クロエ(アヴェンジャー), 克洛伊(Avenger), 클로에(어벤저).
CE names stay in their full official form.
