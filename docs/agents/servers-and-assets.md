# Game servers and assets

`app/src/main/assets/{En,Jp,Cn,Tw,Kr}/` hold per-server template images, named by `Images`
enum entries (`scripts/.../Images.kt`). `ImageLoader` looks up the current server's folder
and **falls back to `En`** when the file is absent, so only add a server-specific copy when
the art actually differs. `IFgoAutomataApi.findImage` additionally tries the `En` image on
JP to support TranslateFGO. `assets/Support/` holds user-provided servant/CE images;
`assets/tessdata/` the OCR data. Template images must be 720p — see the coordinates guide.

`docs/reference-images/{En,Jp,Cn,Tw,Kr}/<category>/` holds device screenshots, usually
contributed by users, that the per-server templates were cut from. The category folders are
per game screen (`battle`, `support`, `friend-summon`, …); put a new screenshot in the
matching one or add a new one. Keep the screenshot a template came from so a later fix can be
re-cut and checked against the same source. They never ship in the APK.

**Store them at 720p**, scaled so the *game area* is 720px tall — on a screen with letterbox
bars that leaves the image itself taller. That is the resolution matching runs at, so a crop
taken from a reference image is exactly the pixels the script compares, with no resampling in
between. Downscale a contributed screenshot before committing it.

To draft the templates of a server and screen that has no screenshot, use the
`apk-template-asset` skill (`.claude/skills/apk-template-asset/`), which cuts them from that
server's APK; the result is checked against a real screenshot before it is committed.

## NA: ported, not played

NA (`GameServer.En.Original`, assets in `En/`) is a target server, but the owner does not play it,
so NA work is ported from KR and JP. Screen logic and layout are the same on every server; only
language, fonts and some art differ.

- A language-free template that matches KR or JP screens also serves NA through the `En`
  fallback. Measured: support screen icons, the Grand CE label and the bond CE marks score
  0.97-0.999 on JP screens with the `En` copies.
- A template with text or localized art gets an `En` copy drafted from the NA APK or Atlas
  Academy (`apk-template-asset`), or cut from an NA screenshot.
- NA screenshots come from the owner when available, otherwise from public sources (wiki,
  Reddit, X, video frames of 1080p or better; a blurry 720p frame loses text labels). Downloading
  them needs the owner's approval, as any download does; they stay out of the repository, and
  only finished templates and account-free crops are committed.
- Code that branches on the server (`GameServer.Kr`, `GameServer.Jp`) runs its default path on
  NA. A port decides for each branch whether NA takes the KR, the JP or the default behaviour,
  including OCR settings.
- The `server-port` skill (`.claude/skills/server-port/`) lists per `Images` entry what carries over, what can be redrawn from the NA APK and what needs a screenshot, and per code branch what to decide.
- No device QA on NA unless the owner provides one; a PR says which NA parts were checked on
  screenshots only.

Server choice and app language are independent: a user can run the JP server with the app's
UI in English, or the EN server in Korean. Don't assume they're coupled when reading prefs or
adding server-specific behavior.
