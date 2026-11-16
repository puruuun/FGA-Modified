# Fixed-Position Command Spell Button Tap

When a Command Spell action runs in a battle command, FGA does not search for the `"영주/令呪"` text image; instead, it directly taps the fixed position of the Command Spell button (diamond icon) to the left of the Master portrait.

## How it works

- **Fixed-position tap:** Uses fixed coordinates relative to the right edge of the game area, tailored separately for 16:9 screens and wide screens (wider than 18:9). There are no extra settings.
- **Existing flow preserved:** After tapping the Command Spell button, FGA waits 0.5 seconds, taps the NP-charge option position (center of the screen), waits 0.5 seconds, checks the confirmation dialog for up to 2 seconds using the Cancel button, then taps OK and performs the follow-up wait as before.
- **Stop condition:** If the confirmation dialog does not appear, FGA treats it as out of Command Spells and stops the battle (`"Ran out of Command Spell(s)."`). This message also stops the battle if the button was missed or the Command Spell menu failed to open, not only when Command Spells are actually empty.
