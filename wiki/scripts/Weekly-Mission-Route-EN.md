# Weekly Mission Clear

**This feature calculates the quests and run counts that clear this week's weekly missions for the least AP, then navigates from the main screen to each chapter and map to run the full route. Check your battle configuration and apple refill settings before starting.**

Works on the KR and JP servers and requires an internet connection. It does not read your actual in-game mission progress.

## How to Start

1. Open the game's **main screen** (the screen with `공지` / `お知らせ` at the top left). Do not start from a quest panel or chapter list.
2. Tap the FGA Play button → **Weekly mission clear** at the bottom left of the launcher dialog.
3. The **Weekly Mission panel** opens on the **right side** of the launcher dialog (tapping the same button again collapses it). While the panel is open, the **Limit** section on the left is hidden and restores without altering saved values when collapsed. The battle config, AP refill, item, and bond settings on the left side are used as-is.
4. Tap **Download**. Download stages for missions, chapters, and quest enemies are shown with a progress bar. If this week's cache already exists, the route appears immediately.
5. Manual missions have no checkbox, and any previously saved selection is ignored. Unchecked missions remain grayed out in their original order. Uncheck a mission to exclude already-completed or unwanted missions from the calculation. All supported missions default to checked, per-server/per-week selections persist across app restarts, and selections reset on a new week. Changing selections recalculates the route and resets **−/+ counts** to the newly computed values.
6. Check the total AP and per-quest counts, then tap the shared **OK** button. If the panel is open and the route still has remaining runs, it executes the weekly route; if the route is empty, tapping OK displays **There are no quests to run** without running. If the panel is collapsed, tapping OK starts a normal battle; tapping **Cancel** performs no game actions.

**More options → Battle → Use weekly mission automation** defaults to ON. Turning it off hides the Weekly mission clear button in the battle launcher.

Enable or disable candidate chapters and quests per server under **More options → Advanced → Weekly quest tracking settings**. Tap `+` on a chapter name to expand its free quests. All default to ON, and turning off a chapter preserves your individual quest selections. If no data is cached yet, download that server's data in the launcher panel first. Mictlan and Ordeal Call I–IV are unsupported and do not appear in the list.

## Confirmation Dialog and Route Calculation

**Treasure Vault Beginner** (10 AP) is also a candidate. Because its enemy lineup is fixed at 9 Casters, only Beginner is used instead of other difficulties, and it is always visited last in the route. It can be turned off under **Weekly quest tracking settings → Daily Quests**, and is also included in Navigation Probe Mode.

After other quests finish, the script confirms the main screen using the notice button and navigates **Main → Chaldea Gate → Daily Quests → yellow Treasure Vault Beginner panel**. After tapping Gate, it waits for it to close; finds and taps the Daily banner in the list; and waits for both closing and a daily quest panel anchor (ignoring right-side titles and event backgrounds). In the list, it treats the **first yellow panel from the top** (hue 38–52°, saturation 0.80 or higher) as Beginner without reading its name (name and AP are read only for `map.log` logging). If no yellow panel is visible, it scrolls down and aborts if none is found. Red Ember quests, green Training Grounds, and `CLEAR` / `NEW!` / `∞` panels are excluded from color matching. It matches by color even when displayed AP changes during campaigns, and returns to the Daily Quests list after the final run.

| Item | Description |
|---|---|
| This week's missions | Only checked missions are included in calculation. Uncheck already completed missions. Partial progress can be adjusted via run counts. |
| Unchecked missions | Remain grayed out in their original positions in this week's list and can be re-enabled. Manual missions have no checkbox. |
| Route | Shows Chapter / Spot / Quest name / AP / run count and total AP. Quests with count 0 are not run. |
| Candidate settings | Chapters or quests disabled in Weekly quest tracking settings are excluded from route calculation. |
| Manual | Conditions that cannot be solved automatically, such as drop missions. Complete these manually. |
| Excluded | **Unsupported chapters:** Mictlan (war 311) uses a different layer-switching UI, and Ordeal Call I–IV (war 402–405) are excluded by policy. They do not appear in route candidates, probe targets, Weekly quest tracking settings, or chapter lists (both JP and KR). Heian-kyo (307) and Traum (310) are supported as Main Interlude chapters. |
| Battle config | Uses the config selected in the left list. Choose a config that works across differing enemy lineups. |

Only repeatable main-story free quests are included; one-time quests with `afterClear=close` (such as Fuyuki `危険地帯`) are excluded. Supports enemy traits, enemy classes, Servant classes, specific enemies, quest clears, and quest traits; unsupported missions or missions with no reachable candidate are marked manual. "Total missions" objectives are fulfilled alongside completing individual conditions.

KR candidates, names, and AP come from KR data, while **KR enemy lineups are based on JP data**. Quests whose JP AP and KR AP differ for the same quest ID are excluded. Solver data is cached per server and week, and Atlas Academy chapter banners, map dimensions, and spot coordinates are fetched and cached.

## Automatic Navigation and Battle

### List Scanning and Entering Chapters

- JP navigates Main Record **1 / 1.5 / 2**. KR navigates **1 / 1.5** and locates Part 2 chapters from the main screen list. Heian-kyo (307) and Traum (310) reside inside the gold **Main Interlude** (`メイン・インタールード` / `메인·인터루드` / KR home **메인 인터루드**) button at the bottom of the main screen rather than Main Record, so they are visited last.
- Chapter banners are matched using the inner button image of the Atlas banner. When scanning lists, top and bottom scrollbar-end reference images from upstream FGA are checked in a small region to skip already-reached directions, and single-page lists showing both ends are not swiped. If ends are not found, it searches down then up for up to 14 swipes per direction (animated backgrounds never cancel end checks, and end state is never inferred from a center rail); leaving the expected list or failing to find the chapter skips or aborts safely.
- Right after entering a map from a chapter banner and returning to Terminal, the script waits 3 seconds each. Heian-kyo has 4 maps and its Free Quests are on `30702`; if a different map is open, spot verification rejects and skips it without guessing map-switching UI.

### Map Zoom and Positioning

Every chapter uses the same geometric map-positioning method.

- **Confirm minimum zoom**: After entering a chapter, pinches to minimum zoom and verifies scale = 1.0 across two consecutive pinches (up to 6 tries); otherwise that chapter is skipped.
- **Edge anchoring and vertical panning**: Minimum-zoom maps fit the screen width (`scale = screen_width ÷ map_width`, vertical travel range = `map_height × scale − 720`, recognition scale tolerance `0.45–1.0` including `22:9` displays). Keeping the spot's X coordinate fixed, spots reachable from the top or bottom edge drag to that edge—otherwise to the closer edge—and verify the map stops moving. Middle spots start from the anchored edge and drag up to 180 px at a time while measuring before/after frames (keeping at least 100 px overlap, 16-drag cap); if measurement fails, it retries from the edge once, then skips with `map movement did not converge`.
- **Blocked-region clearance and early tapping**: Maintains at least 12 px clearance at 720p around the Master portrait diamond, Level/AP area, Gift/Mission speech bubbles, and MENU, choosing rows at least 60 px inside top/bottom edges where possible. On intermediate position checks, if the icon center is at least 4 px clear of blocked regions and 60 px inside top/bottom edges, it taps immediately (`reason=early`); otherwise it finishes panning (`reason=converged`). On 16:9 screens where no unobstructed row exists, it shifts to the least obstructed position in movable range and taps the visible part (up to 30 px away, staying ≥ 4 px clear), reporting `spot out of reach` only if no tappable point exists anywhere.

### Reading and Selecting Quest Panels

- When tapping a spot, the script aims at the icon center (25 px above the Atlas spot coordinate), falling back to tap the visible part or an unobstructed spot coordinate if covered, or skipping if all points are blocked. If no panel opens after tapping a spot, it taps once more 1 second later and records `no panel after spot tap` if still absent.
- Quest panels locate the **Free Quest** label in a wide band and **read first**. On a Match it proceeds without dragging; only when the label is cut off or unmatched does it drag once top-to-bottom on a button-free area inside the panel and re-read. If it still fails, it closes the panel (up to 3 taps waiting up to 3 s each, plus 1 recovery attempt), moves spots within 60 px of edges inward toward screen center when possible (or retries opposite direction if immobile), and tries once more. Party-restricted mission quests are never tapped; if one is on top, lower Free Quests are inspected too.
- Quest names are read in 4 modes (2×/3×/4× scale, binarized and grayscale) against the server's **full Free Quest dictionary** (or per-spot Atlas candidates when ≥ 2 Free Quests exist at that spot). Minor kanji/batchim misreads are tolerated only if within 2 characters and 1/3 of name length, at least 2 closer than runner-up, and confirmed by two agreeing reads.
- Enlarged **spot name** labels provide a second check: whenever a quest name reads reliably, **the quest name decides alone** (`by=quest-name` or `by=quest-name-spot`) and rejects any target mismatch even if the spot name matches. Only when the quest name is completely unreadable is a panel accepted via `by=spot-name+cells` (spot name matches, spot has only 1 Atlas Free Quest, Free Quest label present, and name width matches). AP is logged only and never used for matching. Mismatches are never tapped.

### Battle and Returning

- Battle, support selection, and AP refill follow the standard FGA flow. Only the per-quest run count limit overrides temporarily and restores on exit, and total apples spent are deducted once across the route.
- After closing the Repeat dialog following a quest's final battle, it waits **5 seconds longer** for loading back to the map. Subsequent quests in the same chapter close the panel and relocate the spot; switching chapters returns via `터미널` / `ターミナル` and `닫기` / `閉じる`.

## Exit Summary and Troubleshooting

At exit, it shows each quest's **planned / actual / done · not found · stopped · not run** counts and manual missions. If a battle stops due to insufficient AP, defeat, retreat, or user stop, the remaining route is not run.

If not on the main screen, nothing is tapped and you are prompted to start from the main screen. If loading data fails, check your internet connection and Atlas Academy reachability; if route calculation fails, reduce enabled chapters and retry.

```text
adb -s 127.0.0.1:5555 logcat -s WeeklyRoute
```

## Navigation Probe Mode (Beta Only)

A **beta-only** mode for testing whether quests across all chapter maps can be found in-game without spending AP (hidden in Stable builds, which also omit diagnostic files).

- **Where to use**: Check **Navigation probe (no AP is spent)** at the top of the weekly mission panel. Choose **All main story free quests**, **Selected chapters**, or **Quests enabled in the weekly settings**, plus optional **Skip quests that already succeeded** (per server; excludes Mictlan and Ordeal Call). Logs tag `mode=probe`.
- **Behavior & safety**: Start on the main screen; the script follows the full navigation and panel judgment flow, **never taps quest panels even on Match**, closes the panel, and continues to the next quest. Stopping early saves results collected so far and marks unvisited quests `not run`.
- **Results & diagnostics**: Records status (`success`, `map fit failed`, `no panel after spot tap`, `panel mismatch`, `chapter not found`, `not run`; post-panel-open failures log `panel_mismatch`, while initially unreachable spots log `spot out of reach`) and duration in the summary dialog and CSV under `diagnostics/weeklyRoute/probe/`. Saves 2× evidence PNGs (`diagnostics/weeklyRoute/<run>/evidence/q<questID>.png`) for successes, diagnostic PNGs for panel mismatches (up to 8 per run), probe failures (up to 100), and route aborts, plus a single `map.log` per run. Generate an HTML audit sheet (`spot-name+cells` sorted first) on PC with:

```text
py -3 tools/weekly-audit/contact_sheet.py probe-JP-<timestamp>.csv <evidence-folder> -o audit.html
```

## Precautions and Limitations

- Be sure to disable chapters you have not unlocked yet under **Weekly quest tracking settings**. Because live enemy lineups can differ slightly from Atlas data, verify actual weekly mission completion in-game.
- When aspect ratios, fonts, or unusual layouts leave insufficient reading certainty, quests are safely skipped instead of guess-tapped (`spot out of reach`, `map not recognized`). Test new devices or aspect ratios first with a short route or beta **Navigation Probe Mode**.
