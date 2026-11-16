# Import original FGA data

Open **More options → Additional features → Import original FGA data** and choose one of the following options:

- **Scripts only**
- **Support images only**
- **Scripts and support images**

## Preparation and Importing

1. To move scripts, open the original FGA's **Battle Configs → Export all** to save `.fga` files into a folder.
2. In FGA(mod)'s import dialog, select that folder with **Select exported scripts folder**. `.fga` files inside subfolders are also read.
3. To move images, choose the original FGA storage folder or its `support` subfolder in **Select original support images folder**. Images under `servant`, `ce`, and `friend`, including servant variant subfolders, are copied into the current FGA(mod) storage folder.
4. Tap **Import** to see progress along with imported, skipped, and failed counts for scripts and images. Files with errors show their filename and reason while remaining files continue processing.

When the app is updated, built-in support images are automatically re-extracted to the storage folder (and also extracted once right after changing the storage folder).

Before importing images, set the FGA(mod) destination folder under **More options → Storage**. If importing both categories, select both the scripts and images folders. For script-only imports, you do not need to set an images folder or FGA(mod) storage folder.

## Existing Data and Conflicts

- Existing scripts are retained; for each configuration with identical contents including its name, only the missing copies are added. For example, if the original exported `a.fga`, `a (1).fga`, and `a (2).fga` with identical contents, all 3 are imported when none exist, or 2 are added and 1 is skipped if 1 already exists. Importing the same folder again does not create extra duplicates. Configurations with the same name but different contents are imported as separate scripts.
- Images with the same name are skipped by default to keep existing files. Check **Overwrite existing images with the same name** to replace them with the original images.
- When the same storage folder is selected as both source and destination, identical image files are not rewritten. Selecting a destination folder nested inside the source images folder is rejected.
- Support image filenames and script references are preserved. If any images were missing, skipped, or failed, review the support settings of the imported scripts.

## Compatibility Scope

There is no build-number restriction. Both legacy flat JSON `.fga` files and the current format are supported, and supported extra settings are preserved. Scripts containing commands that this modified build cannot parse are reported as failures. Future upstream format or command syntax changes may require compatibility updates.

The importer never accesses the original app's private data directly. It only reads exported scripts and image folders granted by the user. Global app settings, AP refill settings, and Android permissions are not transferred.

The three selection modes, legacy JSON handling, duplicate prevention, name preservation, per-item failures, and image conflicts are verified by automated tests. Behavior of the Android document picker and device-specific storage providers requires separate device verification.

## Exporting Modified Scripts Back to Original FGA

`.fga` files exported from FGA(mod) can also be imported directly into the original FGA. Because the file format is identical and the original app simply stores unknown fields without using them, it will not crash or corrupt settings. Skill commands, card/servant priority, Brave Chains, spam settings, party, materials, and basic support settings work as usual in the original app.

However, FGA(mod)-only settings **are not applied** in the original FGA:

| FGA(mod) setting | Behavior in original FGA |
|---|---|
| `&` in skill commands ([Direct cards](Direct-Cards-EN.md)) | **Stops at battle start with a "Failed to parse skill command" error.** Remove `&` or replace with standard commands before using in original FGA. |
| Support ascension select (NP image), [Append Skill check](Support-Append-Skills-EN.md), +62/+16 score checks, [NP level check](Support-NP-Level-EN.md) | Ignored. **Supports are picked without those conditions**, so double-check the support selection in original FGA. |
| Second execution config, [Card-count start](Card-Count-Start-EN.md) | Ignored; only the first (default) config runs. |
| [Bond gain tracking](Bond-Tracking-EN.md) | Ignored. |
| Shuffle "First-turn command start condition unmet" | Not recognized in original FGA and falls back to **None (no shuffle)**. |

After importing into original FGA, review your support settings and skill commands before running.
