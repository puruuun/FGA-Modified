# Automatic Teapot / Storm Pod use — 0.9.2

## Configuration

First opt in under **More options → Additional features → Enable Teapot/Storm Pod control**. Control defaults off when no explicit choice was saved. On KR or JP, tap **Play**. In the launcher, between **AP refill and Limits**, choose **Use Storm Pods** and **Use Teapots** independently. Checked enforces ON; unchecked enforces OFF. Both default to unchecked. Choices are remembered per server and shared across battle configurations; they are not included in an exported battle configuration.

With control off, every item read and change is bypassed. The launcher retains the item selections and disables their checkboxes. Enabling control applies those choices before Quest Start. Explicit enable/bypass choices saved in earlier versions retain their behavior; otherwise the default is off. Other servers currently bypass item control.

## Behavior

The sequence is **support selection → party selection → item settings → Quest Start**. One probe tap distinguishes identical-looking direct controls from controls that open the item dialog. Direct controls are verified after the flip and restored with a second tap when the initial state already matched the requested state. Items are consumed at Quest Start, not by these configuration taps.

In the dialog, **left = Storm Pod, right = Teapot**. Only mismatched switches are changed, with a fresh frame after every tap. Both requested states must be verified before Close, and party-screen restoration must be verified before Quest Start. Unreadable states, unresponsive switches or unconfirmed closing stop the script before starting. Quantity 0 or 0/9 never supplies the ON/OFF state.

A party screen without item controls starts normally. Quests offering only one item apply its choice; unavailable items are not clicked. Fast repeats that skip party confirmation retain the game's existing settings. Settings are checked whenever the normal party-confirmation start path is visited.

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
