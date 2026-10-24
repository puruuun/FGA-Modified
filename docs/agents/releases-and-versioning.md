# Releases, Versioning, and Beta-First QA

All version numbers live exclusively in [`version.properties`](../../version.properties) at the
repository root (`app/build.gradle.kts` validates the format and versionCode formulas at build
time).

## Semantic Versioning (`X.Y.Z`)

Choose the next target version (`X.Y.Z`) according to the scope of the change:

- **Patch bump (`X.Y.(Z+1)`, e.g. `1.6.4` → `1.6.5`)**:
  - Refinements or improvements to existing features
  - Bug fixes
  - Built-in asset curation or translation updates
  - Upstream FGA syncs (`sync/upstream-v<code>`)
- **Minor bump (`X.(Y+1).0`, e.g. `1.6.x` → `1.7.0`)**:
  - New user-facing features or automation modes
- **Major bump (`(X+1).0.0`)**:
  - Reserved for incompatible architectural rewrites or explicit owner direction.

## Version Code Formulas

- `stable.versionCode`: Increments by **+1** on each published stable release (e.g. `3091` → `3092`).
- `beta.versionCode`: Computed from `beta.versionName` (`X.Y.Z-beta.N`, where `1 <= N <= 99`) as:
  `major * 1_000_000 + minor * 1000 + patch * 100 + beta`
  (for example, `1.6.5-beta.1` → `1006501`, `1.7.0-beta.1` → `1007001`).

## Beta-First Development & Device Testing Workflow

Every feature or code change follows a **beta-first** workflow before a stable GitHub Release is
published (unless the owner explicitly requests an immediate stable release):

1. **Bump `beta.*` only during development**:
   - Determine the target version `X.Y.Z` using the Semantic Versioning rules above.
   - In `version.properties`, set `beta.versionName=X.Y.Z-beta.1` (or increment `-beta.N` if a
     beta for `X.Y.Z` already exists) and update `beta.versionCode` to match.
   - Leave `stable.versionName` and `stable.versionCode` untouched at the last published stable
     release until beta verification is complete.
2. **Merge to `main` via PR and verify `Main CI`**:
   - Run `./gradlew :app:testCiUnitTest :prefs:testCiUnitTest :scripts:test :libautomata:test`
     locally, open the PR, and fast-forward merge to `main` once `Main CI` passes.
3. **Build and install the Beta APK for device testing**:
   - From `main`, build the beta APK:
     ```bash
     ./gradlew :app:assembleBeta
     ```
   - Stage `app/build/outputs/apk/beta/app-beta.apk` (package
     `io.github.fate_grand_automata.beta`, app name `FGA Beta`) as
     `build/release-assets/FGA-beta-<beta.versionName>-arm.apk` alongside its `.sha256` file.
   - Install onto BlueStacks (`adb -s 127.0.0.1:5555 install -r ...`) and/or provide the APK path
     to the owner for tablet testing.
   - Do **not** publish beta APKs to GitHub Releases unless the owner explicitly asks.
4. **Wait for owner verification before promoting to Stable**:
   - Only after the owner confirms the beta build (or explicitly asks to cut the stable release)
     should you promote `stable.versionName` and `stable.versionCode`.

## Promoting to a Stable Release

When promoting to stable `X.Y.Z`:

1. Update `version.properties`: set `stable.versionName=X.Y.Z` and increment `stable.versionCode`
   by 1 (keeping `beta.versionName=X.Y.Z-beta.N` and `beta.versionCode` aligned with `X.Y.Z`).
2. Add `wiki/releases/<version>-KR.md` and update the latest-release line in `README.md`.
3. Deliver via PR (`release/<version>`), wait for `Main CI` to pass, and fast-forward merge into
   `main`.
4. Build `./gradlew :app:assembleCi`, stage `FGA-mod-<version>-arm.apk` and
   `FGA-mod-<version>-arm.apk.sha256`, verify with `apksigner` / `aapt2`, install onto BlueStacks,
   record `docs/releases/<version>-validation.md`, and publish the GitHub Release `v<version>`.
