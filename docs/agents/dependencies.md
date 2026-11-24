# Build configuration and dependency bumps

## Toolchain pins

- **JDK 21** is pinned as a Java toolchain for all modules in the root `subprojects` block,
  so it doesn't matter which JVM launches Gradle (Studio's JBR, a system JDK, CI's temurin
  21). Don't accept Android Studio's offer to generate `gradle/gradle-daemon-jvm.properties`
  (`updateDaemonJvm`): the toolchain is the pin, and that file forces a ~525 MB JBR download
  that drifts from the CI JDK.
- **Emitted bytecode stays at Java 11** in every module — from `jvmTarget` in the root
  `subprojects` block for `libautomata`/`scripts`, derived by AGP from `compileOptions` for
  `app`/`prefs`. Toolchain and bytecode target are separate knobs; raising the latter means
  checking D8 desugaring against minSdk 24. The one exception is `:scripts`'s *test*
  compilation, pinned to 17 in `scripts/build.gradle.kts` because JUnit 6 requires it — test
  code runs on the toolchain JVM and never reaches Android.
- **AGP 9 compiles Kotlin itself.** `app` and `prefs` must *not* apply
  `org.jetbrains.kotlin.android` — AGP 9 rejects it outright. Their Kotlin output lands in
  `build/intermediates/built_in_kotlinc/`, not `build/tmp/kotlin-classes/`, and the root
  block's `tasks.withType<KotlinCompile>` no longer reaches them: per-module compiler
  settings belong in that module's `android { kotlin { compilerOptions { } } }`.
- `compileSdk` is **37.1** while `targetSdk` stays **36** — recent androidx libraries force
  the compile level, but nothing opts into new runtime behavior. Keep the two decoupled;
  bumping `targetSdk` is a separate, user-visible decision. A minor API level needs the
  `compileSdk { version = release(37) { minorApiLevel = 1 } }` block, not `compileSdk = 37`.
- **OpenCV is 5.1.0** (issue #653; owner decision, 2026-11-24: upgrade with device QA, the macOS
  BlueStacks Air risk below accepted). Its arm64 `libopencv_java5.so` bundles ARM's KleidiCV 26.09
  (`Custom HAL: YES (carotene (ver 0.0.1) KleidiCV (ver 26.09))`), and that KleidiCV has reachable
  SVE2 kernels: 83 functions with SVE/SVE2 instructions (for example `smlalb`, `sqrshrnb`). What was
  measured on the AAR (NDK r28 `llvm-objdump --mattr=+sve,+sve2,+sme,+sme2`, functions from
  `.eh_frame`):
  - **Selection.** Static initializers in `.init_array` pick each kernel once, at load: the SVE2
    variant when `getauxval(AT_HWCAP)` has `HWCAP_SVE` (bit 22) **and** `getauxval(AT_HWCAP2)` has
    `HWCAP2_SVE2` (bit 1), the Neon one otherwise (39 sites, all of this form). 4.14.0 (KleidiCV
    26.03) checked `HWCAP2_SVE2` alone. Traced backwards, every SVE function is reached only through
    those initializers' pointers.
  - **Load.** No SVE instruction is reachable from `.init_array` by direct branches (186 functions),
    so 4.14's death at `System.loadLibrary` (a static constructor ran `rdvl`) is gone.
  - **No switch.** Nothing turns the SVE2 kernels off: the only KleidiCV environment variable in the
    binary is `KLEIDICV_PREFER_SME_BACKEND` (it prefers SME), there is no OpenCV HAL or KleidiCV
    switch string, and the pointers are fixed before the app's first call.
  - **BlueStacks Air on macOS** advertised SVE2 without implementing it, which is why the fork kept
    4.11.0 (KleidiCV 0.3.0, which never calls its SVE kernels): 4.12/4.13 died with `SIGILL` at the
    first image match, 4.14 at `System.loadLibrary`. Upstream's KleidiCV report (work item 16) asked
    for the `HWCAP_SVE` and `HWCAP2_SVE2` check that 5.1.0 has, but neither KleidiCV's nor OpenCV's
    release notes record a fix, and nobody measured BlueStacks Air's HWCAP bits. If it sets
    `HWCAP_SVE` too, 5.1.0 still dies there at the first match. **The macOS case is not verified.**
    No such device is available, and the owner accepted that.
  - **Checking a device:** `/proc/cpuinfo` `Features` listing both `sve` and `sve2` means the SVE2
    kernels run; `sve2` without `sve` means Neon. A device that lists both and has no SVE unit crashes.
  - **Vetting a later bump:** read the KleidiCV version from the build string (`Custom HAL`), then
    whether the selection rule and the load path above still hold. An SVE instruction that is
    *present* says nothing: what matters is what can reach it. Stock `objdump` cannot disassemble
    these files (`architecture UNKNOWN`), so a grep over its output silently finds nothing for every
    version; use the NDK's `llvm-objdump` with the SVE attributes.
- **`libc++_shared.so` comes from the OpenCV AAR itself.** In 5.1.0 every `LOAD` segment of the arm64
  `libc++_shared.so` and `libopencv_java5.so` is 16 KB aligned (`0x4000`), and both `libc++_shared.so`
  copies are byte-identical to those of the 4.14.0 AAR that the fork used to extract and package in
  place of 4.11.0's 4 KB aligned one, so that workaround (`opencv_aligned_libcxx_version`,
  `alignedLibCxx`, `ExtractAlignedLibCxx`, the `pickFirsts` entry) is gone. By ABI, the `LOAD`
  alignment of both libraries in the 5.1.0 AAR is `0x4000` on arm64-v8a and x86_64 and `0x1000` on
  armeabi-v7a and x86. The 32-bit ones are 4 KB aligned, as the extracted copy was. The 16 KB
  requirement applies to 64-bit libraries only. The app ships arm64-v8a and armeabi-v7a. Nothing else in the APK ships a `libc++_shared.so`: Tesseract4Android does not, and
  ONNX Runtime links its C++ runtime in.
- The JVM unit tests use openpnp OpenCV 4.9.0 (`opencv_jvm_version`), a separate artifact that never
  reaches the APK. It stays at 4.9.0: the `scripts` tests use only `core`, `imgproc` and `imgcodecs`,
  and they compile and pass on it unchanged. What 5.1.0 does on Android is checked by device QA, not by them.
  OpenCV 5 renamed the Java package `org.opencv.features2d` to `org.opencv.features`.
- Build types: `debug`, `release`, `ci` (`initWith(release)`, debug-signed, ARM-only
  ABIs), `beta`, `deviceTest` and `rust` (the owner's Rust test APK; which of them package the
  Rust engine is in [`rust-native.md`](rust-native.md)). The `ci` type must exist in every
  Android module (`app`, `prefs`).
- CI's Gradle cache comes from `gradle/actions/setup-gradle`, not `setup-java`'s
  `cache: 'gradle'` — don't re-add the latter, it would cache the same directories twice
  with worse invalidation.
- `release` builds need `app/fgautomata.keystore` (GPG-decrypted in CI) and `KEYSTORE_PASS`.
  The fork does not publish to the Play Store. Upstream's `fastlane/`, `Gemfile` and
  `Gemfile.lock` were removed (owner, 2026-10-30). Stable and beta APKs are built and
  signed locally with `:app:assembleCi` / `:app:assembleBeta` (the `stable.yml` and `beta.yml`
  workflows were removed with the release key rotation, #128).

## Third-party notices of the Android side

`app/src/main/assets/licenses/THIRD_PARTY_NOTICES.txt` (packaged in every build type, shown on the
licence screen before the Rust engine's notices) is written by hand. `tools/ci/android_notices_manifest.json`
names, per component, its version, the URL each text and copyright line came from and the SHA-256 of
each source, so a reviewer can re-fetch and compare. Copy texts verbatim from those upstream files,
never from memory. `tools/ci/android_notices.py check` runs in Main CI through
`tools/ci/test_android_notices.py` and fails when

- a component's `version_ref` differs from `gradle/libs.versions.toml`, or `<name> <version>` is not in
  the notices file as whole tokens (a notices file that says "Shizuku API 13.1.5" does not mention 13.1);
- a `*.traineddata` file under `assets/` has no `data_files` entry (name and SHA-256) or is not named in
  the notices file;
- a runtime dependency of `app`, `prefs`, `scripts` or `libautomata` matches no component's `covers`
  pattern (`group:artifact`, `*` allowed), or cannot be read. Runtime means `implementation`, `api`,
  `runtimeOnly` and their variant forms (`betaImplementation`, `ciRuntimeOnly`, quoted
  `"rustImplementation"(...)`), except variants containing "test"; `ksp`, `kapt`, `compileOnly`, `lintChecks`
  and custom configurations are build-only. The argument may span lines and be `libs.x`, `platform(libs.x)`,
  a local `val` holding one of them, `project(...)` or a `fileTree` whose folder has no jar. Anything else
  (string coordinates, `add("implementation", ...)`, a Groovy-style call) is an error that names file and
  line: the check fails closed.

What the check cannot see, so a reviewer must:

- **What is statically inside a native library.** The third-party list of `libopencv_java5.so` and of
  Tesseract4Android's libraries changes with their versions; the check compares versions only where the
  catalog has a key.
- **Transitive dependencies.** A new transitive dependency of a covered library raises no alarm; add its
  component by hand when a bump brings one (read `:app:dependencies --configuration ciRuntimeClasspath`).
- **The `androidx.*` pattern is broad by design.** A new AndroidX artifact that bundles third-party code
  (as glance does with protobuf) raises no alarm.
- **Custom configurations** (`onnxRuntimeAar`) feed packaged native libraries and are not read; their
  components (`libonnxruntime.so` in engine builds) are kept by hand. libc++ comes with the OpenCV AAR and
  its component carries `opencv_version`.
- Whether a copied text still equals its upstream file: the manifest has the SHA-256 for a reviewer.

- **A bump of OpenCV or Tesseract4Android: redo the bundled list from the library's build string and
  `3rdparty/` at that tag.** For OpenCV, read the **whole** `cv::getBuildInformation()` text of the new
  `libopencv_java5.so` (for example `strings` on it): the "3rdparty dependencies" line, the "Extra
  dependencies" line (a library the binary links from the device, such as `z`, is not redistributed and
  gets no entry), the per-library version lines ("ZLib", "JPEG", "WEBP", "PNG", "TIFF", "OpenEXR",
  "JPEG 2000", "Parallel framework", "Trace"), "Built-in fonts", and the "Other third-party libraries"
  block (HarfBuzz, DNN MLAS, Custom HAL, Protobuf, Flatbuffers). The build information does not list
  everything: also check `modules/*/3rdparty/` and third-party code compiled into OpenCV's own sources
  (5.1.0: SoftFloat and FDLIBM in `modules/core/src/softfloat.cpp`, Annoy and MSCR's `chi_table.h` in
  `modules/features`), and confirm in the binary that each one is linked in. For each library that is built in, take
  its licence file and version from `3rdparty/<dir>` (5.x: also `hal/carotene`, `hal/kleidicv`,
  `modules/imgproc/fonts`) at the tag, plus the oneTBB and KleidiCV versions the build downloads
  (`3rdparty/tbb/CMakeLists.txt`, `hal/kleidicv/kleidicv.cmake`). For Tesseract4Android, read its README
  (Tesseract, Leptonica, libjpeg, libpng versions) and the vendored `cpp/*/src` licence files at the tag.
  Update the notices file and the manifest (components, source URLs, SHA-256) together. The libc++
  component moves with `opencv_version`. A new runtime dependency or a
  new `*.traineddata` file needs its entry in the same PR. Copy a header comment or a section whole: check
  the end of the excerpt in the source, not only the start.

## Bump order

The fork has no dependency bot (`renovate.json` was removed, owner 2026-10-30); version bumps
arrive through upstream syncs or a deliberate PR. The Android toolchain bumps are interlocked,
so a small-looking version change can hide an AGP migration. The constraints run in one direction —
bump along it and most failures never happen:

```
AGP + compileSdk ──▶ Gradle
       │
       ├──▶ Kotlin + KSP + Compose compiler ──▶ Hilt
       │
       └──▶ androidx + Compose BOM
```

- **AGP and `compileSdk` lead.** androidx AARs declare a minimum AGP *and* a minimum
  `compileSdk` in their metadata, so both usually have to move before any androidx bump
  resolves at all.
- **Gradle follows AGP, never leads it.** Gradle removes internal APIs the installed AGP
  still calls, so a wrapper bump ahead of AGP fails at plugin-apply time; AGP separately
  declares a *minimum* Gradle. Move them as a pair, AGP in front.
- **Kotlin, KSP and the Compose compiler move as one unit**, and after AGP — AGP compiles
  Kotlin itself.
- **Hilt is pinned from both sides:** it declares a minimum AGP, and its bundled
  `kotlin-metadata-jvm` caps the Kotlin metadata version it can read. So Hilt cannot lag
  Kotlin, and neither can precede AGP.
- **androidx and the Compose BOM come last**, when what's left is source-level API churn
  rather than resolution failures.
- **Test-only dependencies sit outside the chain.** They resolve against the *test*
  compilation's `jvmTarget` (Gradle's `org.gradle.jvm.version` attribute), not the app's, so
  they can outrun it — but Kotlin aborts unless `compileTestKotlin` and `compileTestJava`
  agree on that target, even with no Java test sources.

The chain can also be dragged forward **transitively**: any dependency pulling a newer
`kotlin-stdlib` re-triggers the Kotlin/Hilt constraint from a PR that looked unrelated.
`dependencyInsight` on `kotlin-stdlib` names the culprit.

When a bump fails, read the `checkAarMetadata` output first — it names the required AGP
version and compileSdk directly, much faster than bisecting versions.

## CI checks and Android Lint

- **Workflows by path:**
  - `PR Build` (`.github/workflows/pr-build.yml`) and `Main CI` (`.github/workflows/main-ci.yml`) run unit tests and `:app:assembleCi` on code changes (ignoring docs, `wiki/`, `docs/`, and markdown files).
  - `Android Lint` (`.github/workflows/android-lint.yml`) runs `./gradlew :app:lintCi :prefs:lintCi` on pull requests touching `app/**`, `prefs/**`, `gradle/**`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, or `.github/workflows/android-lint.yml`.
  - `Manual Build` (`.github/workflows/docs-check.yml`) runs `mkdocs build --strict` on pull requests and `main` pushes touching `wiki/**`, `mkdocs.yml`, `tools/docs-site/**`, or `.github/workflows/docs-check.yml`.
- **Lint baseline:** `app/lint-baseline.xml` and `prefs/lint-baseline.xml` record pre-existing findings, and `abortOnError = true` (with `StringFormatCount` promoted to error) makes CI fail only on findings not in the baseline.
- **Disabled dependency-version lint checks:** `NewerVersionAvailable`, `GradleDependency`, `AndroidGradlePluginVersion`, and `SimilarGradleDependency` are disabled because `./gradlew dependencyUpdates` handles dependency versions, and baselines must not contain machine-specific paths (check for `$HOME` after regenerating).
- **Regenerating a baseline:** delete the module's `lint-baseline.xml` and run `./gradlew :app:lintCi :prefs:lintCi` twice (the first run writes the new baseline and exits with a failure; the second verifies it passes). Regenerate a baseline only intentionally (for example after an AGP/lint upgrade or intentional cleanup) — never to hide a new error.
