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
