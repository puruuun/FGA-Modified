# Chaldea Auto Sync

**Automatically writes account data gathered via Log collection into Chaldea's `userdata.json`. No manual "Import" inside Chaldea is required.** Currently syncs items, Servant Coins, Craft Essences, Command Codes, Mystic Code levels, Class Score boards, Servant progression, Costumes, and Master gender.

Uses Shizuku to read and write Chaldea's data directory (`/sdcard/Android/data/cc.narumi.chaldea/files/user/`). Supports both KR and official JP server accounts, and requires **Log collection** to be enabled. Disabled by default; while off, FGA never invokes Shizuku features (no permission prompts, binding, or service launch) and does nothing. The component (Provider) that receives connection tokens from Shizuku stays registered from app install, only receiving tokens without performing any action.

## Preparation

1. Install and start **Shizuku** on your device (via Wireless debugging or root). Shizuku 13 or later is required.
2. Turn on log collection on FGA's **Log collection** screen.
3. Below on the same screen, under **Chaldea auto sync**, turn on **Sync into Chaldea automatically**.
4. Turning the switch on brings up Shizuku's permission prompt for FGA—tap **Allow**. If Shizuku is not running yet, the prompt appears once Shizuku starts. If the prompt does not appear or no result returns, FGA rechecks when you return to FGA (up to twice per toggle); if it is still not allowed, grant permission inside the Shizuku app.
5. Open Chaldea at least once so `userdata.json` exists. If the file is absent, FGA does not create it and displays `"Chaldea's data file was not found."`.

Configurations where Chaldea's **Use external storage** option moved the data directory are not supported (if the file is missing at the default location, nothing is written). If `userdata.json` or `.bak` exceeds 16 MB (normally around 1 MB), FGA leaves it untouched. When both `cc.narumi.chaldea` and `cc.narumi.chaldea.fdroid` are present, FGA writes to whichever `userdata.json` was modified more recently and shows which package was used in the status line.

## What Is Synced

| Synced | Not synced |
|---|---|
| Items (including QP, Saint Quartz, Mana Prisms, Rare Prisms) | Servant raise plans (`plans`) and priorities (`priority`) |
| Servant Coins (written into items) | `itemsLeft` (remaining count), duplicate Servant mapping (`dupServantMapping`) |
| Craft Essences (owned/unowned state, max level, max limit break; highest value including Second Archive cards; owned CEs without held cards use the collection's max level/LB, while encountered status sets status only) | |
| Command Codes (owned/unowned state, owned count) | |
| Mystic Code levels | |
| Class Score boards (unlocked nodes and locks) | |
| Servant progression (NP level, ascension, Palingenesis, Skills 1–3, Append Skills 1–5, bond level & cap, ★3/★4/★5 HP/ATK Fou, Grand Servant, 5 equipped Command Code slots, Command Card enhancements, favorite flag) | |
| Costumes (Spiritron Dresses) | |
| Master gender | |

- Items and Servant Coins **merge by overwriting** following the same rules as Chaldea's manual import: only entries present in captured data are updated and nothing is deleted.
- **Items that disappeared in game retain their previous values in Chaldea**, matching Chaldea's manual import behavior.
- Craft Essences and Command Codes overwrite only the needed fields while preserving other existing fields (owned CEs with `status == 2` and no held card copies use the collection's recorded max level/limit break, or level 1 and limit break 0 if newly added without those values).
- Craft Essences, Command Codes, and Servants are matched using Atlas collection-number lists cached by FGA.
  - Lists check Atlas's data version in the background when the switch is turned on, on app start, or after an account snapshot is saved, re-downloading only if changed (checked at most once per hour; if a script is running at check time or starts mid-download, remaining lists are deferred until the script ends; if the version cannot be read, retried once per day every 7 days).
  - New CEs and Servants not yet on Atlas are skipped and written on the next sync after Atlas updates.
  - Command Codes whose collection number is unknown are skipped, with the skipped count shown in the status line and retried on the next sync.
- Servant progression follows Chaldea's default import rule by picking the **single best card copy** per Servant (including Second Archive, ranked by level → skill sum → creation time → unique ID) and **writing progression values only when that first card is locked and neither converted nor removed** (if that top card is unlocked, progression is not written even if a subsequent copy is locked). The 5 equipped Command Code slots and Command Card enhancements are synced from separate game tables.
- Existing Servant `priority`, `plans`, and any other fields FGA does not write are preserved intact.
- **Costumes** are matched by reading the game data Chaldea downloaded into its own folder (`files/game/`) and are synced on the next run after Chaldea updates its data. Any costume newer than Chaldea's cached data is left blank until Chaldea updates its game data, after which it fills in automatically. Older accounts other than the currently active one receive new costume data when you log into them again.
- Class Score boards replace one board as a whole; other boards remain untouched.
- Chaldea plans, other users, and fields FGA does not know are never modified. `itemsLeft` is not written either.

## When Sync Runs

- Syncs after account data stays **idle for 1 minute** following a change. If the data matches what was last synced, nothing is written and Chaldea is not closed.
- **Never syncs while a script is running.** Waits 15 seconds after a script ends before syncing; if another script starts during that wait, the sync is canceled.
- **Sync now** immediately syncs the current account for each server (writing even if identical to the last sync). Disabled while a script is running.
- If Android stops the FGA process, syncing pauses too. Syncing works on a **best-effort basis** and picks up pending items once FGA returns to the foreground.
- **Stop when FGO and Chaldea are not in use** (default 10 min; choose **After 10 min / After 30 min / After 60 min**): if neither FGO (KR/JP) nor Chaldea is used for the chosen duration while waiting (neither is visible on screen or the screen is off), FGA handles one final pass according to the current state and stops waiting. While the screen is off, both apps are treated as unused.
  - If Chaldea is closed, FGA writes once and stops.
  - If Chaldea is in the background and **Close Chaldea when it stays in the background** is not **Never close**, FGA rechecks Chaldea's state, force-stops it, writes once, and stops (if set to **Never close**, it stops without closing or writing).
  - If Chaldea is visible on screen or its state cannot be determined, FGA stops without closing or writing.
  - After stopping, reopening FGA or changing permissions does not resume waiting; syncing resumes when a new account snapshot is saved, a script ends, or you toggle the switch off and back on. **Sync now** still works while stopped, performing a single sync without restarting background waiting. Any remaining unsynced accounts continue on the next start.

## When Chaldea Is Open

Because Chaldea overwrites its own files while open, FGA writes **only when Chaldea is completely closed**.

- If Chaldea is **visible on screen** (fullscreen, split screen, floating window, or screen turned off while visible), FGA never closes it and waits—checking every 1 minute for the first 10 minutes, then every 5 minutes thereafter.
- If Chaldea's state cannot be determined, FGA neither closes nor writes (`"Cannot determine Chaldea's state."`).
- If Chaldea is **running in the background**, FGA tracks elapsed background time. Once the configured timeout passes, it rechecks the state right before closing, force-stops Chaldea, and syncs. If Chaldea comes back to the screen in the meantime, the timer resets.
- After writing, FGA checks again whether Chaldea opened during the write; if it did, the sync is not marked complete and will retry later.

### Auto-Close Timeout

Under **Close Chaldea when it stays in the background**, choose **After 10 min / After 30 min / After 60 min / Never close** (default 30 min). Choosing **Never close** guarantees FGA never closes Chaldea and waits until Chaldea closes on its own.

If Chaldea is open when you tap **Sync now**, a confirmation prompt appears before closing it; if Chaldea's state changes after confirming (e.g., it comes to the foreground), FGA asks again.

## New Users and Takeover

FGA matches a Chaldea user to an FGA account by **friend code**. When a Chaldea user's `lastImportId` matches the account's friend code, FGA writes to that user. Accounts without a friend code yet are not synced (`"Account not identified (no friend code yet). Not synced."`).

If no match exists:

- If Chaldea has **only one user, has never been imported into, and is completely empty** (the default user), FGA takes over that user and fills in its name, region, and friend code.
- Otherwise, FGA creates a **new user** named `<Master Name> (KR)` or `(JP)` with the account's server as its region, without switching Chaldea's currently selected user.
- Creating or taking over a user happens only for each server's **current account**; older previously logged accounts are synced only if already paired.
- If the paired Chaldea user's region differs from the game server, FGA leaves the region unchanged and notes it in the status line.

## Backups

Before writing, FGA copies **both original Chaldea files (`userdata.json` and `userdata.json.bak`)** into FGA's private folder `files/chaldea-backup/<package name>/` and reads them back to verify integrity before modifying anything. If backup fails, nothing is written. Each backup folder keeps both files under their original names:

- `first-before-fga/`: the original snapshot from FGA's very first write—never modified or deleted.
- `backup-…/`: the last 3 backups; no new backup is created if identical to FGA's last write.
- `inflight-base/`: exists only during an active write and is removed once the write is verified.
- Deleting log collection data never deletes these backup folders.

If writing fails mid-way, FGA restores `userdata.json` and `userdata.json.bak` **individually back to their original contents**. If FGA stopped mid-write earlier, the next sync inspects the files and restores from backup if `userdata.json` is corrupted—again restoring each file individually while leaving any intact file untouched. If restoration cannot be verified, FGA reports the error and preserves the backup folder.

- Unverified recovery is retried up to 3 times; afterward, **all syncing halts** (an intentional safety lock to avoid writing over files in an unknown state). Nothing is written until you toggle the switch off/on or tap **Sync now**, with the reason displayed in the status line and backups kept intact.
- Even if a write appears finished, if `userdata.json.bak` matches neither the old nor the new contents (partial write interruption), FGA treats it as interrupted, restores both files individually, and retries syncing. `inflight-base/` is deleted only after both files are verified.

## Rooted Devices

On rooted devices, FGA performs the same sync via root without needing Shizuku.

- When root is available, FGA **automatically uses root**. If both root and Shizuku are available, **root takes precedence**. While using root, no Shizuku permission prompt is shown and the status line shows `Access: root`.
- **The first time**, Magisk or KernelSU asks whether to grant root permission to FGA—triggered when turning on the toggle (or tapping **Sync now** if root wasn't granted yet). With KernelSU, you may need to add FGA to the allowlist manually.
- Magisk shows a "Root permission granted" toast once per FGA launch; you can disable notifications for FGA in Magisk's Superuser list if desired.
- After writing via root, FGA verifies that Chaldea's file owner and permissions match their originals and restores them if changed; if restoring fails, `"The owner or permissions of the Chaldea files changed and could not be restored. The backup is kept."` is shown.
- While a script is running, FGA neither checks nor connects to root.
- If root is absent or denied, FGA falls back to Shizuku as described above.
- If root connections fail repeatedly and FGA stops using root for the rest of the run, the saved "root granted" record is kept on purpose, so the next time the app process starts FGA runs one silent root check again.

## Status Line Messages

The status line displays the **last result separately** per server (`KR`, `JP`). Two lines (`KR: …`, `JP: …`) appear if both servers have results, or one line if only one does. Succeeding on one server does not clear a failure message on the other. Shizuku issues always appear first as a single line.

| Message | Meaning & action |
|---|---|
| `Off.` / `Nothing to sync yet.` | The switch is off, or no changes need syncing yet. |
| `Last sync …: N items, …` | Success. May also append skipped Command Code counts (unknown collection ID), `"A new Chaldea user was created."`, region notice, and app package used. |
| `Waiting: a script is running.` | Syncs once the running script finishes. |
| `Waiting: Chaldea is open.` | Continues once Chaldea is closed or moved to the background. |
| `Waiting: Chaldea has been in the background for N min` | Closes Chaldea and syncs once the configured delay elapses. |
| `Auto-sync stopped: FGO and Chaldea were not in use for N min. It resumes when new game data arrives or a script ends.` | Stopped waiting because neither app was used for the configured time. Resumes on the next snapshot save, script end, or toggling the switch (**Sync now** writes once without restarting background waiting). |
| `Shizuku is not installed.` / `Shizuku is not running.` | Install or start Shizuku. Syncing resumes once Shizuku starts (including after reboot). |
| `Shizuku has not allowed FGA yet.` / `Shizuku denied FGA.` | Grant permission in the Shizuku prompt or inside the Shizuku app. |
| `Cannot reach the Chaldea sync service.` | Retries automatically after a moment. |
| `Cannot determine Chaldea's state.` | Waits without closing or writing. |
| `Chaldea started during the sync.` | Retries later. |
| `Chaldea's data file was not found.` | Open Chaldea once. |
| `No access to Chaldea's data folder.` | Check Shizuku permissions. |
| `Unknown Chaldea data version.` | Unsupported file format; Chaldea was not modified. |
| `Chaldea file unreadable, not modified.` | Both `userdata.json` and `.bak` are corrupted; check inside Chaldea. |
| `Backup failed. Chaldea was not modified.` | Check free storage; retries on the next change. |
| `Account not identified (no friend code yet). Not synced.` | Friend code has not been captured yet; log into the game again. |
| `Account data is too large to send to Chaldea. Not synced.` | Data exceeds Android's Binder transaction limit, so Chaldea files were left untouched. |
| `The sync failed and Chaldea's data was restored.` | Original files are intact; retries on the next change. |
| `Restore failed: backup at …` | Could not auto-restore. Manually copy `userdata.json` and `userdata.json.bak` from the shown backup folder into Chaldea's `files/user/` directory. |
| `A sync that was interrupted earlier could not be checked (code).` | Cannot verify an interrupted write, so nothing is written. Report the code, and toggle the switch off/on to retry; backups remain safe. |
| `The sync failed (code).` | Other failure; report the error code. |

Failure messages stay visible until the next successful sync or toggling the switch.
