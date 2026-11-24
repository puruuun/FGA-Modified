# Support list filter

While log collection is on, this leaves the supports that do not meet your conditions out of the support list the game receives from the server. People without a matching support are left out, and the other servant slots of the people who stay show as empty. The script's support selection (screen reading and taps) does not change; the filter only shortens the list the game shows.

## Where to turn it on

- **Battle config → Support → Preferred** screen, the **Support list filter** switch at the top: applies while a script runs with this battle config. Off by default.
- **More options → Additional features → Support list filter (manual play)**: the filter for playing by hand. It has the same items as a battle config, stored on their own, with its own switch (off by default). **Import conditions from a battle config** copies one battle config's support conditions into it.
- **Play button menu**: the manual filter's switch and a one-line summary of its conditions. Editing happens on the Additional features screen.

A battle config's filter works only when its support selection mode is **Preferred** (the mode in which the script reads these conditions); in First, Manual and the other modes it is as if it were off. The manual filter has no selection mode.

While a script runs, only that battle config's switch counts and the manual filter is not applied; when the script ends, the manual filter applies again. With log collection off the filter does nothing, and the screen says so.

The **Last list** line under the switch shows what the filter did to the last support list (people and servant slots kept), or "not changed".

## Conditions the filter applies

Each condition is marked **Filtered** or **Screen reading only**. Only servant slots that meet **all** set conditions stay.

| Condition | Filter rule |
|---|---|
| Preferred servants | one of the chosen servants; empty means any servant |
| Displayed ascension (new) | one of the looks chosen for that servant (stage 1, 2, 3, final, costume N); none chosen means any look |
| Max ascended | final ascension |
| Max skills | the chosen skills at level 10 |
| Append skills | the chosen appends at level 10 |
| NP level | at least the set level |
| Class score +62 | the class score of the servant's class fully unlocked; a servant whose class is not known does not match |
| Preferred CEs / MLB | one of the chosen CEs (MLB on: that CE is max limit broken) |
| Friends only | people who are not friends are left out |

As in the script, max ascended, skills and appends apply only when at least one preferred servant is chosen. NP level and Class score +62 are shown only when a preferred servant is chosen and are turned off when the last one is removed, so a config without a chosen servant applies neither.

Conditions marked **Screen reading only** (friend names, servant name + NP images, the support class tab, Grand servant, Grand score +16, bond CE effect) are not applied by the filter; the script judges them on screen as before. A battle config with a Grand condition on is filtered by its other conditions only.

## When the filter does not run

- **An entry that cannot be matched to a game ID**: an image of your own that does not say which servant or CE it is. The filter could hide the very supports you want, so it does not run for that config, and the screen names the entry. Built-in images and images from **Download images** can be matched.
- **Class score on, and the class of a chosen servant is not known**: for now only the seven classes Saber, Archer, Lancer, Rider, Caster, Assassin and Berserker can be matched to a class board. With a servant of another class (an Extra class, for example) chosen and Class score +62 on, every support of that servant would be hidden, so the filter does not run and the screen names the servant.
- **No servant list for the class score condition**: the servant's class comes from the servant list on the device, which the friend manager, the account dashboard and Download images get. The filter never downloads it.
- A support list response in an unexpected shape goes to the game as received.

## Safety

- Nothing the game sends to the server is changed, and no request is added.
- Only the support list response changes. Matching servant slots and everything else stay as received.
- With every switch off, the support list goes to the game as received.
- The mapping of the displayed ascension values (stage 1 to final, costumes) is still being checked on a device.

The earlier experiment ("Support list filter (experimental)" in the log collection settings) is gone. The servants and CEs chosen there move into the manual filter once; its switch starts off.
