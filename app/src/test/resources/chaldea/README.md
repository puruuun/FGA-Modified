# Chaldea conversion fixtures

`user-share-url.txt` is the battle share URL supplied for this feature. Its expected
command is `ghi1x31ih3gt2acbdefj654`, independently supplied by the user.

`converter-parity.json` contains that battle, two examples embedded in the reference
converter, and focused synthetic battles for party changes, skill targets, NP type
changes, choice buttons, death timers and turn boundaries. Expected strings were
evaluated with the original `fncConvert` function from
[Unic31/fgo-converter at 1c95510bea01c49313f4b17ebcd9e196b385542f](https://github.com/Unic31/fgo-converter/blob/1c95510bea01c49313f4b17ebcd9e196b385542f/src/routes/converter/%2Bpage.svelte).
The function was isolated from its UI, network requests and usage counter.

Real skill metadata was read from the public Atlas Academy JP `nice/svt` and
`nice/MC` endpoints on 2026-10-15. Only fields used by conversion are retained.
Synthetic metadata covers behavior branches without downloading data in unit tests.
These are output compatibility tests; they do not assert that the reference
converter handles every game gimmick or party transition correctly in-game.

`reported-target-share-url.txt` is the second user-supplied link, for quest 94146807.
Its enemy target is index 4 in a six-slot layout with only slots 1, 3 and 5 occupied.
The reference produces `t-1defab1cgix31hgj54`, which isn't valid FGA syntax. The
`reported-target-battle.json` fixture retains the relevant real JP metadata.
With the ignore-enemy-targeting option enabled, the expected command is
`defab1cgix31hgj54`; with it disabled, import returns a targeting error.
All 35 baseline cases also test omission of enemy commands without changes to
skills, ally targets, choices, party changes or attacks.
