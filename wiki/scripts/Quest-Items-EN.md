# Automatic Teapot / Storm Pod Use

## How to Use

1. Turn on **More options → Battle → Enable Teapot/Storm Pod control**. It defaults to ON for new configurations.
2. Select the KR, JP or NA server and tap the Play button.
3. On the right side of the launcher, under **Item auto-use** (between **Refill** and **Limit**), set **Use Storm Pods** and **Use Teapots** shown side by side on one row. You can toggle each by tapping the checkbox or its label. If the labels are too narrow to fit on one line, they wrap onto two lines.
4. Checked means ON; unchecked means OFF. Tapping **OK** starts with the selected launch settings.

These two choices are remembered per server and are not stored inside an individual battle config's skill commands or card priorities. Both choices in the launcher start unchecked. Only when **Enable Teapot/Storm Pod control** is turned on will visible items be set to the ON/OFF states chosen in the launcher. If you import script configuration files onto another device, select these options again in the launcher.

| Launcher selection | State right before Quest Start |
|---|---|
| Both unchecked | Storm Pod OFF, Teapot OFF |
| Only Use Storm Pods checked | Storm Pod ON, Teapot OFF |
| Only Use Teapots checked | Storm Pod OFF, Teapot ON |
| Both checked | Storm Pod ON, Teapot ON |

When **Enable Teapot/Storm Pod control** is turned off, all checking and toggling of in-game item states is skipped. Your launcher selections are preserved while the entire item block is hidden. Turning the feature back on applies the launcher's selections. Previously saved explicit enable/ignore choices are kept, while unconfigured setups default to ON. On other servers (BetterFGO, CN, TW), this control is currently skipped.

## Behavior

The launch sequence is **Support selection → 4 seconds → Party selection → 1 second → Item settings → 1 second → Quest Start**. Party selection retains its own 1.2-second and 1-second waits. After tapping Start, the existing flow follows: 2-second wait → boost item selection → story skip preparation → 5-second wait. These waits apply to all servers and scale with the general Wait Multiplier.

**Starting directly on the party confirmation screen is not supported.** Use the launch flow starting from support selection. If you already opened the item settings dialog manually, close it before starting.

The script taps the identical-looking main button once to tell whether it is a direct-toggle control or opens a dialog. If direct-toggle, it checks the changed state and sets the requested final state; a direct control that already matched the desired state is restored with a second tap. Items are consumed only when Quest Start is pressed, so this probe tap does not spend them.

If it opens a dialog, it checks **left = Storm Pod / right = Teapot** individually and changes only items whose state differs. After confirming both states on a fresh frame, it taps **Close** and verifies that the main item button returned to the requested state. If a change or closing cannot be verified, it stops before Quest Start. Quantity displays such as `0` or `0/9` are separate from ON/OFF and are not used for state detection.

If the party screen has no item button, it starts normally. In quests offering only one item, it controls only the visible item and never taps an unoffered item. When the game's **Fast Repeat** skips the party confirmation screen, the already-applied settings remain in use, and they are checked again on the next launch after support selection.

## NA server

The NA server is supported. KR and JP recognise the settings dialog from its title image together with the Close button and both item buttons. NA does not use the title image: it treats the screen as the dialog when the **Close button and both the Storm Pod and Teapot ON/OFF buttons are found**. If the Close button is visible but one of the two buttons is missing or ambiguous, no item is tapped and the start proceeds as usual.

NA is not played by the owner, so this was not verified on a device. The Close button was checked only against an NA screenshot and the layout against the official notice; no test was run on an NA device.

To allow launching even if the party title image does not match, the title check, up to 3-second retry, 2-second post-check wait, and skip log were removed. All servers proceed through the 4s → 1s → 1s sequence above. On servers other than KR/JP/NA or when auto-control is disabled, item actions are skipped while the 1-second wait is kept.

## Starting with Fixed Waits

If an item icon or ON/OFF button is absent or uncertain, it proceeds to start without tapping items. Dialog title, Close button, and both switch checks are kept. If a transition, restoration, or close check fails after tapping an actual item button, it stops with the existing message (`Could not confirm the Storm Pod/Teapot settings or close the item dialog. Stopped before Quest Start.`).
