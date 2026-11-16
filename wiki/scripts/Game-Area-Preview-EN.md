# Game Area

> This is an experimental feature. The menu item is **Game area (experimental)** at the bottom of **More options → Additional features**.

Configure the bounds of the game screen under **More options → Additional features → Game area (experimental)**. Choose from **Default**, **Custom**, or your saved presets. The Surface Duo-specific option has been removed; if you previously used it, it is read as Default. Existing custom offsets are kept.

## Fitting in Split Screen

### Calibrate from Screenshot — 1.7.0-beta.11

On the JP or KR main screen, you can use **Calibrate from screenshot**.

1. Start the FGA service with screen capture enabled, and leave the game on its main screen. Close any in-game popups.
2. Under **More options → Additional features → Game area (experimental)**, choose **Custom**, then tap **Calibrate from screenshot** below it. The button is hidden when Default or a preset is selected.
3. Existing preview borders hide briefly, and after 1 second the full display is read. The actual game image bounds are calculated from the positions and sizes of the Notice and MENU buttons. Split-screen title bars, dividers, and top/bottom dark-blue margins are excluded from the saved region.
4. Check the Left, Top, Right, and Bottom offsets, game size, and scale in the result dialog, and verify that the preview border on the game side follows the edges of the game picture. It also runs the normal recognition path on the computed region to double-check that MENU is visible.
5. Tapping **Save as new preset** creates and immediately selects a preset named **Auto MM-dd HH:mm**. Existing presets and Custom offsets are unchanged. Tapping **Cancel** discards the result and returns to the previous preview state.

If the service or screen capture is not active, a prompt asks you to start the service first. Other servers show an unsupported-server notice. Nothing is saved if Notice or MENU cannot be found, if the two buttons have different scales, if bounds cannot be determined, or if the MENU double-check fails. Close popups on the main screen, keep the layout unchanged, and retry. Do not run scripts while calibrating.

What gets saved are the **margins of the game picture itself**, not the whole window. If you change the split ratio or screen orientation, re-calibrate and save again. If auto-calibration does not fit, use the manual settings below.

### Setting Offsets Manually

1. Open FGO and FGA settings side by side in Android split screen. Use a game screen where the edges of the actual game picture are visible, such as FGO's Menu or a battle screen.
2. Turn on the FGA accessibility service and choose **Game area (experimental) → Custom**.
3. Turn on **Area preview** below the Left, Top, Right, and Bottom offsets. If the accessibility service is off, the switch is disabled with an explanatory message. Debug mode is not required.
4. Adjust the offsets so the **green border aligns with the edges of the actual game image**. Do not include the whole split window, top title bar, or blue pattern outside the game. Borders update live while holding down the stepper buttons. Tap a number to type an offset directly.
5. Leave the settings screen when finished. Capture scaling is applied once when exiting after changing offsets. Selecting an item in the Game area list or tapping **Reset** applies immediately. If the service is off, the current settings are used the next time it starts. Running screen capture keeps its existing permission without prompting again.
6. Keep the screen layout you plan to play in. **If you change the split ratio, re-select a saved preset or re-adjust Custom offsets.** If the layout itself changes, adjust offsets for the new layout. There is no automatic re-adjustment or out-of-bounds warning at script start.

## Minimum Area and Reset

The configured region must be at least **1280 width and 720 height** in screen pixels. Each offset's upper bound is computed from the display size and opposite margin. Reaching the minimum size disables the corresponding **+** button, and typed values are clamped to the allowed range. If the screen width or height is smaller than that, all Custom offsets are restricted to `0`.

**Reset** returns all four Custom offsets to `0` and applies capture scaling. It does not delete saved presets. Game area settings cannot be changed while a script is running.

## Saving, Selecting, and Deleting Settings

1. Adjust the offsets under Custom and tap **Add setting**.
2. Enter a name and confirm. The default name is **Split screen N**.
3. The saved name is added to the Game area list. Selecting it applies the saved four offsets and capture scaling.
4. On a saved preset screen, offsets are shown read-only and you can toggle **Area preview**. To modify one, adjust under Custom and save as a new preset.
5. Tapping **Delete** removes the selected preset and returns to Default. Other presets saved under the same name are kept.

## Meaning of Borders and Numbers

- **Red border**: The configured user region set by the four offsets. Rotating the screen to the opposite landscape orientation flips left and right as in existing scripts.
- **Green border**: The actual game area inside the user region that FGA recognizes and taps. Because top/bottom or left/right margins are trimmed to match the game's aspect ratio, it may differ from the red border.
- **Region / Game / Ratio**: Width×height (in screen pixels) of the configured region and the actual recognition area, plus the aspect ratio (width/height) of the actual recognition area.

For example, on a 3040×1904 tablet, Left 1049, Top 107, Right 0, Bottom 0 covers most of the split window, yet centering can place the green border lower than the actual game picture. On that same layout, measured values of Left 1049, Top 432, Right 0, Bottom 356 produce a 1991×1116 user region. Because values differ per device and layout, inspect the green border rather than copying numbers directly.

## When Preview Turns Off Automatically

The border disappears when you turn off the switch, leave the settings screen, the app goes into a stopped/background state, or the accessibility service disconnects. **At script start, it is removed before screen-recognition capture**, and it cannot be turned on while a script is running. Reopening the settings screen always starts with preview off.

Preview is purely a visual overlay and does not touch the game or adjust coordinates automatically. Capture scaling and recognition bounds are computed together from the selected Game area, and no extra permissions are needed.
