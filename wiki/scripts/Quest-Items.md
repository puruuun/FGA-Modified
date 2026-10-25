# Automatic Teapot / Storm Pod use — 0.9.2

## Configuration

First opt in under **More options → Additional features → Enable Teapot/Storm Pod control**. Control defaults off when no explicit choice was saved. On KR or JP, tap **Play**. In the launcher, between **AP refill and Limits**, choose **Use Storm Pods** and **Use Teapots** independently. Checked enforces ON; unchecked enforces OFF. Both default to unchecked. Choices are remembered per server and shared across battle configurations; they are not included in an exported battle configuration.

With control off, every item read and change is bypassed. The launcher retains the item selections and disables their checkboxes. Enabling control applies those choices before Quest Start. Explicit enable/bypass choices saved in earlier versions retain their behavior; otherwise the default is off. Other servers currently bypass item control.

## Behavior

Since **1.6.7-beta.2**, all servers use **support selection → 4 seconds → party selection → 1 second → item settings → 1 second → Quest Start**. Party selection retains its own 1.2-second / 1-second waits. After Start, the existing sequence remains: 2 seconds → boost item → story-skip flag → 5 seconds. These waits use the normal wait multiplier, including when item settings are bypassed.

Starting the script directly on the party screen is no longer supported. Start through support selection. The party heading, its 3-second polling, the following 2-second settling wait and the skipped-party log have been removed. Close an already-open item dialog before starting the script.

One probe tap distinguishes identical-looking direct controls from controls that open the item dialog. Direct controls are verified after the flip and restored with a second tap when the initial state already matched the requested state. Items are consumed at Quest Start, not by these configuration taps.

In the dialog, **left = Storm Pod, right = Teapot**. Only mismatched switches are changed, with a fresh frame after every tap. Both requested states must be verified before Close, and the main item control must be verified in its requested state before Quest Start. Initially missing or ambiguous item evidence is skipped without an item tap. An unresponsive switch, failed restoration or unconfirmed closing after a tap stops the script before starting. Quantity 0 or 0/9 never supplies the ON/OFF state.

A party screen without item controls starts normally. Quests offering only one item apply its choice; unavailable items are not clicked. Fast repeats that skip party confirmation retain the game's existing settings. Settings are checked on the launch path after support selection. A main control requires the item icon and an unambiguous ON/OFF button; no party heading is read. Dialog heading, close and both switches retain their existing checks. KR/JP item control and the bypass preference remain unchanged.

## Validation

All **9/9 supplied KR/JP full game screens** correctly distinguished the item kind, dialog/direct screen and visible ON/OFF states. Two normalization orders passed **18 checks**. The enlarged ON-button sample passed at two compatible scales; **12 synthetic missing-control checks** also passed. The launcher screenshot is a layout reference and is excluded from recognition counts.

Automated code tests cover all requested/initial combinations, direct and dialog controls, delayed frames, already-open dialogs, bypass, absent controls, ambiguity and failed transitions preventing Quest Start. Live Android/FGO interaction, wider displays and translated game layouts remain unverified. The 100% figure applies only to the supplied screenshot dataset.

```sh
python -m pip install -r tools/np-level-vision-requirements.txt
python tools/verify_quest_item_images.py \
  --input-dir /path/to/teapot-screenshots \
  --report quest-item-tests.json
```

[Stable release 0.7.0](https://github.com/puruuun/FGA-Modified/releases/tag/v0.7.0). [한국어 안내](Quest-Items-KR.md).

Each Teapot/Storm Pod control tap, including dialog close, waits an additional fixed 0.1 seconds before checking the next frame.

The owner will check the new launch sequence in game together with the next feature fix. Stored-frame Android QA does not exercise live game taps.
