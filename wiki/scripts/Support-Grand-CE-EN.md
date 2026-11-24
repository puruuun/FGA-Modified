# Grand Support Craft Essence Conditions

Under **Battle Configs → Support → Preferred CEs**, configure your preferred CEs along with **Max-limit broken**, **Match both free slot & bonus-up (Bond/FP/QP/EXP) slot**, and **Bond CE effect preference**.

Tapping **Download images** at the bottom left of the **Preferred Servants** or **Preferred CEs** picker opens [Search and Save Support Images](Servant-Number-Portraits-EN.md) directly. Newly saved folders are automatically checked while preserving your existing selections, and tapping **OK** applies them to your config. Even if you tap **Cancel**, the downloaded images remain saved.

For Grand supports, FGA checks both the free slot and the bonus-up (reward) slot CEs. When **Match both free slot & bonus-up (Bond/FP/QP/EXP) slot** is enabled, each slot must hold one of your Preferred CEs. When **Max-limit broken** is enabled, it also checks MLB stars on the matching CE(s). For **Bond CE effect preference**, choose from **NP charge**, **Default effects**, or **Ignore** (`Ignore` applies no effect-icon condition).

**Bond CE effect preference** and **Match both free slot & bonus-up (Bond/FP/QP/EXP) slot** are displayed and applied only when **Grand Servant** is enabled under **Preferred Servants**. When **Grand Servant** is off, both options are hidden and treated as ignored (no effect condition, and matching either slot passes) even if previously saved values remain. As a result, non-Grand supports will never be rejected by leftover NP settings.

Bond CE effect icons use the same template images and the same matching threshold (`0.60`) as the original FGA instead of the global Min Similarity, because the icons are small and even a half-pixel shift lowers the score.

In the beta, when inspecting a Grand support row, the `FGA.SupportCe` log prints a single line with the CE scores and regions for the free and reward slots, MLB results, the bond-effect setting and NP/default-effect scores, and the final decision. If a support is not being picked, check this log together with your support settings.

To have the support list itself show only matching supports while log collection runs, see [Support list filter](Support-List-Filter-EN.md).
