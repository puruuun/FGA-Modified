# The two coordinate systems (most common source of bugs)

Two resolutions are in play at once, and mixing them up is the most frequent bug in this
repo. The essentials:

- **Image matching happens at 720p grayscale.** Screenshots are captured at native
  resolution, converted to grayscale, scaled to 720p, cropped to the game area
  (letterbox/notch bars removed), then cropped to the search region. **Template images
  added to `app/src/main/assets/` must be 720p** and are grayscaled unless matched inside
  `useColor { }`.
- **Coordinates are expressed at 1440p** (`scriptDimension` 1440p, `compareDimension`
  720p; `Scale` converts between script/screen/image space). Origin is the top-left of the
  *game area*, not the physical screen.
- Y ranges 0..1440; the right edge X depends on aspect ratio, so absolute X is almost
  always wrong. Use the `IScriptAreaTransforms` helpers — `xFromCenter()`, `xFromRight()`,
  `yFromBottom()` — on both `Location` and `Region`. `isWide` (wider than 18:9) selects
  alternate coordinates for many UI elements; follow the existing `if (isWide) … else …`
  style in `locations/`.
- Coordinates belong in `scripts/.../locations/*.kt` as named `val`s on the `Locations`
  graph, not inline in modules.

## Every aspect ratio from 16:9 to 21:9 (owner, 2026-10-30)

When a feature adds or changes screen coordinates:

1. Look at the screen at both ends first: a 16:9 frame and a 21:9 frame. A frame wider than
   21:9 is measured after removing its blue side bars, as `FgoGameAreaManager` does.
2. Build the coordinates the way original FGA covers every ratio in between: give each element
   the anchor it moves with — the left edge, the centre (`xFromCenter`) or the right edge
   (`xFromRight`) — and a wide value (`isWide`) where wide screens lay it out differently. The
   fork's `xFromLeftEdge` / `xFromRightEdge` add the measured wide edge inset
   (`WIDE_EDGE_INSET`, see `docs/design/0012-widescreen-audit.md`).
3. Then verify it holds in between, not only at the two ends: matching both ends does not prove
   the anchor is right.

The game can change its layout in an update, so measured values are not fixed rules; when a
screen no longer matches, measure it again in the same way.
