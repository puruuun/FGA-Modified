# Weekly Mission Clear

**This feature calculates the quests and run counts that clear this week's weekly missions for the least AP, then navigates from the main screen to each chapter and map to run the full route. Check your battle configuration and apple refill settings before starting.**

For JP and KR servers starting from stable release 1.6.0. Requires an internet connection. It does not read your actual in-game mission progress.

## How to Start

1. Open the game's **main screen** (the screen with `お知らせ` / `공지` at the top left). Do not start from a quest panel or chapter list.
2. Tap the FGA Play button → **Weekly mission clear** at the bottom left of the launcher dialog.
3. The **Weekly Mission panel** opens on the **right side** of the launcher dialog. Tapping the same button again collapses it. It uses the battle config, AP, item, and limit settings from the left side as-is.
4. Tap **Download**. Download stages for missions, chapters, and quest enemies are shown with a progress bar. If this week's cache already exists, the route appears immediately.
5. Manual missions have no checkbox, and any previously saved selection for them is ignored. Unchecked missions remain grayed out in their original order. Uncheck a mission to exclude it from the calculation—use this to exclude missions you have already completed. All supported missions default to checked, per-server/per-week selections persist across app restarts, and selections reset on a new week. Changing selections recalculates the route and resets **−/+ counts** to the newly computed values.
6. Check the total AP and per-quest counts, then tap the shared **OK** button. If the panel is open and the route still has remaining runs, it executes the weekly route. If the route is empty, tapping OK will not run and displays **There are no quests to run**. If the panel is collapsed, tapping OK starts a normal battle. Tapping **Cancel** performs no game actions.

**More options → Battle → Use weekly mission automation** defaults to ON. Turning it off hides the Weekly mission clear button in the battle launcher.

Enable or disable candidate chapters and quests per server under **More options → Advanced → Weekly quest tracking settings**. Tap `+` on a chapter name to expand its free quests. All default to ON, and turning off a chapter preserves your individual quest selections. If no data is cached yet, download that server's data in the launcher panel first. Mictlan and Ordeal Call I–IV are unsupported and do not appear in the list.

## Confirmation Dialog and Route Calculation

**Treasure Vault Beginner** (10 AP) is also a candidate. Because its enemy lineup is fixed at 9 Casters, only Beginner is used instead of other difficulties, and it is always visited last in the route. It can be turned off under **Weekly quest tracking settings → Daily Quests**, and is also included in the navigation probe.

After other quests finish, the script confirms the main screen using the notice button. Navigation is **Main → Chaldea Gate → Daily Quests → yellow Treasure Vault Beginner panel**. After tapping Gate, it waits for it to close; finds and taps the Daily banner in the list; and waits for both closing and a daily quest panel anchor. Right-side titles and event backgrounds are not used for screen classification. In the list, it treats the **first yellow panel from the top** (hue 38–52°, saturation 0.80 or higher) as Beginner and taps it without reading the name. Name and AP are read only for `map.log` logging. If no yellow panel is visible, it scrolls down, and aborts if none is found by the end. Red Ember quests, green Training Grounds, and `CLEAR` / `NEW!` / `∞` panels are excluded from color matching. It recognizes by color even if displayed AP differs during a campaign, and returns to the Daily Quests list after the final run. Verify with the navigation probe first.

| Item | Description |
|---|---|
| This week's missions | Only checked missions are included in calculation. Uncheck already completed missions. Partial progress can be adjusted via run counts. |
| Unchecked missions | Remain grayed out in their original positions in this week's list and can be re-enabled. Manual missions have no checkbox. |
| Route | Shows Chapter / Spot / Quest name / AP / run count and total AP. Quests with count 0 are not run. |
| Candidate settings | Chapters or quests disabled in Weekly quest tracking settings are excluded from route calculation. |
| Manual | Conditions that cannot be solved automatically, such as drop missions. Complete these manually. |
| Excluded | **Unsupported chapters:** Mictlan (war 311) uses a different layer-switching UI, and Ordeal Call I–IV (war 402–405) are excluded by owner decision. They do not appear in route candidates, probe targets, Weekly quest tracking settings, or chapter lists (both JP and KR). Heian-kyo (307) and Traum (310) are supported as Main Interlude chapters. |
| Battle config | Uses the config selected in the left list. Choose a config that works across differing enemy lineups. |

Only repeatable main-story free quests are included. One-time quests with `afterClear=close`, such as Fuyuki's `危険地帯`, are excluded. Supports enemy traits, enemy classes, Servant classes, specific enemies, quest clears, and quest traits; unsupported missions or missions with no reachable candidate are marked manual. "Total missions" objectives are fulfilled alongside completing individual conditions.

KR candidates, names, and AP come from KR data, while **KR enemy lineups are based on JP data**. Quests whose JP AP and KR AP differ for the same quest ID are excluded. Solver data is cached in the app per server and week. Initial downloads or format updates may take some time. Chapter banners are fetched and cached from Atlas Academy. Starting from beta.10, map images are not downloaded; only map dimensions and spot coordinates are used.

## Automatic Navigation and Battle

- JP goes through Main Record **1 / 1.5 / 2**. KR goes through **1 / 1.5** and looks up Part 2 chapters from the main screen list. Heian-kyo and Traum live under the **Main Interlude** button at the bottom of the main screen rather than Main Record, so they are visited last (beta.10).
- Chapter buttons are matched using the inner button image of the Atlas banner. To find items lower in the list, it swipes upward, and if not found in one direction it searches the opposite direction as well—up to 14 swipes per direction; if not found, that chapter's quests are skipped.
- When entering a chapter, it pinches to zoom the map out to minimum zoom. If the gesture is rejected, it does not proceed.
- It never guesses name-label positions. Since beta.10, instead of matching map images, it drags the map to the top/bottom edge to lock down its position and computes coordinates from spot data (see "Map Positioning — beta.10" below).
- It uses the map size matching the quest's `mapId`. If map position cannot be determined, remaining quests in that chapter are also skipped. It never guesses transitions between multiple maps or Mictlan layer buttons.
- Quest panels verify **Free Quest label + name**. Mission quests with party restrictions are never tapped. If the top panel is a mission quest, it also inspects lower Free Quests.
- Names match JP/KR OCR against the server's **full Free Quest name dictionary** rather than passing only the target name as a dictionary. Rare kanji or Korean batchim misreads are corrected only if all of these hold: at most 2 characters and within 1/3 of the name length, at least 2 characters ahead of the runner-up candidate, and consensus between two reads. If similar names collide or text cannot be read, the quest is skipped. AP is still read for logging but is not used in matching. Panels matching only AP or character width are rejected.
- Battle, support selection, and AP refill use the normal FGA flow. Only the run limit is temporarily overridden per quest and restored on exit. Apple usage is deducted once across the entire route based on total refills used.
- For the next quest in the same chapter, it closes the panel and locates the map spot again. For a different chapter, it returns to the chapter list via `ターミナル` / `터미널` and, if needed, returns to the main screen via `閉じる` / `닫기`.

## Exit Summary and Troubleshooting

At exit, it shows each quest's **planned runs / actual runs / done · not found · stopped · not run** and manual missions. If a battle stops due to insufficient AP, defeat, retreat, or user stop, the remaining route is not run. Check the actual run counts if a map or button was not found.

If not on the main screen, nothing is tapped and it prompts you to start from the main screen. If loading data fails, check your internet connection and Atlas Academy reachability; if route computation fails, reduce the number of enabled chapters and try again. Detailed navigation failure steps are recorded in the log.

```text
adb -s 127.0.0.1:5555 logcat -s WeeklyRoute
```

## Verification Scope and Limitations

- Chapter banners, buttons, and quest panels are tested with the same production code against saved screenshots. Map positioning is tested via simulation across 8 aspect ratios and 3 starting positions; live in-game verification rests with the owner.
- Full multi-chapter navigation, pinch gestures, and consecutive battles in the live game were not operated by the implementer; the owner should verify with a short route first. If a device, aspect ratio, or in-game font leaves insufficient reading evidence, the quest is skipped.
- Be sure to turn off chapters you have not unlocked yet. Differences between Atlas and live game enemy lineups can leave a mission short; verify actual in-game weekly mission progress directly.
- If a map looks different or a spot cannot be confirmed, it will not guess-tap. Not all multi-map chapters, wide screens, and quest names have been verified against live captures.

## When Quest Panel Positions Shift — beta.6

- Searches for the Free Quest label in a wide screen band and derives the AP, name, and tap target positions relative to that label, rather than relying on fixed slot coordinates when the list shifts slightly upward.
- **Reads first.** If it matches, it proceeds immediately without dragging the list. Only when the label is cut off or does not match does it drag once top-to-bottom on a button-free area inside the panel and read again.
- If it still does not match, it closes the panel, moves the spot near the center of the screen without inertia, re-aligns the map, and tries once more. Even at a map edge where centering is impossible, if the current spot is safe it opens it once more in place. If the final name read still does not match, it skips the quest. **Never taps a quest unless it is a Match.**
- Waits 1 second longer (3 s each) right after entering a map from a chapter banner and right after returning to Terminal.
- When the weekly panel is open, the **Limit** section on the left is hidden. AP refill, items, and bond remain visible. Collapsing the panel restores the Limit section without altering saved values.
- In beta builds, when a read fails to match, up to **8 diagnostic PNGs per run** are saved to `diagnostics/weeklyRoute/<run-number>/` inside the FGA storage folder. Check `panel diagnostic path=` in the `WeeklyRoute` log to find the files. Stable builds do not save these.

## Navigation Probe Mode — beta.7

A mode for checking in the live game whether quests across all chapter maps can be found, without spending any AP.

- **Where to enable:** Check **Navigation probe (no AP is spent)** near the top of the weekly mission panel. Checking it replaces the route list with probe options. Start from the main screen.
- **Scope:** **All main story free quests** / **Selected chapters** / **Quests enabled in the weekly settings**. The selected quest count is shown immediately. Mictlan is excluded.
- **Behavior:** Navigates the same path as a real route (main screen → main record → chapter → zoom out & map fit → tap spot → read panel) and stops **after panel judgment**. Even when a panel is a Match, it **never taps the quest panel**, closes the panel, and moves to the next quest. It never starts battles or Repeat. Chapters and map spots are visited in order.
- **Results:** Records per-quest status (**success** / **map fit failed** / **no panel after spot tap** / **panel mismatch** / **chapter not found** / **not run**) and elapsed time. At exit, a summary dialog (counts by status and failure list) is shown, and a CSV is saved to `diagnostics/weeklyRoute/probe/` in the FGA storage folder. Screens from failed cases are saved as diagnostic PNGs (up to 100 images) under `diagnostics/weeklyRoute/<run-number>/`.
- **If stopped early:** Results collected up to that point are saved to the CSV and summary, and unvisited quests are marked `not run`.
- **Skip quests that already succeeded:** When checked, quests that succeeded in previous probes are skipped (remembered per server).
- Logs carry `mode=probe` under the `WeeklyRoute` tag. Available on both JP and KR.
- Just like a real route, if map or panel recognition is uncertain, it records a failure rather than guessing.

## After a Quest Ends — beta.7

After closing the Repeat dialog following the last battle of a quest, waits **5 seconds longer** for the loading back to the map before checking the map and quest panel.

## Name Judgment and Map Edges — beta.8

- If the Free Quest label is present and the name voted across the full quest dictionary matches the target, it is a Match. Proceeds as long as the name matches even if AP cannot be read or differs. If unreadable, retries the existing number of times, and skips if still unconfirmed.
- Avoids real UI regions such as the Master portrait, Level/AP, Gift/Mission, and MENU; a spot is tappable as long as a 12px margin around the spot center is on screen. Spots off-screen or covered by UI are scrolled and the map is re-aligned. At a map edge where the origin moves by less than 4px, the same drag is not repeated.
- If a quest fails after opening the panel at least once, the probe result is **panel mismatch** (`panel_mismatch`), logging the last judgment and recovery failure reason in logs and CSV. Only when a spot could never be moved to a safe location from the start is it reported as `spot out of reach`.
- In probe mode, quest panels are never tapped even on Match. On devices with different aspect ratios, check with Navigation probe first.

## Spot Name Check, Success Evidence & SIN Map — beta.9

Improvements addressing failures found during the beta.8 probe (JP 165/224 succeeded).

- **Uses the spot name as a second check.** Tapping a spot enlarges it and shows its spot name label. This name is read and compared against Atlas spot names in that chapter.
  - If the quest name is readable, **the quest name decides alone** (`by=quest-name`). If the read quest name differs from the target, it is rejected even if the spot name matches.
  - **Only when the quest name is completely unreadable**, a panel is accepted (`by=spot-name+cells`) if **all** of these hold: spot name matches / that spot has only one Atlas Free Quest / panel shows Free Quest / character width of the name matches the target name. This rule is not used if the spot has two or more Free Quests.
  - AP is still not used for matching. Logs (`WeeklyRoute`) record OCR candidates and confidence for quest and spot names (`rawNames=`, `rawSpots=`) and the decision rule (`by=`).
- **Fixed name reading.** Crops the name area tightly around the text, reads in 4 modes (2×, 3×, 4× scale, binarized and raw grayscale), and accepts only when the top reads agree. Exact dictionary matches count as one vote even at low confidence.
- **When a spot overlaps a UI button.** If a spot lies within 30px of a fixed button region such as Master Info or Gift Box and the map cannot move further, it taps the visible part of the icon (up to 30px away from the blocked region). The tapped point must remain at least 4px clear of the region.
- **Taps the spot icon.** The tap target is the icon center (25px above the spot coordinate on the map) rather than the Atlas spot coordinate. If covered, it tries the visible part; if that fails, an uncovered spot coordinate; if all are covered, it does not tap. Includes Gift/Mission speech-bubble regions.
- **If no panel opens after tapping a spot**, it saves the screen right after the tap as a diagnostic PNG and taps the same point once more 1 second later. If still absent, it records `no panel after spot tap`.
- **SIN (Lostbelt No. 3) map.** The Atlas map image for SIN is an 8×8 blank placeholder, and the actual map (`gimmick_030301` base layer in `mapGimmicks`) is not on the Atlas server. Instead of image alignment, positioning uses map dimensions and spot coordinates: it drags the map to the top or bottom edge (where the map stops) to anchor it and taps the spot. Verified within 8px for 11 spots on one owner capture, though **live in-game behavior still needs owner verification.** If the resulting panel name differs from the quest, it fails as usual without starting battle.
- **Heian-kyo (307) not found in chapter list.** Banner image, size, and search bounds match other chapters, so the cause could not be determined from code alone. If not found after searching the full chapter list, it saves diagnostic PNGs of the list (top/bottom ends) and logs the closest similarity score to diagnose the cause.

### Navigation Probe Success Evidence and Audit Sheet

For **every successful quest**, the probe saves a small evidence PNG (quest name area on top, spot name label on bottom, 2× upscaled) to `diagnostics/weeklyRoute/<run-number>/evidence/q<questID>.png` with no file count limit. Four columns were added to the CSV (`diagnostics/weeklyRoute/probe/`): `evidence_file`, `by`, `read_quest`, and `read_spot`.

You can copy the CSV and evidence folder to a PC to generate a single HTML audit sheet showing expected quest/spot names, the evidence image, and the rule used per row, with the higher-risk `spot-name+cells` rows sorted to the top:

```text
py -3 tools/weekly-audit/contact_sheet.py probe-JP-<timestamp>.csv <evidence-folder> -o audit.html
```

If the top quest name or bottom spot name in the image differs from the row's expected values, it is a false positive.

## Main Interlude Chapters, Per-Spot Names & Ordeal Call Exclusion — beta.10

Cleans up remaining failures from the beta.9 probe (JP 202/216, KR 216/216 succeeded, 0 false positives across all 418 successes audited).

- **Excluded Ordeal Call I–IV (402–405).** Like Mictlan, removed from route candidates, probe targets, and Weekly quest tracking settings.
- **Heian-kyo (307) & Traum (310) — Main Interlude.** These two chapters are not in Main Record; they are under the gold **Main Interlude** (`メイン・インタールード` / `메인·인터루드`) button at the bottom of the main screen. Path: main screen → Main Interlude button → chapter banner in list → map. Banners are searched the same way as other lists (inner Atlas banner image, up to 14 swipes up/down), excluding the "Main Interlude" header above the list from the comparison region. When leaving a map, if Terminal returns either to the main screen or to the Main Interlude list, it recognizes the screen and continues. From beta.11, Main Interlude button images are supported on both JP and KR (KR home shows **메인 인터루드**). If the button cannot be found, that chapter is skipped.
- **Heian-kyo has 4 maps** and its Free Quests are on `30702`. Since beta.10 no longer aligns map images, there is no "entered map is not the Free Quest map" check. If a different map is open, spot name verification rejects it and skips. Map-switching UI is never guessed.
- **Stale map cache.** Earlier cached map files stored an upscaled 8×8 placeholder for SIN (303), causing the new solver to skip it. Bumping the map cache format forces a fresh download once, and cached maps also re-check placeholder status against the recorded original dimensions. Downloaded data caches are also refreshed, so the first run re-downloads data.
- **Per-spot name candidates.** At spots with two or more Free Quests, the read name is compared only against that spot's Atlas Free Quests (usually 2–3). It accepts (`by=quest-name-spot`) only when the edit distance to the closest candidate is at most half the name length, at least 2 closer than the runner-up, and both reads agree. If it reads as another candidate, it is treated as a mismatch as before and checks the next panel. Spots with a single Free Quest keep the previous rules.

### 1.6.0-beta.12 Navigation Stabilization

Panel closing taps up to 3 times while waiting up to 3 seconds each for the map screen. If that fails, it saves a diagnostic PNG, checks the current screen, and attempts recovery once. Route abort screens are also saved as diagnostic PNGs.

Spots within 60px of screen edges are first moved inward if the map can move. When immobile, the visible part is tapped, and if no panel opens, it moves once in the opposite direction and retries. Applies to both live routes and the navigation probe.

## 1.6.0 Additional Feature Settings

Stable release 1.6.0 supports weekly mission routes. The navigation probe toggle, scope, and skip-succeeded options are beta-only and hidden in stable builds. Weekly route diagnostic PNGs (including abort diagnostics) are also beta-only.

In **More options → Battle**, toggle **Enable Teapot/Storm Pod control** and **Enable bond tracking** (both default to ON). Turning them off hides the corresponding blocks in the launcher while keeping saved selections. Actual item usage, bond tracking, and MAX stop must each be checked in the launcher. **Save support score diagnostics** is under the **Advanced** tab in beta builds and defaults to OFF. **Check for updates** and **Weekly quest tracking settings** are near the top of the Advanced tab.

## 1.6.2-beta.7 List Scanning

When searching for chapters in Home, Main Record, and Main Interlude, the script checks upstream FGA's top/bottom scrollbar-end reference images in a small region. Already-reached directions are skipped before the first swipe, and detecting the end after a swipe immediately stops that direction. The final screen is still scanned once. No extra setting is needed.

Single-page lists where both top and bottom ends are visible together are scanned once without swiping. If ends are not visible, it searches down then up, up to 14 swipes per direction. Animated background pixel changes never cancel end detection, and end state is never inferred from the presence of a center rail. `WeeklyRoute` logs record both end template scores and reasons for ending, switching, or skipping a direction. Leaving the expected list aborts scanning.

### 1.6.2-beta.9 — Vertical Map Panning & Tablet List Ends

Minimum-zoom maps match the screen width, so they are dragged vertically only. Keeping the spot's X coordinate fixed, it chooses a target height avoiding fixed buttons within the vertical origin range computed from map height and scale. If a drag moves less than requested, the next drag size is scaled by the actual movement ratio. Only when the map does not move or a safe position is unreachable within bounds does it fall back to tapping the visible part of the icon.

Geometric maps without a reference image also verify boundaries and pan to the target origin by comparing map slices before and after movement. If movement measurement itself fails or exceeds safety limits, that spot search aborts safely. Map panning logs record target origin and movement ratio. Actual top/bottom captures of tablet Home, Main Record, and Main Interlude lists were added to regression tests.

## 1.7.0-beta.3 Map Blocked Regions

Maintains at least 12px of clearance (at 720p) around the Master portrait diamond and Level/AP area. Spots hidden behind the portrait—such as Okeanos `Archipelago` (`군도`)—are not tapped; instead, the map is moved to a safe spot. If immovable, it uses the visible-part fallback or skips with `spot out of reach`.

## 1.7.0-beta.7 Early Spot Tapping

Each time position is re-checked after dragging the map, if the actual tap point (icon center, visible part, or spot coordinate) is unobstructed by UI and at least 60px inside the top and bottom edges (at 720p), it taps immediately instead of repeating small corrective drags toward the target origin. Planning prefers the spot coordinate first and uses the icon center only if no safe spot position exists.

When centering and retrying because no panel opened, it still pans all the way to the target position as before. Speech-bubble blocked regions, edge recovery, reference-less map handling, and the 16-drag maximum limit remain unchanged. In the beta diagnostic folder's `map.log`, `reason=early` on a `tap point` line indicates an early tap, while `reason=converged` indicates a tap after finishing panning.

### 1.7.0-beta.5 — List End Reading

Weekly lists check upstream FGA's top and bottom scrollbar-end reference images in a small region: bottom-end skips downward swiping, and top-end skips upward swiping. Single-page lists showing both ends are not swiped. If ends are not found, the 14-swipe per-direction limit applies, and leaving the expected list aborts scanning immediately. Ends are never inferred from a center rail or absence of a rail.

## 1.7.0-beta.8 16:9 Map Calibration and map.log

- Early taps (`kind=icon`) are performed only when the icon center is at least 4px clear of blocked regions. Shifted-icon taps (`icon-shifted`) and spot-coordinate taps (`spot`) happen only after map movement finishes.
- On 16:9 screens where neither the spot nor the icon has an unobstructed row (such as Fuyuki X-G), it no longer gives up immediately: it moves the map within movable bounds to where the icon is least obstructed and taps the visible part. `spot out of reach` is reported only if no tappable point exists anywhere in the movable range.
- Expanded map recognition scale tolerance to 0.45–1.0 to support 22:9 (1760x720) displays.
- Beta diagnostics write a single `map.log` per run: lines are buffered and written back to the same file every 10 lines and at exit (preventing duplicate `map (1).log` files).
- Probe CSV `panel_mismatch` rows and Treasure Vault mismatch rows record the actual OCR-read names in `read_quest` and `read_spot`, and `map.log` also records `mismatch` lines with read vs. expected names.

## Map Positioning — beta.10

Image-based map matching broke on Olympus, Atlantis, Agartha, Salem, Avalon le Fae, and others, and has been retired. Every chapter now uses the same positioning method.

- **Confirm minimum zoom.** Measures the before/after scale of the center of the map after a pinch. If already at minimum zoom, pinching once more leaves scale at 1.0. Confirmed only after two consecutive pinches show no change (up to 6 attempts); otherwise that chapter is skipped.
- **Anchor to an edge.** At minimum zoom, the map fits the screen width, so scale = screen width ÷ map width, and vertical travel range = map height × scale − 720. If the spot is tappable from the top or bottom edge, it drags long to that edge—otherwise to the closer edge—and drags once more to verify the map stops moving. At the edge, position is known accurately.
- **Middle spots.** Spots unreachable from an edge start from the anchored edge and drag up to 180px at a time, measuring before/after movement on every drag to accumulate position (keeping at least 100px overlap). If measurement fails, it returns to the edge to retry once; if it still fails, it skips with `map movement did not converge`.
- **Tap.** Finally, it taps using the same rules as before (fixed button zones, edge margins, icon/spot), with panel name verification as the final safeguard.
- **Logs.** `map.log` records `edge quest=… edge=Top|Bottom scale=… range=…`, `step quest=… drag=… measured=… origin=…`, and `tap point …`.
- **Limitations.** Maps that are not constrained to screen width are skipped with `map not recognized` (none in current data). The 4-map check for Heian-kyo has been removed.
