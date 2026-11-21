# Battle Config Folders

When you have many battle configs, you can group them into collapsible folders. Folders are only for organizing the list; battle behavior is identical to having no folders.

## Creating Folders and Moving Configs

1. Long-press a config in the **Battle Configs** list. Selection mode turns on and a checkbox appears to the left of each config; you can tap other configs to select them together.
2. Tap the **Move to folder** button at the top.
3. Choose an option in the dialog:
   - An existing folder name: moves the selected configs into that folder.
   - **New folder…**: enter a folder name and tap **OK**. An error is shown if the name is blank or over 30 characters. If an existing folder differs only in letter case, that existing folder is reused.
   - **Remove from folder**: moves the selected configs out of their folder.
4. Once moved, selection mode closes and the target folder appears expanded.

## Appearance in the List

- Folders have only a single level; folders appear first in alphabetical order, followed by unfiled configs in alphabetical order.
- Tap a folder row to expand or collapse it. The row displays the number of configs inside.
- Each folder is shown as a single boxed card; when expanded, its configs appear inside the box. Configs outside boxes are unfiled.
- On wide screens (tablets, landscape), the list is displayed in two columns.
- Every time you open the list, all folders start collapsed. Expanded states are not saved.
- Grouping applies per server tab (`ALL` / `JP` / `KR`, etc.) and per server section. Even folders with the same name show only that section's configs in each section.
- In selection mode, if a collapsed folder contains selected configs, `"N selected"` is displayed on the folder row.
- Removing or deleting the last config inside a folder automatically removes that folder. There is no separate rename or merge action—simply move configs to a folder with the new name.

## Launcher Screen

- The config picker in the battle launcher shows the same folder boxes as the Battle Configs list. Folders that have no configs usable on the current server are hidden.
- The folder containing the last-selected config opens expanded automatically and scrolls that config into view. Other folders start collapsed, and if a collapsed folder holds the selected config, the folder row displays `"Folder · Config name"`.

## Exporting and Importing

- Exporting to a `.fga` file also saves the folder name, and importing restores the config into that same folder. Copying a config also preserves its folder.
- Older `.fga` files without folder metadata import as usual without a folder.
