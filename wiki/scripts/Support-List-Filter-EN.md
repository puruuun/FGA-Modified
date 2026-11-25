# Support list filter

While log collection is on, this leaves the supports that do not meet your conditions out of the support list the game receives from the server. People without a matching support are left out, and the other servant slots of the people who stay show as empty. The script's support selection (screen reading and taps) does not change; the filter only shortens the list the game shows.

There is one filter, and you switch it yourself. Starting or ending a script does not change it, so the same filter applies whether a script runs or you play by hand.

## Turning the feature on

Use **Use this feature** at the top of **More options → Additional features → Support list filter**. It is off by default. The menu entry shows only while log collection is on.

- Off: the play button menu shows no support filter and nothing is filtered. The support list reaches the game as received.
- On: the button row at the bottom of the play button menu gets a **Support filter · On/Off** button.

## Using it from the play button menu

**Support filter** opens the filter panel on the right. It shares that place with the weekly mission panel, so only one of them is open at a time. The filter panel is as wide as the battle config list on the left. It comes back open the next time the menu opens if it was open when it last closed (opening the weekly mission panel counts as closing it). The **Weekly mission clear** and **Support filter** buttons at the bottom look the same, and the button of the open panel is highlighted. While it is open, the run limits (runs, materials, CEs) stay visible in the middle. With the weekly mission panel open the limits are hidden as before, because the weekly route does not use them. The button is in every script menu, and the panel shows:

- **The Support list filter checkbox**: the same kind of checkbox as the options in the middle column; the whole row is tappable. Checked, the filter applies from that moment; unchecked, it stops at once. The state survives an app restart.
- **Conditions from**: where the current conditions came from (a battle config name or a profile name).
- **Every active condition**: servants (with the chosen ascension looks), CEs and MLB, skills, appends, NP level, final ascension, class score +62, friends only. Each condition is one line, shown only when it is set.
- **Script** button in the **Take from** row: copies the support conditions of the battle config selected in the list on the left, whatever its support selection mode. Not available when no config is selected or in menus without a battle config list. A displayed ascension stored in the config is not copied.
- **Profile** button in the **Take from** row: copies the conditions of a saved profile (displayed ascensions included). Not available while there is no profile.
- The **Filter result** line: what the filter did to the last support list (for example "Filter result: 6 of 20 shown"), or "not changed".

**Script** and **Profile** both **copy** the conditions. Changing that battle config or profile later does not change the active filter: take or load it again. Conditions are not edited in the play button menu; that happens on the profile screen.

### Refresh the support list after switching

The game asks the server for the support list only when it does not hold one yet. If you switch the filter on or off or load conditions after the support selection screen was opened once, **refresh** the support list in the game to see the list with the new filter. FGA never makes the game ask for the list.

## Profiles

Under **Profiles** in **More options → Additional features → Support list filter** you can keep sets of conditions. Profiles are stored apart from battle configs and are not part of a battle config export (.fga).

- **Add profile**: names a new, empty profile and opens it for editing.
- Tapping a profile opens its editor: the same items as a battle config's preferred support screen, without the items only a script uses (NP images, grand servant, grand score +16, bond CE effect, friend names, friend requests). **Import conditions from a battle config** copies a battle config's conditions into the profile.
- **Use as active filter** copies the profile's conditions into the active filter (the same as **Load profile** in the play button menu).
- The menu on the right has **Rename** and **Delete**. Deleting a profile leaves the conditions already copied into the active filter.

A battle config's preferred support screen is the same with the feature on or off as before it existed (no filter notes, no **Filtered** marks, no **Displayed ascension** item). **Displayed ascension** is chosen only in a profile's editor, which also marks each condition **Filtered** or **Screen reading only**. So conditions taken with **Script** carry no displayed ascension; it can only come from a **Profile**.

## Conditions the filter applies

Only servant slots that meet **all** set conditions stay.

| Condition | How the filter judges it |
|---|---|
| Preferred servants | One of the chosen servants. Empty: any servant |
| Displayed ascension | One of the looks chosen for that servant (stage 1, 2, 3, final, costume N). None chosen: any look |
| Final ascension | The servant is fully ascended |
| Max skill levels | The chosen skills are level 10 |
| Append skills | The chosen appends are level 10 |
| NP level | At least the set level |
| Class score +62 | The class score board (boards 1 to 9) of that servant's class is fully open. A servant whose class is unknown does not match |
| Preferred CEs / MLB | One of the chosen CEs (with MLB on, that CE is MLB) |
| Friends only | People who are not friends are left out |

As in the script, final ascension, skills and appends apply only with at least one preferred servant. NP level and class score +62 show only with a preferred servant and switch off when the last one is removed.

**Screen reading only** conditions (friend names, ascension by servant name + NP, the support class tab, grand servant, grand score +16, bond CE effect) are not applied by the filter. Conditions taken from a battle config with the grand servant condition on are filtered by the other conditions only.

## What stops the filter

With the toggle on, the filter still does not run in these cases, and the panel shows the reason in red.

- **An entry that cannot be matched to a game ID**: an image of your own, for which the servant or CE is unknown, could hide the supports you want, so the filter does not run. The entry is named. Built-in images and images from **Download images** can be matched.
- **Class score on with a chosen servant of unknown class**: the classes that can be matched to a class score board are saber, archer, lancer, rider, caster, assassin, berserker, shielder, ruler, alter ego, avenger, moon cancer, foreigner, pretender, beast and the two beast classes in JP's board 9 (Atlas's beastEresh and unBeastOlgaMarie). With a servant of another class chosen and class score +62 on, every support of that servant would be hidden, so the filter does not run and the servant is named.
- **No servant list for the class score**: telling a servant's class needs the servant list on the device. The friend manager, the account dashboard and the image download get it. The filter never downloads it.
- A support list in a shape the filter does not expect reaches the game as received.
- With log collection off, the filter does nothing.

## Safety limits

- Nothing the game sends to the server is changed, and no request is added.
- Only the support list response changes. Matching servant slots and everything else stay as received.
- With the feature off or the toggle off, the support list reaches the game as received.
- The mapping of the displayed ascension (stage 1 to final, costumes) is still being checked on a device.

## Changes from earlier versions

The **Support list filter** switch of each battle config and the "Preferred mode only" rule are gone. On update, once:

- The manual filter's conditions stay the active conditions and are also saved as a profile named **My filter**.
- The manual filter's switch becomes the toggle.
- **Use this feature** is turned on if the manual filter or any battle config had its switch on. Otherwise it stays off and the play button menu looks as before.
- The battle configs lose their switch value; their other support settings stay. A .fga exported earlier still imports.
