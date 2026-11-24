> Draft manual for while Chain Priority is hidden (`ChainPriorityAvailability.SHOWN = false`). The PR that sets `SHOWN = true` moves it into `wiki/scripts/Card-Priority-EN.md`.

## Chain Priority

On an entry with Chain Priority on, the app first looks for **a chain this hand can make** before it picks cards. This is original FGA's Chain Priority (Mighty, color chains, Avoid), taken over as it is.

- It tries the chains in your order (default: Mighty → Buster → Arts → Quick) and picks the first one the hand can make.
- If none can be made, it picks with servant priority and card priority as before.
- With **Avoid** on, it avoids chains as far as it can. A hand of one color cannot avoid one.
- An NP counts as one card of the chain, so the NP card type of each Servant is read (see below).
- Inside a chain, the order of the cards follows card priority (affinity included). A weak Arts ranked above a normal Buster can give A-B-Q.

Example: card priority Arts > Buster > Quick, hand 1 Arts, 1 Buster, 2 Buster, 3 Buster, 3 Quick

- Off: 1 Arts → 1 Buster → 2 Buster
- On (default order): 1 Arts → 1 Buster → 3 Quick (Mighty Chain)
- On (Buster first): 1 Buster → 2 Buster → 3 Buster (Buster Chain)

### Where to turn it on

Turn on **Use Chain Priority** in the entry settings card of the card priority screen. It is set per wave or turn entry and saved per profile (first, second and third start). When it is on, a **Chain Priority** panel appears below.

- Tap a tile to turn that chain on or off. A grey tile is never tried.
- Hold and drag to reorder.
- A new entry copies the switch and the order of the previous entry.
- The default is off. Existing settings are read as off.

The card priority summary on the auto skill config screen marks an entry with it on as `Chain Mighty>Buster>Arts>Quick`.

### What wins over what

1. A turn with **direct cards** (`&…`) plays the direct cards only. Chain Priority is not applied, and the fallback places of `?` are filled by card and servant priority only.
2. On a **Servant** style entry the Chain Priority switch is greyed out and ignored, like Brave Chains and Rearrange Cards.
3. On an **Affinity** entry with Chain Priority on: Brave Chains → chain order → servant priority → card priority → Rearrange Cards.
4. An entry with Chain Priority off behaves exactly as before.

### Brave Chains "Always"

On an entry with Chain Priority on, Brave Chains gets an **Always** option: also on a turn without an NP, a Brave Chain of one Servant's three cards is picked when the hand has one. With `With NP` or `Always`, a Brave Chain the hand can make on its own wins over a color chain. With Chain Priority off, a saved `Always` acts as `With NP` and is shown as such.

### Reading the NP card type

When any entry has Chain Priority on, the servant face-card check turns on by itself (`Servant face-card check: auto — on (chain priority W1)`), and the NP card type is read in the same Servant details dialog that is opened to save the face. No tap is added. When no entry has it on, the NP type is not read and taps, screen reads and waits stay as before.

- After a **skill with a choice** such as Emiya's or BB Dubai's, the details dialog is opened again to read the new type, and checked again next turn.
- While the type is changed, a banner covers the NP card art and **the new type cannot be read**. The remaining-turns number on the banner is read instead, and **the last known type** is used until the banner is gone. If the number cannot be read either, the dialog is opened again every turn.
- A **transforming** Servant such as Mélusine or Ptolemaios gets the NP type of the other form read separately; the current form is told from the portrait.
- A spam NP joins the chain plan only when its portrait is seen on the NP card of the card screen.
- An NP whose type was never read, a card of unknown type and a stunned card are not used to build a chain.

Not done: NP gauge and seals are not read (an NP in the command is assumed ready). Space Ishtar's color choice is not recognized. Only the current turn is planned; the next hand, damage, stars and enemy HP are not.

### Fine-tune

Settings → Fine-tune has the same four settings as original FGA.

| Setting | Default | Used for |
|---|---|---|
| NP Card Type Similarity | 50% | The NP type in the details dialog (only with Chain Priority) |
| NP card detection similarity | 80% | The portrait on a spam NP card (only with Chain Priority) |
| Wait after Order Change | 1000 ms | The wait after Order Change, always |
| Servant details wait | 250 ms | The wait after tapping the INFO tab of the details dialog, whenever face cards are checked |

The defaults of the two waits are the fixed waits used so far.

### Importing from original FGA

Importing original FGA configs converts the per-wave Chain Priority switch (`use_chain_priority_per_wave`, else `use_chain_priority`), the chain order (`chain_priority`) and the per-wave servant priority switch (`use_servant_priority_per_wave`) into this app's per-entry settings.

Line for the "Servant face-card check (auto)" list:

- Any Affinity-style entry has **Use Chain Priority** enabled.
